package com.estates.sacco.service;

import com.estates.sacco.domain.*;
import com.estates.sacco.repo.AccountRepository;
import com.estates.sacco.repo.BranchRepository;
import com.estates.sacco.repo.GeneralLedgerRepository;
import com.estates.sacco.repo.MemberLedgerRepository;
import com.estates.sacco.service.ReportDoc.Col;
import com.estates.sacco.service.ReportDoc.Kv;
import com.estates.sacco.service.ReportDoc.Section;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds the trial balance, income and expenditure, balance sheet and member statement from the ledgers. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FinancialReportService {
    private final GeneralLedgerRepository generalLedger;
    private final MemberLedgerRepository memberLedger;
    private final AccountRepository accounts;
    private final BranchRepository branches;
    private final PostingService posting;
    private final DeploymentSettings settings;

    private static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final Col CODE = new Col("Code", false);
    private static final Col ACCOUNT = new Col("Account", false);

    private record Bal(String code, String name, AccountType type, BigDecimal debit, BigDecimal credit) {
        BigDecimal debitNet() { return debit.subtract(credit); }
        BigDecimal creditNet() { return credit.subtract(debit); }
    }

    // ------------------------------------------------------------------ trial balance
    public ReportDoc trialBalance(Long branchId, LocalDate asAt) {
        List<List<String>> rows = new ArrayList<>();
        BigDecimal totalDebit = BigDecimal.ZERO, totalCredit = BigDecimal.ZERO;
        for (Bal b : balances(branchId, EARLIEST, asAt)) {
            if (b.debit().signum() == 0 && b.credit().signum() == 0) continue;
            BigDecimal net = b.debitNet();
            BigDecimal dr = net.signum() > 0 ? net : BigDecimal.ZERO;
            BigDecimal cr = net.signum() < 0 ? net.negate() : BigDecimal.ZERO;
            totalDebit = totalDebit.add(dr);
            totalCredit = totalCredit.add(cr);
            rows.add(List.of(b.code(), b.name(), pretty(b.type().name()), blankZero(dr), blankZero(cr)));
        }
        boolean balanced = totalDebit.compareTo(totalCredit) == 0;
        Section s = new Section("Account balances",
                List.of(CODE, ACCOUNT, new Col("Type", false), new Col("Debit", true), new Col("Credit", true)),
                rows, List.of("", "Total", "", fmt(totalDebit), fmt(totalCredit)));
        return new ReportDoc("Trial Balance", branchLine(branchId), null, "As at " + asAt.format(DATE),
                List.of(new Kv("Total debit", fmt(totalDebit)), new Kv("Total credit", fmt(totalCredit)),
                        new Kv("Status", balanced ? "Balanced" : "Out of balance")),
                List.of(s), null);
    }

    // ------------------------------------------------------------------ income and expenditure
    public ReportDoc incomeExpenditure(Long branchId, LocalDate from, LocalDate to) {
        List<Bal> bals = balances(branchId, from, to);
        List<List<String>> income = lines(bals, AccountType.INCOME, Bal::creditNet);
        List<List<String>> expense = lines(bals, AccountType.EXPENSE, Bal::debitNet);
        BigDecimal totalIncome = total(bals, AccountType.INCOME, Bal::creditNet);
        BigDecimal totalExpense = total(bals, AccountType.EXPENSE, Bal::debitNet);
        BigDecimal result = totalIncome.subtract(totalExpense);

        List<Col> cols = List.of(CODE, ACCOUNT, new Col("Amount", true));
        List<Section> sections = List.of(
                new Section("Income", cols, income, List.of("", "Total income", fmt(totalIncome))),
                new Section("Expenditure", cols, expense, List.of("", "Total expenditure", fmt(totalExpense))),
                new Section("Result for the period", List.of(new Col("", false), new Col("", false), new Col("Amount", true)),
                        List.of(List.of("", "Total income", fmt(totalIncome)), List.of("", "Total expenditure", fmt(totalExpense))),
                        List.of("", result.signum() >= 0 ? "Surplus" : "Deficit", fmt(result))));
        return new ReportDoc("Income and Expenditure Statement", branchLine(branchId), null,
                from.format(DATE) + " to " + to.format(DATE),
                List.of(new Kv("Total income", fmt(totalIncome)), new Kv("Total expenditure", fmt(totalExpense)),
                        new Kv(result.signum() >= 0 ? "Surplus" : "Deficit", fmt(result))),
                sections, null);
    }

    // ------------------------------------------------------------------ balance sheet
    public ReportDoc balanceSheet(Long branchId, LocalDate asAt) {
        List<Bal> bals = balances(branchId, EARLIEST, asAt);
        BigDecimal assets = total(bals, AccountType.ASSET, Bal::debitNet);
        BigDecimal liabilities = total(bals, AccountType.LIABILITY, Bal::creditNet);
        BigDecimal equityAccounts = total(bals, AccountType.EQUITY, Bal::creditNet);
        // Income and expense accounts are not closed to equity, so the result to date is shown inside equity
        BigDecimal result = total(bals, AccountType.INCOME, Bal::creditNet).subtract(total(bals, AccountType.EXPENSE, Bal::debitNet));
        BigDecimal equity = equityAccounts.add(result);
        BigDecimal liabilitiesAndEquity = liabilities.add(equity);

        List<Col> cols = List.of(CODE, ACCOUNT, new Col("Amount", true));
        List<List<String>> equityRows = lines(bals, AccountType.EQUITY, Bal::creditNet);
        equityRows.add(List.of("", result.signum() >= 0 ? "Surplus to date" : "Deficit to date", fmt(result)));

        List<Section> sections = List.of(
                new Section("Assets", cols, lines(bals, AccountType.ASSET, Bal::debitNet), List.of("", "Total assets", fmt(assets))),
                new Section("Liabilities", cols, lines(bals, AccountType.LIABILITY, Bal::creditNet), List.of("", "Total liabilities", fmt(liabilities))),
                new Section("Equity", cols, equityRows, List.of("", "Total equity", fmt(equity))));
        boolean balanced = assets.compareTo(liabilitiesAndEquity) == 0;
        return new ReportDoc("Balance Sheet", branchLine(branchId), null, "As at " + asAt.format(DATE),
                List.of(new Kv("Total assets", fmt(assets)), new Kv("Total liabilities", fmt(liabilities)),
                        new Kv("Total equity", fmt(equity)), new Kv("Liabilities + equity", fmt(liabilitiesAndEquity))),
                sections, balanced ? null : "The balance sheet is out of balance. Check the general ledger.");
    }

    // ------------------------------------------------------------------ member statement
    /**
     * One table for the member: every transaction in date order with its amount in the Savings, Shares, Loan or Interest column.
     * Savings and shares go up with deposits and down with withdrawals; the loan column is the loan principal (up when paid out,
     * down when repaid); the interest column is interest charged (up) and interest paid (down). The bottom row is what the
     * member has in each column, or owes, at the end of the period. {@code from} may be null for the whole history.
     */
    public ReportDoc memberStatement(Member member, LocalDate from, LocalDate to) {
        List<MemberLedgerLine> all = memberLedger.findByMemberIdOrderByIdAsc(member.getId()).stream()
                .sorted(Comparator.comparing(MemberLedgerLine::getPostedOn).thenComparing(MemberLedgerLine::getId)).toList();
        BigDecimal[] opening = zeros(), moved = zeros();
        List<List<String>> rows = new ArrayList<>();
        List<MemberLedgerLine> shown = new ArrayList<>();
        for (MemberLedgerLine l : all) {
            if (l.getPostedOn().isAfter(to)) continue;
            BigDecimal[] part = columns(l);
            if (from != null && l.getPostedOn().isBefore(from)) {
                add(opening, part);
            } else {
                add(moved, part);
                shown.add(l);
            }
        }
        // columns of a line: 0 savings, 1 shares, 2 loan principal, 3 loan interest, 4 membership, 5 savings interest; SHOWN in this order:
        int[] order = {0, 5, 1, 4, 2, 3};
        rows.add(shownRow("", "Opening balance", opening, order, false));
        for (MemberLedgerLine l : shown) {
            String desc = l.getProductCode() + " · " + (l.getDescription() == null ? "" : l.getDescription());
            rows.add(shownRow(l.getPostedOn().format(DATE), desc, columns(l), order, true));
        }
        BigDecimal[] closing = zeros();
        add(closing, opening);
        add(closing, moved);
        List<Col> cols = List.of(new Col("Date", false), new Col("Description", false), new Col("Savings", true), new Col("Savings interest", true),
                new Col("Shares", true), new Col("Membership", true), new Col("Loan", true), new Col("Loan interest", true));
        List<Section> sections = all.isEmpty() ? List.of() : List.of(new Section("Member account", cols, rows,
                shownRow("", "Closing balance", closing, order, false)));
        List<Kv> totals = all.isEmpty() ? List.of() : List.of(new Kv("Savings", fmt(closing[0])), new Kv("Savings interest", fmt(closing[5])),
                new Kv("Shares", fmt(closing[1])), new Kv("Membership", fmt(closing[4])), new Kv("Loan", fmt(closing[2])),
                new Kv("Loan interest owed", fmt(closing[3])));
        String period = from == null ? "Up to " + to.format(DATE) : from.format(DATE) + " to " + to.format(DATE);
        return new ReportDoc("Member Statement", branchLine(member.getBranch().getId()),
                "Member: " + member.getMemberNo() + " - " + member.getFullName(), period, totals, sections,
                sections.isEmpty() ? "This member has no transactions yet." : null);
    }

    private static BigDecimal[] zeros() {
        return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    }

    /** One row of the statement: the first two cells, then the amounts in the order they are shown (blank when nothing moved). */
    private static List<String> shownRow(String first, String second, BigDecimal[] values, int[] order, boolean blankZeros) {
        List<String> row = new ArrayList<>();
        row.add(first);
        row.add(second);
        for (int i : order) row.add(blankZeros ? blankZero(values[i]) : fmt(values[i]));
        return row;
    }

    private static void add(BigDecimal[] to, BigDecimal[] from) {
        for (int i = 0; i < to.length; i++) to[i] = to[i].add(from[i]);
    }

    /** What one member-ledger line does to {savings, shares, loan principal, loan interest, membership, savings interest}. */
    private BigDecimal[] columns(MemberLedgerLine l) {
        BigDecimal[] c = zeros();
        switch (l.getCategory()) {
            case SAVINGS -> c[0] = l.getCredit().subtract(l.getDebit());
            case SHARES -> c[1] = l.getCredit().subtract(l.getDebit());
            case MEMBERSHIP -> c[4] = l.getCredit().subtract(l.getDebit());
            case SAVINGS_INTEREST -> c[5] = l.getCredit().subtract(l.getDebit());
            default -> {
                TxType type = l.getLedgerEntry() == null ? null : l.getLedgerEntry().getType();
                if (type == TxType.LOAN_INTEREST) c[3] = l.getDebit();
                else if (type == TxType.LOAN_INTEREST_REVERSAL || type == TxType.LOAN_INTEREST_PAID) c[3] = l.getCredit().negate();
                else if (type == TxType.LOAN_REPAYMENT) {
                    BigDecimal interest = posting.postedInterestPortion(l.getLedgerEntry());
                    c[3] = interest.negate();
                    c[2] = l.getCredit().subtract(interest).negate();
                } else c[2] = l.getDebit().subtract(l.getCredit());
            }
        }
        return c;
    }

    // ------------------------------------------------------------------ helpers
    /** Ledger totals per account (merged by account code when several branches are included), sorted by code. */
    private List<Bal> balances(Long branchId, LocalDate from, LocalDate to) {
        List<Object[]> raw = generalLedger.totalsBetween(branchId, from, to);
        Map<Long, Account> byId = accounts.findAllById(raw.stream().map(r -> (Long) r[0]).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Account::getId, a -> a));
        Map<String, Bal> merged = new TreeMap<>();
        for (Object[] r : raw) {
            Account a = byId.get((Long) r[0]);
            merged.merge(a.getCode(), new Bal(a.getCode(), a.getName(), a.getType(), (BigDecimal) r[1], (BigDecimal) r[2]),
                    (x, y) -> new Bal(x.code(), x.name(), x.type(), x.debit().add(y.debit()), x.credit().add(y.credit())));
        }
        return new ArrayList<>(merged.values());
    }

    private static List<List<String>> lines(List<Bal> bals, AccountType type, Function<Bal, BigDecimal> amount) {
        List<List<String>> rows = new ArrayList<>();
        for (Bal b : bals) {
            if (b.type() != type || amount.apply(b).signum() == 0) continue;
            rows.add(List.of(b.code(), b.name(), fmt(amount.apply(b))));
        }
        return rows;
    }

    private static BigDecimal total(List<Bal> bals, AccountType type, Function<Bal, BigDecimal> amount) {
        return bals.stream().filter(b -> b.type() == type).map(amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String branchLine(Long branchId) {
        if (branchId == null) return "All branches (consolidated)";
        // a one-branch installation shows the name only: there is no Sacco ID to tell branches apart
        return branches.findById(branchId).map(b -> settings.isSingleBranch() ? b.getName() : b.getCode() + " - " + b.getName()).orElse("");
    }

    private static String pretty(String enumName) {
        String t = enumName.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private static String blankZero(BigDecimal v) {
        return v.signum() == 0 ? "" : fmt(v);
    }

    /** 1,234.50 and (1,234.50) for negatives. */
    static String fmt(BigDecimal v) {
        String s = String.format(Locale.US, "%,.2f", v.abs());
        return v.signum() < 0 ? "(" + s + ")" : s;
    }
}
