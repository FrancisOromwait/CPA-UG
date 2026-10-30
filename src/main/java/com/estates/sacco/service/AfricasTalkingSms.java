package com.estates.sacco.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends SMS through Africa's Talking (https://africastalking.com): one text to many numbers in one request.
 * Their status codes 100 (processed), 101 (sent) and 102 (queued) mean the message was accepted; anything else is a failure
 * with its reason (for example InsufficientBalance or InvalidPhoneNumber).
 */
@Component
@RequiredArgsConstructor
public class AfricasTalkingSms {
    private final SmsSettings settings;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public record Result(boolean ok, String providerId, String cost, String error) {}

    /** Sends {@code text} to every number (+256...). Returns a result for each number; a number with no answer counts as failed. */
    public Map<String, Result> send(List<String> phones, String text) {
        Map<String, Result> out = new HashMap<>();
        String url = settings.isSandbox() ? "https://api.sandbox.africastalking.com/version1/messaging"
                : "https://api.africastalking.com/version1/messaging";
        StringBuilder form = new StringBuilder()
                .append("username=").append(enc(settings.getUsername().trim()))
                .append("&to=").append(enc(String.join(",", phones)))
                .append("&message=").append(enc(text));
        if (!settings.getSenderId().isBlank()) form.append("&from=").append(enc(settings.getSenderId().trim()));
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                    // a pasted key can bring a space or an invisible character along: those are left out
                    .header("apiKey", settings.getApiKey().trim())
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString())).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                String why = "Africa's Talking answered " + res.statusCode() + ": " + shorten(res.body());
                for (String p : phones) out.put(p, new Result(false, null, null, why));
                return out;
            }
            JsonNode data = json.readTree(res.body()).path("SMSMessageData");
            for (JsonNode r : data.path("Recipients")) {
                int code = r.path("statusCode").asInt();
                boolean ok = code == 100 || code == 101 || code == 102;
                out.put(r.path("number").asText(), new Result(ok, r.path("messageId").asText(null), r.path("cost").asText(null),
                        ok ? null : r.path("status").asText("Failed")));
            }
            String summary = data.path("Message").asText("");
            for (String p : phones) out.putIfAbsent(p, new Result(false, null, null, "Not accepted: " + shorten(summary)));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            for (String p : phones) out.put(p, new Result(false, null, null, "Could not reach Africa's Talking: " + shorten(e.getMessage())));
        }
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String shorten(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
