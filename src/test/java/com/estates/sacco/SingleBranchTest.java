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
