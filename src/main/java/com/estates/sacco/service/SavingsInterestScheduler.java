package com.estates.sacco.service;

import com.estates.sacco.web.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Books the savings interest of every ended month by itself: early on the 1st of each month, and once when the app starts
 * (in case it was off on the 1st). A month is booked only once, so running it again is harmless.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SavingsInterestScheduler {
    private final SavingsInterestService service;

    @Scheduled(cron = "0 30 1 1 * *")
    public void monthly() {
        bookDueMonths();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void atStart() {
        bookDueMonths();
    }

    public int bookDueMonths() {
        int done = 0;
        for (SavingsInterestService.Due d : service.due()) {
            try {
                service.post(d.branchId(), d.month(), null);
                done++;
                log.info("Savings interest for {} {} booked", d.branchCode(), d.month());
            } catch (ApiException e) {
                log.info("Savings interest for {} {} was not booked: {}", d.branchCode(), d.month(), e.getMessage());
            } catch (RuntimeException e) {
                log.error("Savings interest for {} {} failed", d.branchCode(), d.month(), e);
            }
        }
        return done;
    }
}
