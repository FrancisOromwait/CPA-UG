package com.estates.sacco.web;

import com.estates.sacco.domain.*;
import com.estates.sacco.repo.GeneralLedgerRepository;
import com.estates.sacco.repo.LedgerRepository;
import com.estates.sacco.repo.MemberLedgerRepository;
import com.estates.sacco.service.EntryAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** View, edit and delete one posted transaction, with the ledger lines it created. */
@RestController
@RequestMapping("/api/entries")
@RequiredArgsConstructor
public class EntryController {
    private final EntryAdminService admin;
    private final MemberLedgerRepository memberLedger;
    private final GeneralLedgerRepository generalLedger;
    private final LedgerRepository ledger;

    public record EntryEdit(@NotNull @Positive BigDecimal amount, LocalDate date, String reference, String notes,
                            Long receivingAccountId, Long productId, BigDecimal fee) {}

    public record MlView(LocalDate date, String branchCode, String productCode, String description, BigDecimal debit, BigDecimal credit) {}

    public record GlView(String branchCode, String accountCode, String accountName, String description, BigDecimal debit, BigDecimal credit) {}

    public record EntryView(Long id, String branchCode, Long memberId, String memberNo, String memberName, TxType type,
                            Long productId, String productCode, String productDetails, Long receivingAccountId,
                            String receivingAccount, Source source, String reference, String notes, BigDecimal amount,
                            BigDecimal fee, LocalDate date, Long loanId, Long payrollBatchId, String createdBy, String lockedReason, String depositGroup,
                            List<MlView> memberLedger, List<GlView> generalLedger) {}

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    EntryView view(@PathVariable Long id) {
        return toView(admin.requireEntry(id));
    }

    @PutMapping("/{id}")
    @Transactional
    EntryView edit(@PathVariable Long id, @Valid @RequestBody EntryEdit req) {
        LedgerEntry e = admin.edit(id, req.amount(), req.date(), req.reference(), req.notes(), req.receivingAccountId(), req.productId(), req.fee());
        return toView(e);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable Long id) {
        admin.delete(id);
    }

    private EntryView toView(LedgerEntry e) {
        ProductDef p = e.getProduct();
        Account a = e.getReceivingAccount();
        // the savings interest this entry booked shows with it, as one journal
        List<Long> own = e.getDepositGroup() != null ? ledger.findByDepositGroupOrderByIdAsc(e.getDepositGroup()).stream().map(LedgerEntry::getId).toList()
                : List.of(e.getId());
        List<LedgerEntry> interest = ledger.findByInterestOfIdInOrderByIdAsc(own);
        List<MemberLedgerLine> ml = new ArrayList<>(e.getDepositGroup() != null ? memberLedger.findByLedgerEntry_DepositGroupOrderByIdAsc(e.getDepositGroup())
                : memberLedger.findByLedgerEntryIdOrderByIdAsc(e.getId()));
        List<GeneralLedgerLine> gl = new ArrayList<>(e.getDepositGroup() != null ? generalLedger.findByLedgerEntry_DepositGroupOrderByIdAsc(e.getDepositGroup())
                : generalLedger.findByLedgerEntryIdOrderByIdAsc(e.getId()));
        for (LedgerEntry i : interest) {
            ml.addAll(memberLedger.findByLedgerEntryIdOrderByIdAsc(i.getId()));
            gl.addAll(generalLedger.findByLedgerEntryIdOrderByIdAsc(i.getId()));
        }
        gl.sort(java.util.Comparator.comparing((GeneralLedgerLine g) -> g.getDebit().signum() > 0 ? 0 : 1));   // debits first, as a journal reads
        return new EntryView(e.getId(), e.getBranch().getCode(), e.getMember().getId(), e.getMember().getMemberNo(),
                e.getMember().getFullName(), e.getType(), p == null ? null : p.getId(), p == null ? null : p.getProductCode(),
                p == null ? null : p.getName(), a == null ? null : a.getId(), a == null ? null : a.getCode() + " " + a.getName(),
                e.getSource(), e.getReference(), e.getNotes(), e.getAmount(), e.getFee(), e.getPostedOn(),
                e.getLoan() == null ? null : e.getLoan().getId(), e.getPayrollBatch() == null ? null : e.getPayrollBatch().getId(),
                e.getCreatedBy(), admin.lockedReason(e), e.getDepositGroup(),
                ml.stream()
                        .map(m -> new MlView(m.getPostedOn(), m.getBranchCode(), m.getProductCode(), m.getDescription(), m.getDebit(), m.getCredit())).toList(),
                gl.stream()
                        .map(g -> new GlView(g.getBranchCode(), g.getAccount().getCode(), g.getAccount().getName(), g.getDescription(), g.getDebit(), g.getCredit())).toList());
    }
}
