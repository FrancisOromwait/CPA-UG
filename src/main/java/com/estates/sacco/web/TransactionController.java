package com.estates.sacco.web;

import com.estates.sacco.domain.LedgerEntry;
import com.estates.sacco.domain.Source;
import com.estates.sacco.domain.TxType;
import com.estates.sacco.repo.LedgerRepository;
import com.estates.sacco.security.BranchScope;
import com.estates.sacco.service.LedgerService;
import com.estates.sacco.web.Dtos.EntryDto;
import com.estates.sacco.web.Dtos.TxReq;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Savings and share transactions. Loan movements go through LoanController. */
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {
    private static final Set<TxType> ALLOWED = EnumSet.of(TxType.SAVINGS_DEPOSIT, TxType.SAVINGS_WITHDRAWAL,
            TxType.SHARE_PURCHASE, TxType.SHARE_REFUND);

    private final LedgerRepository ledger;
    private final LedgerService ledgerService;
    private final BranchScope scope;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    EntryDto record(@Valid @RequestBody TxReq req) {
        if (!ALLOWED.contains(req.type())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Use the loan endpoints for loan transactions");
        }
        if (req.source() == Source.PAYROLL) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Payroll deductions are posted through /api/payroll");
        }
        var member = ledgerService.requireMember(req.memberId());
        LedgerEntry e = ledgerService.post(member, req.type(), req.amount(), req.source(), req.date(),
                req.reference(), null, null);
        return EntryDto.of(e);
    }

    @GetMapping
    @Transactional(readOnly = true)
    List<EntryDto> list(@RequestParam(required = false) Long branchId,
                        @RequestParam(required = false) Long memberId,
                        @RequestParam(required = false) TxType type,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @RequestParam(defaultValue = "200") int limit) {
        if (memberId != null) ledgerService.requireMember(memberId);
        return ledger.search(scope.readBranch(branchId), memberId, type,
                        from == null ? LocalDate.of(2000, 1, 1) : from,
                        to == null ? LocalDate.of(2999, 12, 31) : to,
                        PageRequest.of(0, Math.max(1, Math.min(limit, 1000))))
                .stream().map(EntryDto::of).toList();
    }
}
