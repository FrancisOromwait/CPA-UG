package com.estates.sacco.web;

import com.estates.sacco.domain.*;
import com.estates.sacco.repo.AccountRepository;
import com.estates.sacco.repo.LedgerRepository;
import com.estates.sacco.repo.ProductDefRepository;
import com.estates.sacco.security.BranchScope;
import com.estates.sacco.service.LedgerService;
import com.estates.sacco.service.PostingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Savings withdrawal: money paid out to a member from their savings. Debits the member's savings (member ledger and savings
 * account) and credits the account it was paid from (cash, bank or mobile money). A member cannot withdraw more than they have.
 * Edit and delete go through the common transaction screens, like every other entry.
 */
@RestController
@RequestMapping("/api/withdrawals")
@RequiredArgsConstructor
public class WithdrawalController {
    private final LedgerService ledgerService;
    private final LedgerRepository ledger;
    private final ProductDefRepository products;
    private final AccountRepository accounts;
    private final BranchScope scope;
    private final com.estates.sacco.service.AccountCodes codes;

    public record WithdrawalReq(@NotNull Long memberId, @NotNull @Positive BigDecimal amount, LocalDate date, String reference,
                                @NotNull Long paidFromAccountId, Long savingsProductId, String notes,
                                @jakarta.validation.constraints.PositiveOrZero BigDecimal fee) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    DepositController.DepositDto record(@Valid @RequestBody WithdrawalReq req) {
        Long branchId = scope.writeBranch();
        Member member = ledgerService.requireMember(req.memberId());      // 403 for another branch's member
        Account paidFrom = accounts.findById(req.paidFromAccountId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Paying account not found"));
        if (paidFrom.getType() != AccountType.ASSET || !paidFrom.isActive()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The paying account must be an active asset account (cash, bank or mobile money)");
        }
        ProductDef product = null;
        if (req.savingsProductId() != null) {
            product = products.findById(req.savingsProductId()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Product not found"));
            if (!product.getBranch().getId().equals(branchId) || !product.isActive() || product.getCategory() != ProductCategory.SAVINGS) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Product " + product.getProductCode() + " cannot be used for savings");
            }
        }
        String ref = req.reference() == null || req.reference().isBlank() ? "Savings withdrawal" : req.reference().trim();
        LedgerEntry e = ledgerService.post(member, TxType.SAVINGS_WITHDRAWAL, req.amount(), codes.sourceOf(paidFrom),
                req.date() == null ? LocalDate.now() : req.date(), ref, null, null, paidFrom, product, null, req.fee());
        if (req.notes() != null && !req.notes().isBlank()) e.setNotes(req.notes().trim());
        return DepositController.DepositDto.of(e);
    }

    @GetMapping
    @Transactional(readOnly = true)
    List<DepositController.DepositDto> list(@RequestParam(required = false) Long branchId,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                            @RequestParam(defaultValue = "300") int limit) {
        return ledger.search(scope.readBranch(branchId), null, TxType.SAVINGS_WITHDRAWAL,
                        from == null ? LocalDate.of(2000, 1, 1) : from, to == null ? LocalDate.of(2999, 12, 31) : to,
                        PageRequest.of(0, Math.max(1, Math.min(limit, 1000))))
                .stream().map(DepositController.DepositDto::of).toList();
    }
}
