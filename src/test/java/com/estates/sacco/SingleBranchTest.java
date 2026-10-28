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

/** The CPA Uganda set-up: one branch, created on the first start, looked after by its own administrator. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cpasingle;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "sacco.single-branch.enabled=true",
        "sacco.single-branch.code=CPA001",
        "sacco.single-branch.name=CPA Uganda",
        "sacco.single-branch.admin-username=admin",
        "sacco.single-branch.admin-password=admin-pass-1",
        "sacco.app-name=CPA Uganda",
        "sacco.bootstrap.manager.username=support",
        "sacco.bootstrap.manager.password=support-pass-1"})
@AutoConfigureMockMvc
class SingleBranchTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    RequestPostProcessor nobody = r -> r;
    RequestPostProcessor admin = httpBasic("admin", "admin-pass-1");
    RequestPostProcessor support = httpBasic("support", "support-pass-1");

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

    @Test
    void bundledFontsLoadBeforeSignIn() throws Exception {
        for (String font : new String[] {"google-sans-variable.woff2", "google-sans-italic-variable.woff2"}) {
            byte[] bytes = mvc.perform(get("/fonts/" + font)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            assertEquals("wOF2", new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        }
        // Publishing the font files does not publish the app's data.
        send(nobody, "GET", "/api/me", null, 401);
    }

    @Test
    void oneBranchInstallation() throws Exception {
        // the login page learns this is a one-branch installation and sees that one branch only
        JsonNode cfg = send(nobody, "GET", "/api/public/config", null, 200);
        assertEquals(true, cfg.get("singleBranch").asBoolean());
        assertEquals("CPA Uganda", cfg.get("appName").asText());
        JsonNode branches = send(nobody, "GET", "/api/public/branches", null, 200);
        assertEquals(1, branches.size());
        assertEquals("CPA Uganda", branches.get(0).get("name").asText());
        assertEquals("CPA001", branches.get(0).get("code").asText());

        // the administrator and the support login were created on the first start
        JsonNode me = send(admin, "GET", "/api/me", null, 200);
        assertEquals("BRANCH_ADMIN", me.get("role").asText());
        assertEquals("CPA Uganda", me.get("branchName").asText());
        assertEquals("MANAGER", send(support, "GET", "/api/me", null, 200).get("role").asText());
        send(httpBasic("admin", "wrong"), "GET", "/api/me", null, 401);

        // no manager here, so the administrator keeps the chart of accounts
        JsonNode acct = send(admin, "POST", "/api/accounts", "{\"code\":\"5600\",\"name\":\"Test expense\",\"type\":\"EXPENSE\"}", 201);
        send(admin, "PUT", "/api/accounts/" + acct.get("id").asLong(), "{\"name\":\"Renamed expense\"}", 200);
        send(admin, "DELETE", "/api/accounts/" + acct.get("id").asLong(), null, 204);
        send(admin, "POST", "/api/branches", "{\"code\":\"X\",\"name\":\"X\"}", 403);                                  // but cannot add branches

        // mobile money numbers: the administrator sets up two for their own branch
        JsonNode momo1 = send(admin, "POST", "/api/mobile-money", "{\"network\":\"MTN\",\"number\":\"0772000111\"}", 201);
        JsonNode momo2 = send(admin, "POST", "/api/mobile-money", "{\"network\":\"Airtel\",\"number\":\"0705000222\"}", 201);
        assertEquals(me.get("branchId").asLong(), momo1.get("branchId").asLong());
        send(admin, "POST", "/api/mobile-money", "{\"network\":\"MTN\",\"number\":\"0772000333\"}", 400);              // at most two
        send(admin, "PUT", "/api/mobile-money/" + momo2.get("id").asLong(), "{\"network\":\"Airtel Money\",\"number\":\"0705000222\"}", 200);
        send(admin, "DELETE", "/api/mobile-money/" + momo2.get("id").asLong(), null, 204);

        // staff are created by the administrator and cannot touch the chart or the numbers
        send(admin, "POST", "/api/users", "{\"username\":\"cash1\",\"password\":\"cash-pass-1\",\"fullName\":\"Cash One\",\"role\":\"CASHIER\"}", 201);
        RequestPostProcessor cashier = httpBasic("cash1", "cash-pass-1");
        send(cashier, "POST", "/api/accounts", "{\"code\":\"5601\",\"name\":\"Nope\",\"type\":\"EXPENSE\"}", 403);
        send(cashier, "POST", "/api/mobile-money", "{\"network\":\"MTN\",\"number\":\"0772000444\"}", 403);

        // the usual work runs on the one branch: a member, a deposit, balanced books
        long member = send(admin, "POST", "/api/members", "{\"memberNo\":\"M1\",\"fullName\":\"Grace Namuli\"}", 201).get("id").asLong();
        send(cashier, "POST", "/api/transactions", "{\"memberId\":" + member + ",\"type\":\"SAVINGS_DEPOSIT\",\"amount\":50000}", 201);
        JsonNode trial = send(admin, "GET", "/api/reports/trial-balance", null, 200);
        assertEquals("Balanced", trial.get("totals").get(2).get("value").asText());
        assertEquals("CPA Uganda", trial.get("branchLine").asText());                                                    // no Sacco ID on the reports
        assertEquals("CPA Uganda", send(admin, "GET", "/api/reports/member-statement?memberId=" + member, null, 200).get("branchLine").asText());
        send(support, "POST", "/api/transactions", "{\"memberId\":" + member + ",\"type\":\"SAVINGS_DEPOSIT\",\"amount\":1}", 403);   // support only looks

        // the dashboard: members, balances, cash held, twelve months of money in and out, the latest movements
        JsonNode dash = send(cashier, "GET", "/api/reports/dashboard", null, 200);
        assertEquals(1, dash.get("members").asInt());
        assertEquals(1, dash.get("savers").asInt());
        assertEquals(0, new java.math.BigDecimal("50000").compareTo(dash.get("savings").decimalValue()));
        assertEquals(0, new java.math.BigDecimal("50000").compareTo(dash.get("cashTotal").decimalValue()));
        assertEquals(12, dash.get("months").size());
        assertEquals(0, new java.math.BigDecimal("50000").compareTo(dash.get("months").get(11).get("moneyIn").decimalValue()));
        assertEquals("M1", dash.get("recent").get(0).get("memberNo").asText());
        assertEquals(true, dash.get("recent").get(0).get("moneyIn").asBoolean());
        assertEquals(1, send(support, "GET", "/api/reports/dashboard", null, 200).get("members").asInt());

        // the cashbook: the deposit came in as cash from the member; cash then banked by journal is a transfer, not new money
        JsonNode bookAccounts = send(cashier, "GET", "/api/reports/cashbook-accounts", null, 200);
        long cashId = 0, bankId = 0;
        for (JsonNode a : bookAccounts) {
            if (a.get("code").asText().equals("1100")) cashId = a.get("id").asLong();
            if (a.get("code").asText().equals("1200")) bankId = a.get("id").asLong();
        }
        send(admin, "POST", "/api/journals", "{\"reference\":\"BANKING-1\",\"narration\":\"Cash banked\",\"lines\":["
                + "{\"accountId\":" + bankId + ",\"debit\":20000},{\"accountId\":" + cashId + ",\"credit\":20000}]}", 201);
        JsonNode book = send(cashier, "GET", "/api/reports/cashbook-receipts", null, 200);
        assertEquals("70,000.00", book.get("totals").get(0).get("value").asText());
        assertEquals("20,000.00", book.get("totals").get(2).get("value").asText());                                   // transfers
        assertEquals("50,000.00", book.get("totals").get(3).get("value").asText());                                   // from outside
        JsonNode cashRow = book.get("sections").get(0).get("rows").get(0);
        assertEquals("M1 - Grace Namuli", cashRow.get(2).asText());
        JsonNode bankRow = book.get("sections").get(1).get("rows").get(0);
        assertEquals("BANKING-1", bankRow.get(1).asText());
        assertEquals(true, bankRow.get(3).asText().startsWith("Transfer from"));
        JsonNode bankOnly = send(cashier, "GET", "/api/reports/cashbook-receipts?accountId=" + bankId, null, 200);
        assertEquals(1, bankOnly.get("sections").size());

        // a cashier records other money received (New receipt: a two-line journal); member accounts cannot be used that way
        long interestIncome = send(admin, "POST", "/api/accounts", "{\"code\":\"4910\",\"name\":\"Bank interest income\",\"type\":\"INCOME\"}", 201).get("id").asLong();
        send(cashier, "POST", "/api/journals", "{\"reference\":\"RCT-7\",\"narration\":\"Receipt from Stanbic Bank\",\"lines\":["
                + "{\"accountId\":" + bankId + ",\"description\":\"Stanbic Bank\",\"debit\":3500},"
                + "{\"accountId\":" + interestIncome + ",\"description\":\"Stanbic Bank\",\"credit\":3500}]}", 201);
        JsonNode bank = send(cashier, "GET", "/api/reports/cashbook-receipts?accountId=" + bankId, null, 200);
        JsonNode interestRow = bank.get("sections").get(0).get("rows").get(1);
        assertEquals("RCT-7", interestRow.get(1).asText());
        assertEquals("Stanbic Bank", interestRow.get(2).asText());
        assertEquals("Bank interest income", interestRow.get(3).asText());
        assertEquals("3,500.00", interestRow.get(4).asText());
        assertEquals("53,500.00", send(cashier, "GET", "/api/reports/cashbook-receipts", null, 200).get("totals").get(3).get("value").asText());
        // payments: rent paid from cash (New payment) and the cash that went to the bank, which is a transfer out
        long rent = send(admin, "POST", "/api/accounts", "{\"code\":\"5610\",\"name\":\"Office rent\",\"type\":\"EXPENSE\"}", 201).get("id").asLong();
        send(cashier, "POST", "/api/journals", "{\"reference\":\"PV-3\",\"narration\":\"Payment to Landlord\",\"lines\":["
                + "{\"accountId\":" + rent + ",\"description\":\"Landlord\",\"debit\":15000},"
                + "{\"accountId\":" + cashId + ",\"description\":\"Landlord\",\"credit\":15000}]}", 201);
        JsonNode paid = send(cashier, "GET", "/api/reports/cashbook-payments", null, 200);
        assertEquals("Cashbook Payments", paid.get("title").asText());
        assertEquals("35,000.00", paid.get("totals").get(0).get("value").asText());
        assertEquals("20,000.00", paid.get("totals").get(2).get("value").asText());                                   // cash banked
        assertEquals("15,000.00", paid.get("totals").get(3).get("value").asText());                                   // paid to outside
        JsonNode cashOut = paid.get("sections").get(0).get("rows");
        assertEquals(true, cashOut.get(0).get(3).asText().startsWith("Transfer to"));
        assertEquals("PV-3", cashOut.get(1).get(1).asText());
        assertEquals("Landlord", cashOut.get(1).get(2).asText());
        assertEquals("Office rent", cashOut.get(1).get(3).asText());

        long savingsAccount = send(cashier, "GET", "/api/journals/blocked-accounts", null, 200).get(0).asLong();
        send(cashier, "POST", "/api/journals", "{\"lines\":[{\"accountId\":" + bankId + ",\"debit\":100},"
                + "{\"accountId\":" + savingsAccount + ",\"credit\":100}]}", 422);

        // messages to members (test mode: prepared and logged, nothing sent); only the administrator sends
        send(admin, "POST", "/api/members", "{\"memberNo\":\"M2\",\"fullName\":\"Okello,John\",\"phone\":\"0772 123 456 / 0701000000\"}", 201);
        send(admin, "POST", "/api/members", "{\"memberNo\":\"M3\",\"fullName\":\"Landline Only\",\"phone\":\"041466071\"}", 201);
        assertEquals(false, send(admin, "GET", "/api/messages/settings", null, 200).get("live").asBoolean());
        String msg = "{\"audience\":\"ALL\",\"text\":\"Dear {name} ({memberNo}), your savings are UGX {savings}.\"}";
        JsonNode pre = send(admin, "POST", "/api/messages/preview", msg, 200);
        assertEquals(3, pre.get("members").asInt());
        assertEquals(1, pre.get("withNumber").asInt());
        assertEquals("+256772123456", pre.get("samples").get(0).get("phone").asText());
        assertEquals("Dear Okello John (M2), your savings are UGX 0.", pre.get("samples").get(0).get("text").asText());
        assertEquals(2, pre.get("noNumber").size());
        send(cashier, "POST", "/api/messages", msg, 403);
        send(support, "POST", "/api/messages", msg, 403);
        send(cashier, "GET", "/api/messages", null, 403);
        send(admin, "POST", "/api/messages", "{\"audience\":\"CHOSEN\",\"memberIds\":[" + member + "],\"text\":\"Hello\"}", 400);   // Grace has no phone
        JsonNode batch = send(admin, "POST", "/api/messages", msg, 201);
        assertEquals(true, batch.get("testMode").asBoolean());
        JsonNode detail = null;
        for (int i = 0; i < 100; i++) {
            detail = send(support, "GET", "/api/messages/" + batch.get("id").asLong(), null, 200);
            if (detail.get("batch").get("status").asText().equals("DONE")) break;
            Thread.sleep(100);
        }
        assertEquals("DONE", detail.get("batch").get("status").asText());
        assertEquals(1, detail.get("batch").get("sent").asInt());
        assertEquals(2, detail.get("batch").get("failed").asInt());
        java.util.Map<String, String> result = new java.util.HashMap<>();
        for (JsonNode r : detail.get("recipients")) result.put(r.get("memberNo").asText(), r.get("status").asText());
        assertEquals(java.util.Map.of("M1", "NO_NUMBER", "M2", "TEST", "M3", "NO_NUMBER"), result);
        assertEquals(1, send(support, "GET", "/api/messages", null, 200).size());

        // member photos: any staff login adds one; it is stored small; the statement PDF carries it; support only looks
        String photoUrl = "/api/members/" + member + "/photo";
        mvc.perform(get(photoUrl).with(cashier)).andExpect(status().isNotFound());
        java.awt.image.BufferedImage big = new java.awt.image.BufferedImage(1600, 1200, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(big, "png", png);
        String upload = "{\"image\":\"data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png.toByteArray()) + "\"}";
        send(support, "PUT", photoUrl, upload, 403);
        send(cashier, "PUT", photoUrl, "{\"image\":\"" + java.util.Base64.getEncoder().encodeToString("not a picture".getBytes()) + "\"}", 400);
        send(cashier, "PUT", photoUrl, upload, 204);
        byte[] stored = mvc.perform(get(photoUrl).with(support)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        java.awt.image.BufferedImage back = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(stored));
        assertEquals(400, back.getWidth());                                                                           // 1600 x 1200 made 400 x 300
        assertEquals(300, back.getHeight());
        byte[] withPhoto = mvc.perform(get("/api/reports/member-statement/pdf?memberId=" + member).with(cashier)).andReturn().getResponse().getContentAsByteArray();
        java.util.regex.Pattern image = java.util.regex.Pattern.compile("/Subtype\\s*/Image");
        org.junit.jupiter.api.Assertions.assertTrue(image.matcher(new String(withPhoto, java.nio.charset.StandardCharsets.ISO_8859_1)).find(), "the PDF carries the photo");
        send(admin, "DELETE", photoUrl, null, 204);
        mvc.perform(get(photoUrl).with(cashier)).andExpect(status().isNotFound());
        byte[] withoutPhoto = mvc.perform(get("/api/reports/member-statement/pdf?memberId=" + member).with(cashier)).andReturn().getResponse().getContentAsByteArray();
        org.junit.jupiter.api.Assertions.assertFalse(image.matcher(new String(withoutPhoto, java.nio.charset.StandardCharsets.ISO_8859_1)).find());
        mvc.perform(get("/api/reports/cashbook-receipts/pdf").with(cashier)).andExpect(status().isOk());

        // everyone changes their own password: the current one must be right
        send(admin, "PUT", "/api/me/password", "{\"currentPassword\":\"not-it\",\"password\":\"brand-new-pass\"}", 400);
        send(admin, "PUT", "/api/me/password", "{\"currentPassword\":\"admin-pass-1\",\"password\":\"short\"}", 400);
        send(admin, "PUT", "/api/me/password", "{\"currentPassword\":\"admin-pass-1\",\"password\":\"brand-new-pass\"}", 200);
        send(admin, "GET", "/api/me", null, 401);
        send(httpBasic("admin", "brand-new-pass"), "GET", "/api/me", null, 200);
        send(cashier, "PUT", "/api/me/password", "{\"currentPassword\":\"cash-pass-1\",\"password\":\"cash-pass-2\"}", 200);
        send(httpBasic("cash1", "cash-pass-2"), "GET", "/api/me", null, 200);
    }
}
