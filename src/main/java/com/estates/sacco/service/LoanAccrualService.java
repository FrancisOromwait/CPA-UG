package com.estates.sacco.service;

import com.estates.sacco.domain.*;
import com.estates.sacco.repo.GeneralLedgerRepository;
import com.estates.sacco.repo.LedgerRepository;
import com.estates.sacco.repo.LoanRepository;
import com.estates.sacco.repo.MemberLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Loan interest booked as the loan moves, as the old system did and as savings interest is booked here. One rate for every loan
 * ({@code sacco.loan-interest.accrual-rate}, 15% a year), day by day on the principal still owed. Each loan has a last interest
 * date: interest is booked up to and including it. A repayment dated after it books the days in between on the principal before
 * the repayment (Dr interest receivable, Cr loan interest income), tied to the repayment so its journal shows both, and the
 * repayment's date becomes the new last interest date; the month end books the rest of the month.
 * <p>
 * A repayment pays the interest already owed first (including what the old system charged and was not paid there), then principal;
 * anything beyond the principal is interest paid in advance. A repayment dated on or before the last interest date (made late,
 * edited, deleted) changes the principal on days already booked: the difference, principal x rate x days, is booked or taken back.
 * <p>
 * Switched on by {@code sacco.loan-interest.accrual-rate} and {@code sacco.loan-interest.accrual-from} (the first month); without
 * them loans keep the monthly interest run of {@link LoanScheduleService}. Interest entries are saved here and handed back;
 * {@link PostingService} writes their ledger lines.
 */
@Service
@RequiredArgsConstructor
public class LoanAccrualService {
    private static final MathContext MC = new MathContext(30);

    private final LoanRepository loans;
    private final LedgerRepository ledger;
    private final GeneralLedgerRepository generalLedger;
    private final MemberLedgerRepository memberLedger;

    @Value("${sacco.loan-interest.accrual-rate:}")
    private String rateText;
    @Value("${sacco.loan-interest.accrual-from:}")
    private String fromMonth;

    /** A change to the principal a loan owes from a day on: a repayment posted (negative) or taken away (positive). */
    public record Change(Loan loan, LocalDate date, BigDecimal principal, String user, LedgerEntry cause) {
        public Change negate() {
            return new Change(loan, date, principal.negate(), user, null);
        }
    }

    public boolean enabled() {
        return !rateText.isBlank() && !fromMonth.isBlank();
    }

    public BigDecimal rate() {
        return new BigDecimal(rateText.trim());
    }

    public YearMonth firstMonth() {
        return YearMonth.parse(fromMonth.trim());
    }

    /** The day before the first month: the old system charged interest up to here. */
    public LocalDate start() {
        return firstMonth().atDay(1).minusDays(1);
    }

    /** Interest is booked up to and including this day: the last interest date, else the payout (not before the start). */
    public LocalDate lastDateOf(Loan l) {
        if (l.getLastInterestDate() != null) return l.getLastInterestDate();
        LocalDate paid = l.getDisbursedOn() == null ? start() : l.getDisbursedOn();
        return paid.isBefore(start()) ? start() : paid;
    }

    private static boolean live(Loan l) {
        return l.getStatus() == LoanStatus.ACTIVE || l.getStatus() == LoanStatus.CLEARED;
    }

    // ------------------------------------------------------------------ what a repayment pays
    /** The part of a repayment posted to interest receivable (its ledger lines must be written). */
    public BigDecimal postedInterestOf(LedgerEntry repayment) {
        Long account = interestAccountOf(repayment);
        if (account == null) return BigDecimal.ZERO;
        return generalLedger.findByLedgerEntryIdOrderByIdAsc(repayment.getId()).stream()
                .filter(g -> g.getAccount().getId().equals(account)).map(GeneralLedgerLine::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Long interestAccountOf(LedgerEntry e) {
        ProductDef p = e.getProduct();
        return p == null || p.getInterestProduct() == null ? null : p.getInterestProduct().getGlAccount().getId();
    }

    /** The loan's repayments, by date and then as entered. */
    private List<LedgerEntry> repaymentsOf(Loan l) {
        return ledger.findByLoanIdOrderByIdAsc(l.getId()).stream().filter(e -> e.getType() == TxType.LOAN_REPAYMENT)
                .sorted(Comparator.comparing(LedgerEntry::getPostedOn).thenComparing(LedgerEntry::getId)).toList();
    }

    private static boolean before(LedgerEntry a, LedgerEntry b) {
        int byDate = a.getPostedOn().compareTo(b.getPostedOn());
        return byDate < 0 || (byDate == 0 && a.getId() < b.getId());
    }

    /**
     * The interest owed on the loan just before this repayment: interest charged to it up to the repayment's day (not the days the
     * repayment itself books), less what was taken back and what earlier repayments paid, plus, on the member's first loan, the
     * interest the old system charged and was not paid there.
     */
    public BigDecimal interestOwedBefore(LedgerEntry repayment) {
        Loan l = repayment.getLoan();
        LocalDate day = repayment.getPostedOn();
        BigDecimal owed = BigDecimal.ZERO;
        for (LedgerEntry e : ledger.findByLoanIdOrderByIdAsc(l.getId())) {
            if (e.getPostedOn().isAfter(day) || (e.getInterestOf() != null && e.getInterestOf().getId().equals(repayment.getId()))) continue;
            if (e.getType() == TxType.LOAN_INTEREST) owed = owed.add(e.getAmount());
            else if (e.getType() == TxType.LOAN_INTEREST_REVERSAL) owed = owed.subtract(e.getAmount());
            else if (e.getType() == TxType.LOAN_REPAYMENT && !e.getId().equals(repayment.getId()) && before(e, repayment)) {
                owed = owed.subtract(postedInterestOf(e));
            }
        }
        if (carriesOldInterest(l)) owed = owed.add(memberLedger.oldSystemLoanInterestOwed(l.getMember().getId(), day));
        return owed.max(BigDecimal.ZERO);
    }

    /** The member's first loan here pays what the old system charged in interest and was not paid there. */
    private boolean carriesOldInterest(Loan l) {
        return loans.findByMemberId(l.getMember().getId()).stream().filter(LoanAccrualService::live)
                .min(Comparator.comparing(Loan::getId)).map(x -> x.getId().equals(l.getId())).orElse(false);
    }

    /** The principal the loan still owed just before this repayment. */
    public BigDecimal principalLeftBefore(LedgerEntry repayment) {
        Loan l = repayment.getLoan();
        BigDecimal left = l.getPrincipal();
        for (LedgerEntry e : repaymentsOf(l)) {
            if (e.getId().equals(repayment.getId()) || !before(e, repayment)) continue;
            left = left.subtract(e.getAmount().subtract(postedInterestOf(e)));
        }
        return left.max(BigDecimal.ZERO);
    }

    /** The principal the loan owes now. */
    public BigDecimal principalLeft(Loan l) {
        BigDecimal left = l.getPrincipal();
        for (LedgerEntry e : repaymentsOf(l)) left = left.subtract(e.getAmount().subtract(postedInterestOf(e)));
        return left.max(BigDecimal.ZERO);
    }

    /** The principal the loan owed at the end of a day (nothing before its payout). */
    public BigDecimal principalOn(Loan l, LocalDate day) {
        LocalDate paid = l.getDisbursedOn() == null ? start() : l.getDisbursedOn();
        if (paid.isAfter(day)) return BigDecimal.ZERO;
        BigDecimal left = l.getPrincipal();
        for (LedgerEntry e : repaymentsOf(l)) {
            if (!e.getPostedOn().isAfter(day)) left = left.subtract(e.getAmount().subtract(postedInterestOf(e)));
        }
        return left.max(BigDecimal.ZERO);
    }

    /** The date of the loan's last repayment from {@code from} to {@code to} (null: none). */
    public LocalDate lastRepaymentBetween(Loan l, LocalDate from, LocalDate to) {
        LocalDate found = null;
        for (LedgerEntry e : repaymentsOf(l)) {
            if (!e.getPostedOn().isBefore(from) && !e.getPostedOn().isAfter(to)) found = e.getPostedOn();
        }
        return found;
    }

    /** The interest owed on the loan now: charged less taken back less paid, with the old system's on the member's first loan. */
    public BigDecimal interestOwed(Loan l) {
        BigDecimal owed = BigDecimal.ZERO;
        for (LedgerEntry e : ledger.findByLoanIdOrderByIdAsc(l.getId())) {
            if (e.getType() == TxType.LOAN_INTEREST) owed = owed.add(e.getAmount());
            else if (e.getType() == TxType.LOAN_INTEREST_REVERSAL) owed = owed.subtract(e.getAmount());
            else if (e.getType() == TxType.LOAN_REPAYMENT) owed = owed.subtract(postedInterestOf(e));
        }
        if (carriesOldInterest(l)) owed = owed.add(memberLedger.oldSystemLoanInterestOwed(l.getMember().getId(), LocalDate.of(2999, 12, 31)));
        return owed;
    }

    /** What clears the loan today: principal, interest owed, and the interest of the days since the last interest date. */
    public BigDecimal payoff(Loan l) {
        if (!live(l)) return BigDecimal.ZERO;
        LocalDate today = LocalDate.now();
        LocalDate last = lastDateOf(l);
        BigDecimal toDate = today.isAfter(last) ? interestBetween(l, last.plusDays(1), today)[0].setScale(0, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        return principalLeft(l).add(interestOwed(l)).add(toDate);
    }

    // ------------------------------------------------------------------ booking
    /**
     * What an entry does to the principal its loan owes (null: not a repayment): a repayment takes its principal part off. A repayment
     * that paid only interest still counts, so it books the days before it and moves the last interest date.
     */
    public Change changeOf(LedgerEntry e) {
        if (!enabled() || e.getType() != TxType.LOAN_REPAYMENT || e.getLoan() == null) return null;
        return new Change(e.getLoan(), e.getPostedOn(), e.getAmount().subtract(postedInterestOf(e)).negate(), e.getCreatedBy(), e);
    }

    /**
     * Books the interest the changes call for and returns the interest entries (saved, without ledger lines yet). Changes of the same
     * loan and day are added together first, so an edit that leaves amount and date alone books nothing. Earliest first.
     */
    @Transactional
    public List<LedgerEntry> apply(List<Change> changes) {
        List<LedgerEntry> out = new ArrayList<>();
        if (!enabled()) return out;
        Map<List<Object>, Change> netted = new LinkedHashMap<>();
        for (Change c : changes) {
            if (c == null) continue;
            netted.merge(List.of(c.loan().getId(), c.date()), c,
                    (a, b) -> new Change(a.loan(), a.date(), a.principal().add(b.principal()), a.user(), a.cause() != null ? a.cause() : b.cause()));
        }
        netted.values().stream().sorted(Comparator.comparing(Change::date)).forEach(c -> one(c, out));
        return out;
    }

    private void one(Change c, List<LedgerEntry> out) {
        Loan l = loans.findById(c.loan().getId()).orElseThrow();
        if (!live(l)) return;
        LocalDate last = lastDateOf(l);
        if (c.date().isAfter(last)) {
            if (c.cause() == null) return;   // a repayment taken away after the last interest date: nothing was booked for its days
            accrue(l, last, c.date(), null, c.user()).ifPresent(e -> out.add(tie(e, c)));
            l.setLastInterestDate(c.date());
            loans.save(l);
            return;
        }
        if (c.principal().signum() == 0) return;
        // dated on or before the last interest date: these days were booked on a principal that has now changed
        long days = ChronoUnit.DAYS.between(c.date().isAfter(start()) ? c.date() : start(), last);
        if (days <= 0) return;
        BigDecimal amount = c.principal().multiply(dailyRate(), MC).multiply(BigDecimal.valueOf(days)).setScale(0, RoundingMode.HALF_UP);
        if (amount.signum() == 0) return;
        String what = (c.principal().signum() > 0 ? "Principal back " : "Principal paid ") + c.principal().abs().toPlainString() + " dated " + c.date()
                + ": " + days + " days to " + last;
        out.add(tie(entry(l, amount.signum() > 0 ? TxType.LOAN_INTEREST : TxType.LOAN_INTEREST_REVERSAL, amount.abs(), last,
                "Adjustment for " + c.date(), what, c.user()), c));
    }

    private static LedgerEntry tie(LedgerEntry interest, Change c) {
        if (c.cause() != null && c.cause().getId() != null) interest.setInterestOf(c.cause());
        return interest;
    }

    /** Books a loan's interest for the days after {@code last} up to and including {@code to}. Nothing under one shilling. */
    public Optional<LedgerEntry> accrue(Loan l, LocalDate last, LocalDate to, String reference, String user) {
        if (!to.isAfter(last)) return Optional.empty();
        BigDecimal[] r = interestBetween(l, last.plusDays(1), to);
        BigDecimal amount = r[0].setScale(0, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) return Optional.empty();
        long days = ChronoUnit.DAYS.between(last, to);
        String what = last.plusDays(1) + " to " + to + ": " + days + " day" + (days == 1 ? "" : "s") + " on an average principal of "
                + r[1].setScale(2, RoundingMode.HALF_UP).toPlainString() + " at " + rate().stripTrailingZeros().toPlainString() + "% a year";
        return Optional.of(entry(l, TxType.LOAN_INTEREST, amount, to, reference == null ? last.plusDays(1) + " to " + to : reference, what, user));
    }

    /**
     * Interest from {@code from} to {@code to} (both included) and the average principal it was earned on, unrounded. A day's principal
     * is the loan less the principal of the repayments dated before it, counted from the day after the payout.
     */
    public BigDecimal[] interestBetween(Loan l, LocalDate from, LocalDate to) {
        LocalDate paid = l.getDisbursedOn() == null ? start() : l.getDisbursedOn();
        List<Object[]> moves = new ArrayList<>();
        moves.add(new Object[] {paid, l.getPrincipal()});
        for (LedgerEntry e : repaymentsOf(l)) moves.add(new Object[] {e.getPostedOn(), e.getAmount().subtract(postedInterestOf(e)).negate()});
        moves.sort(Comparator.comparing(m -> (LocalDate) m[0]));
        BigDecimal daily = dailyRate(), principal = BigDecimal.ZERO, interest = BigDecimal.ZERO, principalDays = BigDecimal.ZERO;
        int next = 0, days = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1), days++) {
            while (next < moves.size() && ((LocalDate) moves.get(next)[0]).isBefore(day)) principal = principal.add((BigDecimal) moves.get(next++)[1]);
            BigDecimal earning = principal.max(BigDecimal.ZERO);
            interest = interest.add(earning.multiply(daily, MC), MC);
            principalDays = principalDays.add(earning);
        }
        return new BigDecimal[] {interest, days == 0 ? BigDecimal.ZERO : principalDays.divide(BigDecimal.valueOf(days), MC)};
    }

    private BigDecimal dailyRate() {
        return rate().divide(BigDecimal.valueOf(36500), MC);
    }

    /** The product the loan was paid out under (null: the branch's default loan product, as for loans from the old system). */
    private ProductDef productOf(Loan l) {
        return ledger.findByLoanIdOrderByIdAsc(l.getId()).stream().filter(e -> e.getType() == TxType.LOAN_DISBURSEMENT)
                .map(LedgerEntry::getProduct).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private LedgerEntry entry(Loan l, TxType type, BigDecimal amount, LocalDate date, String reference, String notes, String user) {
        LedgerEntry e = new LedgerEntry();
        e.setBranch(l.getBranch());
        e.setMember(l.getMember());
        e.setLoan(l);
        e.setProduct(productOf(l));
        e.setType(type);
        e.setSource(Source.SYSTEM);
        e.setAmount(amount);
        e.setPostedOn(date);
        e.setReference(reference);
        e.setNotes(notes);
        e.setCreatedBy(user == null ? "system" : user);
        return ledger.save(e);
    }
}
