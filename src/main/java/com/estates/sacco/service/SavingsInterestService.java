package com.estates.sacco.service;

import com.estates.sacco.domain.*;
import com.estates.sacco.repo.*;
import com.estates.sacco.web.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Interest on members' savings, worked out day by day. Each day a member's savings earn the yearly rate divided by the days of the
 * year (365) on the balance they have that day. A deposit starts earning the day after it is made and a withdrawal stops the
 * day after, so a deposit on the 13th of a 31-day month earns on the old balance for 13 days and on the new one for the other 18.
 * <p>
 * The interest is booked as the savings move (SavingsAccrualService): each savings entry books the days since the member's last
 * savings date, and once the month has ended the month end books the days from there to the month end. Each booking is
 * Dr Savings interest expense, Cr Savings interest payable, and is kept as owed to the member (their savings interest account). A payout, normally at year end, moves what has built up into the
 * member's savings. The old system's interest up to the month before the first month here came over with the member ledgers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SavingsInterestService {
    private static final MathContext MC = new MathContext(30);

    private final SavingsInterestRunRepository runs;
    private final MemberLedgerRepository memberLedger;
    private final LedgerRepository ledger;
    private final GeneralLedgerRepository generalLedger;
    private final MemberRepository members;
    private final BranchRepository branches;
    private final ProductDefRepository products;
    private final PostingService posting;
    private final SavingsAccrualService accrual;
    private final AuditService audit;

    @Value("${sacco.savings-interest.rate:8}")
    private BigDecimal ratePercent;
    @Value("${sacco.savings-interest.day-basis:365}")
    private int dayBasis;
    /** The first month interest is worked out for (yyyy-MM). Blank: the feature is off. */
    @Value("${sacco.savings-interest.first-month:}")
    private String firstMonth;

    // ------------------------------------------------------------------ the arithmetic
    /** A change to a member's savings on a day: deposits are positive, withdrawals negative. */
    public record Move(LocalDate date, BigDecimal net) {}

    /** The interest for the month, the average balance it was earned on, and the balance at the start of the month. */
    public record Result(BigDecimal interest, BigDecimal averageBalance, BigDecimal openingBalance) {}

    /**
     * Interest for one member for a month. The balance of a day is everything dated before that day, so money counts from the
     * day after it moves. Nothing is earned on a balance that is nothing or less. The month's total is rounded to whole shillings.
     */
    public static Result interestFor(List<Move> moves, YearMonth month, BigDecimal ratePercent, int dayBasis) {
        List<Move> sorted = moves.stream().sorted(Comparator.comparing(Move::date)).toList();
        BigDecimal dailyRate = ratePercent.divide(BigDecimal.valueOf(100L * dayBasis), MC);
        BigDecimal balance = BigDecimal.ZERO, opening = null, interest = BigDecimal.ZERO, balanceDays = BigDecimal.ZERO;
        int next = 0;
        for (LocalDate day = month.atDay(1); !day.isAfter(month.atEndOfMonth()); day = day.plusDays(1)) {
            while (next < sorted.size() && sorted.get(next).date().isBefore(day)) balance = balance.add(sorted.get(next++).net());
            if (opening == null) opening = balance;
            BigDecimal earning = balance.max(BigDecimal.ZERO);
            interest = interest.add(earning.multiply(dailyRate, MC), MC);
            balanceDays = balanceDays.add(earning);
        }
        return new Result(interest.setScale(0, RoundingMode.HALF_UP),
                balanceDays.divide(BigDecimal.valueOf(month.lengthOfMonth()), 2, RoundingMode.HALF_UP),
                opening == null ? BigDecimal.ZERO : opening.max(BigDecimal.ZERO));
    }

    // ------------------------------------------------------------------ one month
    /**
     * One member's month: the interest the whole month earns, what savings entries have booked of it already (with any adjustments
     * dated in the month), and what the month end still books: the days from the member's last savings date to the month end.
     */
    public record Line(Long memberId, String memberNo, String memberName, BigDecimal openingBalance, BigDecimal averageBalance, BigDecimal interest,
                       BigDecimal booked, BigDecimal toBook) {}

    public static YearMonth parse(String month) {
        try {
            return YearMonth.parse(month);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Month must look like 2026-10");
        }
    }

    public boolean enabled() {
        return !firstMonth.isBlank();
    }

    public BigDecimal ratePercent() {
        return ratePercent;
    }

    public int dayBasis() {
        return dayBasis;
    }

    public String firstMonth() {
        return firstMonth;
    }

    private YearMonth first() {
        return YearMonth.parse(firstMonth);
    }

    private static String reference(YearMonth month) {
        return "Month end " + month;
    }

    private static String payoutReference(LocalDate date) {
        return "Savings interest paid " + date;
    }

    /** Every member's savings moves of a branch up to a date. */
    private Map<Long, List<Move>> movesByMember(Long branchId, LocalDate to) {
        Map<Long, List<Move>> byMember = new HashMap<>();
        for (Object[] r : memberLedger.savingsMoves(branchId, to)) {
            byMember.computeIfAbsent((Long) r[0], k -> new ArrayList<>()).add(new Move((LocalDate) r[1], (BigDecimal) r[2]));
        }
        return byMember;
    }

    /** The month's interest per member: earned, booked already by savings entries, and left for the month end. Nothing saved. */
    @Transactional(readOnly = true)
    public List<Line> preview(Long branchId, YearMonth month) {
        if (enabled() && month.isBefore(first())) return List.of();
        LocalDate end = month.atEndOfMonth();
        Map<Long, List<Move>> byMember = movesByMember(branchId, end);
        Map<Long, BigDecimal> booked = new HashMap<>();
        for (Object[] r : memberLedger.savingsInterestBooked(branchId, month.atDay(1), end)) booked.put((Long) r[0], (BigDecimal) r[1]);
        Set<Long> ids = new HashSet<>(byMember.keySet());
        ids.addAll(booked.keySet());
        Map<Long, Member> byId = new HashMap<>();
        members.findAllById(ids).forEach(m -> byId.put(m.getId(), m));
        boolean closed = runs.existsByBranchIdAndKindAndPeriod(branchId, SavingsInterestRun.ACCRUAL, month.toString());
        List<Line> out = new ArrayList<>();
        for (Long id : ids) {
            List<Move> moves = byMember.getOrDefault(id, List.of());
            Result r = interestFor(moves, month, ratePercent, dayBasis);
            Member m = byId.get(id);
            BigDecimal already = booked.getOrDefault(id, BigDecimal.ZERO);
            LocalDate last = accrual.lastDateOf(m);
            BigDecimal toBook = closed || !last.isBefore(end) ? BigDecimal.ZERO
                    : SavingsAccrualService.interestBetween(moves, last.plusDays(1), end, ratePercent, dayBasis)[0].setScale(0, RoundingMode.HALF_UP);
            if (r.interest().signum() <= 0 && already.signum() == 0 && toBook.signum() <= 0) continue;
            out.add(new Line(m.getId(), m.getMemberNo(), m.getFullName(), r.openingBalance(), r.averageBalance(), r.interest(), already, toBook));
        }
        out.sort(Comparator.comparing(Line::memberNo));
        return out;
    }

    private ProductDef interestProduct(Long branchId) {
        return products.findFirstByBranchIdAndCategoryAndActiveTrueOrderByIdAsc(branchId, ProductCategory.SAVINGS_INTEREST)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "No savings interest product is set up for this branch"));
    }

    /** Books the month's interest for a branch. {@code user} is who asked (null: the system, on its own schedule). */
    @Transactional
    public SavingsInterestRun post(Long branchId, YearMonth month, String user) {
        if (!enabled()) throw new ApiException(HttpStatus.BAD_REQUEST, "Savings interest is not switched on");
        if (month.isBefore(first())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Savings interest before " + first() + " was worked out in the old system and is already in the ledgers");
        }
        if (month.atEndOfMonth().isAfter(LocalDate.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Interest can be booked for a month once it has ended");
        }
        if (runs.existsByBranchIdAndKindAndPeriod(branchId, SavingsInterestRun.ACCRUAL, month.toString())) {
            throw new ApiException(HttpStatus.CONFLICT, "Savings interest for " + month + " has already been booked");
        }
        YearMonth before = month.minusMonths(1);
        if (!before.isBefore(first()) && !runs.existsByBranchIdAndKindAndPeriod(branchId, SavingsInterestRun.ACCRUAL, before.toString())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Book the month end of " + before + " first");
        }
        Branch branch = branches.findById(branchId).orElseThrow();
        interestProduct(branchId);
        LocalDate end = month.atEndOfMonth();
        Map<Long, List<Move>> byMember = movesByMember(branchId, end);
        if (byMember.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "No member has savings in " + month);

        SavingsInterestRun run = new SavingsInterestRun();
        run.setBranch(branch);
        run.setBranchCode(branch.getCode());
        run.setKind(SavingsInterestRun.ACCRUAL);
        run.setPeriod(month.toString());
        run.setPeriodEnd(month.atEndOfMonth());
        run.setRatePercent(ratePercent);
        run.setReference(reference(month));
        run.setCreatedBy(user == null ? "system" : user);
        // each member: the days from their last savings date to the month end (savings entries booked the days before)
        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        for (Member m : members.findAllById(byMember.keySet())) {
            LocalDate last = accrual.lastDateOf(m);
            if (!last.isBefore(end)) continue;
            Optional<LedgerEntry> e = accrual.accrue(m, branch, byMember.get(m.getId()), last, end, run.getReference(), run.getCreatedBy());
            m.setLastSavingsDate(end);
            members.save(m);
            if (e.isEmpty()) continue;
            posting.recordLines(e.get());
            total = total.add(e.get().getAmount());
            count++;
        }
        run.setTotalAmount(total);
        run.setMemberCount(count);
        runs.save(run);
        log("Booked the month end of savings interest for " + month + ": " + total + " for " + count + " members", branch, run, user);
        return run;
    }

    // ------------------------------------------------------------------ the payout
    public record Owed(Long memberId, String memberNo, String memberName, BigDecimal amount) {}

    /** What the members are owed in savings interest up to a date. */
    @Transactional(readOnly = true)
    public List<Owed> owed(Long branchId, LocalDate date) {
        List<Object[]> rows = memberLedger.savingsInterestOwed(branchId, date);
        Map<Long, Member> byId = new HashMap<>();
        members.findAllById(rows.stream().map(r -> (Long) r[0]).toList()).forEach(m -> byId.put(m.getId(), m));
        List<Owed> out = new ArrayList<>();
        for (Object[] r : rows) {
            Member m = byId.get((Long) r[0]);
            out.add(new Owed(m.getId(), m.getMemberNo(), m.getFullName(), (BigDecimal) r[1]));
        }
        out.sort(Comparator.comparing(Owed::memberNo));
        return out;
    }

    /** Pays the savings interest owed up to a date into the members' savings. */
    @Transactional
    public SavingsInterestRun payout(Long branchId, LocalDate date, String user) {
        if (!enabled()) throw new ApiException(HttpStatus.BAD_REQUEST, "Savings interest is not switched on");
        if (date.isAfter(LocalDate.now())) throw new ApiException(HttpStatus.BAD_REQUEST, "The payout cannot be dated in the future");
        if (runs.existsByBranchIdAndKindAndPeriod(branchId, SavingsInterestRun.PAYOUT, date.toString())) {
            throw new ApiException(HttpStatus.CONFLICT, "Savings interest was already paid out on " + date);
        }
        Branch branch = branches.findById(branchId).orElseThrow();
        ProductDef product = interestProduct(branchId);
        String by = user == null ? "system" : user;
        // first the interest up to the day of payment, so what is paid is everything earned up to then
        Map<Long, List<Move>> byMember = movesByMember(branchId, date);
        for (Member m : members.findAllById(byMember.keySet())) {
            LocalDate last = accrual.lastDateOf(m);
            if (!last.isBefore(date)) continue;
            accrual.accrue(m, branch, byMember.get(m.getId()), last, date, null, by).ifPresent(posting::recordLines);
            m.setLastSavingsDate(date);
            members.save(m);
        }
        List<Owed> owed = owed(branchId, date);
        if (owed.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "No member is owed savings interest on " + date);

        SavingsInterestRun run = new SavingsInterestRun();
        run.setBranch(branch);
        run.setBranchCode(branch.getCode());
        run.setKind(SavingsInterestRun.PAYOUT);
        run.setPeriod(date.toString());
        run.setPeriodEnd(date);
        run.setReference(payoutReference(date));
        run.setCreatedBy(user == null ? "system" : user);
        BigDecimal total = BigDecimal.ZERO;
        for (Owed o : owed) {
            LedgerEntry e = new LedgerEntry();
            e.setBranch(branch);
            e.setMember(members.getReferenceById(o.memberId()));
            e.setProduct(product);
            e.setType(TxType.SAVINGS_INTEREST_PAYOUT);
            e.setSource(Source.SYSTEM);
            e.setAmount(o.amount());
            e.setPostedOn(date);
            e.setReference(run.getReference());
            e.setCreatedBy(run.getCreatedBy());
            ledger.save(e);
            posting.record(e);
            total = total.add(o.amount());
        }
        run.setTotalAmount(total);
        run.setMemberCount(owed.size());
        runs.save(run);
        log("Paid savings interest into the savings of " + owed.size() + " members: " + total + " on " + date, branch, run, user);
        return run;
    }

    // ------------------------------------------------------------------ undo
    /** Takes back a run. Only the latest one of a branch can go, so a later run never rests on one that is gone. */
    @Transactional
    public void delete(Long runId, Long branchId, String user) {
        SavingsInterestRun run = runs.findById(runId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Run not found"));
        if (!run.getBranch().getId().equals(branchId)) throw new ApiException(HttpStatus.FORBIDDEN, "That run belongs to another branch");
        SavingsInterestRun latest = runs.findFirstByBranchIdOrderByPeriodEndDescIdDesc(branchId).orElseThrow();
        if (!latest.getId().equals(run.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "Only the latest run (" + latest.getKind().toLowerCase() + " " + latest.getPeriod() + ") can be deleted");
        }
        boolean monthEnd = SavingsInterestRun.ACCRUAL.equals(run.getKind());
        LocalDate end = run.getPeriodEnd();
        List<Member> inBranch = members.findAll().stream().filter(m -> m.getBranch() != null && m.getBranch().getId().equals(branchId)).toList();
        if (monthEnd && inBranch.stream().anyMatch(m -> m.getLastSavingsDate() != null && m.getLastSavingsDate().isAfter(end))) {
            throw new ApiException(HttpStatus.CONFLICT, "Savings entries dated after " + end + " have already booked interest past this month end, so it can no longer be deleted");
        }
        TxType type = monthEnd ? TxType.SAVINGS_INTEREST : TxType.SAVINGS_INTEREST_PAYOUT;
        List<SavingsAccrualService.Change> taken = new ArrayList<>();
        for (LedgerEntry e : ledger.findByBranchIdAndTypeAndReference(branchId, type, run.getReference())) {
            SavingsAccrualService.Change c = SavingsAccrualService.changeOf(e);
            if (c != null) taken.add(c.negate());
            generalLedger.deleteByLedgerEntryId(e.getId());
            memberLedger.deleteByLedgerEntryId(e.getId());
            ledger.delete(e);
        }
        ledger.flush();
        if (monthEnd) {
            // back to where each member stood before the month end: the later of the month before's end and their last savings entry in it
            YearMonth month = YearMonth.from(end);
            LocalDate before = month.atDay(1).minusDays(1).isBefore(accrual.start()) ? accrual.start() : month.atDay(1).minusDays(1);
            Map<Long, LocalDate> lastIn = new HashMap<>();
            for (Object[] r : memberLedger.lastSavingsDates(branchId, month.atDay(1), end)) lastIn.put((Long) r[0], (LocalDate) r[1]);
            for (Member m : inBranch) {
                if (!end.equals(m.getLastSavingsDate())) continue;
                LocalDate d = lastIn.get(m.getId());
                m.setLastSavingsDate(d != null && d.isAfter(before) ? d : before);
                members.save(m);
            }
        } else {
            posting.savingsChanged(taken);   // the paid-in interest no longer earns interest of its own
        }
        runs.delete(run);
        log("Deleted the " + run.getKind().toLowerCase() + " " + run.getPeriod() + " of " + run.getTotalAmount(), run.getBranch(), run, user);
    }

    private void log(String what, Branch branch, SavingsInterestRun run, String user) {
        if (user == null) audit.logSystem(branch, "EDIT", "Savings interest", run.getId(), what);
        else audit.log(branch, "EDIT", "Savings interest", run.getId(), what);
    }

    // ------------------------------------------------------------------ on its own
    public record Due(Long branchId, String branchCode, YearMonth month) {}

    /** The months that have ended since the first month and have not been booked yet, oldest first (the scheduler books them one by one). */
    @Transactional(readOnly = true)
    public List<Due> due() {
        List<Due> out = new ArrayList<>();
        if (!enabled()) return out;
        for (Branch b : branches.findAll()) {
            if (products.findFirstByBranchIdAndCategoryAndActiveTrueOrderByIdAsc(b.getId(), ProductCategory.SAVINGS_INTEREST).isEmpty()) continue;
            for (YearMonth m = first(); !m.atEndOfMonth().isAfter(LocalDate.now()); m = m.plusMonths(1)) {
                if (!runs.existsByBranchIdAndKindAndPeriod(b.getId(), SavingsInterestRun.ACCRUAL, m.toString())) out.add(new Due(b.getId(), b.getCode(), m));
            }
        }
        return out;
    }
}
