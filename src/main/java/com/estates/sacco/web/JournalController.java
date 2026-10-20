package com.estates.sacco.web;

import com.estates.sacco.domain.GeneralLedgerLine;
import com.estates.sacco.domain.JournalEntry;
import com.estates.sacco.repo.GeneralLedgerRepository;
import com.estates.sacco.repo.JournalEntryRepository;
import com.estates.sacco.security.BranchScope;
import com.estates.sacco.service.JournalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Manual multi-line journal entries. Branch admins post them; the manager can view every branch's. */
@RestController
@RequestMapping("/api/journals")
@RequiredArgsConstructor
public class JournalController {
    private final JournalService service;
    private final JournalEntryRepository journals;
    private final GeneralLedgerRepository generalLedger;
    private final com.estates.sacco.repo.MemberLedgerRepository memberLedger;
    private final BranchScope scope;

    public record LineReq(Long accountId, String description, BigDecimal debit, BigDecimal credit) {}

    public record JournalReq(LocalDate date, String reference, String narration, @NotEmpty @Valid List<LineReq> lines) {}

    public record LineView(Long accountId, String accountCode, String accountName, String description,
                           BigDecimal debit, BigDecimal credit) {}

    /** A line of a member's account (loan, savings, shares...) that this journal moved. */
    public record MemberLedgerView(String memberNo, String memberName, String productCode, String productName, String category,
                                   String description, BigDecimal debit, BigDecimal credit) {}

    public record JournalView(Long id, String journalNo, LocalDate date, String branchCode, String reference,
                              String narration, BigDecimal totalAmount, String createdBy, List<LineView> lines,
                              List<MemberLedgerView> memberLedger) {}

    public record JournalSummary(Long id, String journalNo, LocalDate date, String branchCode, String reference,
                                 String narration, BigDecimal totalAmount, String createdBy) {}

    @GetMapping
    @Transactional(readOnly = true)
    List<JournalSummary> list(@RequestParam(required = false) Long branchId,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                              @RequestParam(defaultValue = "500") int limit) {
        // newest first, and only the latest ones: the books hold tens of thousands of journals
        return journals.search(scope.readBranch(branchId), from == null ? LocalDate.of(2000, 1, 1) : from,
                        to == null ? LocalDate.of(2999, 12, 31) : to,
                        org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limit, 5000)))).stream()
                .map(j -> new JournalSummary(j.getId(), j.getJournalNo(), j.getPostedOn(), j.getBranch().getCode(),
                        j.getReference(), j.getNarration(), j.getTotalAmount(), j.getCreatedBy())).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    JournalView view(@PathVariable Long id) {
        return toView(service.load(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    JournalView create(@Valid @RequestBody JournalReq req) {
        return toView(service.create(req.date(), req.reference(), req.narration(), lines(req)));
    }

    @PutMapping("/{id}")
    @Transactional
    JournalView update(@PathVariable Long id, @Valid @RequestBody JournalReq req) {
        return toView(service.update(id, req.date(), req.reference(), req.narration(), lines(req)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable Long id) {
        service.delete(id);
    }

    /** Account ids a journal cannot use in the caller's branch (controlled by member transactions). */
    @GetMapping("/blocked-accounts")
    @Transactional(readOnly = true)
    java.util.Set<Long> blocked() {
        return service.controlledAccountIds(scope.writeBranch());
    }

    private static List<JournalService.Line> lines(JournalReq req) {
        return req.lines().stream().map(l -> new JournalService.Line(l.accountId(), l.description(), l.debit(), l.credit())).toList();
    }

    private JournalView toView(JournalEntry j) {
        List<GeneralLedgerLine> glLines = generalLedger.findByJournalIdOrderByIdAsc(j.getId());
        List<LineView> lines = glLines.stream().map(this::line).toList();
        return new JournalView(j.getId(), j.getJournalNo(), j.getPostedOn(), j.getBranch().getCode(), j.getReference(),
                j.getNarration(), j.getTotalAmount(), j.getCreatedBy(), lines, memberLines(j, glLines));
    }

    /**
     * The member accounts the journal moved. The journal's lines name the member; of that member's lines on the journal's date, the
     * ones that sit on a product account of the journal for the same amount are the member's side of it.
     */
    private List<MemberLedgerView> memberLines(JournalEntry j, List<GeneralLedgerLine> glLines) {
        java.util.Set<com.estates.sacco.domain.Member> owners = new java.util.LinkedHashSet<>();
        for (GeneralLedgerLine g : glLines) if (g.getMember() != null) owners.add(g.getMember());
        List<MemberLedgerView> out = new java.util.ArrayList<>();
        for (com.estates.sacco.domain.Member m : owners) {
            for (com.estates.sacco.domain.MemberLedgerLine l : memberLedger.findByMemberIdAndPostedOnOrderByIdAsc(m.getId(), j.getPostedOn())) {
                Long account = l.getProduct().getGlAccount().getId();
                boolean moved = glLines.stream().anyMatch(g -> g.getAccount().getId().equals(account)
                        && ((l.getDebit().signum() > 0 && g.getDebit().compareTo(l.getDebit()) == 0)
                        || (l.getCredit().signum() > 0 && g.getCredit().compareTo(l.getCredit()) == 0)));
                if (moved) {
                    out.add(new MemberLedgerView(m.getMemberNo(), m.getFullName(), l.getProductCode(), l.getProduct().getName(),
                            l.getCategory().name(), l.getDescription(), l.getDebit(), l.getCredit()));
                }
            }
        }
        return out;
    }

    private LineView line(GeneralLedgerLine g) {
        return new LineView(g.getAccount().getId(), g.getAccount().getCode(), g.getAccount().getName(), g.getDescription(),
                g.getDebit(), g.getCredit());
    }
}
