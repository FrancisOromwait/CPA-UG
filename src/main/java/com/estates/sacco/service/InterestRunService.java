package com.estates.sacco.service;

import com.estates.sacco.domain.*;
import com.estates.sacco.repo.*;
import com.estates.sacco.security.BranchScope;
import com.estates.sacco.service.LoanScheduleService.MonthAccrual;
import com.estates.sacco.web.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * The monthly interest computation. For a branch and a month it charges every loan the interest it earned in that month:
 * Dr interest receivable, Cr interest income (dated the last day of the month), and the interest is added to the member's loan
 * in the member ledger. A loan is charged for a month once; a month can be computed again to pick up loans it missed, and a run
 * can be deleted and redone.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterestRunService {
    private final InterestRunRepository runs;
    private final InterestAccrualRepository accruals;
    private final LoanRepository loans;
    private final LedgerRepository ledger;
    private final GeneralLedgerRepository generalLedger;
    private final MemberLedgerRepository memberLedger;
    private final BranchRepository branches;
    private final LoanScheduleService schedules;
    private final LoanAccountingService accounting;
    private final PostingService posting;
    private final AuditService audit;
    private final BranchScope scope;

    /** The first month this system charges interest for; earlier months were charged in the old system and came over in the ledgers. */
    @org.springframework.beans.factory.annotation.Value("${sacco.interest.first-month:}")
    private String firstMonth;

    private boolean beforeFirstMonth(YearMonth month) {
        return !firstMonth.isBlank() && month.isBefore(YearMonth.parse(firstMonth));
    }

    /** One loan's interest for the month. {@code changed} (stored runs only): the loan's repayments were changed after posting. */
    public record Line(Long loanId, String branchCode, String memberNo, String memberName, BigDecimal openingPrincipal,
                       BigDecimal closingPrincipal, BigDecimal ratePercent, BigDecimal interest, boolean changed) {}

    private static boolean live(Loan l) {
        return l.getStatus() == LoanStatus.ACTIVE || l.getStatus() == LoanStatus.CLEARED;
    }

    public static YearMonth parse(String month) {
        try {
            return YearMonth.parse(month);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Month must look like 2026-09");
        }
    }

    public InterestRun load(Long id) {
        InterestRun r = runs.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Interest run not found"));
        scope.assertAccess(r.getBranch().getId());
        return r;
    }

    /** The interest still to be charged for this month, per loan, without saving anything. branchId null = every branch (manager). */
    @Transactional(readOnly = true)
    public List<Line> preview(Long branchId, YearMonth month) {
        List<Line> out = new ArrayList<>();
        if (beforeFirstMonth(month)) return out;
        loans.search(branchId, null).stream().filter(InterestRunService::live).sorted(Comparator.comparing(Loan::getId)).forEach(loan -> {
            if (accruals.existsByLoanIdAndMonth(loan.getId(), month.toString())) return;       // already charged for this month
            MonthAccrual a = schedules.monthAccrual(loan, month);
            if (a.interest().signum() > 0) out.add(line(loan, a, false));
        });
        return out;
    }

    private Line line(Loan loan, MonthAccrual a, boolean changed) {
        return new Line(loan.getId(), loan.getBranch().getCode(), loan.getMember().getMemberNo(), loan.getMember().getFullName(),
                a.openingPrincipal(), a.closingPrincipal(), loan.getAnnualRatePercent(), a.interest(), changed);
    }

    /** The lines of a stored run, each marked if the loan's repayments changed since (so the interest would now be different). */
    @Transactional(readOnly = true)
    public List<Line> lines(InterestRun run) {
        YearMonth month = YearMonth.parse(run.getMonth());
        List<Line> out = new ArrayList<>();
        for (InterestAccrual a : accruals.findByRunIdOrderByIdAsc(run.getId())) {
            boolean changed = schedules.monthAccrual(a.getLoan(), month).interest().compareTo(a.getInterest()) != 0;
            out.add(new Line(a.getLoan().getId(), a.getBranchCode(), a.getMember().getMemberNo(), a.getMember().getFullName(),
                    a.getOpeningPrincipal(), a.getClosingPrincipal(), a.getRatePercent(), a.getInterest(), changed));
        }
        return out;
    }

    @Transactional
    public InterestRun post(YearMonth month) {
        Long branchId = scope.writeBranch();
        if (beforeFirstMonth(month)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Interest before " + firstMonth + " was charged in the old system and is already in the ledgers");
        }
        LocalDate end = month.atEndOfMonth();
        if (end.isAfter(LocalDate.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Interest can be computed for a month once it has ended");
        }
        Branch branch = branches.findById(branchId).orElseThrow();
        Map<Loan, MonthAccrual> work = new LinkedHashMap<>();
        loans.search(branchId, null).stream().filter(InterestRunService::live).sorted(Comparator.comparing(Loan::getId)).forEach(loan -> {
            if (accruals.existsByLoanIdAndMonth(loan.getId(), month.toString())) return;
            MonthAccrual a = schedules.monthAccrual(loan, month);
            if (a.interest().signum() > 0) work.put(loan, a);
        });
        if (work.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "There is no interest to compute for " + month
                    + ": every loan that was out in that month has already been charged, or none was");
        }

        InterestRun run = new InterestRun();
        run.setBranch(branch);
        run.setBranchCode(branch.getCode());
        run.setMonth(month.toString());
        run.setMonthEnd(end);
        run.setCreatedBy(scope.me().getUsername());
        runs.save(run);

        BigDecimal total = BigDecimal.ZERO;
        for (var item : work.entrySet()) {
            Loan loan = item.getKey();
            MonthAccrual a = item.getValue();
            LedgerEntry entry = new LedgerEntry();
            entry.setBranch(branch);
            entry.setMember(loan.getMember());
            entry.setLoan(loan);
            entry.setType(TxType.LOAN_INTEREST);
            entry.setSource(Source.SYSTEM);
            entry.setAmount(a.interest());
            entry.setPostedOn(end);
            entry.setReference("Interest " + month);
            entry.setCreatedBy(scope.me().getUsername());
            ledger.save(entry);
            posting.record(entry);

            InterestAccrual line = new InterestAccrual();
            line.setRun(run);
            line.setLoan(loan);
            line.setMember(loan.getMember());
            line.setBranch(branch);
            line.setBranchCode(branch.getCode());
            line.setMonth(month.toString());
            line.setOpeningPrincipal(a.openingPrincipal());
            line.setClosingPrincipal(a.closingPrincipal());
            line.setRatePercent(loan.getAnnualRatePercent());
            line.setInterest(a.interest());
            line.setLedgerEntry(entry);
            accruals.save(line);
            total = total.add(a.interest());
        }
        run.setTotalInterest(total);
        run.setLoanCount(work.size());
        runs.save(run);
        audit.log(branch, "EDIT", "Interest run", run.getId(), "Computed interest for " + month + ": " + total + " on " + work.size() + " loans");
        return run;
    }

    @Transactional
    public void delete(Long id) {
        InterestRun run = load(id);
        List<LedgerEntry> entries = new ArrayList<>();
        for (InterestAccrual l : accruals.findByRunIdOrderByIdAsc(id)) {
            if (l.getLedgerEntry() != null) entries.add(l.getLedgerEntry());
        }
        accruals.deleteByRunId(id);
        accruals.flush();
        for (LedgerEntry e : entries) {
            generalLedger.deleteByLedgerEntryId(e.getId());
            memberLedger.deleteByLedgerEntryId(e.getId());
            ledger.delete(e);
        }
        ledger.flush();
        String what = "Interest run " + run.getMonth() + " total " + run.getTotalInterest() + " on " + run.getLoanCount() + " loans";
        runs.delete(run);
        runs.flush();
        audit.log(run.getBranch(), "DELETE", "Interest run", id, what);
    }

    /** A loan is being deleted: drop its interest lines from the runs, and any run left empty. */
    @Transactional
    public void forgetLoan(Loan loan) {
        List<InterestAccrual> lines = accruals.findByLoanIdOrderByIdAsc(loan.getId());
        if (lines.isEmpty()) return;
        Set<InterestRun> touched = new LinkedHashSet<>();
        lines.forEach(l -> touched.add(l.getRun()));
        accruals.deleteByLoanId(loan.getId());
        accruals.flush();
        for (InterestRun r : touched) {
            List<InterestAccrual> left = accruals.findByRunIdOrderByIdAsc(r.getId());
            if (left.isEmpty()) {
                runs.delete(r);
            } else {
                r.setTotalInterest(left.stream().map(InterestAccrual::getInterest).reduce(BigDecimal.ZERO, BigDecimal::add));
                r.setLoanCount((int) left.stream().map(x -> x.getLoan().getId()).distinct().count());
                runs.save(r);
            }
        }
    }

    /**
     * Loans paid out before monthly interest existed had all their interest booked as income at payout.
     * One correcting entry per loan takes that interest back out (Dr interest income, Cr interest receivable, and off the
     * member's loan); from then on the monthly runs charge it. Runs once for each loan.
     */
    @Transactional
    public int convertLegacyLoans() {
        int converted = 0;
        for (Loan loan : loans.findAll()) {
            if (loan.isInterestAccrual() || !live(loan)) continue;
            LedgerEntry payout = ledger.findByLoanIdOrderByIdAsc(loan.getId()).stream()
                    .filter(e -> e.getType() == TxType.LOAN_DISBURSEMENT).findFirst().orElse(null);
            BigDecimal upfront = BigDecimal.ZERO;
            if (payout != null && payout.getProduct() != null && payout.getProduct().getInterestProduct() != null) {
                Long interestAccount = payout.getProduct().getInterestProduct().getGlAccount().getId();
                upfront = generalLedger.findByLedgerEntryIdOrderByIdAsc(payout.getId()).stream()
                        .filter(g -> g.getAccount().getId().equals(interestAccount)).map(GeneralLedgerLine::getDebit)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
            }
            if (upfront.signum() > 0) {
                LedgerEntry fix = new LedgerEntry();
                fix.setBranch(loan.getBranch());
                fix.setMember(loan.getMember());
                fix.setLoan(loan);
                fix.setType(TxType.LOAN_INTEREST_REVERSAL);
                fix.setSource(Source.SYSTEM);
                fix.setAmount(upfront);
                fix.setPostedOn(LocalDate.now());
                fix.setReference("Conversion to monthly interest");
                fix.setCreatedBy("system");
                ledger.save(fix);
                posting.record(fix);
            }
            loan.setInterestAccrual(true);
            loans.save(loan);
            accounting.repostRepayments(loan);
            audit.logSystem(loan.getBranch(), "EDIT", "Loan", loan.getId(),
                    "Converted to monthly interest: interest of " + upfront + " recorded at payout was reversed; monthly runs charge it from now");
            log.info("Loan #{} converted to monthly interest accrual (reversed {})", loan.getId(), upfront);
            converted++;
        }
        return converted;
    }
}
