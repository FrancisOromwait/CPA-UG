package com.estates.sacco.web;

import com.estates.sacco.domain.Member;
import com.estates.sacco.security.BranchScope;
import com.estates.sacco.service.FinancialReportService;
import com.estates.sacco.service.LedgerService;
import com.estates.sacco.service.PdfReportService;
import com.estates.sacco.service.ReportDoc;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Financial reports as data ({@code /api/reports/{kind}}) or PDF ({@code /api/reports/{kind}/pdf}).
 * Kinds: trial-balance, income-expenditure, balance-sheet, member-statement, member-summary, cashbook-receipts, cashbook-payments.
 * A branch admin always gets their own branch; the manager picks a branch or leaves it empty for all branches.
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class FinancialReportController {
    private final FinancialReportService reports;
    private final PdfReportService pdf;
    private final LedgerService ledgerService;
    private final BranchScope scope;

    public record CashbookAccount(Long id, String code, String name, boolean active) {}

    /** The cash, bank and mobile money accounts the cashbook can be shown for (and, the active ones, receive money into). */
    @GetMapping("/cashbook-accounts")
    @Transactional(readOnly = true)
    List<CashbookAccount> cashbookAccounts() {
        return reports.cashbookAccounts().stream().map(a -> new CashbookAccount(a.getId(), a.getCode(), a.getName(), a.isActive())).toList();
    }

    @GetMapping("/{kind}")
    @Transactional(readOnly = true)
    ReportDoc view(@PathVariable String kind,
                   @RequestParam(required = false) Long branchId,
                   @RequestParam(required = false) Long memberId,
                   @RequestParam(required = false) Long accountId,
                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return build(kind, branchId, memberId, accountId, from, to);
    }

    @GetMapping("/{kind}/pdf")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> pdf(@PathVariable String kind,
                               @RequestParam(required = false) Long branchId,
                               @RequestParam(required = false) Long memberId,
                               @RequestParam(required = false) Long accountId,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ReportDoc doc = build(kind, branchId, memberId, accountId, from, to);
        byte[] bytes = pdf.render(doc, scope.me().getUsername());
        String name = kind + "-" + LocalDate.now() + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(name).build().toString())
                .body(bytes);
    }

    private ReportDoc build(String kind, Long branchId, Long memberId, Long accountId, LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        switch (kind) {
            case "trial-balance":
                return reports.trialBalance(scope.readBranch(branchId), end);
            case "income-expenditure":
                return reports.incomeExpenditure(scope.readBranch(branchId),
                        from == null ? LocalDate.of(end.getYear(), 1, 1) : from, end);
            case "balance-sheet":
                return reports.balanceSheet(scope.readBranch(branchId), end);
            case "member-statement":
                if (memberId == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a member");
                Member m = ledgerService.requireMember(memberId);
                return reports.memberStatement(m, from, end);
            case "member-summary":
                return reports.memberSummary(scope.readBranch(branchId), end);
            case "cashbook-receipts", "cashbook-payments":
                return reports.cashbook(scope.readBranch(branchId), accountId, from == null ? end.withDayOfMonth(1) : from, end,
                        kind.equals("cashbook-payments"));
            default:
                throw new ApiException(HttpStatus.NOT_FOUND, "Unknown report");
        }
    }
}
