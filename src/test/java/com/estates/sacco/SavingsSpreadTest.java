package com.estates.sacco;

import com.estates.sacco.domain.LedgerEntry;
import com.estates.sacco.domain.Source;
import com.estates.sacco.domain.TxType;
import com.estates.sacco.repo.BranchRepository;
import com.estates.sacco.repo.LedgerRepository;
import com.estates.sacco.repo.MemberRepository;
import com.estates.sacco.service.PostingService;
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
 * Deposits made before each one carried its own interest (October 2026): the month end booked the whole month in one amount. Spreading
 * splits it so each deposit carries the days before it and the month end only the days after the last one, with the same total.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:spread;MODE=MySQL;DB_CLOSE_DELAY=-1",
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
class SavingsSpreadTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired LedgerRepository ledger;
    @Autowired MemberRepository members;
    @Autowired BranchRepository branches;
    @Autowired PostingService posting;
    @Autowired SavingsInterestService savingsInterest;

    JsonNode send(String method, String url, String body, int expected) throws Exception {
        var req = "POST".equals(method) ? post(url) : get(url);
        req.with(httpBasic("admin", "admin-pass-1"));
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        String res = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return res.isBlank() ? null : json.readTree(res);
    }

    /** A deposit posted the way the system did before each deposit booked its interest: ledger lines only. */
    long oldStyleDeposit(long member, long amount, String date) {
        LedgerEntry e = new LedgerEntry();
        e.setMember(members.findById(member).orElseThrow());
        e.setBranch(e.getMember().getBranch());
        e.setType(TxType.SAVINGS_DEPOSIT);
        e.setSource(Source.CASH);
        e.setAmount(BigDecimal.valueOf(amount));
        e.setPostedOn(LocalDate.parse(date));
        e.setCreatedBy("admin");
        ledger.save(e);
        posting.recordLines(e);
        return e.getId();
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
    void theMonthEndIsSplitOverTheDeposits() throws Exception {
        long member = send("POST", "/api/members", "{\"memberNo\":\"S2\",\"fullName\":\"Saver Two\"}", 201).get("id").asLong();
        oldStyleDeposit(member, 1_000_000, "2025-03-01");
        long thirteenth = oldStyleDeposit(member, 500_000, "2025-03-13");
        oldStyleDeposit(member, 200_000, "2025-03-20");

        // the month end books the whole month at once: 12 days on 1,000,000 + 7 on 1,500,000 + 11 on 1,700,000 = 9,030
        send("POST", "/api/savings-interest/runs", "{\"month\":\"2025-03\"}", 201);
        assertMoney("March in one amount", 9_030, net("5400"));
        assertEquals(2, send("GET", "/api/entries/" + thirteenth, null, 200).get("generalLedger").size());

        // spread: 2,630 with the 13th, 2,301 with the 20th, 4,099 left for the month end; the same 9,030
        assertEquals(1, savingsInterest.spreadOverSavingsEntries());
        assertMoney("March after the spread", 9_030, net("5400"));
        JsonNode journal = send("GET", "/api/entries/" + thirteenth, null, 200);
        assertEquals(4, journal.get("generalLedger").size());
        assertEquals("5400", journal.get("generalLedger").get(1).get("accountCode").asText());
        assertMoney("interest with the 13th", 2_630, journal.get("generalLedger").get(1).get("debit").decimalValue());
        JsonNode run = send("GET", "/api/savings-interest/runs", null, 200).get(0);
        assertMoney("the month end keeps the days after the 20th", 4_099, run.get("totalAmount").decimalValue());

        // once split there is nothing more to do
        assertEquals(0, savingsInterest.spreadOverSavingsEntries());
        assertMoney("still 9,030", 9_030, net("5400"));
    }
}
