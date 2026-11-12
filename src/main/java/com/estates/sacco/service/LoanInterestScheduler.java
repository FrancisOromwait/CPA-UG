package com.estates.sacco.service;

import com.estates.sacco.web.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Loan interest booked as the loans move (LoanAccrualService): the month end books itself once the month has ended, as savings
 * interest does. At start-up the loans are first brought onto this way of booking, once (InterestRunService.rebuildIfNeeded).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LoanInterestScheduler {
    private final InterestRunService service;

    @Scheduled(cron = "0 45 1 1 * *")
    public void monthly() {
        bookDueMonths();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void atStart() {
        try {
            service.rebuildIfNeeded();
        } catch (RuntimeException e) {
            log.error("Loan interest could not be worked out again as the loans moved", e);
        }
        bookDueMonths();
    }

    public int bookDueMonths() {
        int done = 0;
        for (InterestRunService.Due d : service.due()) {
            try {
                service.postMonthEnd(d.branchId(), d.month(), null);
                done++;
                log.info("Loan interest month end for {} {} booked", d.branchCode(), d.month());
            } catch (ApiException e) {
                // no loan out that month, or one before it not booked: the later months say so too
                log.info("Loan interest month end for {} {} was not booked: {}", d.branchCode(), d.month(), e.getMessage());
            } catch (RuntimeException e) {
                log.error("Loan interest month end for {} {} failed", d.branchCode(), d.month(), e);
                break;
            }
        }
        return done;
    }
}
