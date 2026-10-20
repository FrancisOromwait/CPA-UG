package com.estates.sacco;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BranchIsolationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired com.estates.sacco.service.BootstrapRunner bootstrap;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    RequestPostProcessor manager = httpBasic("manager", "ChangeMe123!");
    RequestPostProcessor adminA = httpBasic("adminA", "password-A-1");
    RequestPostProcessor adminB = httpBasic("adminB", "password-B-1");

    JsonNode send(RequestPostProcessor who, String method, String url, String body, int expected) throws Exception {
        var req = switch (method) {
            case "POST" -> post(url);
            case "PUT" -> put(url);
            case "DELETE" -> delete(url);
            default -> get(url);
        };
        req.with(who);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        String res = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return res.isBlank() ? null : json.readTree(res);
    }

    void assertBalanced(RequestPostProcessor who) throws Exception {
        JsonNode tb = send(who, "GET", "/api/reports/trial-balance", null, 200);
        assertEquals("Balanced", tb.get("totals").get(2).get("value").asText());
        JsonNode bs = send(who, "GET", "/api/reports/balance-sheet", null, 200);
        assertEquals(bs.get("totals").get(0).get("value").asText(), bs.get("totals").get(3).get("value").asText());
    }

    java.math.BigDecimal bd(String s) { return new java.math.BigDecimal(s); }

    @Test
    void managerSeesAllBranchesAdminsSeeOnlyTheirOwn() throws Exception {
        long a = send(manager, "POST", "/api/branches", "{\"code\":\"sac001\",\"name\":\"Branch A\"}", 201).get("id").asLong();
        long b = send(manager, "POST", "/api/branches", "{\"code\":\"SAC002\",\"name\":\"Branch B\"}", 201).get("id").asLong();
        send(manager, "POST", "/api/users", "{\"username\":\"adminA\",\"password\":\"password-A-1\",\"fullName\":\"A\",\"role\":\"BRANCH_ADMIN\",\"branchId\":" + a + "}", 201);
        send(manager, "POST", "/api/users", "{\"username\":\"adminB\",\"password\":\"password-B-1\",\"fullName\":\"B\",\"role\":\"BRANCH_ADMIN\",\"branchId\":" + b + "}", 201);

        // login page: branch list is public (no credentials) and the account knows its branch name
        String pub = mvc.perform(get("/api/public/branches")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(2, json.readTree(pub).size());
        assertEquals("Branch A", send(adminA, "GET", "/api/me", null, 200).get("branchName").asText());
        mvc.perform(get("/api/branches")).andExpect(status().isUnauthorized());

        // chart of accounts: ONE shared chart, seen alike by every branch; only the manager changes it
        int standard = send(adminA, "GET", "/api/accounts", null, 200).size();
        assertEquals(20, standard);
        assertEquals(standard, send(adminB, "GET", "/api/accounts", null, 200).size());
        send(adminA, "POST", "/api/accounts", "{\"code\":\"1110\",\"name\":\"Petty Cash\",\"type\":\"ASSET\"}", 403);   // admins cannot add
        long cash = send(manager, "POST", "/api/accounts", "{\"code\":\"1110\",\"name\":\"Petty Cash\",\"type\":\"ASSET\"}", 201).get("id").asLong();
        send(manager, "POST", "/api/accounts", "{\"code\":\"1110\",\"name\":\"Dup\",\"type\":\"ASSET\"}", 409);
        send(manager, "POST", "/api/accounts", "{\"code\":\"2999\",\"name\":\"Bad parent\",\"type\":\"LIABILITY\",\"parentId\":" + cash + "}", 400);
        // the manager's new account is there for every branch at once
        assertEquals(standard + 1, send(adminA, "GET", "/api/accounts", null, 200).size());
        assertEquals(standard + 1, send(adminB, "GET", "/api/accounts", null, 200).size());
        assertEquals(standard + 1, send(manager, "GET", "/api/accounts", null, 200).size());
        send(adminB, "PUT", "/api/accounts/" + cash, "{\"name\":\"Hijack\"}", 403);
        send(adminA, "PUT", "/api/accounts/" + cash + "/active/false", null, 403);
        send(adminA, "DELETE", "/api/accounts/" + cash, null, 403);
        assertEquals("Petty Cash (office)", send(manager, "PUT", "/api/accounts/" + cash, "{\"name\":\"Petty Cash (office)\"}", 200).get("name").asText());

        long memA = send(adminA, "POST", "/api/members", "{\"memberNo\":\"M1\",\"fullName\":\"Alice\",\"employer\":\"Acme\"}", 201).get("id").asLong();
        long memB = send(adminB, "POST", "/api/members", "{\"memberNo\":\"M1\",\"fullName\":\"Bob\"}", 201).get("id").asLong();

        // savings, shares, loan with repayment in A; savings in B
        send(adminA, "POST", "/api/transactions", "{\"memberId\":" + memA + ",\"type\":\"SAVINGS_DEPOSIT\",\"amount\":1000}", 201);
        send(adminA, "POST", "/api/transactions", "{\"memberId\":" + memA + ",\"type\":\"SHARE_PURCHASE\",\"amount\":500}", 201);
        send(adminA, "POST", "/api/transactions", "{\"memberId\":" + memA + ",\"type\":\"SAVINGS_WITHDRAWAL\",\"amount\":5000}", 422);
        send(adminB, "POST", "/api/transactions", "{\"memberId\":" + memB + ",\"type\":\"SAVINGS_DEPOSIT\",\"amount\":300}", 201);
        long loan = send(adminA, "POST", "/api/loans", "{\"memberId\":" + memA + ",\"principal\":1000,\"annualRatePercent\":12,\"termMonths\":12,\"interestMethod\":\"STRAIGHT_LINE\"}", 201).get("id").asLong();
        send(adminA, "POST", "/api/loans/" + loan + "/disburse", null, 200);
        assertEquals(12, jdbc.queryForObject("select count(*) from loan_schedule where loan_id = ?", Integer.class, loan));
        // payroll: 100 savings + 50 shares + 120 loan repayment
        long bankA = 0, mobileA = 0, loanAccA = 0;
        for (JsonNode acc : send(adminA, "GET", "/api/accounts", null, 200)) {
            if (acc.get("code").asText().equals("1200")) bankA = acc.get("id").asLong();
            if (acc.get("code").asText().equals("1300")) mobileA = acc.get("id").asLong();
            if (acc.get("code").asText().equals("2100")) loanAccA = acc.get("id").asLong();
        }
        // receiving account must be an asset account
        send(adminA, "POST", "/api/payroll", "{\"employer\":\"Bad\",\"period\":\"2026-08\",\"receivingAccountId\":" + loanAccA + ",\"lines\":[{\"memberId\":" + memA + ",\"savings\":1}]}", 400);
        send(adminA, "POST", "/api/payroll", "{\"employer\":\"Acme\",\"period\":\"2026-09\",\"receivingAccountId\":" + mobileA + ",\"date\":\"2026-09-28\",\"lines\":[{\"memberId\":" + memA
                + ",\"savings\":100,\"shares\":50,\"loanRepayment\":120,\"loanId\":" + loan + "}]}", 201);
        send(adminA, "POST", "/api/payroll", "{\"employer\":\"Acme\",\"period\":\"2026-09\",\"receivingAccountId\":" + bankA + ",\"lines\":[{\"memberId\":" + memA + ",\"savings\":1}]}", 409);
        // the batch keeps its date and receiving account, and the GL lines use them
        JsonNode batchA = send(adminA, "GET", "/api/payroll", null, 200).get(0);
        assertEquals("2026-09-28", batchA.get("paymentDate").asText());
        assertEquals("1300 Mobile Money", batchA.get("receivingAccount").asText());
        boolean mobileDebited = false;
        for (JsonNode g : send(adminA, "GET", "/api/general-ledger?from=2026-09-28&to=2026-09-28", null, 200)) {
            if (g.get("accountCode").asText().equals("1300") && g.get("debit").decimalValue().signum() > 0) mobileDebited = true;
        }
        org.junit.jupiter.api.Assertions.assertTrue(mobileDebited, "payroll money must land in the chosen receiving account");

        // isolation: B cannot touch or see A's data
        send(adminB, "GET", "/api/members/" + memA + "/statement", null, 403);
        send(adminB, "POST", "/api/transactions", "{\"memberId\":" + memA + ",\"type\":\"SAVINGS_DEPOSIT\",\"amount\":1}", 403);
        send(adminB, "GET", "/api/reports/summary?branchId=" + a, null, 403);
        assertEquals(1, send(adminB, "GET", "/api/members", null, 200).size());
        assertEquals(1, send(adminB, "GET", "/api/branches", null, 200).size());
        assertEquals(0, send(adminB, "GET", "/api/loans", null, 200).size());
        // role separation
        send(adminA, "POST", "/api/branches", "{\"code\":\"X\",\"name\":\"X\"}", 403);
        send(adminA, "GET", "/api/users", null, 200);        // a branch admin sees only the users of their own branch (checked below)
        send(manager, "POST", "/api/transactions", "{\"memberId\":" + memA + ",\"type\":\"SAVINGS_DEPOSIT\",\"amount\":1}", 403);

        // product definitions + member ledger (ML) + general ledger (GL)
        JsonNode prodsA = send(adminA, "GET", "/api/products", null, 200);
        assertEquals(4, prodsA.size());
        java.util.Set<String> codes = new java.util.HashSet<>();
        prodsA.forEach(x -> codes.add(x.get("productCode").asText() + ":" + x.get("category").asText()));
        assertEquals(java.util.Set.of("SAV01:SAVINGS", "SHR01:SHARES", "LON01:LOAN", "LIN01:INTEREST_RECEIVABLE"), codes);
        assertEquals(4, send(manager, "GET", "/api/products?branchId=" + b, null, 200).size());
        // add a product: details come from the chart account; many per category; code unique; account type checked
        long savAcc = 0, loanAcc = 0;
        for (JsonNode acc : send(adminA, "GET", "/api/accounts", null, 200)) {
            if (acc.get("code").asText().equals("2100")) savAcc = acc.get("id").asLong();
            if (acc.get("code").asText().equals("1400")) loanAcc = acc.get("id").asLong();
        }
        JsonNode comsv = send(adminA, "POST", "/api/products", "{\"productCode\":\"comsv\",\"category\":\"SAVINGS\",\"accountId\":" + savAcc + ",\"remarks\":\"XX\"}", 201);
        assertEquals("COMSV", comsv.get("productCode").asText());
        assertEquals("Member Savings Deposits", comsv.get("productDetails").asText());
        send(adminA, "POST", "/api/products", "{\"productCode\":\"COMSV\",\"category\":\"SAVINGS\",\"accountId\":" + savAcc + "}", 409);
        send(adminA, "POST", "/api/products", "{\"productCode\":\"BAD1\",\"category\":\"SAVINGS\",\"accountId\":" + loanAcc + "}", 400);
        send(adminB, "PUT", "/api/products/" + comsv.get("id").asLong(), "{\"accountId\":" + savAcc + "}", 403);
        send(manager, "POST", "/api/products", "{\"productCode\":\"ZZ\",\"category\":\"SAVINGS\",\"accountId\":" + savAcc + "}", 403);
        assertEquals(5, send(adminA, "GET", "/api/products", null, 200).size());
        send(adminB, "GET", "/api/products?branchId=" + a, null, 403);
        JsonNode ml = send(adminA, "GET", "/api/member-ledger?memberId=" + memA, null, 200);
        for (JsonNode pb : ml.get("balances")) {
            String code = pb.get("productCode").asText();
            String expect = code.equals("SAV01") ? "1100" : code.equals("SHR01") ? "550" : "880";
            assertEquals(0, pb.get("balance").decimalValue().compareTo(new java.math.BigDecimal(expect)), code);
        }
        assertEquals(3, ml.get("balances").size());
        send(adminB, "GET", "/api/member-ledger?memberId=" + memA, null, 403);
        java.util.function.Function<String, java.math.BigDecimal> num = v -> new java.math.BigDecimal(v.replace(",", ""));
        for (RequestPostProcessor who : new RequestPostProcessor[] {adminA, adminB, manager}) {
            JsonNode tb = send(who, "GET", "/api/reports/trial-balance", null, 200);
            assertEquals(0, num.apply(tb.get("totals").get(0).get("value").asText()).compareTo(num.apply(tb.get("totals").get(1).get("value").asText())), "trial balance must balance");
            assertEquals("Balanced", tb.get("totals").get(2).get("value").asText());
            JsonNode bs = send(who, "GET", "/api/reports/balance-sheet", null, 200);
            assertEquals(0, num.apply(bs.get("totals").get(0).get("value").asText()).compareTo(num.apply(bs.get("totals").get(3).get("value").asText())), "assets must equal liabilities + equity");
        }
        // income and expenditure: interest is now earned month by month (no interest run yet, so none); manager can pick a branch or all
        JsonNode ieA = send(adminA, "GET", "/api/reports/income-expenditure", null, 200);
        assertEquals("0.00", ieA.get("totals").get(0).get("value").asText());
        assertEquals("0.00", send(manager, "GET", "/api/reports/income-expenditure?branchId=" + a, null, 200).get("totals").get(0).get("value").asText());
        assertEquals("0.00", send(manager, "GET", "/api/reports/income-expenditure?branchId=" + b, null, 200).get("totals").get(0).get("value").asText());
        assertEquals("0.00", send(manager, "GET", "/api/reports/income-expenditure", null, 200).get("totals").get(0).get("value").asText());
        // branch scoping and the member statement
        send(adminB, "GET", "/api/reports/trial-balance?branchId=" + a, null, 403);
        send(adminB, "GET", "/api/reports/member-statement?memberId=" + memA, null, 403);
        send(adminA, "GET", "/api/reports/member-statement", null, 400);
        // savings withdrawal: pays a member out of their savings, never more than they have, and balances in both ledgers
        long cashAcctA = 0;
        for (JsonNode acc : send(adminA, "GET", "/api/accounts", null, 200)) if (acc.get("code").asText().equals("1100")) cashAcctA = acc.get("id").asLong();
        long wdDeposit = send(adminA, "POST", "/api/transactions", "{\"memberId\":" + memA + ",\"type\":\"SAVINGS_DEPOSIT\",\"amount\":7}", 201).get("id").asLong();
        java.math.BigDecimal savingsBefore = send(adminA, "GET", "/api/members/" + memA + "/statement", null, 200).get("savingsBalance").decimalValue();
        String wd = "{\"memberId\":" + memA + ",\"amount\":%s,\"paidFromAccountId\":" + cashAcctA + ",\"reference\":\"PV-1\"}";
        send(adminA, "POST", "/api/withdrawals", String.format(wd, "0"), 400);
        send(adminA, "POST", "/api/withdrawals", String.format(wd, savingsBefore.add(java.math.BigDecimal.ONE).toPlainString()), 422);   // more than the member has
        send(manager, "POST", "/api/withdrawals", String.format(wd, "3"), 403);
        send(adminB, "POST", "/api/withdrawals", String.format(wd, "3"), 403);                                          // another branch's member
        send(adminA, "POST", "/api/withdrawals", String.format(wd, "3").replace("\"reference\"", "\"fee\":3,\"reference\""), 400);   // the fee must be less than the amount
        JsonNode wdOut = send(adminA, "POST", "/api/withdrawals", String.format(wd, "3").replace("\"reference\"", "\"fee\":1,\"reference\""), 201);
        assertEquals("SAVINGS_WITHDRAWAL", wdOut.get("type").asText());
        assertEquals(0, new java.math.BigDecimal("1").compareTo(wdOut.get("fee").decimalValue()));
        // the member's savings drop by the whole 3; the cash account pays out 2; the fee of 1 is income on 4400
        long wdId = wdOut.get("id").asLong();
        assertEquals(0, new java.math.BigDecimal("2").compareTo(jdbc.queryForObject(
                "select coalesce(sum(g.credit),0) from general_ledger g join accounts a on a.id = g.account_id where g.ledger_entry_id = ? and a.code = '1100'", java.math.BigDecimal.class, wdId)));
        assertEquals(0, new java.math.BigDecimal("1").compareTo(jdbc.queryForObject(
                "select coalesce(sum(g.credit),0) from general_ledger g join accounts a on a.id = g.account_id where g.ledger_entry_id = ? and a.code = '4400'", java.math.BigDecimal.class, wdId)));
        assertEquals(0, new java.math.BigDecimal("3").compareTo(jdbc.queryForObject(
                "select coalesce(sum(debit),0) from member_ledger where ledger_entry_id = ?", java.math.BigDecimal.class, wdId)));
        // editing the entry keeps the fee in step: raise it to 2, then a fee as big as the amount is refused
        send(adminA, "PUT", "/api/entries/" + wdId, "{\"amount\":3,\"fee\":2,\"receivingAccountId\":" + cashAcctA + "}", 200);
        assertEquals(0, new java.math.BigDecimal("2").compareTo(jdbc.queryForObject(
                "select coalesce(sum(g.credit),0) from general_ledger g join accounts a on a.id = g.account_id where g.ledger_entry_id = ? and a.code = '4400'", java.math.BigDecimal.class, wdId)));
        send(adminA, "PUT", "/api/entries/" + wdId, "{\"amount\":3,\"fee\":3,\"receivingAccountId\":" + cashAcctA + "}", 400);
        assertEquals(0, savingsBefore.subtract(new java.math.BigDecimal("3")).compareTo(
                send(adminA, "GET", "/api/members/" + memA + "/statement", null, 200).get("savingsBalance").decimalValue()));
        assertEquals(1, send(adminA, "GET", "/api/withdrawals", null, 200).size());
        assertEquals(0, send(adminB, "GET", "/api/withdrawals", null, 200).size());
        assertEquals(1, send(manager, "GET", "/api/withdrawals", null, 200).size());
        assertBalanced(adminA);
        send(adminA, "DELETE", "/api/entries/" + wdOut.get("id").asLong(), null, 204);
        send(adminA, "DELETE", "/api/entries/" + wdDeposit, null, 204);
        assertEquals(0, send(adminA, "GET", "/api/withdrawals", null, 200).size());
        assertBalanced(adminA);
        // member logins: adding a member creates a login (username and password are the full names) that sees only their own statement
        RequestPostProcessor aliceLogin = httpBasic("Alice", "Alice");
        assertEquals("MEMBER", send(aliceLogin, "GET", "/api/me", null, 200).get("role").asText());
        assertEquals(true, send(aliceLogin, "GET", "/api/my/statement", null, 200).get("subjectLine").asText().contains("M1 - Alice"));
        mvc.perform(get("/api/my/statement/pdf").with(aliceLogin)).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("application/pdf"));
        for (String blocked : new String[] {"/api/members", "/api/loans", "/api/users", "/api/accounts", "/api/general-ledger", "/api/reports/member-statement?memberId=" + memA,
                "/api/members/" + memA + "/statement", "/api/interest-runs"}) send(aliceLogin, "GET", blocked, null, 403);
        send(aliceLogin, "POST", "/api/transactions", "{\"memberId\":" + memA + ",\"type\":\"SAVINGS_DEPOSIT\",\"amount\":5}", 403);
        send(adminA, "GET", "/api/my/statement", null, 403);                                                           // staff do not use the member screen
        send(manager, "GET", "/api/my/statement", null, 403);
        send(adminA, "POST", "/api/users", "{\"username\":\"x\",\"password\":\"password-1\",\"fullName\":\"X\",\"role\":\"MEMBER\"}", 400);
        for (JsonNode u : send(adminA, "GET", "/api/users", null, 200)) org.junit.jupiter.api.Assertions.assertNotEquals("MEMBER", u.get("role").asText());
        // a second "Alice" in another branch gets a different username; deleting that member removes the login
        JsonNode alice2 = send(adminB, "POST", "/api/members", "{\"memberNo\":\"M9\",\"fullName\":\"Alice\"}", 201);
        assertEquals("Alice M9", alice2.get("loginUsername").asText());
        assertEquals(true, send(httpBasic("Alice M9", "Alice"), "GET", "/api/my/statement", null, 200).get("subjectLine").asText().contains("M9 - Alice"));
        send(adminA, "POST", "/api/members/" + alice2.get("id").asLong() + "/reset-login", null, 403);                // another branch
        send(adminB, "DELETE", "/api/members/" + alice2.get("id").asLong(), null, 204);
        send(httpBasic("Alice M9", "Alice"), "GET", "/api/my/statement", null, 401);
        // a member changes their own password; the admin can give the first one back
        send(aliceLogin, "PUT", "/api/my/password", "{\"password\":\"short\"}", 400);
        send(aliceLogin, "PUT", "/api/my/password", "{\"password\":\"alice-new-pass\"}", 200);
        send(aliceLogin, "GET", "/api/my/statement", null, 401);
        send(httpBasic("Alice", "alice-new-pass"), "GET", "/api/my/statement", null, 200);
        send(adminA, "POST", "/api/members/" + memA + "/reset-login", null, 200);
        send(aliceLogin, "GET", "/api/my/statement", null, 200);
        // mobile money numbers: two per branch, each with its own account; members see where to pay; messages can be read
        String momo1 = "{\"branchId\":" + a + ",\"network\":\"MTN\",\"number\":\"0772000111\",\"holderName\":\"Branch A\"}";
        String momo2 = "{\"branchId\":" + a + ",\"network\":\"Airtel\",\"number\":\"0705000222\"}";
        send(adminA, "POST", "/api/mobile-money", momo1, 403);                                                        // only the manager sets them up
        JsonNode momoOne = send(manager, "POST", "/api/mobile-money", momo1, 201);
        JsonNode momoTwo = send(manager, "POST", "/api/mobile-money", momo2, 201);
        assertEquals(true, momoOne.get("accountCode").asText().startsWith("13"));
        org.junit.jupiter.api.Assertions.assertNotEquals(momoOne.get("accountCode").asText(), momoTwo.get("accountCode").asText());
        send(manager, "POST", "/api/mobile-money", momo2.replace("0705000222", "0705000333"), 400);                   // a branch has at most two
        send(manager, "POST", "/api/mobile-money", momo1.replace("\"branchId\":" + a, "\"branchId\":" + b), 409);        // the number is already set up
        send(manager, "POST", "/api/mobile-money", momo1.replace("0772000111", "12"), 400);
        assertEquals(2, send(adminA, "GET", "/api/mobile-money", null, 200).size());
        assertEquals(0, send(adminB, "GET", "/api/mobile-money", null, 200).size());
        assertEquals(2, send(manager, "GET", "/api/mobile-money", null, 200).size());
        send(adminB, "GET", "/api/mobile-money?branchId=" + a, null, 403);
        String momoCode = momoOne.get("accountCode").asText();
        boolean seenByA = false, seenByB = false;
        for (JsonNode acc : send(adminA, "GET", "/api/accounts", null, 200)) if (acc.get("code").asText().equals(momoCode)) seenByA = true;
        for (JsonNode acc : send(adminB, "GET", "/api/accounts", null, 200)) if (acc.get("code").asText().equals(momoCode)) seenByB = true;
        assertEquals(true, seenByA);
        assertEquals(false, seenByB);                                                                                 // another branch's number is not offered
        send(manager, "PUT", "/api/mobile-money/" + momoTwo.get("id").asLong(), momo2.replace("Airtel", "Airtel Money"), 200);
        // a deposit received on the number: the money is mobile money, and the same transaction cannot be recorded twice
        String momoDeposit = "{\"memberId\":" + memA + ",\"totalAmount\":5,\"savings\":5,\"receivingAccountId\":" + momoOne.get("accountId").asLong()
                + ",\"reference\":\"MoMo 12345678901\"}";
        send(adminA, "POST", "/api/deposits", momoDeposit, 201);
        send(adminA, "POST", "/api/deposits", momoDeposit, 409);
        assertEquals("MOBILE_MONEY", jdbc.queryForObject("select source from ledger_entries where reference = 'MoMo 12345678901'", String.class));
        String mtnText = "You have received UGX 50,000 from JOHN MUGISHA 0772123456. Transaction ID: 12345678901. Your new balance is UGX 900,000. Thank you for using MTN MoMo.";
        JsonNode read = send(adminA, "POST", "/api/mobile-payments/parse", "{\"text\":\"" + mtnText + "\"}", 200);
        assertEquals(0, new java.math.BigDecimal("50000").compareTo(read.get("amount").decimalValue()));
        assertEquals("0772123456", read.get("senderPhone").asText());
        assertEquals("JOHN MUGISHA", read.get("senderName").asText());
        assertEquals("MoMo 12345678901", read.get("reference").asText());
        assertEquals("MTN", read.get("network").asText());
        assertEquals(true, read.get("duplicate").asBoolean());                                                        // recorded just above
        assertEquals(momoOne.get("accountId").asLong(), read.get("receivingAccountId").asLong());                       // the branch's MTN number
        String airtelText = "Dear customer, you have received UGX 20,000 from 0705123456 JANE DOE. TID: MP241008.1234.A12345. Balance UGX 5,000.";
        JsonNode read2 = send(adminA, "POST", "/api/mobile-payments/parse", "{\"text\":\"" + airtelText + "\"}", 200);
        assertEquals(0, new java.math.BigDecimal("20000").compareTo(read2.get("amount").decimalValue()));
        assertEquals("0705123456", read2.get("senderPhone").asText());
        assertEquals("MP241008.1234.A12345", read2.get("transactionId").asText());
        assertEquals("Airtel", read2.get("network").asText());
        assertEquals(false, read2.get("duplicate").asBoolean());
        send(adminA, "POST", "/api/mobile-payments/parse", "{\"text\":\"hello there\"}", 422);
        send(manager, "POST", "/api/mobile-payments/parse", "{\"text\":\"" + mtnText + "\"}", 403);
        // the member sees where to pay
        assertEquals(2, send(aliceLogin, "GET", "/api/my/pay-to", null, 200).size());
        send(adminA, "GET", "/api/my/pay-to", null, 403);
        // clean up: remove the deposit, then the numbers (one that has had money through it only after its deposit is gone)
        long momoEntry = jdbc.queryForObject("select id from ledger_entries where reference = 'MoMo 12345678901'", Long.class);
        send(adminA, "DELETE", "/api/entries/" + momoEntry, null, 204);
        send(manager, "DELETE", "/api/mobile-money/" + momoTwo.get("id").asLong(), null, 204);
        assertEquals(1, send(adminA, "GET", "/api/mobile-money", null, 200).size());
        assertBalanced(adminA);
        // loan top-up: a bigger new loan clears the old one (principal + interest), and the bank gets only what the member receives
        String tuLoan = "{\"memberId\":" + memA + ",\"principal\":200000,\"annualRatePercent\":12,\"termMonths\":12,\"applicationFee\":0}";
        long tuOld = send(adminA, "POST", "/api/loans", tuLoan, 201).get("id").asLong();
        send(adminA, "POST", "/api/loans", tuLoan.replace("}", ",\"topUpOfLoanId\":" + tuOld + "}"), 409);          // a pending loan cannot be topped up
        send(adminA, "POST", "/api/loans/" + tuOld + "/disburse", "{\"source\":\"CASH\"}", 200);
        String tuTop = "{\"memberId\":" + memA + ",\"principal\":%s,\"annualRatePercent\":12,\"termMonths\":12,\"applicationFee\":10000,\"topUpOfLoanId\":" + tuOld + "}";
        send(adminA, "POST", "/api/loans", String.format(tuTop, "205000"), 400);                                      // 205,000 - 10,000 fee does not cover the 200,000 owed
        send(adminB, "POST", "/api/loans", String.format(tuTop, "500000").replace("\"memberId\":" + memA, "\"memberId\":" + memB), 403);   // not another branch's loan
        long tuNew = send(adminA, "POST", "/api/loans", String.format(tuTop, "500000"), 201).get("id").asLong();
        send(adminA, "POST", "/api/loans", String.format(tuTop, "600000"), 409);                                      // one top-up at a time
        assertEquals(tuOld, send(adminA, "GET", "/api/loans/" + tuNew, null, 200).get("topUpOfLoanId").asLong());
        send(adminA, "POST", "/api/loans/" + tuNew + "/disburse", "{\"source\":\"CASH\"}", 200);
        JsonNode tuOldNow = send(adminA, "GET", "/api/loans/" + tuOld, null, 200);
        assertEquals("CLEARED", tuOldNow.get("status").asText());
        assertEquals(0, tuOldNow.get("outstanding").decimalValue().signum());
        JsonNode tuNewNow = send(adminA, "GET", "/api/loans/" + tuNew, null, 200);
        assertEquals("ACTIVE", tuNewNow.get("status").asText());
        assertEquals(0, new java.math.BigDecimal("200000").compareTo(tuNewNow.get("topUpSettled").decimalValue()));
        assertEquals(0, new java.math.BigDecimal("500000").compareTo(tuNewNow.get("outstanding").decimalValue()));
        long tuPayout = jdbc.queryForObject("select id from ledger_entries where loan_id = ? and type = 'LOAN_DISBURSEMENT'", Long.class, tuNew);
        String tuSql = "select coalesce(sum(%s),0) from general_ledger g join accounts a on a.id = g.account_id where g.ledger_entry_id = ? and a.code = ?";
        // payout: Dr loans 500,000; Cr cash 290,000 (500,000 - 10,000 fee - 200,000 cleared); Cr fee income 10,000
        assertEquals(0, new java.math.BigDecimal("500000").compareTo(jdbc.queryForObject(String.format(tuSql, "g.debit"), java.math.BigDecimal.class, tuPayout, "1400")));
        assertEquals(0, new java.math.BigDecimal("290000").compareTo(jdbc.queryForObject(String.format(tuSql, "g.credit"), java.math.BigDecimal.class, tuPayout, "1100")));
        assertEquals(0, new java.math.BigDecimal("10000").compareTo(jdbc.queryForObject(String.format(tuSql, "g.credit"), java.math.BigDecimal.class, tuPayout, "4200")));
        // the settlement of the old loan moves no cash: it credits the loan account only
        long tuSettle = jdbc.queryForObject("select id from ledger_entries where loan_id = ? and type = 'LOAN_REPAYMENT' and source = 'SYSTEM'", Long.class, tuOld);
        assertEquals(0, new java.math.BigDecimal("200000").compareTo(jdbc.queryForObject(String.format(tuSql, "g.credit"), java.math.BigDecimal.class, tuSettle, "1400")));
        assertEquals(0, jdbc.queryForObject("select count(*) from general_ledger g join accounts a on a.id = g.account_id where g.ledger_entry_id = ? and a.code in ('1100','1200','1300')",
                Integer.class, tuSettle));
        for (JsonNode dep : send(adminA, "GET", "/api/deposits", null, 200)) org.junit.jupiter.api.Assertions.assertNotEquals((long) tuSettle, dep.get("id").asLong());
        send(adminA, "DELETE", "/api/entries/" + tuSettle, null, 409);                                                 // only the top-up loan can undo it
        send(adminA, "DELETE", "/api/loans/" + tuOld, null, 409);
        assertBalanced(adminA);
        assertBalanced(manager);
        // deleting the top-up opens the old loan again
        send(adminA, "DELETE", "/api/loans/" + tuNew, null, 204);
        JsonNode tuReopened = send(adminA, "GET", "/api/loans/" + tuOld, null, 200);
        assertEquals("ACTIVE", tuReopened.get("status").asText());
        assertEquals(0, new java.math.BigDecimal("200000").compareTo(tuReopened.get("outstanding").decimalValue()));
        assertBalanced(adminA);
        send(adminA, "DELETE", "/api/loans/" + tuOld, null, 204);
        assertBalanced(adminA);
        // generate new year codes: FixedData, MonthData and interval (tables outside the app's data model)
        jdbc.execute("create table if not exists FixedData (IDNo int auto_increment primary key, CurrentYear smallint)");
        jdbc.execute("create table if not exists MonthData (id int auto_increment primary key, FINTCODE varchar(10) not null, FINTNAME varchar(60), FSTART date not null, FEND date not null, "
                + "DaysInMonth smallint not null default 0, FirstDay smallint not null default 1, LastDay smallint not null default 0)");
        jdbc.execute("create table if not exists `interval` (FINTCODE varchar(10) not null, YRCODE varchar(10) not null, FINTNAME varchar(60), FSTART date not null, FEND date not null, "
                + "Xrate decimal(15,4) not null default 0, Bxrate decimal(15,4) not null default 0, Exrate decimal(15,4) not null default 0, FSTATUS char(1) not null default 'A', "
                + "FTRANS varchar(20), FYrClose varchar(20), FyrReOpen varchar(20), primary key (YRCODE, FINTCODE))");
        send(adminA, "GET", "/api/year-codes", null, 403);                                                            // group-wide setup: manager only
        send(adminA, "POST", "/api/year-codes", null, 403);
        send(manager, "POST", "/api/year-codes", null, 409);                                                          // nothing set up yet
        jdbc.update("insert into FixedData (CurrentYear) values (2027)");
        send(manager, "POST", "/api/year-codes", null, 409);                                                          // MonthData still empty
        jdbc.update("insert into MonthData (FINTCODE, FINTNAME, FSTART, FEND, DaysInMonth, FirstDay, LastDay) values ('03', 'Feb-01', '2027-02-01', '2027-02-14', 14, 1, 14)");
        jdbc.update("insert into MonthData (FINTCODE, FINTNAME, FSTART, FEND, DaysInMonth, FirstDay, LastDay) values ('04', 'Feb-02', '2027-02-15', '2027-02-28', 14, 15, 28)");
        jdbc.update("insert into MonthData (FINTCODE, FINTNAME, FSTART, FEND, DaysInMonth, FirstDay, LastDay) values ('24', 'Dec-02', '2027-12-16', '2027-12-31', 16, 16, 31)");
        JsonNode yearBefore = send(manager, "GET", "/api/year-codes", null, 200);
        assertEquals(2027, yearBefore.get("currentYear").asInt());
        assertEquals(2028, yearBefore.get("nextYear").asInt());
        assertEquals(false, yearBefore.hasNonNull("problem"));
        JsonNode yearRun = send(manager, "POST", "/api/year-codes", null, 200);
        assertEquals(2028, yearRun.get("year").asInt());
        assertEquals(3, yearRun.get("periods").asInt());
        assertEquals(2028, jdbc.queryForObject("select CurrentYear from FixedData", Integer.class).intValue());      // step 4
        assertEquals(java.time.LocalDate.of(2028, 2, 29), jdbc.queryForObject("select FEND from MonthData where FINTCODE = '04'", java.time.LocalDate.class));   // a month end stays a month end (leap year)
        assertEquals(java.time.LocalDate.of(2028, 2, 15), jdbc.queryForObject("select FSTART from MonthData where FINTCODE = '04'", java.time.LocalDate.class));
        assertEquals(15, jdbc.queryForObject("select DaysInMonth from MonthData where FINTCODE = '04'", Integer.class).intValue());   // 15 Feb to 29 Feb
        assertEquals(3, jdbc.queryForObject("select count(*) from `interval` where YRCODE = '2028'", Integer.class).intValue());      // step 3
        assertEquals(java.time.LocalDate.of(2028, 12, 31), jdbc.queryForObject("select FEND from `interval` where YRCODE = '2028' and FINTCODE = '24'", java.time.LocalDate.class));
        assertEquals("A", jdbc.queryForObject("select FSTATUS from `interval` where YRCODE = '2028' and FINTCODE = '24'", String.class));
        // the same year cannot be generated twice, and a refused run changes nothing
        jdbc.update("update FixedData set CurrentYear = 2027");
        JsonNode yearBlocked = send(manager, "GET", "/api/year-codes", null, 200);
        assertEquals(true, yearBlocked.get("problem").asText().contains("2028"));
        send(manager, "POST", "/api/year-codes", null, 409);
        assertEquals(java.time.LocalDate.of(2028, 2, 29), jdbc.queryForObject("select FEND from MonthData where FINTCODE = '04'", java.time.LocalDate.class));
        assertEquals(2027, jdbc.queryForObject("select CurrentYear from FixedData", Integer.class).intValue());
        // MonthData that is not on the current year is refused (it would give periods outside the year)
        jdbc.update("update FixedData set CurrentYear = 2035");
        send(manager, "POST", "/api/year-codes", null, 409);
        assertEquals(0, jdbc.queryForObject("select count(*) from `interval` where YRCODE = '2036'", Integer.class).intValue());
        jdbc.execute("drop table `interval`");
        jdbc.execute("drop table MonthData");
        jdbc.execute("drop table FixedData");
        // branch staff: a branch admin creates a cashier in their own branch; the cashier works like the admin but cannot manage users
        String cashBody = "{\"username\":\"cashA\",\"password\":\"cash-pass-1\",\"fullName\":\"Cash A\",\"role\":\"CASHIER\"}";
        JsonNode cashUser = send(adminA, "POST", "/api/users", cashBody, 201);
        assertEquals("CASHIER", cashUser.get("role").asText());
        assertEquals(send(adminA, "GET", "/api/me", null, 200).get("branchId").asLong(), cashUser.get("branchId").asLong());
        send(adminA, "POST", "/api/users", cashBody.replace("cashA", "cashA2"), 201);                                   // treasurer or cashier, as many as needed
        send(adminA, "POST", "/api/users", cashBody.replace("cashA", "adminA9").replace("CASHIER", "BRANCH_ADMIN"), 403);
        send(adminA, "POST", "/api/users", cashBody.replace("cashA", "mgr9").replace("CASHIER", "MANAGER"), 403);
        send(adminA, "POST", "/api/users", cashBody, 409);                                                                // username taken
        RequestPostProcessor cashA = httpBasic("cashA", "cash-pass-1");
        send(cashA, "GET", "/api/members", null, 200);
        send(cashA, "POST", "/api/transactions", "{\"memberId\":" + memA + ",\"type\":\"SAVINGS_DEPOSIT\",\"amount\":0}", 400);   // allowed to post (the amount is what is refused)
        send(cashA, "GET", "/api/users", null, 403);
        send(cashA, "POST", "/api/users", cashBody.replace("cashA", "cashA3"), 403);
        send(cashA, "GET", "/api/reports/member-statement?memberId=" + memB, null, 403);                                 // still limited to its own branch
        for (JsonNode u : send(adminA, "GET", "/api/users", null, 200)) assertEquals(cashUser.get("branchId").asLong(), u.get("branchId").asLong());
        long cashId = cashUser.get("id").asLong();
        send(adminB, "PUT", "/api/users/" + cashId + "/enabled/false", null, 403);                                        // another branch's admin cannot touch it
        send(adminA, "PUT", "/api/users/" + cashId + "/enabled/false", null, 200);
        send(cashA, "GET", "/api/members", null, 401);
        send(adminA, "PUT", "/api/users/" + cashId + "/enabled/true", null, 200);
        send(adminA, "PUT", "/api/users/" + cashId + "/password", "{\"password\":\"cash-pass-2\"}", 200);
        send(cashA, "GET", "/api/members", null, 401);
        send(httpBasic("cashA", "cash-pass-2"), "GET", "/api/members", null, 200);
        send(manager, "POST", "/api/users", cashBody.replace("cashA", "cashM").replace("CASHIER", "TREASURER"), 400);       // the manager must say which branch
        JsonNode st = send(adminA, "GET", "/api/reports/member-statement?memberId=" + memA, null, 200);
        assertEquals(1, st.get("sections").size());
        assertEquals(6, st.get("totals").size());      // savings, savings interest, shares, membership, loan, loan interest owed
        // PDF export opens as a real PDF and is scoped the same way
        for (String kind : new String[] {"trial-balance", "income-expenditure", "balance-sheet", "member-statement?memberId=" + memA}) {
            String url = "/api/reports/" + kind.replaceFirst("\\?.*", "") + "/pdf" + (kind.contains("?") ? kind.substring(kind.indexOf('?')) : "");
            byte[] pdf = mvc.perform(get(url).with(adminA)).andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("application/pdf"))
                    .andReturn().getResponse().getContentAsByteArray();
            assertEquals("%PDF", new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII), kind);
        }
        mvc.perform(get("/api/reports/member-statement/pdf?memberId=" + memA).with(adminB)).andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/balance-sheet/pdf").with(manager)).andExpect(status().isOk());
        // an inactive posting account blocks the transaction, leaving no half-posted ledger lines
        long cashB = 0;
        for (JsonNode acc : send(adminB, "GET", "/api/accounts", null, 200)) if (acc.get("code").asText().equals("1100")) cashB = acc.get("id").asLong();
        int glBefore = send(adminB, "GET", "/api/general-ledger", null, 200).size();
        send(adminB, "PUT", "/api/accounts/" + cashB + "/active/false", null, 403);   // a branch admin cannot
        send(manager, "PUT", "/api/accounts/" + cashB + "/active/false", null, 200);
        send(adminB, "POST", "/api/transactions", "{\"memberId\":" + memB + ",\"type\":\"SAVINGS_DEPOSIT\",\"amount\":50}", 422);
        assertEquals(glBefore, send(adminB, "GET", "/api/general-ledger", null, 200).size());
        send(manager, "PUT", "/api/accounts/" + cashB + "/active/true", null, 200);
        send(adminB, "POST", "/api/transactions", "{\"memberId\":" + memB + ",\"type\":\"SAVINGS_DEPOSIT\",\"amount\":50}", 201);
        assertEquals(glBefore + 2, send(adminB, "GET", "/api/general-ledger", null, 200).size());

        // admin A's report: own branch only. savings 1100, shares 550, disbursed 1000, repaid 120, outstanding 1000+0 interest-120=880
        JsonNode repA = send(adminA, "GET", "/api/reports/summary", null, 200);
        assertEquals(1, repA.get("branches").size());
        JsonNode rowA = repA.get("branches").get(0);
        assertEquals("SAC001", rowA.get("branchCode").asText());
        assertEquals(0, rowA.get("savings").decimalValue().compareTo(new java.math.BigDecimal("1100")));
        assertEquals(0, rowA.get("shares").decimalValue().compareTo(new java.math.BigDecimal("550")));
        assertEquals(0, rowA.get("loanOutstanding").decimalValue().compareTo(new java.math.BigDecimal("880")));
        assertEquals(0, rowA.get("payrollCollected").decimalValue().compareTo(new java.math.BigDecimal("270")));

        // manager: both branches + group totals
        JsonNode all = send(manager, "GET", "/api/reports/summary", null, 200);
        assertEquals(2, all.get("branches").size());
        assertEquals(0, all.get("totals").get("savings").decimalValue().compareTo(new java.math.BigDecimal("1450")));
        assertEquals(2, all.get("totals").get("members").asInt());

        // ---- regular deposit: one payment split across savings, shares and loan repayment
        long comsvId = comsv.get("id").asLong();
        String base = "\"memberId\":" + memA + ",\"date\":\"2026-09-30\",\"reference\":\"RCT-001\",\"notes\":\"monthly\",\"receivingAccountId\":" + bankA;
        // the split must add up to the total
        send(adminA, "POST", "/api/deposits", "{" + base + ",\"totalAmount\":500,\"savings\":200,\"shares\":100,\"loanRepayment\":100}", 400);
        // an unknown product is refused
        send(adminA, "POST", "/api/deposits", "{" + base + ",\"totalAmount\":200,\"savings\":200,\"savingsProductId\":999999}", 404);
        JsonNode dep = send(adminA, "POST", "/api/deposits", "{" + base + ",\"totalAmount\":500,\"savings\":200,\"shares\":100,\"loanRepayment\":200,\"savingsProductId\":" + comsvId + "}", 201);
        assertEquals(3, dep.get("entries").asInt());
        // savings went to the chosen product, shares to the default, and the loan was repaid by 200 (auto-picked: only active loan)
        JsonNode ml2 = send(adminA, "GET", "/api/member-ledger?memberId=" + memA, null, 200);
        java.util.Map<String, java.math.BigDecimal> bal = new java.util.HashMap<>();
        ml2.get("balances").forEach(x -> bal.put(x.get("productCode").asText(), x.get("balance").decimalValue()));
        assertEquals(0, bal.get("COMSV").compareTo(new java.math.BigDecimal("200")));
        assertEquals(0, bal.get("SHR01").compareTo(new java.math.BigDecimal("650")));
        assertEquals(0, bal.get("LON01").compareTo(new java.math.BigDecimal("680")));   // principal 1000 - repaid 320; interest is added by the monthly runs
        assertEquals(0, send(adminA, "GET", "/api/loans/" + loan, null, 200).get("remaining").decimalValue().compareTo(new java.math.BigDecimal("800")));   // still to pay in total: 1120 - 320
        assertEquals(0, send(adminA, "GET", "/api/loans/" + loan, null, 200).get("outstanding").decimalValue().compareTo(new java.math.BigDecimal("680")));   // what the books show
        // the money was debited to the chosen receiving account on the chosen date
        boolean bankDebited = false;
        for (JsonNode g : send(adminA, "GET", "/api/general-ledger?from=2026-09-30&to=2026-09-30", null, 200)) {
            if (g.get("accountCode").asText().equals("1200") && g.get("debit").decimalValue().signum() > 0) bankDebited = true;
        }
        org.junit.jupiter.api.Assertions.assertTrue(bankDebited);
        int bankDebitLines = 0;
        java.math.BigDecimal bankDebitTotal = java.math.BigDecimal.ZERO;
        for (JsonNode g : send(adminA, "GET", "/api/general-ledger?from=2026-09-30&to=2026-09-30", null, 200)) {
            if (g.get("accountCode").asText().equals("1200") && g.get("debit").decimalValue().signum() > 0) {
                bankDebitLines++;
                bankDebitTotal = bankDebitTotal.add(g.get("debit").decimalValue());
            }
        }
        assertEquals(1, bankDebitLines, "the bank is debited once for the whole deposit, not once per part");
        assertEquals(0, bankDebitTotal.compareTo(new java.math.BigDecimal("500")));
        // history lists the three entries; payroll deductions are not in it
        JsonNode hist = send(adminA, "GET", "/api/deposits?from=2026-09-30&to=2026-09-30", null, 200);
        assertEquals(3, hist.size());
        assertEquals("RCT-001", hist.get(0).get("reference").asText());
        // scoping and role rules
        send(adminB, "POST", "/api/deposits", "{" + base + ",\"totalAmount\":10,\"savings\":10}", 403);
        send(manager, "POST", "/api/deposits", "{" + base + ",\"totalAmount\":10,\"savings\":10}", 403);
        assertEquals(0, send(adminB, "GET", "/api/deposits?from=2026-09-30&to=2026-09-30", null, 200).size());
        assertEquals(3, send(manager, "GET", "/api/deposits?branchId=" + a + "&from=2026-09-30&to=2026-09-30", null, 200).size());
        // no active loan: repayment is refused
        send(adminB, "POST", "/api/deposits", "{\"memberId\":" + memB + ",\"receivingAccountId\":" + cashB + ",\"totalAmount\":10,\"loanRepayment\":10}", 400);
        // everything still balances
        JsonNode tb2 = send(manager, "GET", "/api/reports/trial-balance", null, 200);
        assertEquals("Balanced", tb2.get("totals").get(2).get("value").asText());

        // ================= view / edit / delete of posted transactions =================
        long depSav = 0, depShr = 0, depRep = 0;
        for (JsonNode h : send(adminA, "GET", "/api/deposits?from=2026-09-30&to=2026-09-30", null, 200)) {
            switch (h.get("type").asText()) {
                case "SAVINGS_DEPOSIT" -> depSav = h.get("id").asLong();
                case "SHARE_PURCHASE" -> depShr = h.get("id").asLong();
                default -> depRep = h.get("id").asLong();
            }
        }
        // view: the entry with its member-ledger line and balanced general-ledger lines
        JsonNode v1 = send(adminA, "GET", "/api/entries/" + depSav, null, 200);
        assertEquals(3, v1.get("memberLedger").size());   // savings, shares and loan repayment parts of the one deposit
        java.util.Set<String> mlProducts = new java.util.HashSet<>();
        v1.get("memberLedger").forEach(x -> mlProducts.add(x.get("productCode").asText()));
        assertEquals(java.util.Set.of("COMSV", "SHR01", "LON01"), mlProducts);
        assertEquals(4, v1.get("generalLedger").size());   // savings, shares, loan (no interest has fallen due yet) + ONE bank debit
        assertEquals(4, send(adminA, "GET", "/api/entries/" + depRep, null, 200).get("generalLedger").size());
        send(adminB, "GET", "/api/entries/" + depSav, null, 403);
        assertEquals(200, mvc.perform(get("/api/entries/" + depSav).with(manager)).andReturn().getResponse().getStatus());

        // edit a savings deposit: amount up, the ledgers are re-posted and still balance
        JsonNode e1 = send(adminA, "PUT", "/api/entries/" + depSav, "{\"amount\":250,\"date\":\"2026-09-29\",\"reference\":\"RCT-001A\",\"notes\":\"fixed\",\"receivingAccountId\":" + mobileA + ",\"productId\":" + comsvId + "}", 200);
        assertEquals("2026-09-29", e1.get("date").asText());
        assertEquals("1300 Mobile Money", e1.get("receivingAccount").asText());
        assertEquals(3, e1.get("memberLedger").size());
        assertEquals(0, bd("250").compareTo(e1.get("memberLedger").get(0).get("credit").decimalValue()));
        assertBalanced(adminA);
        // edit a loan repayment up, then try to over-repay
        send(adminA, "PUT", "/api/entries/" + depRep, "{\"amount\":300}", 200);
        assertEquals(0, bd("700").compareTo(send(adminA, "GET", "/api/loans/" + loan, null, 200).get("remaining").decimalValue()));
        send(adminA, "PUT", "/api/entries/" + depRep, "{\"amount\":5000}", 422);
        assertEquals(0, bd("700").compareTo(send(adminA, "GET", "/api/loans/" + loan, null, 200).get("remaining").decimalValue()));
        assertBalanced(adminA);
        // role / scope
        send(adminB, "PUT", "/api/entries/" + depSav, "{\"amount\":1}", 403);
        send(adminB, "DELETE", "/api/entries/" + depSav, null, 403);
        send(manager, "DELETE", "/api/entries/" + depSav, null, 403);
        // delete the COMSV deposit: its lines go with it
        send(adminA, "DELETE", "/api/entries/" + depSav, null, 204);
        send(adminA, "GET", "/api/entries/" + depSav, null, 404);
        boolean comsvLeft = false;
        for (JsonNode pb : send(adminA, "GET", "/api/member-ledger?memberId=" + memA, null, 200).get("balances")) if (pb.get("productCode").asText().equals("COMSV")) comsvLeft = true;
        org.junit.jupiter.api.Assertions.assertFalse(comsvLeft);
        assertBalanced(adminA);

        // payroll entries are locked on their own; the batch can be viewed, edited and deleted as a whole
        long batchId = send(adminA, "GET", "/api/payroll", null, 200).get(0).get("id").asLong();
        JsonNode pv = send(adminA, "GET", "/api/payroll/" + batchId, null, 200);
        assertEquals(3, pv.get("entries").size());
        long payrollEntry = pv.get("entries").get(0).get("id").asLong();
        org.junit.jupiter.api.Assertions.assertNotNull(send(adminA, "GET", "/api/entries/" + payrollEntry, null, 200).get("lockedReason"));
        send(adminA, "PUT", "/api/entries/" + payrollEntry, "{\"amount\":1}", 409);
        send(adminA, "DELETE", "/api/entries/" + payrollEntry, null, 409);
        JsonNode pe = send(adminA, "PUT", "/api/payroll/" + batchId, "{\"employer\":\"Acme Ltd\",\"period\":\"2026-09\",\"receivingAccountId\":" + bankA + ",\"date\":\"2026-09-27\"}", 200);
        assertEquals("Acme Ltd", pe.get("batch").get("employer").asText());
        assertEquals("2026-09-27", pe.get("entries").get(0).get("date").asText());
        assertEquals("1200 Bank Accounts", pe.get("batch").get("receivingAccount").asText());
        send(adminB, "GET", "/api/payroll/" + batchId, null, 403);
        send(adminB, "DELETE", "/api/payroll/" + batchId, null, 403);
        assertBalanced(adminA);
        send(adminA, "DELETE", "/api/payroll/" + batchId, null, 204);
        assertEquals(0, send(adminA, "GET", "/api/payroll", null, 200).size());
        assertBalanced(adminA);

        // balances may not go negative: after a withdrawal the original deposit cannot be deleted
        long origDeposit = 0;
        for (JsonNode x : send(adminA, "GET", "/api/transactions?memberId=" + memA + "&type=SAVINGS_DEPOSIT", null, 200)) if (x.get("amount").decimalValue().compareTo(bd("1000")) == 0) origDeposit = x.get("id").asLong();
        send(adminA, "POST", "/api/transactions", "{\"memberId\":" + memA + ",\"type\":\"SAVINGS_WITHDRAWAL\",\"amount\":900}", 201);
        send(adminA, "DELETE", "/api/entries/" + origDeposit, null, 422);
        send(adminA, "PUT", "/api/entries/" + origDeposit, "{\"amount\":500}", 422);
        assertEquals(0, bd("1000").compareTo(send(adminA, "GET", "/api/entries/" + origDeposit, null, 200).get("amount").decimalValue()));   // untouched
        assertBalanced(adminA);

        // loans: one with repayments cannot be deleted until the repayment is; pending loans can be edited and deleted
        send(adminA, "DELETE", "/api/loans/" + loan, null, 409);
        send(adminA, "DELETE", "/api/entries/" + depRep, null, 204);
        assertEquals("ACTIVE", send(adminA, "GET", "/api/loans/" + loan, null, 200).get("status").asText());
        assertEquals(1, send(adminA, "GET", "/api/loans/" + loan + "/view", null, 200).get("entries").size());   // just the payout now
        send(adminA, "PUT", "/api/loans/" + loan, "{\"principal\":1,\"annualRatePercent\":1,\"termMonths\":1}", 409);        // active: not editable
        send(adminA, "DELETE", "/api/loans/" + loan, null, 204);
        assertEquals(0, jdbc.queryForObject("select count(*) from loan_schedule where loan_id = ?", Integer.class, loan));   // its installments go with it
        assertEquals(0, send(adminA, "GET", "/api/loans", null, 200).size());
        assertBalanced(adminA);
        long loanB = send(adminB, "POST", "/api/loans", "{\"memberId\":" + memB + ",\"principal\":1000,\"annualRatePercent\":12,\"termMonths\":12,\"interestMethod\":\"STRAIGHT_LINE\"}", 201).get("id").asLong();
        JsonNode editedLoan = send(adminB, "PUT", "/api/loans/" + loanB, "{\"principal\":2000,\"annualRatePercent\":10,\"termMonths\":6,\"applicationFee\":25,\"loanDate\":\"2026-09-01\"}", 200);
        assertEquals(0, bd("2100").compareTo(editedLoan.get("totalDue").decimalValue()));
        assertEquals("2026-09-01", editedLoan.get("appliedOn").asText());
        send(adminA, "PUT", "/api/loans/" + loanB, "{\"principal\":1,\"annualRatePercent\":1,\"termMonths\":1}", 403);
        send(adminB, "DELETE", "/api/loans/" + loanB, null, 204);

        // members, accounts and products: edit / delete rules
        long memTmp = send(adminB, "POST", "/api/members", "{\"memberNo\":\"M9\",\"fullName\":\"Temp\"}", 201).get("id").asLong();
        assertEquals("Temp Renamed", send(adminB, "PUT", "/api/members/" + memTmp, "{\"memberNo\":\"M9\",\"fullName\":\"Temp Renamed\"}", 200).get("fullName").asText());
        send(adminB, "PUT", "/api/members/" + memTmp, "{\"memberNo\":\"M1\",\"fullName\":\"Dup\"}", 409);
        send(adminB, "DELETE", "/api/members/" + memTmp, null, 204);
        send(adminB, "DELETE", "/api/members/" + memB, null, 409);            // has transactions
        send(manager, "DELETE", "/api/accounts/" + cash, null, 204);          // Petty Cash was never used
        send(manager, "DELETE", "/api/accounts/" + savAcc, null, 409);        // 2100 is in use
        send(adminA, "DELETE", "/api/products/" + comsvId, null, 204);        // COMSV has no entries left
        long sav01 = 0;
        for (JsonNode p : send(adminA, "GET", "/api/products", null, 200)) if (p.get("productCode").asText().equals("SAV01")) sav01 = p.get("id").asLong();
        send(adminA, "DELETE", "/api/products/" + sav01, null, 409);

        // audit trail: every edit and delete is recorded, scoped by branch
        JsonNode auditA = send(adminA, "GET", "/api/audit", null, 200);
        org.junit.jupiter.api.Assertions.assertTrue(auditA.size() >= 8);
        boolean sawEdit = false, sawDelete = false;
        for (JsonNode a1 : auditA) { sawEdit |= a1.get("action").asText().equals("EDIT"); sawDelete |= a1.get("action").asText().equals("DELETE"); }
        org.junit.jupiter.api.Assertions.assertTrue(sawEdit && sawDelete);
        for (JsonNode a1 : send(adminB, "GET", "/api/audit", null, 200)) assertEquals("SAC002", a1.get("branchCode").asText());
        org.junit.jupiter.api.Assertions.assertTrue(send(manager, "GET", "/api/audit", null, 200).size() > auditA.size());
        assertBalanced(manager);

        // ================= manual multi-line journals =================
        java.util.Map<String, Long> acA = new java.util.HashMap<>(), acB = new java.util.HashMap<>();
        for (JsonNode x : send(adminA, "GET", "/api/accounts", null, 200)) acA.put(x.get("code").asText(), x.get("id").asLong());
        for (JsonNode x : send(adminB, "GET", "/api/accounts", null, 200)) acB.put(x.get("code").asText(), x.get("id").asLong());
        acA.remove(momoCode);          // the one account that differs: branch A's own mobile money number (set up above)
        assertEquals(acA, acB, "both branches see the very same shared chart");
        java.util.function.BiFunction<Long, String, String> line = (acct, side) ->
                "{\"accountId\":" + acct + ",\"description\":\"line\"," + side + "}";
        String simple = "{\"date\":\"2026-10-01\",\"reference\":\"BV-1\",\"narration\":\"Office rent\",\"lines\":["
                + line.apply(acA.get("5200"), "\"debit\":300") + "," + line.apply(acA.get("1100"), "\"credit\":300") + "]}";
        JsonNode j1 = send(adminA, "POST", "/api/journals", simple, 201);
        assertEquals("JV0001", j1.get("journalNo").asText());
        // a compound journal: two debits, one credit
        String multi = "{\"date\":\"2026-10-02\",\"narration\":\"Stationery and bank charges\",\"lines\":["
                + line.apply(acA.get("5300"), "\"debit\":100") + "," + line.apply(acA.get("5200"), "\"debit\":50.50") + ","
                + line.apply(acA.get("1200"), "\"credit\":150.50") + "]}";
        JsonNode j2 = send(adminA, "POST", "/api/journals", multi, 201);
        assertEquals("JV0002", j2.get("journalNo").asText());
        assertEquals(3, j2.get("lines").size());
        assertEquals(0, bd("150.50").compareTo(j2.get("totalAmount").decimalValue()));

        // rules: balance, two lines, one side per line, protected accounts
        send(adminA, "POST", "/api/journals", "{\"lines\":[" + line.apply(acA.get("5200"), "\"debit\":100") + "," + line.apply(acA.get("1100"), "\"credit\":90") + "]}", 400);
        send(adminA, "POST", "/api/journals", "{\"lines\":[" + line.apply(acA.get("5200"), "\"debit\":100") + "]}", 400);
        send(adminA, "POST", "/api/journals", "{\"lines\":[" + line.apply(acA.get("5200"), "\"debit\":100,\"credit\":100") + "," + line.apply(acA.get("1100"), "\"credit\":100") + "]}", 400);
        send(adminA, "POST", "/api/journals", "{\"lines\":[" + line.apply(acA.get("5200"), "\"debit\":0") + "," + line.apply(acA.get("1100"), "\"credit\":0") + "]}", 400);
        send(adminA, "POST", "/api/journals", "{\"lines\":[" + line.apply(acA.get("2100"), "\"debit\":100") + "," + line.apply(acA.get("1100"), "\"credit\":100") + "]}", 422);   // member savings
        send(adminA, "POST", "/api/journals", "{\"lines\":[" + line.apply(acA.get("1400"), "\"debit\":100") + "," + line.apply(acA.get("1100"), "\"credit\":100") + "]}", 422);   // member loans
        send(manager, "POST", "/api/journals", simple, 403);
        assertEquals(2, send(adminA, "GET", "/api/journals", null, 200).size());     // the refused ones left nothing behind
        org.junit.jupiter.api.Assertions.assertTrue(send(adminA, "GET", "/api/journals/blocked-accounts", null, 200).toString().contains(String.valueOf(acA.get("2100"))));

        // the journals flow into the reports, which still balance
        assertBalanced(adminA);
        assertEquals("450.50", send(adminA, "GET", "/api/reports/income-expenditure?from=2026-01-01", null, 200).get("totals").get(1).get("value").asText());   // 300 + 100 + 50.50 of expense
        assertBalanced(manager);
        boolean sawJournalLine = false;
        for (JsonNode g : send(adminA, "GET", "/api/general-ledger?from=2026-10-01&to=2026-10-02", null, 200)) {
            if (g.get("journalNo").asText().equals("JV0002")) sawJournalLine = true;
        }
        org.junit.jupiter.api.Assertions.assertTrue(sawJournalLine);

        // scope: other branch cannot see or change it; the manager can read it
        long j1id = j1.get("id").asLong();
        send(adminB, "GET", "/api/journals/" + j1id, null, 403);
        send(adminB, "PUT", "/api/journals/" + j1id, simple, 403);
        send(adminB, "DELETE", "/api/journals/" + j1id, null, 403);
        assertEquals(0, send(adminB, "GET", "/api/journals", null, 200).size());
        assertEquals(2, send(manager, "GET", "/api/journals?branchId=" + a, null, 200).size());
        send(manager, "DELETE", "/api/journals/" + j1id, null, 403);

        // edit re-posts the lines; delete removes them; both are audited
        String edited = "{\"date\":\"2026-10-01\",\"reference\":\"BV-1\",\"narration\":\"Office rent (corrected)\",\"lines\":["
                + line.apply(acA.get("5200"), "\"debit\":400") + "," + line.apply(acA.get("1100"), "\"credit\":400") + "]}";
        JsonNode j1b = send(adminA, "PUT", "/api/journals/" + j1id, edited, 200);
        assertEquals(0, bd("400").compareTo(j1b.get("totalAmount").decimalValue()));
        assertEquals("JV0001", j1b.get("journalNo").asText());
        assertEquals("550.50", send(adminA, "GET", "/api/reports/income-expenditure?from=2026-01-01", null, 200).get("totals").get(1).get("value").asText());
        send(adminA, "PUT", "/api/journals/" + j1id, "{\"lines\":[" + line.apply(acA.get("5200"), "\"debit\":5") + "," + line.apply(acA.get("1100"), "\"credit\":6") + "]}", 400);
        assertEquals("550.50", send(adminA, "GET", "/api/reports/income-expenditure?from=2026-01-01", null, 200).get("totals").get(1).get("value").asText());   // failed edit changed nothing
        send(adminA, "DELETE", "/api/journals/" + j1id, null, 204);
        send(adminA, "DELETE", "/api/journals/" + j2.get("id").asLong(), null, 204);
        assertEquals(0, send(adminA, "GET", "/api/journals", null, 200).size());
        assertEquals("0.00", send(adminA, "GET", "/api/reports/income-expenditure?from=2026-01-01", null, 200).get("totals").get(1).get("value").asText());
        assertBalanced(adminA);
        boolean auditedJournal = false;
        for (JsonNode a1 : send(adminA, "GET", "/api/audit", null, 200)) auditedJournal |= a1.get("entityType").asText().equals("Journal");
        org.junit.jupiter.api.Assertions.assertTrue(auditedJournal);
        // a new journal after deletes still gets a fresh, unique number
        assertEquals("JV0001", send(adminA, "POST", "/api/journals", simple, 201).get("journalNo").asText());

        // ================= earlier deposits (separate parts) are joined into one bank debit =================
        send(adminB, "POST", "/api/transactions", "{\"memberId\":" + memB + ",\"type\":\"SAVINGS_DEPOSIT\",\"amount\":10,\"date\":\"2026-09-15\",\"reference\":\"OLD-1\"}", 201);
        send(adminB, "POST", "/api/transactions", "{\"memberId\":" + memB + ",\"type\":\"SHARE_PURCHASE\",\"amount\":20,\"date\":\"2026-09-15\",\"reference\":\"OLD-1\"}", 201);
        int before = 0;
        for (JsonNode g : send(adminB, "GET", "/api/general-ledger?from=2026-09-15&to=2026-09-15", null, 200)) if (g.get("accountCode").asText().equals("1100") && g.get("debit").decimalValue().signum() > 0) before++;
        assertEquals(2, before);
        bootstrap.run(null);
        int after = 0;
        java.math.BigDecimal cashIn = java.math.BigDecimal.ZERO;
        for (JsonNode g : send(adminB, "GET", "/api/general-ledger?from=2026-09-15&to=2026-09-15", null, 200)) {
            if (g.get("accountCode").asText().equals("1100") && g.get("debit").decimalValue().signum() > 0) { after++; cashIn = cashIn.add(g.get("debit").decimalValue()); }
        }
        assertEquals(1, after);
        assertEquals(0, cashIn.compareTo(new java.math.BigDecimal("30")));
        assertBalanced(adminB);
        assertBalanced(adminA);
        assertBalanced(manager);

        // ================= reducing-balance loans and the monthly repayment schedule =================
        // preview: 200,000 at 12% a year over 12 months is the textbook 17,769.76 a month
        JsonNode prev = send(adminB, "GET", "/api/loans/preview?principal=200000&annualRatePercent=12&termMonths=12", null, 200);
        assertEquals(0, bd("17769.76").compareTo(prev.get("installment").decimalValue()));
        org.junit.jupiter.api.Assertions.assertTrue(prev.get("totalInterest").decimalValue().compareTo(bd("13237")) >= 0
                && prev.get("totalInterest").decimalValue().compareTo(bd("13238")) < 0, "reducing interest is far below the flat 24,000");
        JsonNode flat = send(adminB, "GET", "/api/loans/preview?principal=200000&annualRatePercent=12&termMonths=12&method=STRAIGHT_LINE", null, 200);
        assertEquals(0, bd("24000").compareTo(flat.get("totalInterest").decimalValue()));
        send(adminB, "GET", "/api/loans/preview?principal=0&annualRatePercent=12&termMonths=12", null, 400);
        send(adminB, "GET", "/api/loans/preview?principal=1000&annualRatePercent=12&termMonths=0", null, 400);

        // a new loan is reducing balance by default; its totals come from the schedule
        long rb = send(adminB, "POST", "/api/loans", "{\"memberId\":" + memB + ",\"principal\":200000,\"annualRatePercent\":12,\"termMonths\":12,\"loanDate\":\"2025-01-10\"}", 201).get("id").asLong();
        JsonNode rbLoan = send(adminB, "GET", "/api/loans/" + rb, null, 200);
        assertEquals("REDUCING_BALANCE", rbLoan.get("interestMethod").asText());
        assertEquals(0, bd("17769.76").compareTo(rbLoan.get("installment").decimalValue()));
        // before payout the schedule is a projection: no payments, nothing overdue-paid
        JsonNode proj = send(adminB, "GET", "/api/loans/" + rb + "/schedule", null, 200);
        org.junit.jupiter.api.Assertions.assertNotNull(proj.get("note"));
        send(adminB, "POST", "/api/loans/" + rb + "/disburse", "{\"date\":\"2025-01-10\"}", 200);

        JsonNode sch = send(adminB, "GET", "/api/loans/" + rb + "/schedule", null, 200);
        JsonNode rows = sch.get("sections").get(0).get("rows");
        assertEquals(12, rows.size());
        // month 1 interest is 1% of 200,000; every month charges interest on the balance left; the last month clears it
        assertEquals("2,000.00", rows.get(0).get(4).asText());
        assertEquals("200,000.00", rows.get(0).get(2).asText());
        assertEquals("10 Feb 2025", rows.get(0).get(1).asText());
        assertEquals("10 Jan 2026", rows.get(11).get(1).asText());
        assertEquals("0.00", rows.get(11).get(6).asText());
        java.math.BigDecimal sumPrincipal = bd("0"), sumInterest = bd("0"), prevInterest = null;
        for (JsonNode row : rows) {
            sumPrincipal = sumPrincipal.add(bd(row.get(3).asText().replace(",", "")));
            sumInterest = sumInterest.add(bd(row.get(4).asText().replace(",", "")));
            java.math.BigDecimal interest = bd(row.get(4).asText().replace(",", ""));
            if (prevInterest != null) org.junit.jupiter.api.Assertions.assertTrue(interest.compareTo(prevInterest) < 0, "interest falls as the balance falls");
            prevInterest = interest;
        }
        assertEquals(0, bd("200000").compareTo(sumPrincipal));
        assertEquals(0, sumInterest.compareTo(rbLoan.get("totalDue").decimalValue().subtract(bd("200000"))));
        assertEquals(0, bd(sch.get("totals").get(2).get("value").asText().replace(",", "")).compareTo(rbLoan.get("totalDue").decimalValue()));
        // nothing paid yet: every installment is overdue (the dates are in the past)
        for (JsonNode row : rows) assertEquals("Overdue", row.get(8).asText());

        // pay one installment and a half: installment 1 is Paid, 2 is part paid, the rest overdue
        send(adminB, "POST", "/api/loans/" + rb + "/repayments", "{\"amount\":17769.76,\"date\":\"2025-02-10\"}", 201);
        send(adminB, "POST", "/api/loans/" + rb + "/repayments", "{\"amount\":8000,\"date\":\"2025-02-20\"}", 201);
        JsonNode rows2 = send(adminB, "GET", "/api/loans/" + rb + "/schedule", null, 200).get("sections").get(0).get("rows");
        assertEquals("Paid", rows2.get(0).get(8).asText());
        assertEquals("Part paid - overdue", rows2.get(1).get(8).asText());
        assertEquals("8,000.00", rows2.get(1).get(7).asText());
        assertEquals("Overdue", rows2.get(2).get(8).asText());
        // deleting the repayment changes the statuses back, because the schedule is worked out from the repayments
        long firstRepayment = 0;
        for (JsonNode e : send(adminB, "GET", "/api/loans/" + rb + "/view", null, 200).get("entries")) if (e.get("type").asText().equals("LOAN_REPAYMENT") && e.get("amount").decimalValue().compareTo(bd("8000")) == 0) firstRepayment = e.get("id").asLong();
        send(adminB, "DELETE", "/api/entries/" + firstRepayment, null, 204);
        assertEquals("Overdue", send(adminB, "GET", "/api/loans/" + rb + "/schedule", null, 200).get("sections").get(0).get("rows").get(1).get(8).asText());

        // the schedule is stored in the loan_schedule table
        assertEquals(12, jdbc.queryForObject("select count(*) from loan_schedule where loan_id = ?", Integer.class, rb));
        assertEquals(0, bd("200000").compareTo(jdbc.queryForObject("select sum(principal_due) from loan_schedule where loan_id = ?", java.math.BigDecimal.class, rb)));
        assertEquals(0, jdbc.queryForObject("select count(*) from loan_schedule where loan_id = ? and branch_code <> 'SAC002'", Integer.class, rb));
        assertEquals(0, jdbc.queryForObject("select count(*) from loan_schedule where loan_id = ? and installment_due <> principal_due + interest_due", Integer.class, rb));
        // payments are matched to installments in the table: no. 1 is paid in full on the date of its repayment; the deleted 8,000 left no. 2 unpaid
        assertEquals(0, bd("17769.76").compareTo(jdbc.queryForObject("select paid_amount from loan_schedule where loan_id = ? and installment_no = 1", java.math.BigDecimal.class, rb)));
        assertEquals("2025-02-10", jdbc.queryForObject("select cast(paid_on as char(10)) from loan_schedule where loan_id = ? and installment_no = 1", String.class, rb));
        assertEquals(0, jdbc.queryForObject("select count(*) from loan_schedule where loan_id = ? and installment_no > 1 and (paid_amount > 0 or paid_on is not null)", Integer.class, rb));
        // arrears: all installments after the first are overdue, so arrears is everything still owed, and it has been late for a long time
        JsonNode arrearsDoc = send(adminB, "GET", "/api/loans/" + rb + "/schedule", null, 200);
        assertEquals("Arrears", arrearsDoc.get("totals").get(6).get("label").asText());
        assertEquals(0, bd(arrearsDoc.get("totals").get(6).get("value").asText().replace(",", ""))
                .compareTo(rbLoan.get("totalDue").decimalValue().subtract(bd("17769.76"))));
        org.junit.jupiter.api.Assertions.assertTrue(Integer.parseInt(arrearsDoc.get("totals").get(7).get("value").asText()) > 300, "late since Mar 2025");

        // ================= monthly interest computation: each calendar month, reducing balance on the actual unpaid principal =================
        // loan rb: 200,000 at 12% for 12 months, paid out 2025-01-10; installment 1 (17,769.76) was paid on its due date 2025-02-10.
        // Days are not counted: every month end the loan is still owed, it is charged a full month (1% of the principal owed then).
        java.util.function.BiFunction<String, Long, JsonNode> previewOf = (month, branch) -> {
            try { return send(adminB, "GET", "/api/interest-runs/preview?month=" + month, null, 200); } catch (Exception ex) { throw new RuntimeException(ex); }
        };
        JsonNode pvJan = previewOf.apply("2025-01", null);
        assertEquals(1, pvJan.get("lines").size());
        assertEquals(0, bd("2000.00").compareTo(pvJan.get("lines").get(0).get("interest").decimalValue()));
        assertEquals(0, bd("200000").compareTo(pvJan.get("lines").get(0).get("openingPrincipal").decimalValue()));
        assertEquals(0, bd("200000").compareTo(pvJan.get("lines").get(0).get("closingPrincipal").decimalValue()));
        send(adminB, "GET", "/api/interest-runs/preview?month=not-a-month", null, 400);
        send(adminB, "POST", "/api/interest-runs", "{\"month\":\"2999-01\"}", 400);                                   // the month has not ended
        send(manager, "POST", "/api/interest-runs", "{\"month\":\"2025-01\"}", 403);                                  // posting is for branch admins
        JsonNode runJan = send(adminB, "POST", "/api/interest-runs", "{\"month\":\"2025-01\"}", 201);
        assertEquals(0, bd("2000.00").compareTo(runJan.get("run").get("totalInterest").decimalValue()));
        send(adminB, "POST", "/api/interest-runs", "{\"month\":\"2025-01\"}", 400);                                   // every loan already charged for January
        assertEquals(0, previewOf.apply("2025-01", null).get("lines").size());
        assertEquals(1, previewOf.apply("2025-01", null).get("runsThisMonth").size());
        assertEquals("2,000.00", send(adminB, "GET", "/api/reports/income-expenditure?from=2025-01-01&to=2025-01-31", null, 200).get("totals").get(0).get("value").asText());

        // February: the 10 Feb repayment cut the principal to 184,230.24, so February is 1% of that
        JsonNode pvFeb = previewOf.apply("2025-02", null);
        assertEquals(0, bd("1842.30").compareTo(pvFeb.get("lines").get(0).get("interest").decimalValue()));
        assertEquals(0, bd("200000").compareTo(pvFeb.get("lines").get(0).get("openingPrincipal").decimalValue()));
        assertEquals(0, bd("184230.24").compareTo(pvFeb.get("lines").get(0).get("closingPrincipal").decimalValue()));   // after the 10 Feb repayment
        JsonNode runFeb = send(adminB, "POST", "/api/interest-runs", "{\"month\":\"2025-02\"}", 201);
        JsonNode rbNow = send(adminB, "GET", "/api/loans/" + rb, null, 200);
        assertEquals(0, bd("3842.30").compareTo(rbNow.get("interestCharged").decimalValue()));
        assertEquals(0, bd("186072.54").compareTo(rbNow.get("outstanding").decimalValue()));                         // 200,000 + 3,842.30 - 17,769.76
        assertBalanced(adminB);

        // the member paid nothing after February, so the principal stays 184,230.24 and interest is charged on all of it
        long[] later = new long[3];
        String[][] months = {{"2025-03", "1842.30"}, {"2025-04", "1842.30"}, {"2025-05", "1842.30"}};
        for (int i = 0; i < months.length; i++) {
            JsonNode monthView = previewOf.apply(months[i][0], null);
            assertEquals(0, bd("184230.24").compareTo(monthView.get("lines").get(0).get("openingPrincipal").decimalValue()), months[i][0]);
            assertEquals(0, bd(months[i][1]).compareTo(monthView.get("lines").get(0).get("interest").decimalValue()), months[i][0]);
            later[i] = send(adminB, "POST", "/api/interest-runs", "{\"month\":\"" + months[i][0] + "\"}", 201).get("run").get("id").asLong();
        }
        assertEquals("9,369.20", send(adminB, "GET", "/api/reports/income-expenditure?from=2025-01-01&to=2025-12-31", null, 200).get("totals").get(0).get("value").asText());
        assertBalanced(adminB);
        assertBalanced(manager);

        // scope: another branch cannot see or change them; the manager sees every branch
        assertEquals(0, send(adminA, "GET", "/api/interest-runs", null, 200).size());
        send(adminA, "GET", "/api/interest-runs/" + later[0], null, 403);
        send(adminA, "DELETE", "/api/interest-runs/" + later[0], null, 403);
        send(manager, "DELETE", "/api/interest-runs/" + later[0], null, 403);
        assertEquals(5, send(manager, "GET", "/api/interest-runs", null, 200).size());
        send(adminA, "GET", "/api/interest-runs/preview?month=2025-05&branchId=" + b, null, 403);

        // each interest entry belongs to its run and cannot be changed by itself
        long interestEntry = 0;
        for (JsonNode e : send(adminB, "GET", "/api/loans/" + rb + "/view", null, 200).get("entries")) if (e.get("type").asText().equals("LOAN_INTEREST")) interestEntry = e.get("id").asLong();
        org.junit.jupiter.api.Assertions.assertNotEquals(0L, interestEntry);
        send(adminB, "PUT", "/api/entries/" + interestEntry, "{\"amount\":1}", 409);
        send(adminB, "DELETE", "/api/entries/" + interestEntry, null, 409);

        // changing a repayment after posting is flagged: delete the February repayment and February to May would now be higher
        long feb10 = 0;
        for (JsonNode e : send(adminB, "GET", "/api/loans/" + rb + "/view", null, 200).get("entries")) if (e.get("type").asText().equals("LOAN_REPAYMENT")) feb10 = e.get("id").asLong();
        send(adminB, "DELETE", "/api/entries/" + feb10, null, 204);
        assertEquals(false, send(adminB, "GET", "/api/interest-runs/" + runJan.get("run").get("id").asLong(), null, 200).get("lines").get(0).get("changed").asBoolean());
        assertEquals(true, send(adminB, "GET", "/api/interest-runs/" + runFeb.get("run").get("id").asLong(), null, 200).get("lines").get(0).get("changed").asBoolean());
        assertEquals(true, send(adminB, "GET", "/api/interest-runs/" + later[2], null, 200).get("lines").get(0).get("changed").asBoolean());

        // deleting a run takes its entries away; a month can then be computed again
        for (long id : later) send(adminB, "DELETE", "/api/interest-runs/" + id, null, 204);
        send(adminB, "DELETE", "/api/interest-runs/" + runFeb.get("run").get("id").asLong(), null, 204);
        assertEquals("2,000.00", send(adminB, "GET", "/api/reports/income-expenditure?from=2025-01-01&to=2025-12-31", null, 200).get("totals").get(0).get("value").asText());
        send(adminB, "DELETE", "/api/interest-runs/" + runJan.get("run").get("id").asLong(), null, 204);
        assertEquals("0.00", send(adminB, "GET", "/api/reports/income-expenditure?from=2025-01-01&to=2025-12-31", null, 200).get("totals").get(0).get("value").asText());
        assertEquals(0, send(adminB, "GET", "/api/interest-runs", null, 200).size());
        assertEquals(1, previewOf.apply("2025-01", null).get("lines").size());                                       // January is open again
        assertEquals(0, bd("200000").compareTo(send(adminB, "GET", "/api/loans/" + rb, null, 200).get("outstanding").decimalValue()));   // the repayment was deleted above
        assertBalanced(adminB);

        // ---- a loan from before monthly interest: all its interest was booked as income at payout. It is corrected once.
        long lz = send(adminB, "POST", "/api/loans", "{\"memberId\":" + memB + ",\"principal\":100000,\"annualRatePercent\":12,\"termMonths\":6,\"loanDate\":\"2025-03-01\"}", 201).get("id").asLong();
        send(adminB, "POST", "/api/loans/" + lz + "/disburse", "{\"date\":\"2025-03-01\"}", 200);
        java.math.BigDecimal upfront = send(adminB, "GET", "/api/loans/preview?principal=100000&annualRatePercent=12&termMonths=6", null, 200).get("totalInterest").decimalValue();
        // recreate the old booking: Dr interest receivable / Cr interest income at payout, and the interest on the member's loan
        long payoutId = jdbc.queryForObject("select id from ledger_entries where loan_id = ? and type = 'LOAN_DISBURSEMENT'", Long.class, lz);
        long acct1500 = jdbc.queryForObject("select id from accounts where code = '1500'", Long.class);
        long acct4100 = jdbc.queryForObject("select id from accounts where code = '4100'", Long.class);
        String legacy = "insert into general_ledger (branch_id, branch_code, account_id, ledger_entry_id, member_id, product_code, posted_on, description, debit, credit, created_at) "
                + "select branch_id, (select code from branches where branches.id = ledger_entries.branch_id), cast(? as bigint), id, member_id, 'LIN01', posted_on, 'legacy interest at payout', cast(? as decimal(15,2)), cast(? as decimal(15,2)), current_timestamp from ledger_entries where id = ?";
        jdbc.update(legacy, acct1500, upfront, bd("0"), payoutId);
        jdbc.update(legacy, acct4100, bd("0"), upfront, payoutId);
        jdbc.update("update member_ledger set debit = debit + ? where ledger_entry_id = ?", upfront, payoutId);
        jdbc.update("update loans set interest_accrual = false where id = ?", lz);
        assertBalanced(adminB);
        assertEquals(0, upfront.compareTo(bd(send(adminB, "GET", "/api/reports/income-expenditure?from=2025-03-01&to=2025-03-31", null, 200)
                .get("totals").get(0).get("value").asText().replace(",", ""))));               // the old way: all of it counted as March income
        bootstrap.run(null);                                                                     // the start-up conversion
        // the correction is dated the day it is made (March stays as it was); from March to today the loan has earned nothing yet
        assertEquals(0, upfront.compareTo(bd(send(adminB, "GET", "/api/reports/income-expenditure?from=2025-03-01&to=2025-03-31", null, 200)
                .get("totals").get(0).get("value").asText().replace(",", ""))));
        assertEquals("0.00", send(adminB, "GET", "/api/reports/income-expenditure?from=2025-03-01", null, 200).get("totals").get(0).get("value").asText());
        assertEquals(1, (int) jdbc.queryForObject("select count(*) from ledger_entries where loan_id = ? and type = 'LOAN_INTEREST_REVERSAL'", Integer.class, lz));
        assertEquals(0, bd("100000").compareTo(send(adminB, "GET", "/api/loans/" + lz, null, 200).get("outstanding").decimalValue()));   // principal only, until the monthly runs
        assertBalanced(adminB);
        bootstrap.run(null);                                                                     // and only once
        assertEquals(1, (int) jdbc.queryForObject("select count(*) from ledger_entries where loan_id = ? and type = 'LOAN_INTEREST_REVERSAL'", Integer.class, lz));
        assertEquals(1, (int) jdbc.queryForObject("select count(*) from audit_log where entity_type = 'Loan' and entity_id = ? and username = 'system'", Integer.class, lz));
        send(adminB, "DELETE", "/api/loans/" + lz, null, 204);                                   // deleting the loan clears everything it posted
        assertBalanced(adminB);
        assertBalanced(manager);

        // the schedule exports as a PDF, scoped like everything else
        byte[] schedPdf = mvc.perform(get("/api/loans/" + rb + "/schedule/pdf").with(adminB)).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        assertEquals("%PDF", new String(schedPdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        send(adminA, "GET", "/api/loans/" + rb + "/schedule", null, 403);
        mvc.perform(get("/api/loans/" + rb + "/schedule/pdf").with(adminA)).andExpect(status().isForbidden());
        assertEquals(200, mvc.perform(get("/api/loans/" + rb + "/schedule").with(manager)).andReturn().getResponse().getStatus());

        // an older straight-line loan still gets an even monthly schedule that adds up
        JsonNode oldSched = send(adminB, "POST", "/api/loans", "{\"memberId\":" + memB + ",\"principal\":200000,\"annualRatePercent\":12,\"termMonths\":12,\"interestMethod\":\"STRAIGHT_LINE\"}", 201);
        JsonNode slRows = send(adminB, "GET", "/api/loans/" + oldSched.get("id").asLong() + "/schedule", null, 200).get("sections").get(0).get("rows");
        assertEquals("18,666.67", slRows.get(0).get(5).asText());
        assertEquals("2,000.00", slRows.get(0).get(4).asText());
        assertEquals("0.00", slRows.get(11).get(6).asText());
        send(adminB, "DELETE", "/api/loans/" + oldSched.get("id").asLong(), null, 204);

        // the books stay balanced and the loan lines carry the interest properly
        assertBalanced(adminB);
        assertBalanced(manager);

        // ================= branch_code is stored on every ledger row =================
        String bad = "branch_code is null or branch_code <> (select code from branches where branches.id = %s.branch_id)";
        Integer glRows = jdbc.queryForObject("select count(*) from general_ledger", Integer.class);
        Integer mlRows = jdbc.queryForObject("select count(*) from member_ledger", Integer.class);
        org.junit.jupiter.api.Assertions.assertTrue(glRows > 0 && mlRows > 0);
        assertEquals(0, jdbc.queryForObject("select count(*) from general_ledger where " + String.format(bad, "general_ledger"), Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from member_ledger where " + String.format(bad, "member_ledger"), Integer.class));
        // journal lines (no transaction) carry it too, and the screens' data shows it
        assertEquals(0, jdbc.queryForObject("select count(*) from general_ledger where journal_id is not null and branch_code <> 'SAC001'", Integer.class));
        for (JsonNode g : send(adminA, "GET", "/api/general-ledger", null, 200)) assertEquals("SAC001", g.get("branchCode").asText());
        for (JsonNode pb : send(adminA, "GET", "/api/member-ledger?memberId=" + memA, null, 200).get("lines")) assertEquals("SAC001", pb.get("branchCode").asText());

        // ================= every general-ledger line can be opened (its transaction or its journal) =================
        int opened = 0;
        for (JsonNode g : send(adminA, "GET", "/api/general-ledger", null, 200)) {
            boolean tx = g.hasNonNull("entryId"), jr = g.hasNonNull("journalId");
            org.junit.jupiter.api.Assertions.assertTrue(tx ^ jr, "a line belongs to a transaction or to a journal");
            send(adminA, "GET", tx ? "/api/entries/" + g.get("entryId").asLong() : "/api/journals/" + g.get("journalId").asLong(), null, 200);
            opened++;
        }
        org.junit.jupiter.api.Assertions.assertTrue(opened > 0);
        // running balance: the latest line of every account shows the same balance the trial balance reports
        java.util.Map<String, java.math.BigDecimal> tbNet = new java.util.HashMap<>();
        for (JsonNode row : send(adminA, "GET", "/api/reports/trial-balance", null, 200).get("sections").get(0).get("rows")) {
            String dr = row.get(3).asText().replace(",", ""), cr = row.get(4).asText().replace(",", "");
            tbNet.put(row.get(0).asText(), (dr.isEmpty() ? bd("0") : bd(dr)).subtract(cr.isEmpty() ? bd("0") : bd(cr)));
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JsonNode g : send(adminA, "GET", "/api/general-ledger", null, 200)) {
            if (!seen.add(g.get("accountCode").asText())) continue;              // newest line of this account comes first
            java.math.BigDecimal signed = g.get("balance").decimalValue().multiply(bd(g.get("balanceSide").asText().equals("Dr") ? "1" : "-1"));
            java.math.BigDecimal expected = tbNet.getOrDefault(g.get("accountCode").asText(), bd("0"));
            assertEquals(0, expected.compareTo(signed), "balance of " + g.get("accountCode").asText());
        }
        org.junit.jupiter.api.Assertions.assertFalse(seen.isEmpty());
        // balances do not depend on the dates or account filter that are shown
        JsonNode narrow = send(adminA, "GET", "/api/general-ledger?from=2999-01-01", null, 200);
        assertEquals(0, narrow.size());

        // the manager can pick a GL account: one code covers that account in every branch, or just the chosen branch
        JsonNode cashAll = send(manager, "GET", "/api/general-ledger?accountCode=1100", null, 200);
        org.junit.jupiter.api.Assertions.assertTrue(cashAll.size() > 0);
        java.util.Set<String> cashBranches = new java.util.HashSet<>();
        for (JsonNode g : cashAll) { assertEquals("1100", g.get("accountCode").asText()); cashBranches.add(g.get("branchCode").asText()); }
        org.junit.jupiter.api.Assertions.assertTrue(cashBranches.size() >= 2, "cash lines from more than one branch");
        for (JsonNode g : send(manager, "GET", "/api/general-ledger?accountCode=1100&branchId=" + b, null, 200)) {
            assertEquals("1100", g.get("accountCode").asText());
            assertEquals("SAC002", g.get("branchCode").asText());
        }
        for (JsonNode g : send(adminA, "GET", "/api/general-ledger?accountCode=1100", null, 200)) assertEquals("SAC001", g.get("branchCode").asText());
        assertEquals(0, send(manager, "GET", "/api/general-ledger?accountCode=NO-SUCH", null, 200).size());

        // the manager can open them too, but never another branch's lines through a branch admin
        for (JsonNode g : send(manager, "GET", "/api/general-ledger?branchId=" + b, null, 200)) {
            if (g.hasNonNull("entryId")) send(adminA, "GET", "/api/entries/" + g.get("entryId").asLong(), null, 403);
        }
    }
}
