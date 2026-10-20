package com.estates.sacco;

import com.estates.sacco.service.SavingsInterestService;
import com.estates.sacco.service.SavingsInterestService.Move;
import com.estates.sacco.service.SavingsInterestService.Result;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The daily savings interest: yearly rate / 365 on the balance of each day, money counting from the day after it moves. */
class SavingsInterestCalcTest {
    private static final BigDecimal RATE = new BigDecimal("8");

    private static Move move(String date, long amount) {
        return new Move(LocalDate.parse(date), BigDecimal.valueOf(amount));
    }

    private static long interest(List<Move> moves, String month) {
        return SavingsInterestService.interestFor(moves, YearMonth.parse(month), RATE, 365).interest().longValueExact();
    }

    @Test
    void aBalanceThatStaysTheSameEarnsEveryDay() {
        // 1,000,000 for the 30 days of April: 1,000,000 x 8% / 365 x 30 = 6,575.34
        assertEquals(6575, interest(List.of(move("2014-01-10", 1_000_000)), "2014-04"));
    }

    @Test
    void aDepositEarnsFromTheDayAfterItIsMade() {
        // 150,000 all month and 200,000 more on the 13th of March (31 days): 13 days on 150,000, the other 18 on 350,000
        List<Move> moves = List.of(move("2014-02-01", 150_000), move("2014-03-13", 200_000));
        double expected = (150_000 * 13 + 350_000 * 18) * 0.08 / 365;           // 1,808.22
        assertEquals(Math.round(expected), interest(moves, "2014-03"));
        assertEquals(1808, interest(moves, "2014-03"));
    }

    @Test
    void aWithdrawalStopsEarningFromTheDayAfter() {
        // 1,000,000, of which 400,000 is taken out on the 10th of a 30-day month: 10 days on 1,000,000, 20 on 600,000
        List<Move> moves = List.of(move("2026-01-05", 1_000_000), move("2026-11-10", -400_000));
        double expected = (1_000_000 * 10 + 600_000 * 20) * 0.08 / 365;          // 4,822.19
        assertEquals(Math.round(expected), interest(moves, "2026-11"));
    }

    @Test
    void moneyPaidInDuringTheMonthEarnsNothingOnTheLastDay() {
        // paid in on the last day of the month: no day of that month is earned on it
        assertEquals(0, interest(List.of(move("2026-10-31", 5_000_000)), "2026-10"));
    }

    @Test
    void noBalanceOrANegativeOneEarnsNothing() {
        assertEquals(0, interest(List.of(), "2026-10"));
        assertEquals(0, interest(List.of(move("2026-01-01", 100_000), move("2026-02-01", -150_000)), "2026-10"));
    }

    @Test
    void reportsTheOpeningAndTheAverageBalance() {
        Result r = SavingsInterestService.interestFor(List.of(move("2026-09-01", 300_000), move("2026-10-16", 100_000)),
                YearMonth.parse("2026-10"), RATE, 365);
        assertEquals(0, new BigDecimal("300000").compareTo(r.openingBalance()));
        // 16 days on 300,000 and 15 days on 400,000 in a 31-day month
        assertEquals(new BigDecimal("348387.10"), r.averageBalance());
    }
}
