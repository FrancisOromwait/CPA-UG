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
import java.util.List;

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
        long thirteenth = deposit(member, "SAVINGS_DEPOSIT", 500_000, "2025-03-13");
        assertEquals("2025-03-13", lastSavingsDate(member));
        assertMoney("interest to the 13th", 2_630, net("5400"));

        // the deposit shows as one journal with the interest it booked: cash, interest expense, interest payable, savings
        JsonNode journal = send("GET", "/api/entries/" + thirteenth, null, 200);
        assertEquals(4, journal.get("generalLedger").size());
        assertEquals(2, journal.get("memberLedger").size());
        BigDecimal debits = BigDecimal.ZERO, credits = BigDecimal.ZERO;
        for (JsonNode g : journal.get("generalLedger")) {
            debits = debits.add(g.get("debit").decimalValue());
            credits = credits.add(g.get("credit").decimalValue());
        }
        assertMoney("journal debits", 502_630, debits);
        assertMoney("journal credits", 502_630, credits);
        assertEquals("5400", journal.get("generalLedger").get(1).get("accountCode").asText());

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

        // interest is paid out at a month end only, once that month end is booked
        send("POST", "/api/savings-interest/payout", "{\"date\":\"2025-04-15\"}", 400);
        send("POST", "/api/savings-interest/payout", "{\"date\":\"2025-04-30\"}", 400);
        // April: 30 days on 1,700,000 = 11,178.08 -> 11,178; then all 20,208 owed goes into savings
        send("POST", "/api/savings-interest/runs", "{\"month\":\"2025-04\"}", 201);
        JsonNode payout = send("POST", "/api/savings-interest/payout", "{\"date\":\"2025-04-30\"}", 201);
        assertMoney("paid into savings", 9_030 + 11_178, payout.get("totalAmount").decimalValue());
        assertEquals("2025-04-30", lastSavingsDate(member));
        assertMoney("expense to the payout", 9_030 + 11_178, net("5400"));
        assertMoney("nothing owed after the payout", 0, net("2300"));

        // a deposit dated ahead of today carries its days at once, like any other
        String ahead = java.time.LocalDate.now().plusDays(10).toString();
        long future = deposit(member, "SAVINGS_DEPOSIT", 100_000, ahead);
        assertEquals(ahead, lastSavingsDate(member));
        assertEquals(4, send("GET", "/api/entries/" + future, null, 200).get("generalLedger").size());
        // one typed in afterwards with an earlier date gets its days up to then as an adjustment, in its own journal
        long earlier = deposit(member, "SAVINGS_DEPOSIT", 365_000, java.time.LocalDate.now().plusDays(5).toString());
        JsonNode adj = send("GET", "/api/entries/" + earlier, null, 200);
        assertEquals(4, adj.get("generalLedger").size());
        assertMoney("5 days x 80", 400, adj.get("generalLedger").get(1).get("debit").decimalValue());

        // trial balance and balance sheet for a period: opening, movement, closing, and they balance
        JsonNode tb = send("GET", "/api/reports/trial-balance?from=2025-04-01&to=2025-04-30", null, 200);
        assertEquals("Balanced", tb.get("totals").get(4).get("value").asText());
        assertEquals(8, tb.get("sections").get(0).get("columns").size());
        JsonNode bs = send("GET", "/api/reports/balance-sheet?from=2025-04-01&to=2025-04-30", null, 200);
        assertEquals(true, bs.get("note").isNull());
        assertEquals("Balanced", send("GET", "/api/reports/trial-balance", null, 200).get("totals").get(2).get("value").asText());

        // every report, and the general ledger, as an Excel workbook
        for (String url : List.of("/api/reports/trial-balance/xlsx?from=2025-04-01&to=2025-04-30", "/api/reports/balance-sheet/xlsx",
                "/api/reports/income-expenditure/xlsx", "/api/reports/member-summary/xlsx", "/api/general-ledger/xlsx")) {
            byte[] file = mvc.perform(get(url).with(httpBasic("admin", "admin-pass-1"))).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(file))) {
                String title = wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue();
                assertEquals(true, !title.isBlank(), url);
            }
        }

        // the last interest date report shows the member's last savings date
        JsonNode report = send("GET", "/api/reports/last-interest-date", null, 200);
        JsonNode row = report.get("sections").get(0).get("rows").get(0);
        assertEquals("S1", row.get(0).asText());
        assertEquals(java.time.LocalDate.parse(ahead).format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", java.util.Locale.ENGLISH)), row.get(4).asText());
    }
}
