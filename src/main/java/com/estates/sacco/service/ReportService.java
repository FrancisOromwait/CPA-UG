package com.estates.sacco.service;

import com.estates.sacco.domain.Branch;
import com.estates.sacco.domain.LoanStatus;
import com.estates.sacco.domain.Source;
import com.estates.sacco.domain.TxType;
import com.estates.sacco.repo.BranchRepository;
import com.estates.sacco.repo.LedgerRepository;
import com.estates.sacco.repo.LoanRepository;
import com.estates.sacco.repo.MemberRepository;
import com.estates.sacco.web.Dtos.BranchSummary;
import com.estates.sacco.web.Dtos.ConsolidatedReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReportService {
    private final BranchRepository branches;
    private final MemberRepository members;
    private final LedgerRepository ledger;
    private final LoanRepository loans;

    /** branchId null = every branch (manager only; the controller resolves this through BranchScope). */
    @Transactional(readOnly = true)
    public ConsolidatedReport summary(Long branchId) {
        List<Branch> scope = branchId == null
                ? branches.findAll()
                : List.of(branches.findById(branchId).orElseThrow());
        List<BranchSummary> rows = scope.stream().map(this::summarise).toList();

        BranchSummary totals = new BranchSummary(null, "ALL", "All branches",
                rows.stream().mapToLong(BranchSummary::members).sum(),
                sum(rows, BranchSummary::savings), sum(rows, BranchSummary::shares),
                sum(rows, BranchSummary::loansDisbursed), sum(rows, BranchSummary::loanRepayments),
                sum(rows, BranchSummary::loanOutstanding),
                rows.stream().mapToLong(BranchSummary::activeLoans).sum(),
                sum(rows, BranchSummary::payrollCollected));
        return new ConsolidatedReport(rows, totals);
    }

    private BranchSummary summarise(Branch b) {
        Map<TxType, BigDecimal> t = new EnumMap<>(TxType.class);
        for (Object[] row : ledger.totalsByType(b.getId())) {
            t.put((TxType) row[0], (BigDecimal) row[1]);
        }
        // interest paid that came over from the old system counts as repaid too
        BigDecimal repayments = t.getOrDefault(TxType.LOAN_REPAYMENT, BigDecimal.ZERO).add(t.getOrDefault(TxType.LOAN_INTEREST_PAID, BigDecimal.ZERO));
        BigDecimal payroll = ledger.sumBySource(b.getId(), Source.PAYROLL,
                List.of(TxType.SAVINGS_DEPOSIT, TxType.SHARE_PURCHASE, TxType.LOAN_REPAYMENT));

        return new BranchSummary(b.getId(), b.getCode(), b.getName(),
                members.countByBranchId(b.getId()),
                t.getOrDefault(TxType.SAVINGS_DEPOSIT, BigDecimal.ZERO).add(t.getOrDefault(TxType.SAVINGS_INTEREST_PAYOUT, BigDecimal.ZERO))
                        .subtract(t.getOrDefault(TxType.SAVINGS_WITHDRAWAL, BigDecimal.ZERO)),
                t.getOrDefault(TxType.SHARE_PURCHASE, BigDecimal.ZERO)
                        .subtract(t.getOrDefault(TxType.SHARE_REFUND, BigDecimal.ZERO)),
                t.getOrDefault(TxType.LOAN_DISBURSEMENT, BigDecimal.ZERO),
                repayments,
                t.getOrDefault(TxType.LOAN_DISBURSEMENT, BigDecimal.ZERO).add(t.getOrDefault(TxType.LOAN_INTEREST, BigDecimal.ZERO))
                        .subtract(repayments),
                loans.countByBranchIdAndStatus(b.getId(), LoanStatus.ACTIVE),
                payroll);
    }

    private static BigDecimal sum(List<BranchSummary> rows, java.util.function.Function<BranchSummary, BigDecimal> f) {
        return rows.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
