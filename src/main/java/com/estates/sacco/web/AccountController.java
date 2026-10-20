package com.estates.sacco.web;

import com.estates.sacco.domain.Account;
import com.estates.sacco.domain.AccountType;
import com.estates.sacco.repo.AccountRepository;
import com.estates.sacco.repo.GeneralLedgerRepository;
import com.estates.sacco.repo.LedgerRepository;
import com.estates.sacco.repo.PayrollBatchRepository;
import com.estates.sacco.repo.ProductDefRepository;
import com.estates.sacco.service.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The chart of accounts, one chart shared by all branches. Everyone signed in can read it;
 * only the manager can add, rename, deactivate or delete accounts (enforced in SecurityConfig).
 */
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {
    private final AccountRepository accounts;
    private final GeneralLedgerRepository generalLedger;
    private final ProductDefRepository productRepo;
    private final LedgerRepository ledger;
    private final PayrollBatchRepository batchRepo;
    private final AuditService audit;
    private final com.estates.sacco.repo.MobileMoneyNumberRepository momoNumbers;
    private final com.estates.sacco.security.BranchScope scope;

    public record AccountReq(@NotBlank @Size(max = 20) @Pattern(regexp = "[A-Za-z0-9.-]+", message = "may only contain letters, digits, '.' and '-'") String code,
                             @NotBlank String name, @NotNull AccountType type, Long parentId, String description) {}

    public record AccountUpdate(@NotBlank String name, Long parentId, String description) {}

    public record AccountDto(Long id, String code, String name, AccountType type,
                             Long parentId, String parentName, String description, boolean active, boolean system) {
        static AccountDto of(Account a) {
            Account p = a.getParent();
            return new AccountDto(a.getId(), a.getCode(), a.getName(), a.getType(),
                    p == null ? null : p.getId(), p == null ? null : p.getCode() + " " + p.getName(),
                    a.getDescription(), a.isActive(), a.isSystemAccount());
        }
    }

    @GetMapping
    @Transactional(readOnly = true)
    List<AccountDto> list(@RequestParam(required = false) AccountType type) {
        // another branch's mobile money numbers are not shown to this branch
        java.util.Set<Long> hidden = scope.isManager() ? java.util.Set.of()
                : momoNumbers.findByBranchIdNot(scope.me().getBranchId()).stream().map(n -> n.getAccount().getId()).collect(java.util.stream.Collectors.toSet());
        return accounts.search(type).stream().filter(a -> !hidden.contains(a.getId())).map(AccountDto::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    AccountDto create(@Valid @RequestBody AccountReq req) {
        String code = req.code().trim();
        if (accounts.existsByCode(code)) {
            throw new ApiException(HttpStatus.CONFLICT, "Account code " + code + " is already used");
        }
        Account a = new Account();
        a.setCode(code);
        a.setName(req.name().trim());
        a.setType(req.type());
        a.setDescription(req.description());
        a.setParent(resolveParent(req.parentId(), a));
        Account saved = accounts.save(a);
        audit.log(null, "EDIT", "Account", saved.getId(), "Added " + saved.getCode() + " " + saved.getName());
        return AccountDto.of(saved);
    }

    @PutMapping("/{id}")
    @Transactional
    AccountDto update(@PathVariable Long id, @Valid @RequestBody AccountUpdate req) {
        Account a = load(id);
        String before = a.getCode() + " " + a.getName();
        a.setName(req.name().trim());
        a.setDescription(req.description());
        a.setParent(resolveParent(req.parentId(), a));
        audit.log(null, "EDIT", "Account", a.getId(), "Was: " + before + " | Now: " + a.getCode() + " " + a.getName());
        return AccountDto.of(accounts.save(a));
    }

    @PutMapping("/{id}/active/{value}")
    @Transactional
    AccountDto setActive(@PathVariable Long id, @PathVariable boolean value) {
        Account a = load(id);
        a.setActive(value);
        audit.log(null, "EDIT", "Account", a.getId(), a.getCode() + " " + a.getName() + (value ? " activated" : " deactivated"));
        return AccountDto.of(accounts.save(a));
    }

    private Account load(Long id) {
        return accounts.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));
    }

    /** A parent must have the same type and must not create a loop. */
    private Account resolveParent(Long parentId, Account child) {
        if (parentId == null) return null;
        Account p = load(parentId);
        if (p.getType() != child.getType()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Parent must be an account of the same type");
        }
        for (Account up = p; up != null; up = up.getParent()) {
            if (child.getId() != null && up.getId().equals(child.getId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "An account cannot be its own parent");
            }
        }
        return p;
    }

    /** An account that has never been used can be deleted. Otherwise deactivate it. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable Long id) {
        Account a = load(id);
        if (generalLedger.existsByAccountId(id) || productRepo.existsByGlAccountId(id) || productRepo.existsByInterestIncomeAccountId(id)
                || accounts.existsByParentId(id) || ledger.existsByReceivingAccountId(id) || batchRepo.existsByReceivingAccountId(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "This account is in use (transactions, products or sub-accounts). Deactivate it instead.");
        }
        audit.log(null, "DELETE", "Account", a.getId(), a.getCode() + " " + a.getName());
        accounts.delete(a);
    }
}
