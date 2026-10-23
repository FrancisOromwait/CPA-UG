package com.estates.sacco.service;

import com.estates.sacco.domain.*;
import com.estates.sacco.repo.AccountRepository;
import com.estates.sacco.repo.BranchRepository;
import com.estates.sacco.repo.GeneralLedgerRepository;
import com.estates.sacco.repo.MemberLedgerRepository;
import com.estates.sacco.repo.MemberRepository;
import com.estates.sacco.repo.ProductDefRepository;
import com.estates.sacco.service.ReportDoc.Col;
import com.estates.sacco.service.ReportDoc.Kv;
import com.estates.sacco.service.ReportDoc.Section;
import com.estates.sacco.web.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds the trial balance, income and expenditure, balance sheet, member statement, member summary and cashbook from the ledgers. */
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
    private final AccountCodes codes;
    private final MemberRepository members;
    private final ProductDefRepository products;

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

    // ------------------------------------------------------------------ member summary
    /**
     * Every member on one line with what they hold or owe at a date, in the member statement's columns: savings, savings interest,
     * shares, membership, loan (principal) and loan interest. It follows the statement's rules, added up per member in a few queries
     * rather than member by member: a repayment pays the interest that had fallen due first and the rest reduces the loan.
     */
    public ReportDoc memberSummary(Long branchId, LocalDate asAt) {
        Map<Long, BigDecimal[]> byMember = balancesByMember(branchId, asAt);
        int[] order = {0, 5, 1, 4, 2, 3};
        BigDecimal[] sum = zeros();
        List<List<String>> rows = new ArrayList<>();
        List<Member> list = new ArrayList<>(members.search(branchId, null));
        list.sort((a, b) -> compareMemberNo(a.getMemberNo(), b.getMemberNo()));
        for (Member m : list) {
            BigDecimal[] c = byMember.getOrDefault(m.getId(), zeros());
            add(sum, c);
            rows.add(shownRow(m.getMemberNo(), m.getFullName(), c, order, true));
        }
        List<Col> cols = List.of(new Col("No", false), new Col("Member", false), new Col("Savings", true), new Col("Savings interest", true),
                new Col("Shares", true), new Col("Membership", true), new Col("Loan", true), new Col("Loan interest", true));
        List<String> totalRow = shownRow("", "Total (" + list.size() + " members)", sum, order, false);
        return new ReportDoc("Member Summary", branchLine(branchId), null, "As at " + asAt.format(DATE),
                List.of(new Kv("Members", String.valueOf(list.size())), new Kv("Savings", fmt(sum[SAVINGS])), new Kv("Savings interest", fmt(sum[SAVINGS_INTEREST])),
                        new Kv("Shares", fmt(sum[SHARES])), new Kv("Membership", fmt(sum[MEMBERSHIP])), new Kv("Loans", fmt(sum[LOAN])),
                        new Kv("Loan interest owed", fmt(sum[LOAN_INTEREST]))),
                List.of(new Section("Members", cols, rows, totalRow)), list.isEmpty() ? "There are no members yet." : null);
    }

    /**
     * One kind of balance on its own, as at a date: "savings" (savings and the savings interest owed to the member), "shares", or
     * "loans" (loan principal and the interest owed). Only members with something in it are listed. The figures are the member
     * summary's, so the reports always agree.
     */
    public ReportDoc balanceReport(Long branchId, LocalDate asAt, String kind) {
        record Part(String name, int at) {}
        List<Part> parts;
        String title, totalLabel, who;
        switch (kind) {
            case "savings" -> { title = "Savings Report"; parts = List.of(new Part("Savings", SAVINGS), new Part("Savings interest", SAVINGS_INTEREST));
                totalLabel = "Total savings"; who = "Savers"; }
            case "shares" -> { title = "Shares Report"; parts = List.of(new Part("Shares", SHARES)); totalLabel = "Total shares"; who = "Shareholders"; }
            case "loans" -> { title = "Loan Report"; parts = List.of(new Part("Loan balance", LOAN), new Part("Interest owed", LOAN_INTEREST));
                totalLabel = "Total owed"; who = "Borrowers"; }
            default -> throw new ApiException(HttpStatus.NOT_FOUND, "Unknown report");
        }
        Map<Long, BigDecimal[]> byMember = balancesByMember(branchId, asAt);
        List<Member> list = new ArrayList<>(members.search(branchId, null));
        list.sort((a, b) -> compareMemberNo(a.getMemberNo(), b.getMemberNo()));
        BigDecimal[] sums = new BigDecimal[parts.size()];
        Arrays.fill(sums, BigDecimal.ZERO);
        BigDecimal grand = BigDecimal.ZERO;
        List<List<String>> rows = new ArrayList<>();
        for (Member m : list) {
            BigDecimal[] c = byMember.get(m.getId());
            if (c == null || parts.stream().allMatch(p -> c[p.at()].signum() == 0)) continue;
            List<String> row = new ArrayList<>(List.of(m.getMemberNo(), m.getFullName()));
            BigDecimal total = BigDecimal.ZERO;
            for (int i = 0; i < parts.size(); i++) {
                BigDecimal v = c[parts.get(i).at()];
                row.add(blankZero(v));
                sums[i] = sums[i].add(v);
                total = total.add(v);
            }
            if (parts.size() > 1) row.add(fmt(total));
            grand = grand.add(total);
            rows.add(row);
        }
        List<Col> cols = new ArrayList<>(List.of(new Col("No", false), new Col("Member", false)));
        parts.forEach(p -> cols.add(new Col(p.name(), true)));
        if (parts.size() > 1) cols.add(new Col("Total", true));
        List<String> totalRow = new ArrayList<>(List.of("", "Total (" + rows.size() + " members)"));
        for (BigDecimal s : sums) totalRow.add(fmt(s));
        if (parts.size() > 1) totalRow.add(fmt(grand));
        List<Kv> totals = new ArrayList<>(List.of(new Kv(who, String.valueOf(rows.size()))));
        for (int i = 0; i < parts.size(); i++) totals.add(new Kv(parts.get(i).name(), fmt(sums[i])));
        if (parts.size() > 1) totals.add(new Kv(totalLabel, fmt(grand)));
        return new ReportDoc(title, branchLine(branchId), null, "As at " + asAt.format(DATE), totals,
                List.of(new Section(who, cols, rows, totalRow)), rows.isEmpty() ? "No member has a balance here at this date." : null);
    }

    /** Where each figure sits in a member's balances (the member statement's columns). */
    public static final int SAVINGS = 0, SHARES = 1, LOAN = 2, LOAN_INTEREST = 3, MEMBERSHIP = 4, SAVINGS_INTEREST = 5;

    /** Every member's balances at a date (by member id; see SAVINGS, SHARES...), added up in a few queries. Members with no lines are left out. */
    public Map<Long, BigDecimal[]> balancesByMember(Long branchId, LocalDate asAt) {
        Map<Long, BigDecimal[]> byMember = new HashMap<>();
        for (Object[] r : memberLedger.totalsByMember(branchId, asAt)) {
            BigDecimal[] c = byMember.computeIfAbsent((Long) r[0], k -> zeros());
            ProductCategory cat = (ProductCategory) r[1];
            TxType type = (TxType) r[2];
            BigDecimal dr = (BigDecimal) r[3], cr = (BigDecimal) r[4];
            switch (cat) {
                case SAVINGS -> c[0] = c[0].add(cr).subtract(dr);
                case SHARES -> c[1] = c[1].add(cr).subtract(dr);
                case MEMBERSHIP -> c[4] = c[4].add(cr).subtract(dr);
                case SAVINGS_INTEREST -> c[5] = c[5].add(cr).subtract(dr);
                default -> {
                    if (type == TxType.LOAN_INTEREST) c[3] = c[3].add(dr);
                    else if (type == TxType.LOAN_INTEREST_REVERSAL || type == TxType.LOAN_INTEREST_PAID) c[3] = c[3].subtract(cr);
                    else if (type == TxType.LOAN_REPAYMENT) c[2] = c[2].subtract(cr);      // the interest part is moved back below
                    else c[2] = c[2].add(dr).subtract(cr);
                }
            }
        }
        List<Long> interestAccounts = products.findAll().stream().filter(p -> p.getCategory() == ProductCategory.INTEREST_RECEIVABLE)
                .map(p -> p.getGlAccount().getId()).distinct().toList();
        if (!interestAccounts.isEmpty()) {
            for (Object[] r : generalLedger.repaymentInterestByMember(branchId, interestAccounts, asAt)) {
                BigDecimal[] c = byMember.computeIfAbsent((Long) r[0], k -> zeros());
                BigDecimal interest = (BigDecimal) r[1];
                c[3] = c[3].subtract(interest);
                c[2] = c[2].add(interest);
            }
        }
        return byMember;
    }

    /** Member numbers in natural order: M2 before M10, 99 before 100. */
    public static int compareMemberNo(String a, String b) {
        String x = a == null ? "" : a, y = b == null ? "" : b;
        String px = x.replaceAll("\\d+$", ""), py = y.replaceAll("\\d+$", "");
        if (!px.equalsIgnoreCase(py)) return px.compareToIgnoreCase(py);
        String nx = x.substring(px.length()), ny = y.substring(py.length());
        if (!nx.isEmpty() && !ny.isEmpty() && nx.length() < 19 && ny.length() < 19) {
            int byNumber = Long.compare(Long.parseLong(nx), Long.parseLong(ny));
            if (byNumber != 0) return byNumber;
        }
        return x.compareToIgnoreCase(y);
    }

    public static BigDecimal[] zeros() {
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

    // ------------------------------------------------------------------ cashbook receipts
    /** The accounts the cashbook covers: cash, bank and mobile money, by code. */
    public List<Account> cashbookAccounts() {
        return accounts.findAll().stream().filter(a -> a.getType() == AccountType.ASSET && codes.isCashbook(a.getCode()))
                .sorted(Comparator.comparing(Account::getCode)).toList();
    }

    /** One line of the cashbook; a payroll batch adds all its members' lines into one. */
    private static final class CashLine {
        final LocalDate date;
        final String ref, party;
        String details;
        BigDecimal amount;
        int members = 1;
        boolean transfer;

        CashLine(LocalDate date, String ref, String party, String details, BigDecimal amount) {
            this.date = date; this.ref = ref; this.party = party; this.details = details; this.amount = amount;
        }
    }

    /**
     * The cashbook of the cash, bank and mobile money accounts, one table per account, oldest first: money received (every debit
     * on them) or, with {@code payments}, money paid out (every credit). A payroll batch is one line, not one per member. The details
     * name the accounts on the other side of the same transaction (credited for a receipt, debited for a payment); money moved
     * between the sacco's own cash and bank accounts is marked as a transfer and totalled on its own.
     * {@code accountId} null: all cashbook accounts.
     */
    public ReportDoc cashbook(Long branchId, Long accountId, LocalDate from, LocalDate to, boolean payments) {
        if (from.isAfter(to)) throw new ApiException(HttpStatus.BAD_REQUEST, "The From date is after the To date");
        List<Account> book = cashbookAccounts();
        if (accountId != null) {
            book = book.stream().filter(a -> a.getId().equals(accountId)).toList();
            if (book.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "That account is not a cash, bank or mobile money account");
        }
        Map<Long, Account> all = accounts.findAll().stream().collect(Collectors.toMap(Account::getId, a -> a));
        List<Object[]> raw = book.isEmpty() ? List.of()
                : generalLedger.cashbook(branchId, book.stream().map(Account::getId).toList(), from, to, payments);

        // what the money was for: the accounts on the other side of its transaction
        Set<Long> journalIds = new HashSet<>(), entryIds = new HashSet<>();
        Set<String> groups = new HashSet<>();
        for (Object[] r : raw) {
            if (r[12] != null) journalIds.add((Long) r[12]);
            else if (r[8] != null && !payments) groups.add((String) r[8]);
            else if (r[6] != null && r[9] == null) entryIds.add((Long) r[6]);
        }
        Map<String, Set<Long>> otherSide = new HashMap<>();
        for (List<Long> ids : chunks(journalIds)) collect(otherSide, "J", generalLedger.otherSideOfJournals(ids, payments));
        for (List<Long> ids : chunks(entryIds)) collect(otherSide, "E", generalLedger.otherSideOfEntries(ids, payments));
        for (List<String> gs : chunks(groups)) collect(otherSide, "G", generalLedger.creditedByDeposits(gs));

        Map<Long, List<CashLine>> byAccount = new LinkedHashMap<>();
        Map<String, CashLine> payrolls = new HashMap<>();
        for (Object[] r : raw) {
            LocalDate date = (LocalDate) r[0];
            Long account = (Long) r[1];
            BigDecimal amount = (BigDecimal) r[2];
            List<CashLine> list = byAccount.computeIfAbsent(account, k -> new ArrayList<>());
            if (r[9] != null) {      // a member's line of a payroll batch: added to the batch's one line
                String key = account + "|" + r[9] + "|" + date;
                CashLine p = payrolls.get(key);
                if (p == null) {
                    p = new CashLine(date, "Payroll " + r[11], r[10] + " payroll", "", amount);
                    payrolls.put(key, p);
                    list.add(p);
                } else {
                    p.amount = p.amount.add(amount);
                    p.members++;
                }
                p.details = "Payroll deductions, " + p.members + (p.members == 1 ? " member" : " members");
                continue;
            }
            String ref = firstText((String) r[7], (String) r[14], (String) r[13]);
            String party = r[4] != null ? r[4] + " - " + r[5] : firstText((String) r[3], (String) r[15]);
            boolean grouped = r[12] == null && r[8] != null && !payments;
            Set<Long> others = otherSide.get(r[12] != null ? "J" + r[12] : grouped ? "G" + r[8] : "E" + r[6]);
            CashLine line = new CashLine(date, ref, party, "", amount);
            describe(line, others, all, (String) r[3], payments);
            list.add(line);
        }

        String received = payments ? "paid" : "received";
        BigDecimal total = BigDecimal.ZERO, transfers = BigDecimal.ZERO;
        int count = 0;
        List<Col> cols = List.of(new Col("Date", false), new Col(payments ? "Voucher / Ref" : "Receipt / Ref", false),
                new Col(payments ? "Paid to" : "Received from", false), new Col("Details", false), new Col("Amount", true));
        List<Section> sections = new ArrayList<>();
        for (Account a : book) {
            List<CashLine> list = byAccount.getOrDefault(a.getId(), List.of());
            if (list.isEmpty() && accountId == null) continue;       // all accounts: only those that moved money
            BigDecimal sum = BigDecimal.ZERO;
            List<List<String>> rows = new ArrayList<>();
            for (CashLine l : list) {
                rows.add(List.of(l.date.format(DATE), l.ref, l.party, l.details, fmt(l.amount)));
                sum = sum.add(l.amount);
                if (l.transfer) transfers = transfers.add(l.amount);
            }
            total = total.add(sum);
            count += list.size();
            sections.add(new Section(a.getCode() + " - " + a.getName(), cols, rows, List.of("", "", "", "Total " + received, fmt(sum))));
        }
        List<Kv> totals = new ArrayList<>(List.of(new Kv("Total " + received, fmt(total)), new Kv(payments ? "Payments" : "Receipts", String.valueOf(count))));
        if (transfers.signum() > 0) {
            totals.add(new Kv("Transfers between own accounts", fmt(transfers)));
            totals.add(new Kv(payments ? "Paid to outside" : "Received from outside", fmt(total.subtract(transfers))));
        }
        String subject = accountId == null ? "All cash, bank and mobile money accounts" : book.get(0).getCode() + " - " + book.get(0).getName();
        return new ReportDoc(payments ? "Cashbook Payments" : "Cashbook Receipts", branchLine(branchId), subject,
                from.format(DATE) + " to " + to.format(DATE), totals, sections,
                sections.isEmpty() ? "No money was " + received + " in this period." : null);
    }

    /** Fills in what the money was for, from the accounts on the other side (a transfer when they are all cashbook accounts). */
    private void describe(CashLine line, Set<Long> others, Map<Long, Account> all, String fallback, boolean payments) {
        List<Account> accs = others == null ? List.of() : others.stream().map(all::get).filter(Objects::nonNull)
                .sorted(Comparator.comparing(Account::getCode)).toList();
        if (accs.isEmpty()) {
            line.details = fallback == null ? "" : fallback;
            return;
        }
        line.transfer = accs.stream().allMatch(a -> a.getType() == AccountType.ASSET && codes.isCashbook(a.getCode()));
        String names = accs.stream().limit(3).map(Account::getName).collect(Collectors.joining(", "))
                + (accs.size() > 3 ? " and " + (accs.size() - 3) + " more" : "");
        line.details = (line.transfer ? (payments ? "Transfer to " : "Transfer from ") : "") + names;
    }

    private static void collect(Map<String, Set<Long>> into, String prefix, List<Object[]> rows) {
        for (Object[] r : rows) into.computeIfAbsent(prefix + r[0], k -> new HashSet<>()).add((Long) r[1]);
    }

    /** Splits a long list of ids into pieces, so no single query asks for thousands at once. */
    private static <T> List<List<T>> chunks(Collection<T> items) {
        List<T> list = new ArrayList<>(items);
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += 1000) out.add(list.subList(i, Math.min(i + 1000, list.size())));
        return out;
    }

    private static String firstText(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
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
