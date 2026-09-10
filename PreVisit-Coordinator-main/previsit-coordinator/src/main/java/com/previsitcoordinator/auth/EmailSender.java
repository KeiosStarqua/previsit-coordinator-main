package com.previsitcoordinator.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Sends transactional email through Resend (https://resend.com).
 *
 * Configuration (application.properties / environment):
 *   resend.api-key   -> RESEND_API_KEY   (e.g. re_xxx). If blank, email is not
 *                       actually sent; the code is logged instead (dev mode).
 *   resend.from      -> the verified "from" address (defaults to Resend's shared
 *                       onboarding@resend.dev, which only delivers to the
 *                       Resend account owner's own address).
 *
 * Uses the JDK HttpClient so no extra dependency is required.
 */
@Component
public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);
    private static final String RESEND_ENDPOINT = "https://api.resend.com/emails";

    private final String apiKey;
    private final String from;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    EmailSender(
            @Value("${resend.api-key:}") String apiKey,
            @Value("${resend.from:PreVisit Coordinator <onboarding@resend.dev>}") String from) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.from = from;
    }

    /** True when a Resend API key is configured and email will really be sent. */
    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    /**
     * Send the verification code to the given address. Returns true if Resend
     * accepted the message. In dev mode (no API key) it logs the code and
     * returns false so callers can surface the code another way.
     */
    public boolean sendOtp(String toEmail, String displayName, String code) {
        String subject = "Your PreVisit verification code: " + code;
        String html = buildOtpHtml(displayName, code);

        if (!isConfigured()) {
            log.warn("[DEV] RESEND_API_KEY not set. OTP for {} is {} (email NOT sent).", toEmail, code);
            return false;
        }

        String payload = "{"
                + "\"from\":" + json(from) + ","
                + "\"to\":[" + json(toEmail) + "],"
                + "\"subject\":" + json(subject) + ","
                + "\"html\":" + json(html)
                + "}";

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(RESEND_ENDPOINT))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                log.info("Sent verification code to {} (Resend accepted).", toEmail);
                return true;
            }
            log.error("Resend rejected the email to {} ({}): {}", toEmail, response.statusCode(), response.body());
            return false;
        } catch (Exception e) {
            log.error("Failed to send verification email to {}: {}", toEmail, e.toString());
            return false;
        }
    }

    private String buildOtpHtml(String displayName, String code) {
        String name = (displayName == null || displayName.isBlank()) ? "there" : displayName;
        return "<div style=\"font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;"
                + "max-width:480px;margin:0 auto;padding:24px;color:#0f172a\">"
                + "<h2 style=\"margin:0 0 8px\">PreVisit Coordinator</h2>"
                + "<p style=\"color:#475569;margin:0 0 20px\">Hi " + escapeHtml(name)
                + ", use this code to verify your account:</p>"
                + "<div style=\"font-size:34px;font-weight:700;letter-spacing:10px;"
                + "background:#f1f5f9;border-radius:12px;padding:18px 0;text-align:center;"
                + "color:#2563eb\">" + escapeHtml(code) + "</div>"
                + "<p style=\"color:#94a3b8;font-size:12.5px;margin:20px 0 0\">"
                + "This code expires in 10 minutes. If you didn't request it, you can ignore this email."
                + "</p></div>";
    }

    /** Minimal JSON string encoder for the few fields sent above. */
    private static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append("\"").toString();
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
