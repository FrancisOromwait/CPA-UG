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

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The charges taken out of a loan when it is paid out: application fee, process fee, insurance and legal charges.
 * Each goes to its own chart account, and the bank is credited with the loan less all of them.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:loancharges;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "sacco.single-branch.enabled=true",
        "sacco.single-branch.code=CPA001",
        "sacco.single-branch.name=CPA Uganda",
        "sacco.single-branch.admin-username=admin",
        "sacco.single-branch.admin-password=admin-pass-1",
        "sacco.bootstrap.manager.username=support",
        "sacco.bootstrap.manager.password=support-pass-1",
        // the standard chart: application fee 4200 (default), process fee 4300, insurance 2200, legal charges 4400
        "sacco.accounts.loan-process-fee-income=4300",
        "sacco.accounts.loan-insurance=2200",
        "sacco.accounts.legal-charges-income=4400",
        "sacco.loan-interest.daily=true",
        "sacco.loan-interest.default-rate=15",
        "sacco.loan-fees.application=10000",
        "sacco.loan-fees.process-percent=1",
        "sacco.loan-fees.insurance-percent=0.6",
        "sacco.loan-fees.legal-charges=0"})
@AutoConfigureMockMvc
class LoanChargesTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    RequestPostProcessor admin = httpBasic("admin", "admin-pass-1");

    JsonNode send(String method, String url, String body, int expected) throws Exception {
        var req = switch (method) {
            case "POST" -> post(url);
            case "PUT" -> put(url);
            default -> get(url);
        };
        req.with(admin);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        String res = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return res.isBlank() ? null : json.readTree(res);
    }

    /** Debit and credit totals on a chart account, as {debit, credit}. */
    BigDecimal[] totals(String code) throws Exception {
        BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        for (JsonNode g : send("GET", "/api/general-ledger?accountCode=" + code, null, 200)) {
            debit = debit.add(g.get("debit").decimalValue());
            credit = credit.add(g.get("credit").decimalValue());
        }
        return new BigDecimal[] {debit, credit};
    }

    static void assertMoney(String what, String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), what + ": expected " + expected + " but was " + actual);
    }

    @Test
    void chargesComeOutOfThePayoutAndGoToTheirOwnAccounts() throws Exception {
        // the form starts with these
        JsonNode defaults = send("GET", "/api/loans/fee-defaults", null, 200);
        assertMoney("application fee", "10000", defaults.get("applicationFee").decimalValue());
        assertMoney("process percent", "1", defaults.get("processPercent").decimalValue());
        assertMoney("insurance percent", "0.6", defaults.get("insurancePercent").decimalValue());
        assertMoney("the rate a new loan starts with", "15", defaults.get("defaultRate").decimalValue());

        long member = send("POST", "/api/members", "{\"memberNo\":\"L1\",\"fullName\":\"Loan Taker\"}", 201).get("id").asLong();

        // a 10,000,000 loan: process fee 1% = 100,000, insurance 0.6% = 60,000, application fee 10,000, legal charges 300,000
        String loanBody = "{\"memberId\":" + member + ",\"principal\":10000000,\"annualRatePercent\":12,\"termMonths\":12,"
                + "\"applicationFee\":10000,\"processFee\":100000,\"insuranceFee\":60000,\"legalCharges\":300000}";
        JsonNode loan = send("POST", "/api/loans", loanBody, 201);
        assertMoney("process fee saved", "100000", loan.get("processFee").decimalValue());
        assertMoney("insurance saved", "60000", loan.get("insuranceFee").decimalValue());
        assertMoney("application fee saved", "10000", loan.get("applicationFee").decimalValue());
        assertMoney("legal charges saved", "300000", loan.get("legalCharges").decimalValue());
        long id = loan.get("id").asLong();

        // the charges together must be less than the loan
        send("POST", "/api/loans", loanBody.replace("\"legalCharges\":300000", "\"legalCharges\":10000000"), 400);

        // a pending loan can be edited, charges included
        JsonNode edited = send("PUT", "/api/loans/" + id, "{\"principal\":10000000,\"annualRatePercent\":12,\"termMonths\":12,"
                + "\"applicationFee\":10000,\"processFee\":100000,\"insuranceFee\":60000,\"legalCharges\":250000}", 200);
        assertMoney("legal charges edited", "250000", edited.get("legalCharges").decimalValue());
        send("PUT", "/api/loans/" + id, "{\"principal\":10000000,\"annualRatePercent\":12,\"termMonths\":12,"
                + "\"applicationFee\":10000,\"processFee\":100000,\"insuranceFee\":60000,\"legalCharges\":300000}", 200);

        // paid out through the bank: the member owes 10,000,000, the bank pays 10,000,000 - 470,000 = 9,530,000
        send("POST", "/api/loans/" + id + "/disburse", "{\"source\":\"BANK\",\"reference\":\"test\"}", 200);
        assertMoney("loans debited with the whole loan", "10000000", totals("1400")[0]);
        assertMoney("bank credited with the loan less the charges", "9530000", totals("1200")[1]);
        assertMoney("application fee income", "10000", totals("4200")[1]);
        assertMoney("process fee income", "100000", totals("4300")[1]);
        assertMoney("insurance", "60000", totals("2200")[1]);
        assertMoney("legal charges income", "300000", totals("4400")[1]);

        // the loan's own view shows what the payout did to the member ledger and the general ledger
        JsonNode view = send("GET", "/api/loans/" + id + "/view", null, 200);
        assertEquals(1, view.get("memberLedger").size());
        assertMoney("member ledger debit", "10000000", view.get("memberLedger").get(0).get("debit").decimalValue());
        BigDecimal glDebit = BigDecimal.ZERO, glCredit = BigDecimal.ZERO;
        for (JsonNode g : view.get("generalLedger")) {
            glDebit = glDebit.add(g.get("debit").decimalValue());
            glCredit = glCredit.add(g.get("credit").decimalValue());
        }
        assertEquals(6, view.get("generalLedger").size());           // loans, bank and the four charges
        assertMoney("general ledger debits", "10000000", glDebit);
        assertMoney("general ledger credits", "10000000", glCredit);

        // a loan can be paid out from any asset account chosen on the form: here Cash on Hand (1100), not the bank
        JsonNode second = send("POST", "/api/loans", "{\"memberId\":" + member + ",\"principal\":1000000,\"annualRatePercent\":12,\"termMonths\":6,"
                + "\"applicationFee\":0,\"processFee\":10000,\"insuranceFee\":6000,\"legalCharges\":0}", 201);
        send("POST", "/api/loans/" + second.get("id").asLong() + "/disburse", "{\"receivingAccountId\":" + accountId("4200") + "}", 400);   // an income account cannot pay out
        send("POST", "/api/loans/" + second.get("id").asLong() + "/disburse", "{\"receivingAccountId\":" + accountId("1100") + ",\"reference\":\"cash\"}", 200);
        assertMoney("cash credited with the loan less the charges", "984000", totals("1100")[1]);
        assertMoney("the bank was not used for it", "9530000", totals("1200")[1]);
        assertMoney("process fee income now", "110000", totals("4300")[1]);

        // loan interest by the days of the month: balance at the month end x 12% / 365 x the days of that month, a full month even
        // for the month the loan is paid out in (this one on the 20th of January)
        JsonNode dated = send("POST", "/api/loans", "{\"memberId\":" + member + ",\"principal\":10000000,\"annualRatePercent\":12,\"termMonths\":12,"
                + "\"applicationFee\":0,\"loanDate\":\"2026-01-20\"}", 201);
        long datedId = dated.get("id").asLong();
        send("POST", "/api/loans/" + datedId + "/disburse", "{\"receivingAccountId\":" + accountId("1200") + ",\"date\":\"2026-01-20\"}", 200);
        assertMoney("January (31 days)", "101917.81", interestOf(datedId, "2026-01"));     // 10,000,000 x 12% x 31 / 365
        assertMoney("February (28 days)", "92054.79", interestOf(datedId, "2026-02"));     // 10,000,000 x 12% x 28 / 365
        assertMoney("April (30 days)", "98630.14", interestOf(datedId, "2026-04"));        // 10,000,000 x 12% x 30 / 365

        // the same loan at the rate CPA uses, 15%: October (31 days) 127,397.26 and November (30 days) 123,287.67
        JsonNode at15 = send("POST", "/api/loans", "{\"memberId\":" + member + ",\"principal\":10000000,\"annualRatePercent\":15,\"termMonths\":12,"
                + "\"applicationFee\":0,\"loanDate\":\"2025-10-22\"}", 201);
        long at15Id = at15.get("id").asLong();
        send("POST", "/api/loans/" + at15Id + "/disburse", "{\"receivingAccountId\":" + accountId("1200") + ",\"date\":\"2025-10-22\"}", 200);
        assertMoney("October at 15%", "127397.26", interestOf(at15Id, "2025-10"));
        assertMoney("November at 15%", "123287.67", interestOf(at15Id, "2025-11"));

        // the books still balance
        assertEquals("Balanced", send("GET", "/api/reports/trial-balance", null, 200).get("totals").get(2).get("value").asText());
    }

    /** The interest the interest computation shows for one loan in a month. */
    BigDecimal interestOf(long loanId, String month) throws Exception {
        for (JsonNode line : send("GET", "/api/interest-runs/preview?month=" + month, null, 200).get("lines")) {
            if (line.get("loanId").asLong() == loanId) return line.get("interest").decimalValue();
        }
        throw new AssertionError("loan " + loanId + " has no interest in " + month);
    }

    long accountId(String code) throws Exception {
        for (JsonNode a : send("GET", "/api/accounts", null, 200)) if (a.get("code").asText().equals(code)) return a.get("id").asLong();
        throw new AssertionError("no account " + code);
    }
}
