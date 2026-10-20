package com.estates.sacco.web;

import com.estates.sacco.domain.*;
import com.estates.sacco.repo.GeneralLedgerRepository;
import com.estates.sacco.repo.MemberLedgerRepository;
import com.estates.sacco.security.BranchScope;
import com.estates.sacco.service.LedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Read access to the member ledger (ML) and the general ledger (GL). Both are written only by PostingService. */
@RestController
@RequiredArgsConstructor
public class LedgerController {
    private final MemberLedgerRepository memberLedger;
    private final GeneralLedgerRepository generalLedger;
    private final LedgerService ledgerService;
    private final BranchScope scope;

    // ---- member ledger
    public record MlLine(Long id, LocalDate date, String branchCode, String productCode, ProductCategory category, String description,
                         BigDecimal debit, BigDecimal credit, BigDecimal balance) {}

    public record ProductBalance(String productCode, String productName, ProductCategory category, BigDecimal balance) {}

    public record MemberLedgerView(Long memberId, String memberNo, String memberName, String branchCode,
                                   List<ProductBalance> balances, List<MlLine> lines) {}

    /** Savings and shares grow with credits; loans grow with debits. */
    private static BigDecimal movement(ProductCategory c, BigDecimal debit, BigDecimal credit) {
        return c == ProductCategory.LOAN ? debit.subtract(credit) : credit.subtract(debit);
    }

    @GetMapping("/api/member-ledger")
    @Transactional(readOnly = true)
    MemberLedgerView memberLedger(@RequestParam Long memberId) {
        Member m = ledgerService.requireMember(memberId);
        List<MemberLedgerLine> rows = memberLedger.findByMemberIdOrderByIdAsc(memberId);

        Map<String, BigDecimal> running = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        Map<String, ProductCategory> cats = new HashMap<>();
        List<MlLine> lines = new ArrayList<>();
        for (MemberLedgerLine r : rows) {
            BigDecimal bal = running.merge(r.getProductCode(), movement(r.getCategory(), r.getDebit(), r.getCredit()), BigDecimal::add);
            names.put(r.getProductCode(), r.getProduct().getName());
            cats.put(r.getProductCode(), r.getCategory());
            lines.add(new MlLine(r.getId(), r.getPostedOn(), r.getBranchCode(), r.getProductCode(), r.getCategory(), r.getDescription(),
                    r.getDebit(), r.getCredit(), bal));
        }
        List<ProductBalance> balances = running.entrySet().stream()
                .map(e -> new ProductBalance(e.getKey(), names.get(e.getKey()), cats.get(e.getKey()), e.getValue())).toList();
        return new MemberLedgerView(m.getId(), m.getMemberNo(), m.getFullName(), m.getBranch().getCode(), balances, lines);
    }

    // ---- general ledger
    public record GlLine(Long id, LocalDate date, String branchCode, String journalNo, String accountCode,
                         String accountName, String productCode, String memberNo, String description,
                         BigDecimal debit, BigDecimal credit, Long entryId, Long journalId,
                         BigDecimal balance, String balanceSide) {}

    @GetMapping("/api/general-ledger")
    @Transactional(readOnly = true)
    List<GlLine> generalLedger(@RequestParam(required = false) Long branchId,
                               @RequestParam(required = false) Long accountId,
                               @RequestParam(required = false) String accountCode,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                               @RequestParam(defaultValue = "300") int limit,
                               @RequestParam(defaultValue = "false") boolean oldestFirst) {
        LocalDate end = to == null ? LocalDate.of(2999, 12, 31) : to;
        Long branch = scope.readBranch(branchId);
        String code = accountCode == null || accountCode.isBlank() ? null : accountCode.trim();
        LocalDate start = from == null ? LocalDate.of(2000, 1, 1) : from;
        PageRequest size = PageRequest.of(0, Math.max(1, Math.min(limit, 2000)));
        // newest first by default (the latest lines); oldest first reads a chosen stretch from its first line, like a ledger book
        List<GeneralLedgerLine> page = oldestFirst ? generalLedger.searchOldestFirst(branch, accountId, code, start, end, size)
                : generalLedger.search(branch, accountId, code, start, end, size);

        // Running balance of each account after every line: all of its history up to that line, not only the dates shown
        Map<Long, BigDecimal> balanceAfter = new HashMap<>();
        if (!page.isEmpty()) {
            // Only the stretch of dates on the page is read; what came before it is added up by the database
            List<Long> accountIds = page.stream().map(l -> l.getAccount().getId()).distinct().toList();
            List<Long> branchIds = page.stream().map(l -> l.getBranch().getId()).distinct().toList();
            LocalDate first = page.stream().map(GeneralLedgerLine::getPostedOn).min(LocalDate::compareTo).orElseThrow();
            LocalDate last = page.stream().map(GeneralLedgerLine::getPostedOn).max(LocalDate::compareTo).orElseThrow();
            Map<String, BigDecimal> running = new HashMap<>();   // keyed by branch and account: the chart is shared, the books are not
            for (Object[] o : generalLedger.balancesBefore(accountIds, branchIds, first)) running.put(o[0] + ":" + o[1], (BigDecimal) o[2]);
            for (Object[] o : generalLedger.movementsBetween(accountIds, branchIds, first, last)) {
                BigDecimal net = running.merge(o[1] + ":" + o[2], ((BigDecimal) o[3]).subtract((BigDecimal) o[4]), BigDecimal::add);
                balanceAfter.put((Long) o[0], net);   // debit minus credit so far; positive = debit balance
            }
        }
        return page.stream()
                .map(g -> new GlLine(g.getId(), g.getPostedOn(), g.getBranch().getCode(), g.getJournal() != null ? g.getJournal().getJournalNo() : "T" + g.getLedgerEntry().getId(),
                        g.getAccount().getCode(), g.getAccount().getName(), g.getProductCode(),
                        g.getMember() == null ? null : g.getMember().getMemberNo(), g.getDescription(),
                        g.getDebit(), g.getCredit(),
                        g.getLedgerEntry() == null ? null : g.getLedgerEntry().getId(),
                        g.getJournal() == null ? null : g.getJournal().getId(),
                        balanceAfter.getOrDefault(g.getId(), BigDecimal.ZERO).abs(),
                        balanceAfter.getOrDefault(g.getId(), BigDecimal.ZERO).signum() < 0 ? "Cr" : "Dr"))
                .toList();
    }
}
