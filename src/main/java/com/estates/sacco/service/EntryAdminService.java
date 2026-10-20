package com.estates.sacco.service;

import com.estates.sacco.domain.*;
import com.estates.sacco.repo.*;
import com.estates.sacco.security.BranchScope;
import com.estates.sacco.web.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Edits and deletes posted transactions without breaking the books. A change removes the transaction's
 * member-ledger and general-ledger lines and, for an edit, posts them again from the new values, all in one
 * database transaction. Anything that would leave a negative savings, shares or loan balance is refused
 * and nothing is changed. Every edit and delete is written to the audit log.
 */
@Service
@RequiredArgsConstructor
public class EntryAdminService {
    private final LedgerRepository ledger;
    private final MemberLedgerRepository memberLedger;
    private final GeneralLedgerRepository generalLedger;
    private final LoanRepository loans;
    private final PayrollBatchRepository batches;
    private final AccountRepository accounts;
    private final ProductDefRepository products;
    private final LedgerService ledgerService;
    private final PostingService posting;
    private final LoanScheduleService schedules;
    private final LoanAccountingService accounting;
    private final InterestRunService interestRuns;
    private final AuditService audit;
    private final BranchScope scope;
    private final AccountCodes codes;

    public LedgerEntry requireEntry(Long id) {
        LedgerEntry e = ledger.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Transaction not found"));
        scope.assertAccess(e.getBranch().getId());
        return e;
    }

    /** Why a transaction cannot be changed on its own, or null if it can. */
    public String lockedReason(LedgerEntry e) {
        if (e.getPayrollBatch() != null) {
            return "Part of payroll batch #" + e.getPayrollBatch().getId() + ". Edit or delete the whole batch on the Payroll screen.";
        }
        if (e.getType() == TxType.LOAN_DISBURSEMENT) {
            return "This is the payout of loan #" + e.getLoan().getId() + ". Edit or delete the loan instead.";
        }
        if (e.getType() == TxType.LOAN_REPAYMENT && e.getSource() == Source.SYSTEM) {
            return "This loan was cleared by a top-up. Delete the top-up loan to undo it.";
        }
        if (e.getType() == TxType.LOAN_INTEREST) {
            return "Interest charged by the monthly interest run. Delete that run on the Interest computation screen to change it.";
        }
        if (e.getType() == TxType.LOAN_INTEREST_REVERSAL) {
            return "A one-off correction made when the loan moved to monthly interest. It cannot be changed here.";
        }
        return null;
    }

    // ------------------------------------------------------------------ single transactions
    @Transactional
    public LedgerEntry edit(Long id, BigDecimal amount, LocalDate date, String reference, String notes,
                            Long receivingAccountId, Long productId, BigDecimal fee) {
        LedgerEntry e = requireEntry(id);
        String locked = lockedReason(e);
        if (locked != null) throw new ApiException(HttpStatus.CONFLICT, locked);
        String before = describe(e);
        BigDecimal newFee = e.getType() == TxType.SAVINGS_WITHDRAWAL && fee != null ? fee.setScale(2, RoundingMode.HALF_UP) : e.getFee();
        if (newFee == null) newFee = BigDecimal.ZERO;
        if (newFee.signum() < 0 || (newFee.signum() > 0 && newFee.compareTo(amount) >= 0)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The withdrawal fee must be less than the amount withdrawn");
        }

        Account receiving = e.getReceivingAccount();
        if (receivingAccountId != null) {
            receiving = asset(receivingAccountId);
            e.setSource(codes.sourceOf(receiving));
        }
        ProductDef product = e.getProduct();
        if (productId != null && e.getType() != TxType.LOAN_REPAYMENT) {
            product = products.findById(productId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Product not found"));
            if (!product.getBranch().getId().equals(e.getBranch().getId()) || !product.isActive()
                    || product.getCategory() != PostingService.categoryOf(e.getType())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "That product cannot be used for this transaction");
            }
        }

        // a part of a regular deposit: date, receipt, notes and receiving account belong to the whole deposit
        List<LedgerEntry> parts = e.getDepositGroup() == null ? List.of(e) : ledger.findByDepositGroupOrderByIdAsc(e.getDepositGroup());
        for (LedgerEntry p : parts) removeLines(p);
        for (LedgerEntry p : parts) {
            if (p.getId().equals(e.getId())) {
                p.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
                p.setFee(newFee);
                p.setProduct(product);
            }
            if (date != null) p.setPostedOn(date);
            p.setReference(blankToNull(reference));
            p.setNotes(blankToNull(notes));
            if (receivingAccountId != null) {
                p.setReceivingAccount(receiving);
                p.setSource(codes.sourceOf(receiving));
            }
            ledger.save(p);
        }
        for (LedgerEntry p : parts) posting.record(p);
        for (LedgerEntry p : parts) if (p.getLoan() != null) schedules.reallocate(p.getLoan());
        checkIntegrity(e.getMember(), e.getLoan());
        audit.log(e.getBranch(), "EDIT", "Transaction", e.getId(), "Was: " + before + " | Now: " + describe(e));
        return e;
    }

    @Transactional
    public void delete(Long id) {
        LedgerEntry e = requireEntry(id);
        String locked = lockedReason(e);
        if (locked != null) throw new ApiException(HttpStatus.CONFLICT, locked);
        String before = describe(e);
        String group = e.getDepositGroup();
        remove(e);
        if (group != null) posting.refreshGroupCash(group);   // the one receiving-account line now totals what is left
        checkIntegrity(e.getMember(), e.getLoan());
        audit.log(e.getBranch(), "DELETE", "Transaction", id, before);
    }

    // ------------------------------------------------------------------ loans
    @Transactional
    public Loan editLoan(Loan loan, BigDecimal principal, BigDecimal rate, int term, LoanFees fees, LocalDate date) {
        String before = describe(loan);
        Loan saved = ledgerService.updatePending(loan, principal, rate, term, fees, date);
        audit.log(loan.getBranch(), "EDIT", "Loan", loan.getId(), "Was: " + before + " | Now: " + describe(saved));
        return saved;
    }

    /** A loan can go if nothing has been repaid on it. Its payout lines are removed with it. */
    @Transactional
    public void deleteLoan(Loan loan) {
        List<LedgerEntry> entries = ledger.findByLoanIdOrderByIdAsc(loan.getId());
        if (loan.getStatus() == LoanStatus.CLEARED || entries.stream().anyMatch(e -> e.getType() == TxType.LOAN_REPAYMENT)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This loan has repayments. Delete the repayments first (Regular deposit history), then the loan.");
        }
        String before = describe(loan);
        interestRuns.forgetLoan(loan);     // its interest lines leave the monthly runs
        entries.forEach(this::remove);
        ledger.flush();
        schedules.remove(loan);
        Loan old = loan.getTopUpOf();
        loans.delete(loan);
        if (old != null && old.getStatus() == LoanStatus.CLEARED) {        // a top-up that was paid out: the earlier loan is open again
            String ref = "Top-up settlement by loan #" + loan.getId();
            for (LedgerEntry s : ledger.findByLoanIdOrderByIdAsc(old.getId())) {
                if (s.getType() == TxType.LOAN_REPAYMENT && s.getSource() == Source.SYSTEM && ref.equals(s.getReference())) remove(s);
            }
            old.setStatus(LoanStatus.ACTIVE);
            loans.save(old);
            ledger.flush();
            schedules.reallocate(old);
            accounting.repostRepayments(old);
        }
        audit.log(loan.getBranch(), "DELETE", "Loan", loan.getId(), before);
    }

    // ------------------------------------------------------------------ payroll batches
    @Transactional
    public PayrollBatch editBatch(PayrollBatch batch, String employer, String period, Long receivingAccountId, LocalDate date) {
        Long branchId = batch.getBranch().getId();
        if (batches.existsByBranchIdAndEmployerIgnoreCaseAndPeriodAndIdNot(branchId, employer.trim(), period, batch.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "Payroll for this employer and period already exists");
        }
        String before = describe(batch);
        Account receiving = asset(receivingAccountId);
        LocalDate day = date == null ? batch.getPaymentDate() : date;
        batch.setEmployer(employer.trim());
        batch.setPeriod(period);
        batch.setPaymentDate(day);
        batch.setReceivingAccount(receiving);
        String ref = "PAYROLL " + period + " " + batch.getEmployer();
        for (LedgerEntry e : ledger.findByPayrollBatchId(batch.getId())) {
            removeLines(e);
            e.setReference(ref);
            if (day != null) e.setPostedOn(day);
            e.setReceivingAccount(receiving);
            ledger.save(e);
            posting.record(e);
        }
        batches.save(batch);
        audit.log(batch.getBranch(), "EDIT", "Payroll batch", batch.getId(), "Was: " + before + " | Now: " + describe(batch));
        return batch;
    }

    @Transactional
    public void deleteBatch(PayrollBatch batch) {
        String before = describe(batch);
        List<LedgerEntry> entries = ledger.findByPayrollBatchId(batch.getId());
        Map<Long, Member> members = new LinkedHashMap<>();
        Map<Long, Loan> touchedLoans = new LinkedHashMap<>();
        for (LedgerEntry e : entries) {
            members.put(e.getMember().getId(), e.getMember());
            if (e.getLoan() != null) touchedLoans.put(e.getLoan().getId(), e.getLoan());
            remove(e);
        }
        ledger.flush();
        members.values().forEach(m -> checkIntegrity(m, null));
        touchedLoans.values().forEach(l -> checkIntegrity(l.getMember(), l));
        batches.delete(batch);
        audit.log(batch.getBranch(), "DELETE", "Payroll batch", batch.getId(), before + " (" + entries.size() + " entries)");
    }

    // ------------------------------------------------------------------ helpers
    /** Removes a transaction together with its ledger lines. */
    private void remove(LedgerEntry e) {
        removeLines(e);
        ledger.delete(e);
        ledger.flush();
    }

    private void removeLines(LedgerEntry e) {
        generalLedger.deleteByLedgerEntryId(e.getId());
        memberLedger.deleteByLedgerEntryId(e.getId());
        generalLedger.flush();
    }

    /** Refuses a change that would leave a negative savings, shares or loan balance; keeps the loan status in step. */
    void checkIntegrity(Member member, Loan loan) {
        ledger.flush();
        if (ledgerService.savingsBalance(member.getId()).signum() < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "That would leave this member's savings balance negative");
        }
        if (ledgerService.sharesBalance(member.getId()).signum() < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "That would leave this member's share balance negative");
        }
        if (loan != null && (loan.getStatus() == LoanStatus.ACTIVE || loan.getStatus() == LoanStatus.CLEARED)) {
            BigDecimal outstanding = ledgerService.outstanding(loan);
            if (outstanding.signum() < 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "That would repay more than the loan owes");
            }
            loan.setStatus(outstanding.signum() == 0 ? LoanStatus.CLEARED : LoanStatus.ACTIVE);
            loans.save(loan);
            schedules.reallocate(loan);   // repayments changed: re-match them to the installments
            accounting.repostRepayments(loan);   // ...and re-split them between interest and principal
        }
    }

    private Account asset(Long id) {
        Account a = accounts.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Receiving account not found"));
        if (a.getType() != AccountType.ASSET || !a.isActive()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The receiving account must be an active asset account (cash, bank or mobile money)");
        }
        return a;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String describe(LedgerEntry e) {
        return e.getType() + " " + e.getAmount() + " member " + e.getMember().getMemberNo() + " on " + e.getPostedOn()
                + (e.getReference() == null ? "" : " ref " + e.getReference());
    }

    private static String describe(Loan l) {
        return "loan #" + l.getId() + " " + l.getMember().getMemberNo() + " principal " + l.getPrincipal() + " at "
                + l.getAnnualRatePercent() + "% for " + l.getTermMonths() + " months, fees " + LoanFees.of(l).total() + " (application " + l.getApplicationFee() + ", process " + l.getProcessFee()
                + ", insurance " + l.getInsuranceFee() + ", legal " + l.getLegalCharges() + "), " + l.getStatus();
    }

    private static String describe(PayrollBatch b) {
        return "payroll #" + b.getId() + " " + b.getEmployer() + " " + b.getPeriod() + " total " + b.getTotalAmount() + " on " + b.getPaymentDate();
    }
}
