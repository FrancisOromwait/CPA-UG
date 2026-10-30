package com.estates.sacco.service;

import com.estates.sacco.domain.*;
import com.estates.sacco.repo.LedgerRepository;
import com.estates.sacco.repo.MemberLedgerRepository;
import com.estates.sacco.repo.MemberRepository;
import com.estates.sacco.repo.ProductDefRepository;
import com.estates.sacco.service.SavingsInterestService.Move;
import com.estates.sacco.web.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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
 * Savings interest booked as the savings move, not only at month end. Every member keeps a last savings date: interest is booked up
 * to and including it. A savings entry dated after it books the days in between on the balance before the entry
 * (Dr Savings interest expense, Cr Savings interest payable), and the entry's date becomes the new last savings date:
 * a deposit on the 13th books 13 - 0 days, one on the 20th 20 - 13 days, and month end 31 - 20 days. A day's balance is
 * everything dated before it, as in {@link SavingsInterestService#interestFor}, so the month adds up to the same interest.
 * <p>
 * A savings entry dated on or before the last savings date (made late, edited or deleted) earned interest that is not booked,
 * or was booked and is not earned: the difference, amount x rate x days up to the last savings date, is booked or taken back
 * (Dr Savings interest payable, Cr Savings interest expense) on the last savings date. The days before the first month here
 * belong to the old system and are left alone. An entry dated in the future waits: the next entry or the month end books it.
 * <p>
 * The interest entries are saved here and handed back; {@link PostingService} writes their ledger lines.
 */
@Service
@RequiredArgsConstructor
public class SavingsAccrualService {
    private static final MathContext MC = new MathContext(30);

    private final MemberRepository members;
    private final MemberLedgerRepository memberLedger;
    private final LedgerRepository ledger;
    private final ProductDefRepository products;

    @Value("${sacco.savings-interest.rate:8}")
    private BigDecimal ratePercent;
    @Value("${sacco.savings-interest.day-basis:365}")
    private int dayBasis;
    @Value("${sacco.savings-interest.first-month:}")
    private String firstMonth;

    /** A change to a member's savings on a day: an entry posted (positive for money in) or taken away (the opposite). */
    public record Change(Member member, Branch branch, LocalDate date, BigDecimal net, String user) {
        public Change negate() {
            return new Change(member, branch, date, net.negate(), user);
        }
    }

    /** What an entry does to the member's savings: deposits and interest paid into savings add, withdrawals take away. */
    public static BigDecimal savingsNet(LedgerEntry e) {
        return switch (e.getType()) {
            case SAVINGS_DEPOSIT, SAVINGS_INTEREST_PAYOUT -> e.getAmount();
            case SAVINGS_WITHDRAWAL -> e.getAmount().negate();
            default -> BigDecimal.ZERO;
        };
    }

    /** The change an entry makes to savings as it stands now (null: it does not touch savings). */
    public static Change changeOf(LedgerEntry e) {
        BigDecimal net = savingsNet(e);
        return net.signum() == 0 || e.getMember() == null ? null : new Change(e.getMember(), e.getBranch(), e.getPostedOn(), net, e.getCreatedBy());
    }

    public boolean enabled() {
        return !firstMonth.isBlank();
    }

    /** The day before the first month: where every member's booked interest starts. */
    public LocalDate start() {
        return YearMonth.parse(firstMonth).atDay(1).minusDays(1);
    }

    public LocalDate lastDateOf(Member m) {
        return m.getLastSavingsDate() == null ? start() : m.getLastSavingsDate();
    }

    /**
     * Books the interest the changes call for and returns the interest entries (saved, without ledger lines yet). Changes of the same
     * member and day are added together first, so an edit that leaves amount and date alone books nothing. Earliest first.
     */
    @Transactional
    public List<LedgerEntry> apply(List<Change> changes) {
        List<LedgerEntry> out = new ArrayList<>();
        if (!enabled()) return out;
        Map<List<Object>, Change> netted = new LinkedHashMap<>();
        for (Change c : changes) {
            if (c == null) continue;
            netted.merge(List.of(c.member().getId(), c.date()), c,
                    (a, b) -> new Change(a.member(), a.branch(), a.date(), a.net().add(b.net()), a.user()));
        }
        netted.values().stream().filter(c -> c.net().signum() != 0).sorted(Comparator.comparing(Change::date))
                .forEach(c -> one(c, out));
        return out;
    }

    private void one(Change c, List<LedgerEntry> out) {
        Member m = members.findById(c.member().getId()).orElseThrow();
        LocalDate last = lastDateOf(m);
        if (c.date().isAfter(last)) {
            if (c.date().isAfter(LocalDate.now())) return;   // a future date: booked when its day has come
            accrue(m, c.branch(), last, c.date(), null, c.user()).ifPresent(out::add);
            m.setLastSavingsDate(c.date());
            members.save(m);
            return;
        }
        // dated on or before the last savings date: the interest these days earned on this amount
        long days = ChronoUnit.DAYS.between(c.date().isAfter(start()) ? c.date() : start(), last);
        if (days <= 0) return;
        BigDecimal amount = c.net().multiply(dailyRate(), MC).multiply(BigDecimal.valueOf(days)).setScale(0, RoundingMode.HALF_UP);
        if (amount.signum() == 0) return;
        String what = (c.net().signum() > 0 ? "Money in " : "Money out ") + c.net().abs().toPlainString() + " dated " + c.date()
                + ": " + days + " days to " + last;
        out.add(entry(m, c.branch(), amount.signum() > 0 ? TxType.SAVINGS_INTEREST : TxType.SAVINGS_INTEREST_REVERSAL, amount.abs(), last,
                "Adjustment for " + c.date(), what, c.user()));
    }

    /**
     * Books a member's interest for the days after {@code last} up to and including {@code to}, worked out day by day on the
     * member's savings as they are now in the ledger. Nothing is saved for less than one shilling. The caller moves the last
     * savings date.
     */
    public Optional<LedgerEntry> accrue(Member m, Branch branch, LocalDate last, LocalDate to, String reference, String user) {
        if (!to.isAfter(last)) return Optional.empty();
        List<Move> moves = new ArrayList<>();
        for (Object[] r : memberLedger.savingsMovesOf(m.getId(), to)) moves.add(new Move((LocalDate) r[0], (BigDecimal) r[1]));
        return accrue(m, branch, moves, last, to, reference, user);
    }

    /** As above, on moves already read (the month end reads the whole branch at once). */
    public Optional<LedgerEntry> accrue(Member m, Branch branch, List<Move> moves, LocalDate last, LocalDate to, String reference, String user) {
        if (!to.isAfter(last)) return Optional.empty();
        BigDecimal[] r = interestBetween(moves, last.plusDays(1), to, ratePercent, dayBasis);
        BigDecimal amount = r[0].setScale(0, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) return Optional.empty();
        long days = ChronoUnit.DAYS.between(last, to);
        String what = last.plusDays(1) + " to " + to + ": " + days + " day" + (days == 1 ? "" : "s") + " on an average balance of "
                + r[1].setScale(2, RoundingMode.HALF_UP).toPlainString() + " at " + ratePercent.stripTrailingZeros().toPlainString() + "% a year";
        return Optional.of(entry(m, branch, TxType.SAVINGS_INTEREST, amount, to,
                reference == null ? last.plusDays(1) + " to " + to : reference, what, user));
    }

    /**
     * Interest from {@code from} to {@code to} (both included) and the average balance it was earned on, unrounded. A day's balance
     * is everything dated before it; nothing is earned on a balance of nothing or less.
     */
    public static BigDecimal[] interestBetween(List<Move> moves, LocalDate from, LocalDate to, BigDecimal ratePercent, int dayBasis) {
        List<Move> sorted = moves.stream().sorted(Comparator.comparing(Move::date)).toList();
        BigDecimal dailyRate = ratePercent.divide(BigDecimal.valueOf(100L * dayBasis), MC);
        BigDecimal balance = BigDecimal.ZERO, interest = BigDecimal.ZERO, balanceDays = BigDecimal.ZERO;
        int next = 0, days = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1), days++) {
            while (next < sorted.size() && sorted.get(next).date().isBefore(day)) balance = balance.add(sorted.get(next++).net());
            BigDecimal earning = balance.max(BigDecimal.ZERO);
            interest = interest.add(earning.multiply(dailyRate, MC), MC);
            balanceDays = balanceDays.add(earning);
        }
        return new BigDecimal[] {interest, days == 0 ? BigDecimal.ZERO : balanceDays.divide(BigDecimal.valueOf(days), MC)};
    }

    private BigDecimal dailyRate() {
        return ratePercent.divide(BigDecimal.valueOf(100L * dayBasis), MC);
    }

    private LedgerEntry entry(Member m, Branch branch, TxType type, BigDecimal amount, LocalDate date, String reference, String notes, String user) {
        ProductDef product = products.findFirstByBranchIdAndCategoryAndActiveTrueOrderByIdAsc(branch.getId(), ProductCategory.SAVINGS_INTEREST)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "No savings interest product is set up for this branch"));
        LedgerEntry e = new LedgerEntry();
        e.setBranch(branch);
        e.setMember(m);
        e.setProduct(product);
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
