package com.estates.sacco.web;

import com.estates.sacco.domain.*;
import com.estates.sacco.repo.AccountRepository;
import com.estates.sacco.repo.LedgerRepository;
import com.estates.sacco.repo.LoanRepository;
import com.estates.sacco.repo.ProductDefRepository;
import com.estates.sacco.security.BranchScope;
import com.estates.sacco.service.LedgerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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
 * Regular deposit: one payment from one member, split across savings, shares and loan repayment.
 * The split must add up to the total. Each part is posted to the member ledger and the general ledger,
 * debiting the chosen receiving account. (Payroll deductions have their own screen.)
 */
@RestController
@RequestMapping("/api/deposits")
@RequiredArgsConstructor
public class DepositController {
    private final LedgerService ledgerService;
    private final LedgerRepository ledger;
    private final LoanRepository loans;
    private final ProductDefRepository products;
    private final AccountRepository accounts;
    private final BranchScope scope;
    private final com.estates.sacco.service.AccountCodes codes;

    public record DepositReq(@NotNull Long memberId, LocalDate date, String reference,
                             @NotNull @Positive BigDecimal totalAmount,
                             @PositiveOrZero BigDecimal savings, @PositiveOrZero BigDecimal shares,
                             @PositiveOrZero BigDecimal loanRepayment,
                             Long savingsProductId, Long sharesProductId, Long loanId,
                             @NotNull Long receivingAccountId, String notes) {}

    public record DepositResult(int entries, BigDecimal total, LocalDate date) {}

    public record DepositDto(Long id, LocalDate date, String branchCode, String memberNo, String memberName,
                             TxType type, String productCode, String productDetails, String receivingAccount,
                             Source source, String reference, String notes, BigDecimal amount, BigDecimal fee) {
        static DepositDto of(LedgerEntry e) {
            ProductDef p = e.getProduct();
            Account a = e.getReceivingAccount();
            return new DepositDto(e.getId(), e.getPostedOn(), e.getBranch().getCode(), e.getMember().getMemberNo(),
                    e.getMember().getFullName(), e.getType(), p == null ? null : p.getProductCode(),
                    p == null ? null : p.getName(), a == null ? null : a.getCode() + " " + a.getName(),
                    e.getSource(), e.getReference(), e.getNotes(), e.getAmount(), e.getFee());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    DepositResult record(@Valid @RequestBody DepositReq req) {
        Long branchId = scope.writeBranch();
        BigDecimal savings = nz(req.savings()), shares = nz(req.shares()), repay = nz(req.loanRepayment());
        BigDecimal split = savings.add(shares).add(repay);
        if (split.compareTo(req.totalAmount()) != 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The split (" + split.toPlainString() + ") must add up to the total amount ("
                    + req.totalAmount().toPlainString() + ")");
        }

        Member member = ledgerService.requireMember(req.memberId());   // 403 for another branch's member
        Account receiving = accounts.findById(req.receivingAccountId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Receiving account not found"));
        if (receiving.getType() != AccountType.ASSET || !receiving.isActive()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The receiving account must be an active asset account (cash, bank or mobile money)");
        }
        LocalDate date = req.date() == null ? LocalDate.now() : req.date();
        String ref = req.reference() == null || req.reference().isBlank() ? "Regular deposit" : req.reference().trim();
        if (ref.startsWith(MobilePaymentController.REF_PREFIX) && ledger.existsByBranchIdAndReference(branchId, ref)) {
            throw new ApiException(HttpStatus.CONFLICT, "The mobile money transaction " + ref.substring(MobilePaymentController.REF_PREFIX.length()) + " has already been recorded");
        }
        String notes = req.notes() == null || req.notes().isBlank() ? null : req.notes().trim();
        Source source = codes.sourceOf(receiving);

        String group = java.util.UUID.randomUUID().toString();   // the parts below are one deposit
        int entries = 0;
        if (savings.signum() > 0) {
            post(member, TxType.SAVINGS_DEPOSIT, savings, source, date, ref, notes, null,
                    receiving, product(req.savingsProductId(), branchId, ProductCategory.SAVINGS), group);
            entries++;
        }
        if (shares.signum() > 0) {
            post(member, TxType.SHARE_PURCHASE, shares, source, date, ref, notes, null,
                    receiving, product(req.sharesProductId(), branchId, ProductCategory.SHARES), group);
            entries++;
        }
        if (repay.signum() > 0) {
            post(member, TxType.LOAN_REPAYMENT, repay, source, date, ref, notes, loanFor(member, req.loanId()), receiving, null, group);
            entries++;
        }
        return new DepositResult(entries, req.totalAmount(), date);
    }

    @GetMapping
    @Transactional(readOnly = true)
    List<DepositDto> list(@RequestParam(required = false) Long branchId,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          @RequestParam(defaultValue = "300") int limit) {
        return ledger.searchDeposits(scope.readBranch(branchId),
                        from == null ? LocalDate.of(2000, 1, 1) : from, to == null ? LocalDate.of(2999, 12, 31) : to,
                        PageRequest.of(0, Math.max(1, Math.min(limit, 1000))))
                .stream().map(DepositDto::of).toList();
    }

    private void post(Member m, TxType type, BigDecimal amount, Source source, LocalDate date, String ref, String notes,
                      Loan loan, Account receiving, ProductDef product, String group) {
        LedgerEntry e = ledgerService.post(m, type, amount, source, date, ref, loan, null, receiving, product, group);
        e.setNotes(notes);
    }

    /** The chosen product, or null for the branch default. It must be an active product of the right category. */
    private ProductDef product(Long id, Long branchId, ProductCategory expected) {
        if (id == null) return null;
        ProductDef p = products.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Product not found"));
        if (!p.getBranch().getId().equals(branchId) || !p.isActive() || p.getCategory() != expected) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Product " + p.getProductCode() + " cannot be used for "
                    + expected.name().toLowerCase());
        }
        return p;
    }

    /** The loan being repaid: the one given, or the member's only active loan. */
    private Loan loanFor(Member member, Long loanId) {
        if (loanId != null) {
            Loan l = ledgerService.requireLoan(loanId);
            if (!l.getMember().getId().equals(member.getId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "That loan does not belong to this member");
            }
            return l;
        }
        List<Loan> active = loans.findByMemberId(member.getId()).stream().filter(l -> l.getStatus() == LoanStatus.ACTIVE).toList();
        if (active.size() == 1) return active.get(0);
        throw new ApiException(HttpStatus.BAD_REQUEST, active.isEmpty()
                ? "This member has no active loan to repay" : "Choose which loan is being repaid");
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
