package com.estates.sacco.service;

import com.estates.sacco.domain.*;
import com.estates.sacco.repo.LedgerRepository;
import com.estates.sacco.repo.LoanInstallmentRepository;
import com.estates.sacco.service.ReportDoc.Col;
import com.estates.sacco.service.ReportDoc.Kv;
import com.estates.sacco.service.ReportDoc.Section;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Monthly repayment schedules, kept in the loan_schedule table.
 * <ul>
 *   <li>{@link #generate} writes the installments when a loan is paid out (dates fixed from that day).</li>
 *   <li>{@link #reallocate} matches the loan's repayments to the installments, oldest first, and is run whenever
 *       a repayment is posted, edited or deleted, so the paid columns, the arrears and the statuses stay right.</li>
 *   <li>{@link #document} reads the stored rows for the screen and the PDF.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LoanScheduleService {
    private final LedgerRepository ledger;
    private final LoanInstallmentRepository installments;
    private final DeploymentSettings settings;

    private static final MathContext MC = new MathContext(30);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    public record Row(int no, LocalDate due, BigDecimal opening, BigDecimal principal, BigDecimal interest,
                      BigDecimal installment, BigDecimal closing) {}

    // ------------------------------------------------------------------ the arithmetic
    /** One row per month. The last installment absorbs any rounding so the loan clears exactly. */
    public static List<Row> build(InterestMethod method, BigDecimal principal, BigDecimal ratePercent, int months, LocalDate start) {
        BigDecimal p = principal.setScale(2, RoundingMode.HALF_UP);
        return method == InterestMethod.STRAIGHT_LINE ? straightLine(p, ratePercent, months, start) : reducing(p, ratePercent, months, start);
    }

    private static List<Row> reducing(BigDecimal p, BigDecimal ratePercent, int months, LocalDate start) {
        BigDecimal r = ratePercent.divide(BigDecimal.valueOf(1200), MC);          // interest rate per month
        BigDecimal installment;
        if (r.signum() == 0) {
            installment = p.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
        } else {
            BigDecimal growth = BigDecimal.ONE.add(r).pow(months, MC);            // (1 + r) ^ n
            installment = p.multiply(r, MC).multiply(growth, MC).divide(growth.subtract(BigDecimal.ONE), MC).setScale(2, RoundingMode.HALF_UP);
        }
        List<Row> rows = new ArrayList<>();
        BigDecimal balance = p;
        for (int k = 1; k <= months; k++) {
            BigDecimal interest = balance.multiply(r, MC).setScale(2, RoundingMode.HALF_UP);
            BigDecimal principalPart = k == months ? balance : installment.subtract(interest).min(balance);
            BigDecimal closing = balance.subtract(principalPart);
            rows.add(new Row(k, start.plusMonths(k), balance, principalPart, interest, principalPart.add(interest), closing));
            balance = closing;
        }
        return rows;
    }

    private static List<Row> straightLine(BigDecimal p, BigDecimal ratePercent, int months, LocalDate start) {
        BigDecimal totalInterest = p.multiply(ratePercent).multiply(BigDecimal.valueOf(months))
                .divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_UP);
        BigDecimal principalStep = p.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
        BigDecimal interestStep = totalInterest.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
        List<Row> rows = new ArrayList<>();
        BigDecimal balance = p, interestLeft = totalInterest;
        for (int k = 1; k <= months; k++) {
            BigDecimal principalPart = k == months ? balance : principalStep;
            BigDecimal interest = k == months ? interestLeft : interestStep;
            BigDecimal closing = balance.subtract(principalPart);
            rows.add(new Row(k, start.plusMonths(k), balance, principalPart, interest, principalPart.add(interest), closing));
            balance = closing;
            interestLeft = interestLeft.subtract(interest);
        }
        return rows;
    }

    /** Principal plus all the interest: what the member will have paid when the loan is cleared. */
    public static BigDecimal totalDue(InterestMethod method, BigDecimal principal, BigDecimal ratePercent, int months) {
        return build(method, principal, ratePercent, months, LocalDate.of(2000, 1, 1)).stream()
                .map(Row::installment).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The regular monthly amount (the last installment can differ by a few cents). */
    public static BigDecimal monthlyInstallment(InterestMethod method, BigDecimal principal, BigDecimal ratePercent, int months) {
        List<Row> rows = build(method, principal, ratePercent, months, LocalDate.of(2000, 1, 1));
        return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0).installment();
    }

    // ------------------------------------------------------------------ the loan_schedule table
    /** Writes the installments of a loan that has been paid out, then matches any repayments to them. */
    @Transactional
    public void generate(Loan loan) {
        installments.deleteByLoanId(loan.getId());
        installments.flush();
        LocalDate start = loan.getDisbursedOn() != null ? loan.getDisbursedOn() : loan.getAppliedOn();
        for (Row r : build(loan.getInterestMethod(), loan.getPrincipal(), loan.getAnnualRatePercent(), loan.getTermMonths(), start)) {
            LoanInstallment i = new LoanInstallment();
            i.setLoan(loan);
            i.setBranch(loan.getBranch());
            i.setBranchCode(loan.getBranch().getCode());
            i.setInstallmentNo(r.no());
            i.setDueDate(r.due());
            i.setOpeningBalance(r.opening());
            i.setPrincipalDue(r.principal());
            i.setInterestDue(r.interest());
            i.setInstallmentDue(r.installment());
            i.setClosingBalance(r.closing());
            installments.save(i);
        }
        reallocate(loan);
    }

    /** Creates the schedule for a paid-out loan that does not have one yet (loans from before the table existed). */
    @Transactional
    public void ensureGenerated(Loan loan) {
        if (loan.getStatus() != LoanStatus.PENDING && loan.getStatus() != LoanStatus.REJECTED && !installments.existsByLoanId(loan.getId())) {
            generate(loan);
        }
    }

    @Transactional
    public void remove(Loan loan) {
        installments.deleteByLoanId(loan.getId());
        installments.flush();
    }

    /**
     * Matches the loan's repayments to its installments: oldest repayment first, oldest installment first.
     * Run after any repayment is posted, edited or deleted.
     */
    @Transactional
    public void reallocate(Loan loan) {
        List<LoanInstallment> rows = installments.findByLoanIdOrderByInstallmentNoAsc(loan.getId());
        if (rows.isEmpty()) return;
        for (LoanInstallment r : rows) {
            r.setPaidAmount(BigDecimal.ZERO);
            r.setPaidOn(null);
        }
        List<LedgerEntry> payments = ledger.findByLoanIdOrderByIdAsc(loan.getId()).stream()
                .filter(e -> e.getType() == TxType.LOAN_REPAYMENT)
                .sorted(Comparator.comparing(LedgerEntry::getPostedOn).thenComparing(LedgerEntry::getId)).toList();
        int next = 0;
        for (LedgerEntry pay : payments) {
            BigDecimal left = pay.getAmount();
            while (left.signum() > 0 && next < rows.size()) {
                LoanInstallment r = rows.get(next);
                BigDecimal take = left.min(r.getInstallmentDue().subtract(r.getPaidAmount()));
                r.setPaidAmount(r.getPaidAmount().add(take));
                left = left.subtract(take);
                if (r.getPaidAmount().compareTo(r.getInstallmentDue()) >= 0) {
                    r.setPaidOn(pay.getPostedOn());
                    next++;
                }
            }
        }
        installments.saveAll(rows);
    }

    // ------------------------------------------------------------------ interest: one simulation of the loan, month by month
    /** The loan's repayments, oldest first. */
    public List<LedgerEntry> repayments(Loan loan) {
        return ledger.findByLoanIdOrderByIdAsc(loan.getId()).stream()
                .filter(e -> e.getType() == TxType.LOAN_REPAYMENT)
                .sorted(Comparator.comparing(LedgerEntry::getPostedOn).thenComparing(LedgerEntry::getId)).toList();
    }

    public record Split(Long entryId, LocalDate date, BigDecimal interest, BigDecimal principal) {}

    /** One month end of the loan: the principal still owed then, and the month's interest charged on it. */
    public record MonthRow(YearMonth month, LocalDate end, BigDecimal closing, BigDecimal interest) {}

    public record Sim(List<MonthRow> months, List<Split> splits) {
        /** Principal repaid by payments dated before {@code d}. */
        public BigDecimal principalBefore(LocalDate d) {
            return splits.stream().filter(sp -> sp.date().isBefore(d)).map(Split::principal).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        /** Principal repaid by payments dated on or before {@code d}. */
        public BigDecimal principalThrough(LocalDate d) {
            return splits.stream().filter(sp -> !sp.date().isAfter(d)).map(Split::principal).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private static final class Paid {
        BigDecimal interest = BigDecimal.ZERO, principal = BigDecimal.ZERO;
    }

    private static LocalDate startOf(Loan loan) {
        return loan.getDisbursedOn() != null ? loan.getDisbursedOn() : loan.getAppliedOn();
    }

    /**
     * Plays the loan forward one month at a time. At every month end a loan that still owes principal is charged one full month's
     * interest: its monthly rate (the yearly rate divided by 12) on the principal still owed then (reducing balance), or on the
     * original amount (straight-line). Days are not counted, so a loan taken on the 1st and one taken on the 20th are charged alike.
     * Each repayment pays the interest of the month ends before its date first, then principal; anything paid beyond the
     * principal counts as interest paid in advance. Runs as far as {@code upTo}, or the last repayment if that is later.
     */
    private Sim simulate(Loan loan, List<LedgerEntry> pays, LocalDate upTo) {
        LocalDate horizon = upTo;
        if (!pays.isEmpty() && pays.get(pays.size() - 1).getPostedOn().isAfter(horizon)) horizon = pays.get(pays.size() - 1).getPostedOn();

        List<MonthRow> months = new ArrayList<>();
        List<Split> splits = new ArrayList<>();
        Paid paid = new Paid();
        int next = 0;
        YearMonth last = YearMonth.from(horizon);
        for (YearMonth m = YearMonth.from(startOf(loan)); !m.isAfter(last); m = m.plusMonths(1)) {
            LocalDate end = m.atEndOfMonth();
            // repayments dated up to this month end shape the balance it is charged on
            while (next < pays.size() && !pays.get(next).getPostedOn().isAfter(end)) splits.add(apply(loan, pays.get(next++), months, paid));
            BigDecimal closing = loan.getPrincipal().subtract(paid.principal).max(BigDecimal.ZERO);
            BigDecimal r = monthRate(loan, m);
            BigDecimal interest = closing.signum() == 0 ? BigDecimal.ZERO
                    : loan.getInterestMethod() == InterestMethod.REDUCING_BALANCE
                    ? closing.multiply(r, MC).setScale(2, RoundingMode.HALF_UP)
                    : loan.getPrincipal().multiply(r, MC).setScale(2, RoundingMode.HALF_UP);
            months.add(new MonthRow(m, end, closing, interest));
        }
        return new Sim(months, splits);
    }

    /**
     * The rate a month is charged at. With daily interest it is the yearly rate / 365 for every day of that month (31 days in
     * January, 28 or 29 in February...); otherwise a twelfth of the yearly rate.
     */
    private BigDecimal monthRate(Loan loan, YearMonth month) {
        BigDecimal yearly = loan.getAnnualRatePercent();
        return settings.isLoanInterestDaily()
                ? yearly.multiply(BigDecimal.valueOf(month.lengthOfMonth()), MC).divide(BigDecimal.valueOf(36500), MC)
                : yearly.divide(BigDecimal.valueOf(1200), MC);
    }

    private static Split apply(Loan loan, LedgerEntry pay, List<MonthRow> months, Paid paid) {
        BigDecimal fallenDue = BigDecimal.ZERO;
        for (MonthRow m : months) if (m.end().isBefore(pay.getPostedOn())) fallenDue = fallenDue.add(m.interest());
        BigDecimal available = fallenDue.subtract(paid.interest).max(BigDecimal.ZERO);
        BigDecimal interest = pay.getAmount().min(available);
        BigDecimal principal = pay.getAmount().subtract(interest).min(loan.getPrincipal().subtract(paid.principal).max(BigDecimal.ZERO));
        interest = pay.getAmount().subtract(principal);                  // paid beyond the principal: interest in advance
        paid.interest = paid.interest.add(interest);
        paid.principal = paid.principal.add(principal);
        return new Split(pay.getId(), pay.getPostedOn(), interest, principal);
    }

    /** How much of one repayment is interest (the rest is principal). */
    public BigDecimal interestPortionOf(Loan loan, LedgerEntry payment) {
        if (!installments.existsByLoanId(loan.getId())) return BigDecimal.ZERO;
        return simulate(loan, repayments(loan), LocalDate.now()).splits().stream()
                .filter(sp -> sp.entryId().equals(payment.getId())).map(Split::interest).findFirst().orElse(BigDecimal.ZERO);
    }

    /**
     * Principal plus all the interest the member will have paid when the loan is cleared: the interest of the month ends
     * already passed (more than planned for a member who is behind), and the plan for the months still to come.
     */
    public BigDecimal contractualTotal(Loan loan) {
        List<LoanInstallment> rows = installments.findByLoanIdOrderByInstallmentNoAsc(loan.getId());
        if (rows.isEmpty()) return loan.getTotalDue();
        LocalDate today = LocalDate.now();
        Sim sim = simulate(loan, repayments(loan), today);
        BigDecimal total = loan.getPrincipal();
        int counted = 0;
        for (MonthRow m : sim.months()) {
            if (m.end().isAfter(today)) break;
            total = total.add(m.interest());
            counted++;
        }
        if (loan.getPrincipal().subtract(sim.principalThrough(today)).signum() > 0) {
            for (int k = counted + 1; k <= rows.size(); k++) total = total.add(rows.get(k - 1).getInterestDue());
        }
        return total;
    }

    /** A loan's balance at the start and the end of a calendar month, and the interest charged for it. */
    public record MonthAccrual(BigDecimal openingPrincipal, BigDecimal closingPrincipal, BigDecimal interest) {}

    /** The interest the loan is charged for a month: one month on the principal it still owes at the month end. */
    public MonthAccrual monthAccrual(Loan loan, YearMonth month) {
        LocalDate start = startOf(loan);
        if (!installments.existsByLoanId(loan.getId()) || start.isAfter(month.atEndOfMonth())) {
            return new MonthAccrual(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        Sim sim = simulate(loan, repayments(loan), month.atEndOfMonth());
        MonthRow row = sim.months().stream().filter(m -> m.month().equals(month)).findFirst().orElse(null);
        if (row == null) return new MonthAccrual(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        LocalDate openingDay = month.atDay(1).isBefore(start) ? start : month.atDay(1);
        BigDecimal opening = loan.getPrincipal().subtract(sim.principalBefore(openingDay)).max(BigDecimal.ZERO);
        return new MonthAccrual(opening, row.closing(), row.interest());
    }

    // ------------------------------------------------------------------ the schedule as a document (screen and PDF)
    public ReportDoc document(Loan loan) {
        List<LoanInstallment> stored = installments.findByLoanIdOrderByInstallmentNoAsc(loan.getId());
        List<Row> rows = new ArrayList<>();
        List<BigDecimal> paidList = new ArrayList<>();
        if (stored.isEmpty()) {
            // not paid out yet: a projection from the loan's terms, with nothing paid
            LocalDate start = loan.getDisbursedOn() != null ? loan.getDisbursedOn() : loan.getAppliedOn();
            rows = build(loan.getInterestMethod(), loan.getPrincipal(), loan.getAnnualRatePercent(), loan.getTermMonths(), start);
            rows.forEach(r -> paidList.add(BigDecimal.ZERO));
        } else {
            for (LoanInstallment i : stored) {
                rows.add(new Row(i.getInstallmentNo(), i.getDueDate(), i.getOpeningBalance(), i.getPrincipalDue(), i.getInterestDue(),
                        i.getInstallmentDue(), i.getClosingBalance()));
                paidList.add(i.getPaidAmount());
            }
        }

        LocalDate today = LocalDate.now();
        BigDecimal principalTotal = BigDecimal.ZERO, interestTotal = BigDecimal.ZERO, installmentTotal = BigDecimal.ZERO, paidTotal = BigDecimal.ZERO;
        BigDecimal arrears = BigDecimal.ZERO;
        LocalDate oldestLate = null;
        List<List<String>> out = new ArrayList<>();
        for (int k = 0; k < rows.size(); k++) {
            Row r = rows.get(k);
            BigDecimal paid = paidList.get(k);
            boolean cleared = paid.compareTo(r.installment()) >= 0;
            boolean late = !cleared && r.due().isBefore(today);
            String status = cleared ? "Paid"
                    : late ? (paid.signum() > 0 ? "Part paid - overdue" : "Overdue")
                    : (paid.signum() > 0 ? "Part paid" : "Upcoming");
            if (late) {
                arrears = arrears.add(r.installment().subtract(paid));
                if (oldestLate == null) oldestLate = r.due();
            }
            out.add(List.of(String.valueOf(r.no()), r.due().format(DATE), fmt(r.opening()), fmt(r.principal()), fmt(r.interest()),
                    fmt(r.installment()), fmt(r.closing()), fmt(paid), status));
            principalTotal = principalTotal.add(r.principal());
            interestTotal = interestTotal.add(r.interest());
            installmentTotal = installmentTotal.add(r.installment());
            paidTotal = paidTotal.add(paid);
        }

        List<Col> cols = List.of(new Col("No", false), new Col("Due date", false), new Col("Opening balance", true),
                new Col("Principal", true), new Col("Interest", true), new Col("Installment", true),
                new Col("Closing balance", true), new Col("Paid", true), new Col("Status", false));
        Section section = new Section("Monthly installments", cols, out,
                List.of("", "Total", "", fmt(principalTotal), fmt(interestTotal), fmt(installmentTotal), "", fmt(paidTotal), ""));

        String method = loan.getInterestMethod() == InterestMethod.REDUCING_BALANCE ? "reducing balance" : "straight-line";
        String note = stored.isEmpty() && loan.getStatus() == LoanStatus.PENDING
                ? "Projected schedule: it starts one month after the loan date, and the dates are fixed when the loan is paid out." : null;
        long daysLate = oldestLate == null ? 0 : ChronoUnit.DAYS.between(oldestLate, today);
        return new ReportDoc("Loan Repayment Schedule",
                settings.isSingleBranch() ? loan.getBranch().getName() : loan.getBranch().getCode() + " - " + loan.getBranch().getName(),
                "Member: " + loan.getMember().getMemberNo() + " - " + loan.getMember().getFullName() + "   |   Loan #" + loan.getId(),
                fmt(loan.getPrincipal()) + " at " + loan.getAnnualRatePercent().stripTrailingZeros().toPlainString() + "% a year, "
                        + method + ", " + loan.getTermMonths() + " months, first payment " + rows.get(0).due().format(DATE),
                List.of(new Kv("Loan amount", fmt(principalTotal)), new Kv("Total interest", fmt(interestTotal)),
                        new Kv("Total payable", fmt(installmentTotal)), new Kv("Monthly installment", fmt(rows.get(0).installment())),
                        new Kv("Paid so far", fmt(paidTotal)), new Kv("Outstanding", fmt(installmentTotal.subtract(paidTotal).max(BigDecimal.ZERO))),
                        new Kv("Arrears", fmt(arrears)), new Kv("Days late", String.valueOf(daysLate))),
                List.of(section), note);
    }

    private static String fmt(BigDecimal v) {
        return FinancialReportService.fmt(v);
    }
}
