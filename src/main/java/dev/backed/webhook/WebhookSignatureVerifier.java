package dev.backed.webhook;

import org.springframework.http.HttpHeaders;

/** Confirms a webhook delivery really came from PayPal. Only present when the check is switched on. */
public interface WebhookSignatureVerifier {

    /**
     * @return true when PayPal confirms the signature, false when it does not or headers are missing
     * @throws IllegalStateException when PayPal could not be asked
     */
    boolean verify(HttpHeaders headers, String rawBody);
}
