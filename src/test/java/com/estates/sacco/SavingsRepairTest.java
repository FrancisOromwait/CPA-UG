package com.estates.sacco;

import com.estates.sacco.domain.Member;
import com.estates.sacco.repo.MemberRepository;
import com.estates.sacco.service.PostingService;
import com.estates.sacco.service.SavingsAccrualService;
import com.estates.sacco.service.SavingsInterestService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A payout made in the middle of a month (3 November 2026) booked every member's interest up to its day and moved their last savings
 * date there, so the deposits after it carried fewer days. The repair takes that interest out and works the month out again from
 * its deposits, so each one carries all its days since the month before.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:repair;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "sacco.single-branch.enabled=true",
        "sacco.single-branch.code=CPA001",
        "sacco.single-branch.name=CPA Uganda",
        "sacco.single-branch.admin-username=admin",
        "sacco.single-branch.admin-password=admin-pass-1",
        "sacco.bootstrap.manager.username=support",
        "sacco.bootstrap.manager.password=support-pass-1",
        "sacco.accounts.savings-interest-payable=2300",
        "sacco.savings-interest.rate=8",
        "sacco.savings-interest.day-basis=365",
        "sacco.savings-interest.first-month=2025-03"})
@AutoConfigureMockMvc
class SavingsRepairTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MemberRepository members;
    @Autowired PostingService posting;
    @Autowired SavingsAccrualService accrual;
    @Autowired SavingsInterestService savingsInterest;

    JsonNode send(String method, String url, String body, int expected) throws Exception {
        var req = "POST".equals(method) ? post(url) : get(url);
        req.with(httpBasic("admin", "admin-pass-1"));
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        String res = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return res.isBlank() ? null : json.readTree(res);
    }

    long deposit(long member, long amount, String date) throws Exception {
        return send("POST", "/api/transactions", "{\"memberId\":" + member + ",\"type\":\"SAVINGS_DEPOSIT\",\"amount\":" + amount
                + ",\"source\":\"CASH\",\"date\":\"" + date + "\"}", 201).get("id").asLong();
    }

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
    void interestBookedByAMidMonthPayoutGoesBackToTheDeposits() throws Exception {
        long id = send("POST", "/api/members", "{\"memberNo\":\"S3\",\"fullName\":\"Saver Three\"}", 201).get("id").asLong();
        deposit(id, 1_000_000, "2025-03-01");

        // what the payout of the 10th left behind: 2nd to 10th = 9 days on 1,000,000 = 1,973, not tied to a deposit, last savings date the 10th
        Member m = members.findById(id).orElseThrow();
        accrual.accrue(m, m.getBranch(), LocalDate.parse("2025-03-01"), LocalDate.parse("2025-03-10"), null, "admin").ifPresent(posting::recordLines);
        m.setLastSavingsDate(LocalDate.parse("2025-03-10"));
        members.save(m);

        // the deposit on the 13th then carried only 13 - 10 = 3 days = 658
        long thirteenth = deposit(id, 500_000, "2025-03-13");
        assertMoney("the deposit's 3 days", 658, send("GET", "/api/entries/" + thirteenth, null, 200).get("generalLedger").get(1).get("debit").decimalValue());
        assertMoney("1,973 + 658", 2_631, net("5400"));

        // repaired: the deposit carries all 12 days since the 1st = 2,630, and nothing else is booked for March yet
        assertEquals(1, savingsInterest.repairPayoutsOffMonthEnd());
        JsonNode journal = send("GET", "/api/entries/" + thirteenth, null, 200);
        assertEquals(4, journal.get("generalLedger").size());
        assertMoney("the deposit's 12 days", 2_630, journal.get("generalLedger").get(1).get("debit").decimalValue());
        assertMoney("March so far", 2_630, net("5400"));
        assertEquals(LocalDate.parse("2025-03-13"), members.findById(id).orElseThrow().getLastSavingsDate());

        // nothing more to repair; the month end books the rest as usual: 31 - 13 = 18 days on 1,500,000 = 5,918
        assertEquals(0, savingsInterest.repairPayoutsOffMonthEnd());
        send("POST", "/api/savings-interest/runs", "{\"month\":\"2025-03\"}", 201);
        assertMoney("March", 2_630 + 5_918, net("5400"));
    }
}
