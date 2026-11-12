package dev.backed.webhook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Checks a delivery with PayPal's verify-webhook-signature endpoint. Off unless
 * backed.webhooks.verify-signature=true; it then needs backed.paypal.webhook-id (or the
 * PAYPAL_WEBHOOK_ID environment variable) and the PayPal client credentials.
 */
@Component
@ConditionalOnProperty(name = "backed.webhooks.verify-signature", havingValue = "true")
public class PayPalWebhookSignatureVerifier implements WebhookSignatureVerifier {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;
    private final String webhookId;

    public PayPalWebhookSignatureVerifier(
            @Value("${backed.paypal.base-url:https://api-m.sandbox.paypal.com}") String baseUrl,
            @Value("${backed.paypal.client-id:}") String clientId,
            @Value("${backed.paypal.client-secret:}") String clientSecret,
            @Value("${backed.paypal.webhook-id:${PAYPAL_WEBHOOK_ID:}}") String webhookId) {
        this.baseUrl = baseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.webhookId = webhookId;
    }

    @Override
    public boolean verify(HttpHeaders headers, String rawBody) {
        if (webhookId.isBlank() || clientId.isBlank() || clientSecret.isBlank()) {
            throw new IllegalStateException("Webhook signature check is on but the webhook id or credentials are missing");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("auth_algo", headers.getFirst("PAYPAL-AUTH-ALGO"));
        fields.put("cert_url", headers.getFirst("PAYPAL-CERT-URL"));
        fields.put("transmission_id", headers.getFirst("PAYPAL-TRANSMISSION-ID"));
        fields.put("transmission_sig", headers.getFirst("PAYPAL-TRANSMISSION-SIG"));
        fields.put("transmission_time", headers.getFirst("PAYPAL-TRANSMISSION-TIME"));
        fields.put("webhook_id", webhookId);
        if (fields.values().stream().anyMatch(v -> v == null || v.isBlank())) {
            return false;
        }
        String head = JsonMapper.shared().writeValueAsString(fields);
        // The event goes in byte for byte as PayPal sent it; re-serialising it can change the signed content.
        String body = head.substring(0, head.length() - 1) + ",\"webhook_event\":" + rawBody + "}";
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                                    URI.create(baseUrl + "/v1/notifications/verify-webhook-signature"))
                            .timeout(TIMEOUT)
                            .header("Authorization", "Bearer " + accessToken())
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("verify-webhook-signature returned HTTP " + response.statusCode());
            }
            return "SUCCESS".equals(Json.text(JsonMapper.shared().readTree(response.body()), "verification_status"));
        } catch (IOException e) {
            throw new IllegalStateException("verify-webhook-signature failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private String accessToken() throws IOException, InterruptedException {
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/oauth2/token"))
                        .timeout(TIMEOUT)
                        .header("Authorization", "Basic " + basic)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("PayPal token request returned HTTP " + response.statusCode());
        }
        JsonNode json = JsonMapper.shared().readTree(response.body());
        String token = Json.text(json, "access_token");
        if (token == null) {
            throw new IllegalStateException("PayPal token response had no access_token");
        }
        return token;
    }
}
