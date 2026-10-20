package com.estates.sacco.service;

import com.estates.sacco.domain.*;
import com.estates.sacco.repo.InterestAccrualRepository;
import com.estates.sacco.repo.LedgerRepository;
import com.estates.sacco.repo.LoanRepository;
import com.estates.sacco.repo.MemberRepository;
import com.estates.sacco.security.BranchScope;
import com.estates.sacco.web.ApiException;
import com.estates.sacco.web.Dtos.LoanDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;

/** Posts money movements and enforces the rules (no overdrawn savings, no over-repayment). */
@Service
@RequiredArgsConstructor
public class LedgerService {
    private final LedgerRepository ledger;
    private final LoanRepository loans;
    private final MemberRepository members;
    private final BranchScope scope;
    private final PostingService posting;
    private final LoanScheduleService schedules;
    private final LoanAccountingService accounting;
    private final InterestAccrualRepository accruals;

    public Member requireMember(Long id) {
        Member m = members.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Member not found"));
        scope.assertAccess(m.getBranch().getId());
        return m;
    }

    public Loan requireLoan(Long id) {
        Loan l = loans.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Loan not found"));
        scope.assertAccess(l.getBranch().getId());
        return l;
    }

    public BigDecimal savingsBalance(Long memberId) {
        return ledger.sumByMember(memberId, TxType.SAVINGS_DEPOSIT).add(ledger.sumByMember(memberId, TxType.SAVINGS_INTEREST_PAYOUT))
                .subtract(ledger.sumByMember(memberId, TxType.SAVINGS_WITHDRAWAL));
    }

    public BigDecimal sharesBalance(Long memberId) {
        return ledger.sumByMember(memberId, TxType.SHARE_PURCHASE)
                .subtract(ledger.sumByMember(memberId, TxType.SHARE_REFUND));
    }

    public BigDecimal outstanding(Loan l) {
        if (l.getStatus() != LoanStatus.ACTIVE && l.getStatus() != LoanStatus.CLEARED) return BigDecimal.ZERO;
        // still to be paid in total: the principal plus the interest of every installment (charged where a run has been done, planned otherwise)
        return schedules.contractualTotal(l).subtract(ledger.repaidOnLoan(l.getId()));
    }

    public LoanDto loanDto(Loan l) {
        BigDecimal repaid = ledger.repaidOnLoan(l.getId());
        BigDecimal remaining = outstanding(l);
        BigDecimal charged = ledger.sumByLoan(l.getId(), TxType.LOAN_INTEREST);
        boolean live = l.getStatus() == LoanStatus.ACTIVE || l.getStatus() == LoanStatus.CLEARED;
        // what the books show the member owing: principal paid out + interest charged so far - repaid
        BigDecimal outstanding = !live ? BigDecimal.ZERO : ledger.sumByLoan(l.getId(), TxType.LOAN_DISBURSEMENT).add(l.getBroughtForward()).add(charged)
                .subtract(repaid).max(BigDecimal.ZERO);
        return new LoanDto(l.getId(), l.getBranch().getId(), l.getBranch().getCode(), l.getMember().getId(),
                l.getMember().getFullName(), l.getPrincipal(), l.getAnnualRatePercent(), l.getTermMonths(),
                l.getTotalDue(), l.getApplicationFee(), repaid, outstanding, l.getStatus(), l.getAppliedOn(), l.getDisbursedOn(),
                l.getInterestMethod(), LoanScheduleService.monthlyInstallment(l.getInterestMethod(), l.getPrincipal(), l.getAnnualRatePercent(), l.getTermMonths()),
                remaining, charged, l.getTopUpOf() == null ? null : l.getTopUpOf().getId(), l.getTopUpSettled(),
                l.getProcessFee(), l.getInsuranceFee(), l.getLegalCharges());
    }

    /** What the books show the member owing on a loan: principal paid out (or brought forward) + interest charged - repaid. */
    public BigDecimal bookedOutstanding(Loan l) {
        return ledger.sumByLoan(l.getId(), TxType.LOAN_DISBURSEMENT).add(l.getBroughtForward()).add(ledger.sumByLoan(l.getId(), TxType.LOAN_INTEREST))
                .subtract(ledger.repaidOnLoan(l.getId())).max(BigDecimal.ZERO);
    }

    public Loan apply(Member member, BigDecimal principal, BigDecimal ratePercent, int termMonths, BigDecimal applicationFee, LocalDate loanDate,
                      InterestMethod method) {
        return apply(member, principal, ratePercent, termMonths, new LoanFees(applicationFee, null, null, null), loanDate, method, null);
    }

    /** {@code topUpOfLoanId}: this loan tops up an earlier active loan of the same member, whose balance its payout clears. */
    @Transactional
    public Loan apply(Member member, BigDecimal principal, BigDecimal ratePercent, int termMonths, LoanFees fees, LocalDate loanDate,
                      InterestMethod method, Long topUpOfLoanId) {
        requireFeesBelow(fees, principal);
        BigDecimal totalFees = fees.total();
        Loan old = null;
        if (topUpOfLoanId != null) {
            old = requireLoan(topUpOfLoanId);
            if (!old.getMember().getId().equals(member.getId())) throw new ApiException(HttpStatus.BAD_REQUEST, "That loan does not belong to this member");
            if (old.getStatus() != LoanStatus.ACTIVE) throw new ApiException(HttpStatus.CONFLICT, "Only an active loan can be topped up");
            if (loans.findByMemberId(member.getId()).stream().anyMatch(x -> x.getStatus() == LoanStatus.PENDING && x.getTopUpOf() != null
                    && x.getTopUpOf().getId().equals(topUpOfLoanId))) {
                throw new ApiException(HttpStatus.CONFLICT, "There is already a top-up waiting for loan #" + old.getId());
            }
            requireBiggerThanBalance(old, principal, totalFees);
        }
        Loan l = new Loan();
        l.setTopUpOf(old);
        l.setBranch(member.getBranch());
        l.setMember(member);
        l.setPrincipal(money(principal));
        l.setAnnualRatePercent(ratePercent);
        l.setTermMonths(termMonths);
        l.setAppliedOn(loanDate == null ? LocalDate.now() : loanDate);
        setFees(l, fees);
        l.setInterestMethod(method == null ? InterestMethod.REDUCING_BALANCE : method);
        l.setTotalDue(LoanScheduleService.totalDue(l.getInterestMethod(), l.getPrincipal(), ratePercent, termMonths));
        return loans.save(l);
    }

    /** Changes the terms of a loan that has not been paid out yet. */
    @Transactional
    public Loan updatePending(Loan l, BigDecimal principal, BigDecimal ratePercent, int termMonths, LoanFees fees, LocalDate loanDate) {
        requireFeesBelow(fees, principal);
        if (l.getStatus() != LoanStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "Only a pending loan can be edited. Delete and re-enter an active loan if it was wrong.");
        }
        l.setPrincipal(money(principal));
        l.setAnnualRatePercent(ratePercent);
        l.setTermMonths(termMonths);
        l.setAppliedOn(loanDate == null ? l.getAppliedOn() : loanDate);
        setFees(l, fees);
        l.setTotalDue(LoanScheduleService.totalDue(l.getInterestMethod(), l.getPrincipal(), ratePercent, termMonths));
        return loans.save(l);
    }

    /** The charges are taken out of what is paid out, so together they must be less than the loan. */
    private static void requireFeesBelow(LoanFees fees, BigDecimal principal) {
        if (fees.total().compareTo(principal) >= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The fees (application, process, insurance and legal charges) must together be less than the loan amount, because they are taken out of what is paid out");
        }
    }

    private static void setFees(Loan l, LoanFees fees) {
        l.setApplicationFee(fees.application());
        l.setProcessFee(fees.process());
        l.setInsuranceFee(fees.insurance());
        l.setLegalCharges(fees.legal());
    }

    /** A top-up must pay out something to the member: the new amount less the fee has to be more than the old loan's balance. */
    private void requireBiggerThanBalance(Loan old, BigDecimal principal, BigDecimal fee) {
        BigDecimal owed = bookedOutstanding(old);
        BigDecimal net = money(principal).subtract(fee == null ? BigDecimal.ZERO : money(fee));
        if (net.compareTo(owed) <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A top-up must be bigger than what the member still owes on loan #" + old.getId()
                    + " (UGX " + owed.toPlainString() + ") plus the fees");
        }
    }

    /** The earlier loan's interest must be on the books for every month that has ended, or the balance cleared would be too low. */
    private void requireInterestPosted(Loan old, LocalDate day) {
        YearMonth upTo = YearMonth.from(day);
        for (YearMonth m = YearMonth.from(old.getDisbursedOn()); m.isBefore(upTo); m = m.plusMonths(1)) {
            if (!accruals.existsByLoanIdAndMonth(old.getId(), m.toString()) && schedules.monthAccrual(old, m).interest().signum() > 0) {
                throw new ApiException(HttpStatus.CONFLICT, "Post the interest for " + m + " on the Interest computation screen first, so loan #"
                        + old.getId() + " is cleared with all its interest");
            }
        }
    }

    public Loan disburse(Loan loan, Source source, String reference, LocalDate date) {
        return disburse(loan, source, reference, date, null);
    }

    /** {@code paidFrom}: the chart account the loan is paid out from; null derives it from the source (cash, bank or mobile money). */
    @Transactional
    public Loan disburse(Loan loan, Source source, String reference, LocalDate date, Account paidFrom) {
        if (loan.getStatus() != LoanStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "Only a pending loan can be disbursed");
        }
        LocalDate day = date == null ? LocalDate.now() : date;
        Loan old = loan.getTopUpOf();
        BigDecimal cleared = BigDecimal.ZERO;
        if (old != null) {                       // a top-up: the earlier loan's balance (principal and interest) is cleared by this payout
            if (old.getStatus() != LoanStatus.ACTIVE) {
                throw new ApiException(HttpStatus.CONFLICT, "Loan #" + old.getId() + " is no longer active, so it cannot be topped up");
            }
            requireInterestPosted(old, day);
            cleared = bookedOutstanding(old);
            requireBiggerThanBalance(old, loan.getPrincipal(), LoanFees.of(loan).total());
            loan.setTopUpSettled(cleared);
        }
        loan.setStatus(LoanStatus.ACTIVE);
        loan.setInterestAccrual(true);     // interest is charged month by month from now on
        loan.setDisbursedOn(date == null ? LocalDate.now() : date);
        loans.save(loan);
        if (old != null && cleared.signum() > 0) {
            // the earlier loan is repaid by the system: no cash moves, the payout below is net of it
            post(old.getMember(), TxType.LOAN_REPAYMENT, cleared, Source.SYSTEM, day, "Top-up settlement by loan #" + loan.getId(), old, null);
            if (old.getStatus() != LoanStatus.CLEARED) {
                old.setStatus(LoanStatus.CLEARED);
                loans.save(old);
            }
        }
        post(loan.getMember(), TxType.LOAN_DISBURSEMENT, loan.getPrincipal(), source, date, reference, loan, null, paidFrom);
        schedules.generate(loan);          // the installments are written, with their due dates, the day the loan is paid out
        return loan;
    }

    public LedgerEntry post(Member m, TxType type, BigDecimal amount, Source source, LocalDate date,
                            String reference, Loan loan, PayrollBatch batch) {
        return post(m, type, amount, source, date, reference, loan, batch, null);
    }

    public LedgerEntry post(Member m, TxType type, BigDecimal amount, Source source, LocalDate date,
                            String reference, Loan loan, PayrollBatch batch, Account receiving) {
        return post(m, type, amount, source, date, reference, loan, batch, receiving, null);
    }

    /** {@code receiving} is the chart account the money went to or came from; null derives it from the source. */
    public LedgerEntry post(Member m, TxType type, BigDecimal amount, Source source, LocalDate date,
                            String reference, Loan loan, PayrollBatch batch, Account receiving, ProductDef product) {
        return post(m, type, amount, source, date, reference, loan, batch, receiving, product, null);
    }

    /** {@code depositGroup} ties the parts of one regular deposit together (one receiving-account debit for the total). */
    public LedgerEntry post(Member m, TxType type, BigDecimal amount, Source source, LocalDate date,
                            String reference, Loan loan, PayrollBatch batch, Account receiving, ProductDef product, String depositGroup) {
        return post(m, type, amount, source, date, reference, loan, batch, receiving, product, depositGroup, null);
    }

    /** {@code fee} is the savings withdrawal fee kept out of the amount paid (null for none). */
    @Transactional
    public LedgerEntry post(Member m, TxType type, BigDecimal amount, Source source, LocalDate date,
                            String reference, Loan loan, PayrollBatch batch, Account receiving, ProductDef product, String depositGroup,
                            BigDecimal fee) {
        BigDecimal amt = money(amount);
        if (amt.signum() <= 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Amount must be positive");
        BigDecimal keep = fee == null ? BigDecimal.ZERO : money(fee);
        if (keep.signum() < 0 || (keep.signum() > 0 && (type != TxType.SAVINGS_WITHDRAWAL || keep.compareTo(amt) >= 0))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The withdrawal fee must be less than the amount withdrawn");
        }

        switch (type) {
            case SAVINGS_WITHDRAWAL -> {
                if (savingsBalance(m.getId()).compareTo(amt) < 0)
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient savings balance");
            }
            case SHARE_REFUND -> {
                if (sharesBalance(m.getId()).compareTo(amt) < 0)
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient share balance");
            }
            case LOAN_REPAYMENT -> {
                if (loan == null || loan.getStatus() != LoanStatus.ACTIVE)
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Loan is not active");
                if (!loan.getMember().getId().equals(m.getId()))
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Loan does not belong to this member");
                if (outstanding(loan).compareTo(amt) < 0)
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Repayment exceeds outstanding balance");
            }
            default -> { }
        }

        LedgerEntry e = new LedgerEntry();
        e.setBranch(m.getBranch());
        e.setMember(m);
        e.setLoan(loan);
        e.setPayrollBatch(batch);
        e.setType(type);
        e.setSource(source == null ? Source.CASH : source);
        e.setReceivingAccount(receiving);
        e.setProduct(product);
        e.setDepositGroup(depositGroup);
        e.setAmount(amt);
        e.setFee(keep);
        e.setReference(reference);
        e.setPostedOn(date == null ? LocalDate.now() : date);
        e.setCreatedBy(scope.me().getUsername());
        ledger.save(e);
        posting.record(e);   // member ledger + general ledger, same transaction

        if (type == TxType.LOAN_REPAYMENT && outstanding(loan).signum() == 0) {
            loan.setStatus(LoanStatus.CLEARED);
            loans.save(loan);
        }
        if (type == TxType.LOAN_REPAYMENT) {
            schedules.reallocate(loan);
            accounting.repostRepayments(loan);   // a back-dated repayment can change how later ones split
        }
        return e;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
