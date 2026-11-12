package com.estates.sacco;

import com.estates.sacco.repo.LoanRepository;
import com.estates.sacco.service.InterestRunService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Loan interest booked as the loan moves, as the old system did: 15% a year on every loan, day by day on the principal owed. Each
 * repayment pays the interest owed first and books the days since the last booking; month end books the rest. One day of
 * 1,000,000 at 15% is 410.96.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:loanaccrual;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "sacco.single-branch.enabled=true",
        "sacco.single-branch.code=CPA001",
        "sacco.single-branch.name=CPA Uganda",
        "sacco.single-branch.admin-username=admin",
        "sacco.single-branch.admin-password=admin-pass-1",
        "sacco.bootstrap.manager.username=support",
        "sacco.bootstrap.manager.password=support-pass-1",
        "sacco.loan-fees.application=0",
        "sacco.loan-interest.accrual-rate=15",
        "sacco.loan-interest.accrual-from=2025-03"})
@AutoConfigureMockMvc
class LoanAccrualTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired LoanRepository loans;
    @Autowired InterestRunService interestRuns;

    JsonNode send(String method, String url, String body, int expected) throws Exception {
        var req = "POST".equals(method) ? post(url) : get(url);
        req.with(httpBasic("admin", "admin-pass-1"));
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        String res = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return res.isBlank() ? null : json.readTree(res);
    }

    long repay(long loan, long amount, String date) throws Exception {
        return send("POST", "/api/loans/" + loan + "/repayments", "{\"amount\":" + amount + ",\"date\":\"" + date + "\",\"source\":\"CASH\"}", 201)
                .get("id").asLong();
    }

    /** Debit less credit on a chart account. */
    BigDecimal net(String code) throws Exception {
        BigDecimal n = BigDecimal.ZERO;
        for (JsonNode g : send("GET", "/api/general-ledger?accountCode=" + code, null, 200)) {
            n = n.add(g.get("debit").decimalValue()).subtract(g.get("credit").decimalValue());
        }
        return n;
    }

    static void assertMoney(String what, long expected, BigDecimal actual) {
        assertEquals(0, BigDecimal.valueOf(expected).compareTo(actual), what + ": expected " + expected + " but was " + actual);
    }

    @Test
    void interestIsBookedAtEveryRepaymentAt15Percent() throws Exception {
        long member = send("POST", "/api/members", "{\"memberNo\":\"L7\",\"fullName\":\"Borrower Seven\"}", 201).get("id").asLong();
        // the loan record says 12%: interest is still 15%, the one rate for every loan
        long loan = send("POST", "/api/loans", "{\"memberId\":" + member + ",\"principal\":1000000,\"annualRatePercent\":12,\"termMonths\":10,"
                + "\"applicationFee\":0,\"processFee\":0,\"insuranceFee\":0,\"legalCharges\":0,\"loanDate\":\"2025-03-01\"}", 201).get("id").asLong();
        send("POST", "/api/loans/" + loan + "/disburse", "{\"source\":\"CASH\",\"date\":\"2025-03-01\"}", 200);
        assertMoney("no interest at the payout", 0, net("4100"));

        // 200,000 on the 11th: the 2nd to the 11th = 10 days on 1,000,000 = 4,109.59 -> 4,110, in the repayment's own journal
        long eleventh = repay(loan, 200_000, "2025-03-11");
        assertMoney("interest to the 11th", -4_110, net("4100"));
        JsonNode journal = send("GET", "/api/entries/" + eleventh, null, 200);
        assertEquals(4, journal.get("generalLedger").size());
        assertMoney("nothing was owed before it, so all of it was principal", 800_000, net("1400"));

        // 100,000 on the 21st pays the 4,110 owed first, then 95,890 principal; it books 12th-21st = 10 days on 800,000 = 3,288
        repay(loan, 100_000, "2025-03-21");
        assertMoney("interest to the 21st", -4_110 - 3_288, net("4100"));
        assertMoney("principal left", 704_110, net("1400"));

        // the month end books 22nd-31st = 10 days on 704,110 = 2,893.6 -> 2,894
        JsonNode run = send("POST", "/api/interest-runs", "{\"month\":\"2025-03\"}", 201);
        assertMoney("month end", 2_894, run.get("run").get("totalInterest").decimalValue());
        assertMoney("March", -4_110 - 3_288 - 2_894, net("4100"));
        send("POST", "/api/interest-runs", "{\"month\":\"2025-03\"}", 409);

        // a repayment typed in late, dated the 15th: it pays the 4,110 owed then and 45,890 principal, so 16 days of interest on that
        // principal (302) are taken back; the one on the 21st now pays only principal, 4,110 more, so 10 days on it (17) go too
        repay(loan, 50_000, "2025-03-15");
        assertMoney("March after the late repayment", -4_110 - 3_288 - 2_894 + 302 + 17, net("4100"));
        assertMoney("principal left after it", 654_110, net("1400"));
        assertMoney("interest still owed", 4_110 + 3_288 + 2_894 - 302 - 17 - 4_110, net("1500"));

        // worked out again from the start (as when loans moved onto this way of booking): the repayments in date order, then the month end
        var l = loans.findById(loan).orElseThrow();
        l.setLastInterestDate(null);
        loans.save(l);
        assertEquals(true, interestRuns.rebuildIfNeeded());
        assertEquals(false, interestRuns.rebuildIfNeeded());
        // 11th: 4,110; 15th pays it and books 12th-15th on 800,000 = 1,315; 21st pays that and books 16th-21st on 754,110 = 1,859
        assertMoney("rebuilt to the 21st", -4_110 - 1_315 - 1_859, net("4100"));
        send("POST", "/api/interest-runs", "{\"month\":\"2025-03\"}", 201);
        // 22nd-31st on 655,425 = 2,693.5 -> 2,694 (the 21st paid 1,315 interest and 98,685 principal)
        assertMoney("rebuilt March", -4_110 - 1_315 - 1_859 - 2_694, net("4100"));
        assertMoney("principal left, rebuilt", 1_000_000 - 200_000 - 45_890 - 98_685, net("1400"));
    }
}
