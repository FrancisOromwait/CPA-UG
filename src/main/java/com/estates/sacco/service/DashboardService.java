package com.estates.sacco.service;

import com.estates.sacco.domain.Account;
import com.estates.sacco.domain.LedgerEntry;
import com.estates.sacco.domain.LoanStatus;
import com.estates.sacco.domain.Member;
import com.estates.sacco.domain.TxType;
import com.estates.sacco.repo.GeneralLedgerRepository;
import com.estates.sacco.repo.LedgerRepository;
import com.estates.sacco.repo.LoanRepository;
import com.estates.sacco.repo.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Everything the dashboard shows, in one answer: the members' balances (the member summary's figures), the money held on the cash,
 * bank and mobile money accounts, money in from and out to members over the last twelve months, the latest of those movements, and
 * the loan applications waiting for a decision. {@code branchId} null: every branch (the support login).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {
    private final FinancialReportService reports;
    private final MemberRepository members;
    private final LedgerRepository ledger;
    private final LoanRepository loans;
    private final GeneralLedgerRepository generalLedger;

    /** Money members bring in: deposits, shares, membership, loan repayments (and interest paid in the old system). */
    static final Set<TxType> MONEY_IN = EnumSet.of(TxType.SAVINGS_DEPOSIT, TxType.SHARE_PURCHASE, TxType.MEMBERSHIP_FEE,
            TxType.LOAN_REPAYMENT, TxType.LOAN_INTEREST_PAID);
    /** Money paid out to members: withdrawals, refunds and loans paid out. */
    static final Set<TxType> MONEY_OUT = EnumSet.of(TxType.SAVINGS_WITHDRAWAL, TxType.SHARE_REFUND, TxType.MEMBERSHIP_REFUND,
            TxType.LOAN_DISBURSEMENT);

    private static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    public record Cash(String code, String name, BigDecimal balance) {}
    public record Month(String label, BigDecimal moneyIn, BigDecimal moneyOut) {}
    public record Recent(LocalDate date, String memberNo, String memberName, String type, BigDecimal amount, boolean moneyIn) {}
    public record Dashboard(LocalDate asAt, long members, long activeMembers, long savers, long borrowers, long pendingLoans,
                            BigDecimal savings, BigDecimal savingsInterest, BigDecimal shares, BigDecimal loans, BigDecimal loanInterest,
                            List<Cash> cash, BigDecimal cashTotal, List<Month> months, List<Recent> recent) {}

    public Dashboard build(Long branchId) {
        LocalDate today = LocalDate.now();

        // members and their balances
        List<Member> all = members.search(branchId, null);
        long active = all.stream().filter(Member::isActive).count();
        BigDecimal[] sum = FinancialReportService.zeros();
        long savers = 0, borrowers = 0;
        for (BigDecimal[] c : reports.balancesByMember(branchId, today).values()) {
            for (int i = 0; i < sum.length; i++) sum[i] = sum[i].add(c[i]);
            if (c[FinancialReportService.SAVINGS].signum() > 0) savers++;
            if (c[FinancialReportService.LOAN].signum() > 0) borrowers++;
        }

        // what each cash, bank and mobile money account holds today
        Map<Long, Account> book = new HashMap<>();
        for (Account a : reports.cashbookAccounts()) book.put(a.getId(), a);
        Map<Long, BigDecimal> held = new HashMap<>();
        for (Object[] r : generalLedger.totalsBetween(branchId, EARLIEST, today)) {
            if (book.containsKey((Long) r[0])) held.merge((Long) r[0], ((BigDecimal) r[1]).subtract((BigDecimal) r[2]), BigDecimal::add);
        }
        List<Cash> cash = held.entrySet().stream().filter(e -> e.getValue().signum() != 0)
                .map(e -> new Cash(book.get(e.getKey()).getCode(), book.get(e.getKey()).getName(), e.getValue()))
                .sorted(Comparator.comparing(Cash::code)).toList();
        BigDecimal cashTotal = cash.stream().map(Cash::balance).reduce(BigDecimal.ZERO, BigDecimal::add);

        // money in and out, month by month, for the last twelve months (this month included)
        YearMonth last = YearMonth.from(today), first = last.minusMonths(11);
        Map<YearMonth, BigDecimal[]> byMonth = new LinkedHashMap<>();
        for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) byMonth.put(m, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
        for (Object[] r : ledger.monthlyTotals(branchId, first.atDay(1), today)) {
            BigDecimal[] io = byMonth.get(YearMonth.of(((Number) r[0]).intValue(), ((Number) r[1]).intValue()));
            TxType type = (TxType) r[2];
            if (io == null) continue;
            if (MONEY_IN.contains(type)) io[0] = io[0].add((BigDecimal) r[3]);
            else if (MONEY_OUT.contains(type)) io[1] = io[1].add((BigDecimal) r[3]);
        }
        List<Month> months = byMonth.entrySet().stream().map(e -> new Month(e.getKey().format(MONTH), e.getValue()[0], e.getValue()[1])).toList();

        // the latest money in and out
        Set<TxType> moving = EnumSet.copyOf(MONEY_IN);
        moving.addAll(MONEY_OUT);
        List<Recent> recent = ledger.latestOfTypes(branchId, moving, today, PageRequest.of(0, 8)).stream()
                .map(DashboardService::recent).toList();

        long pending = branchId == null ? loans.countByStatus(LoanStatus.PENDING) : loans.countByBranchIdAndStatus(branchId, LoanStatus.PENDING);
        return new Dashboard(today, all.size(), active, savers, borrowers, pending,
                sum[FinancialReportService.SAVINGS], sum[FinancialReportService.SAVINGS_INTEREST], sum[FinancialReportService.SHARES],
                sum[FinancialReportService.LOAN], sum[FinancialReportService.LOAN_INTEREST], cash, cashTotal, months, recent);
    }

    private static Recent recent(LedgerEntry e) {
        String type = e.getType().name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return new Recent(e.getPostedOn(), e.getMember().getMemberNo(), e.getMember().getFullName(),
                Character.toUpperCase(type.charAt(0)) + type.substring(1), e.getAmount(), MONEY_IN.contains(e.getType()));
    }
}
