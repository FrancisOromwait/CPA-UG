package com.estates.sacco.service;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How SMS messages to members are sent. The provider "test" (the default) sends nothing: messages are prepared and logged only, so
 * the Messages screen can be tried without an SMS account or credit. "africastalking" sends through Africa's Talking. The username,
 * API key and sender name come from environment variables (on Railway: the service Variables), never from a file.
 */
@Component
@Getter
public class SmsSettings {
    @Value("${sacco.sms.provider:test}")
    private String provider;

    @Value("${sacco.sms.username:}")
    private String username;

    @Value("${sacco.sms.api-key:}")
    private String apiKey;

    /** The registered sender name shown on the phone (blank: the provider's default number). */
    @Value("${sacco.sms.sender-id:}")
    private String senderId;

    /** Africa's Talking sandbox: messages go to their simulator, not to real phones. */
    @Value("${sacco.sms.sandbox:false}")
    private boolean sandbox;

    public boolean isLive() {
        return !"test".equalsIgnoreCase(provider) && !apiKey.isBlank();
    }
}
