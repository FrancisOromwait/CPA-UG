package com.estates.sacco.service;

import com.estates.sacco.domain.LedgerEntry;
import com.estates.sacco.domain.Loan;
import com.estates.sacco.repo.GeneralLedgerRepository;
import com.estates.sacco.repo.MemberLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

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
    private final LoanAccrualService loanAccrual;

    @Transactional
    public void repostRepayments(Loan loan) {
        if (loanAccrual.enabled()) {
            // interest booked as the loan moves: each repayment pays the interest owed before it, so a change earlier on can move
            // the split of the later ones; the principal they now pay more or less changes the interest of the days already booked
            for (LedgerEntry e : schedules.repayments(loan)) {
                BigDecimal posted = loanAccrual.postedInterestOf(e);
                BigDecimal interest = loanAccrual.interestOwedBefore(e).min(e.getAmount());
                BigDecimal principal = e.getAmount().subtract(interest).min(loanAccrual.principalLeftBefore(e));
                if (e.getAmount().subtract(principal).compareTo(posted) == 0) continue;
                BigDecimal principalBefore = e.getAmount().subtract(posted);
                generalLedger.deleteByLedgerEntryId(e.getId());
                memberLedger.deleteByLedgerEntryId(e.getId());
                generalLedger.flush();
                posting.recordLines(e);
                posting.loanChanged(List.of(new LoanAccrualService.Change(loan, e.getPostedOn(), principalBefore.subtract(principal), e.getCreatedBy(), null)));
            }
            return;
        }
        for (LedgerEntry e : schedules.repayments(loan)) {
            if (schedules.interestPortionOf(loan, e).min(e.getAmount()).compareTo(posting.postedInterestPortion(e)) == 0) continue;
            generalLedger.deleteByLedgerEntryId(e.getId());
            memberLedger.deleteByLedgerEntryId(e.getId());
            generalLedger.flush();
            posting.record(e);
        }
    }
}
