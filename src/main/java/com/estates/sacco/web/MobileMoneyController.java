package com.estates.sacco.web;

import com.estates.sacco.domain.Account;
import com.estates.sacco.domain.AccountType;
import com.estates.sacco.domain.Branch;
import com.estates.sacco.domain.MobileMoneyNumber;
import com.estates.sacco.repo.AccountRepository;
import com.estates.sacco.repo.BranchRepository;
import com.estates.sacco.repo.GeneralLedgerRepository;
import com.estates.sacco.repo.LedgerRepository;
import com.estates.sacco.repo.MobileMoneyNumberRepository;
import com.estates.sacco.security.BranchScope;
import com.estates.sacco.service.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The mobile money numbers of each branch (at most two). The manager sets them up; each number gets its own chart account under
 * Mobile Money 1300, so deposits by mobile money can say which number received the money. Everyone in a branch can read its numbers.
 */
@RestController
@RequestMapping("/api/mobile-money")
@RequiredArgsConstructor
public class MobileMoneyController {
    static final int MAX_PER_BRANCH = 2;

    private final MobileMoneyNumberRepository numbers;
    private final BranchRepository branches;
    private final AccountRepository accounts;
    private final GeneralLedgerRepository generalLedger;
    private final LedgerRepository ledger;
    private final BranchScope scope;
    private final AuditService audit;
    private final com.estates.sacco.service.DeploymentSettings settings;
    private final com.estates.sacco.service.AccountCodes codes;

    public record NumberReq(Long branchId, @NotBlank String network, @NotBlank String number, String holderName, Boolean active) {}

    public record NumberDto(Long id, Long branchId, String branchCode, String network, String number, String holderName,
                            Long accountId, String accountCode, String accountName, boolean active) {
        static NumberDto of(MobileMoneyNumber n) {
            return new NumberDto(n.getId(), n.getBranch().getId(), n.getBranch().getCode(), n.getNetwork(), n.getNumber(), n.getHolderName(),
                    n.getAccount().getId(), n.getAccount().getCode(), n.getAccount().getName(), n.isActive());
        }
    }

    @GetMapping
    @Transactional(readOnly = true)
    List<NumberDto> list(@RequestParam(required = false) Long branchId) {
        Long scoped = scope.readBranch(branchId);
        return (scoped == null ? numbers.findAllByOrderByBranchIdAscIdAsc() : numbers.findByBranchIdOrderByIdAsc(scoped))
                .stream().map(NumberDto::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    NumberDto create(@Valid @RequestBody NumberReq req) {
        managerOnly();
        Long wanted = scope.isManager() ? req.branchId() : scope.me().getBranchId();      // a branch administrator sets up their own branch only
        if (wanted == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a branch");
        Branch branch = branches.findById(wanted).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Branch not found"));
        if (numbers.countByBranchId(branch.getId()) >= MAX_PER_BRANCH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A branch can have " + MAX_PER_BRANCH + " mobile money numbers. Change or remove one first.");
        }
        String number = cleanNumber(req.number());
        if (numbers.existsByNumber(number)) throw new ApiException(HttpStatus.CONFLICT, "That number is already set up");

        Account account = new Account();
        account.setCode(freeCode());
        account.setName(accountName(branch, req.network(), number));
        account.setType(AccountType.ASSET);
        if (!codes.getMobileMoneyParent().isBlank()) accounts.findByCode(codes.getMobileMoneyParent()).ifPresent(account::setParent);
        account.setDescription("Mobile money number of " + branch.getCode());
        accounts.save(account);

        MobileMoneyNumber n = new MobileMoneyNumber();
        n.setBranch(branch);
        n.setNetwork(req.network().trim());
        n.setNumber(number);
        n.setHolderName(blankToNull(req.holderName()));
        n.setAccount(account);
        numbers.save(n);
        audit.log(branch, "CREATE", "Mobile money number", n.getId(), n.getNetwork() + " " + number + " (account " + account.getCode() + ")");
        return NumberDto.of(n);
    }

    @PutMapping("/{id}")
    @Transactional
    NumberDto update(@PathVariable Long id, @Valid @RequestBody NumberReq req) {
        managerOnly();
        MobileMoneyNumber n = load(id);
        scope.assertAccess(n.getBranch().getId());
        String number = cleanNumber(req.number());
        if (numbers.existsByNumberAndIdNot(number, id)) throw new ApiException(HttpStatus.CONFLICT, "That number is already set up");
        n.setNetwork(req.network().trim());
        n.setNumber(number);
        n.setHolderName(blankToNull(req.holderName()));
        if (req.active() != null) n.setActive(req.active());
        Account a = n.getAccount();
        a.setName(accountName(n.getBranch(), n.getNetwork(), number));
        a.setActive(n.isActive());
        accounts.save(a);
        audit.log(n.getBranch(), "EDIT", "Mobile money number", n.getId(), n.getNetwork() + " " + number + (n.isActive() ? "" : " (switched off)"));
        return NumberDto.of(numbers.save(n));
    }

    /** A number that has taken part in transactions cannot be removed (switch it off instead). */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable Long id) {
        managerOnly();
        MobileMoneyNumber n = load(id);
        scope.assertAccess(n.getBranch().getId());
        Account a = n.getAccount();
        if (generalLedger.existsByAccountId(a.getId()) || ledger.existsByReceivingAccountId(a.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "Money has already moved through this number, so it cannot be removed. Switch it off instead.");
        }
        audit.log(n.getBranch(), "DELETE", "Mobile money number", n.getId(), n.getNetwork() + " " + n.getNumber());
        numbers.delete(n);
        accounts.delete(a);
    }

    private MobileMoneyNumber load(Long id) {
        return numbers.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Number not found"));
    }

    private void managerOnly() {
        boolean branchAdmin = settings.isSingleBranch() && scope.me().getRole() == com.estates.sacco.domain.Role.BRANCH_ADMIN;
        if (!scope.isManager() && !branchAdmin) throw new ApiException(HttpStatus.FORBIDDEN, "Only the manager sets up mobile money numbers");
    }

    /** First unused chart code in the range set aside for mobile money numbers (1301-1399 in the standard chart). */
    private String freeCode() {
        for (int c = codes.getMobileMoneyFrom(); c <= codes.getMobileMoneyTo(); c++) if (!accounts.existsByCode(String.valueOf(c))) return String.valueOf(c);
        throw new ApiException(HttpStatus.CONFLICT, "No free account code is left between " + codes.getMobileMoneyFrom() + " and " + codes.getMobileMoneyTo());
    }

    private static String accountName(Branch b, String network, String number) {
        return "Mobile Money - " + network.trim() + " " + number + " (" + b.getCode() + ")";
    }

    private static String cleanNumber(String raw) {
        String n = raw.replaceAll("[\\s-]", "");
        if (!n.matches("\\+?\\d{9,15}")) throw new ApiException(HttpStatus.BAD_REQUEST, "Enter the number with digits only, for example 0772123456");
        return n;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
