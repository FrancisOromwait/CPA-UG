package com.estates.sacco;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.Period;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The CPA SACCO membership application form: saved with the member, read back, printed filled in. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:application;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "sacco.single-branch.enabled=true",
        "sacco.single-branch.code=CPA001",
        "sacco.single-branch.name=CPA Uganda",
        "sacco.single-branch.admin-username=admin",
        "sacco.single-branch.admin-password=admin-pass-1",
        "sacco.bootstrap.manager.username=support",
        "sacco.bootstrap.manager.password=support-pass-1"})
@AutoConfigureMockMvc
class MemberApplicationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

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
    void theFormIsKeptReadBackAndPrinted() throws Exception {
        long id = send(admin, "POST", "/api/members", "{\"memberNo\":\"CSM/0900\",\"fullName\":\"Nakato Sarah\",\"phone\":\"0772000111\","
                + "\"nationalId\":\"CF900\",\"employer\":\"KPMG\"}", 201).get("id").asLong();
        String url = "/api/members/" + id + "/application";

        // nothing filled in yet: the member record's own details only
        JsonNode empty = send(admin, "GET", url, null, 200);
        assertEquals("Nakato Sarah", empty.get("fullName").asText());
        assertEquals("CF900", empty.get("nationalId").asText());
        assertEquals(0, empty.get("beneficiaries").size());

        String body = "{\"fmNumber\":\"FM1234\",\"address\":\"Plot 5, Kampala\",\"email\":\"sarah@example.com\",\"dateOfBirth\":\"1990-05-20\","
                + "\"tin\":\"1000123\",\"maritalStatus\":\"married\",\"spouseName\":\"John\",\"spouseTel\":\"0700111222\",\"designation\":\"Accountant\","
                + "\"nextOfKin\":\"Mary 0701000000\",\"applicationDate\":\"2026-11-06\",\"checkedBy\":\"Peter\",\"approvedBy\":\"Grace\","
                + "\"beneficiaries\":[{\"name\":\"Ivan\",\"relationship\":\"Son\",\"contact\":\"0703\"},{\"name\":\"\",\"relationship\":\"\",\"contact\":\"\"}],"
                + "\"children\":[{\"name\":\"Ivan\",\"relationship\":\"Son\"}],"
                + "\"mother\":{\"name\":\"Rose\",\"relationship\":\"Mother\",\"alive\":true},\"father\":{\"name\":\"Paul\",\"relationship\":\"Father\",\"alive\":false}}";
        // the support login only looks
        send(support, "PUT", url, body, 403);
        JsonNode a = send(admin, "PUT", url, body, 200);
        assertEquals("FM1234", a.get("fmNumber").asText());
        assertEquals("MARRIED", a.get("maritalStatus").asText());
        assertEquals(Period.between(LocalDate.of(1990, 5, 20), LocalDate.now()).getYears(), a.get("age").asInt());
        assertEquals(1, a.get("beneficiaries").size());       // the empty line is left out
        assertEquals("Ivan", a.get("beneficiaries").get(0).get("name").asText());
        assertEquals(true, a.get("mother").get("alive").asBoolean());
        assertEquals(false, a.get("father").get("alive").asBoolean());
        assertEquals("Peter", send(support, "GET", url, null, 200).get("checkedBy").asText());

        // printed filled in, as a PDF
        byte[] pdf = mvc.perform(get(url + "/pdf").with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals("%PDF", new String(pdf, 0, 4));

        // saved again with fewer people: the list is replaced
        send(admin, "PUT", url, "{\"beneficiaries\":[]}", 200);
        assertEquals(0, send(admin, "GET", url, null, 200).get("beneficiaries").size());

        // a member with no history can still be deleted, form and all
        send(admin, "DELETE", "/api/members/" + id, null, 204);
    }
}
