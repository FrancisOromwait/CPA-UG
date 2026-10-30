package com.estates.sacco;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Savings interest booked at every savings entry: the days since the member's last savings date, on the balance before the entry,
 * and the month end books the rest. 8% a year over 365 days; one day of 365,000 earns exactly 80.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:accrual;MODE=MySQL;DB_CLOSE_DELAY=-1",
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
class SavingsAccrualTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    JsonNode send(String method, String url, String body, int expected) throws Exception {
        var req = switch (method) {
            case "POST" -> post(url);
            case "PUT" -> put(url);
            case "DELETE" -> delete(url);
            default -> get(url);
        };
        req.with(httpBasic("admin", "admin-pass-1"));
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        String res = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return res.isBlank() ? null : json.readTree(res);
    }

    long deposit(long member, String type, long amount, String date) throws Exception {
        return send("POST", "/api/transactions", "{\"memberId\":" + member + ",\"type\":\"" + type + "\",\"amount\":" + amount
                + ",\"source\":\"CASH\",\"date\":\"" + date + "\"}", 201).get("id").asLong();
    }

    /** Debit less credit on a chart account. */
    BigDecimal net(String code) throws Exception {
        BigDecimal n = BigDecimal.ZERO;
        for (JsonNode g : send("GET", "/api/general-ledger?accountCode=" + code, null, 200)) {
            n = n.add(g.get("debit").decimalValue()).subtract(g.get("credit").decimalValue());
        }
        return n;
    }

    String lastSavingsDate(long member) throws Exception {
        for (JsonNode m : send("GET", "/api/members", null, 200)) {
            if (m.get("id").asLong() == member) return m.get("lastSavingsDate").isNull() ? null : m.get("lastSavingsDate").asText();
        }
        throw new AssertionError("member not listed");
    }

    static void assertMoney(String what, long expected, BigDecimal actual) {
        assertEquals(0, BigDecimal.valueOf(expected).compareTo(actual), what + ": expected " + expected + " but was " + actual);
    }

    @Test
    void interestIsBookedAtEverySavingsEntry() throws Exception {
        long member = send("POST", "/api/members", "{\"memberNo\":\"S1\",\"fullName\":\"Saver One\"}", 201).get("id").asLong();
        assertEquals(null, lastSavingsDate(member));

        // 1,000,000 on 1 March: nothing was saved before it, so nothing is earned yet
        deposit(member, "SAVINGS_DEPOSIT", 1_000_000, "2025-03-01");
        assertEquals("2025-03-01", lastSavingsDate(member));
        assertMoney("no interest yet", 0, net("5400"));

        // 500,000 on the 13th: 2nd to 13th = 12 days on 1,000,000 = 2,630.14 -> 2,630
        deposit(member, "SAVINGS_DEPOSIT", 500_000, "2025-03-13");
        assertEquals("2025-03-13", lastSavingsDate(member));
        assertMoney("interest to the 13th", 2_630, net("5400"));

        // 200,000 on the 20th: 20 - 13 = 7 days on 1,500,000 = 2,301.37 -> 2,301
        deposit(member, "SAVINGS_DEPOSIT", 200_000, "2025-03-20");
        assertMoney("interest to the 20th", 2_630 + 2_301, net("5400"));

        // the month: 9,030 earned, 4,931 booked at the deposits, the month end books 31 - 20 = 11 days on 1,700,000 = 4,098.63 -> 4,099
        JsonNode p = send("GET", "/api/savings-interest/preview?month=2025-03", null, 200);
        JsonNode line = p.get("lines").get(0);
        assertMoney("the month's interest", 9_030, line.get("interest").decimalValue());
        assertMoney("booked at deposits", 4_931, line.get("booked").decimalValue());
        assertMoney("left for the month end", 4_099, line.get("toBook").decimalValue());

        send("POST", "/api/savings-interest/runs", "{\"month\":\"2025-03\"}", 201);
        assertEquals("2025-03-31", lastSavingsDate(member));
        assertMoney("expense for March", 9_030, net("5400"));
        assertMoney("owed to the member", -9_030, net("2300"));

        // a deposit typed in late, dated the 10th: it earned 11th to 31st = 21 days x 80 = 1,680, booked now
        long late = deposit(member, "SAVINGS_DEPOSIT", 365_000, "2025-03-10");
        assertEquals("2025-03-31", lastSavingsDate(member));
        assertMoney("expense with the late deposit", 9_030 + 1_680, net("5400"));

        // moved to the 24th: it earned 7 days, so 14 days x 80 = 1,120 are taken back
        send("PUT", "/api/entries/" + late, "{\"amount\":365000,\"date\":\"2025-03-24\"}", 200);
        assertMoney("expense after the date moved", 9_030 + 560, net("5400"));

        // deleted: what it earned is taken back, back to March's 9,030
        send("DELETE", "/api/entries/" + late, null, 204);
        assertMoney("expense after the delete", 9_030, net("5400"));
        assertMoney("owed after the delete", -9_030, net("2300"));

        // the interest entries are the system's: they are not edited by hand
        for (JsonNode e : send("GET", "/api/transactions?memberId=" + member + "&type=SAVINGS_INTEREST", null, 200)) {
            send("DELETE", "/api/entries/" + e.get("id").asLong(), null, 409);
            break;
        }

        // the month end can be taken back while nothing later has booked interest, and the last savings date goes back to the 20th
        long run = send("GET", "/api/savings-interest/runs", null, 200).get(0).get("id").asLong();
        send("DELETE", "/api/savings-interest/runs/" + run, null, 204);
        assertEquals("2025-03-20", lastSavingsDate(member));
        assertMoney("expense without the month end", 4_931, net("5400"));
        send("POST", "/api/savings-interest/runs", "{\"month\":\"2025-03\"}", 201);
        assertMoney("booked again", 9_030, net("5400"));

        // April cannot be skipped to May
        send("POST", "/api/savings-interest/runs", "{\"month\":\"2025-05\"}", 400);

        // paid out on 15 April: first 1st to 15th = 15 days on 1,700,000 = 5,589.04 -> 5,589, then all 14,619 owed goes into savings
        JsonNode payout = send("POST", "/api/savings-interest/payout", "{\"date\":\"2025-04-15\"}", 201);
        assertMoney("paid into savings", 9_030 + 5_589, payout.get("totalAmount").decimalValue());
        assertEquals("2025-04-15", lastSavingsDate(member));
        assertMoney("expense to the payout", 9_030 + 5_589, net("5400"));
        assertMoney("nothing owed after the payout", 0, net("2300"));
    }
}
