package com.estates.sacco.service;

import com.estates.sacco.domain.LedgerEntry;
import com.estates.sacco.domain.Loan;
import com.estates.sacco.repo.GeneralLedgerRepository;
import com.estates.sacco.repo.MemberLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the ledger lines of a loan's repayments in step with the interest that has fallen due.
 * A repayment pays interest first, so when a repayment is changed, or an interest run is posted or deleted,
 * the interest/principal split of the loan's repayments can change; this re-posts only the ones that differ.
 */
@Service
@RequiredArgsConstructor
public class LoanAccountingService {
    private final LoanScheduleService schedules;
    private final PostingService posting;
    private final GeneralLedgerRepository generalLedger;
    private final MemberLedgerRepository memberLedger;

    @Transactional
    public void repostRepayments(Loan loan) {
        for (LedgerEntry e : schedules.repayments(loan)) {
            if (schedules.interestPortionOf(loan, e).min(e.getAmount()).compareTo(posting.postedInterestPortion(e)) == 0) continue;
            generalLedger.deleteByLedgerEntryId(e.getId());
            memberLedger.deleteByLedgerEntryId(e.getId());
            generalLedger.flush();
            posting.record(e);
        }
    }
}
