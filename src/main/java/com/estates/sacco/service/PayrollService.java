package com.estates.sacco.service;

import com.estates.sacco.domain.*;
import com.estates.sacco.repo.AccountRepository;
import com.estates.sacco.repo.BranchRepository;
import com.estates.sacco.repo.PayrollBatchRepository;
import com.estates.sacco.security.BranchScope;
import com.estates.sacco.web.ApiException;
import com.estates.sacco.web.Dtos.PayrollLine;
import com.estates.sacco.web.Dtos.PayrollReq;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** Posts an employer's monthly deduction list. All lines succeed or the whole batch is rolled back. */
@Service
@RequiredArgsConstructor
public class PayrollService {
    private final PayrollBatchRepository batches;
    private final BranchRepository branches;
    private final AccountRepository accounts;
    private final LedgerService ledgerService;
    private final BranchScope scope;

    @Transactional
    public PayrollBatch post(PayrollReq req) {
        Long branchId = scope.writeBranch();
        if (batches.existsByBranchIdAndEmployerIgnoreCaseAndPeriod(branchId, req.employer().trim(), req.period())) {
            throw new ApiException(HttpStatus.CONFLICT, "Payroll for this employer and period was already posted");
        }

        Account receiving = accounts.findById(req.receivingAccountId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Receiving account not found"));
        if (receiving.getType() != AccountType.ASSET || !receiving.isActive()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The receiving account must be an active asset account (cash, bank or mobile money)");
        }
        java.time.LocalDate date = req.date() == null ? java.time.LocalDate.now() : req.date();

        PayrollBatch batch = new PayrollBatch();
        batch.setBranch(branches.findById(branchId).orElseThrow());
        batch.setEmployer(req.employer().trim());
        batch.setPeriod(req.period());
        batch.setCreatedBy(scope.me().getUsername());
        batch.setPaymentDate(date);
        batch.setReceivingAccount(receiving);
        batches.save(batch);

        BigDecimal total = BigDecimal.ZERO;
        String ref = "PAYROLL " + req.period() + " " + batch.getEmployer();

        for (PayrollLine line : req.lines()) {
            Member m = ledgerService.requireMember(line.memberId());
            BigDecimal savings = nz(line.savings());
            BigDecimal shares = nz(line.shares());
            BigDecimal repay = nz(line.loanRepayment());
            if (savings.signum() + shares.signum() + repay.signum() == 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Line for member " + m.getMemberNo() + " has no amounts");
            }
            if (savings.signum() > 0) {
                ledgerService.post(m, TxType.SAVINGS_DEPOSIT, savings, Source.PAYROLL, date, ref, null, batch, receiving);
            }
            if (shares.signum() > 0) {
                ledgerService.post(m, TxType.SHARE_PURCHASE, shares, Source.PAYROLL, date, ref, null, batch, receiving);
            }
            if (repay.signum() > 0) {
                if (line.loanId() == null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "loanId required for loan repayment (member " + m.getMemberNo() + ")");
                }
                Loan loan = ledgerService.requireLoan(line.loanId());
                ledgerService.post(m, TxType.LOAN_REPAYMENT, repay, Source.PAYROLL, date, ref, loan, batch, receiving);
            }
            total = total.add(savings).add(shares).add(repay);
        }

        batch.setTotalAmount(total);
        batch.setLineCount(req.lines().size());
        return batches.save(batch);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
