package dev.backed.paypal;

/**
 * 5xx, 408, 409, 429, or no connection. Retry later with the same request id. When PayPal answered
 * with a status, a write may still have taken effect (see {@link #reachedPayPal()}).
 */
public class PayPalUnavailableException extends PayPalException {

    /** False only when no request reached PayPal (connection refused, DNS, TLS, OAuth, not configured). */
    public boolean reachedPayPal() {
        return httpStatus() > 0;
    }

    public PayPalUnavailableException(String message, int httpStatus, String debugId) {
        super(message, httpStatus, null, debugId, null);
    }

    public PayPalUnavailableException(String message, int httpStatus, String debugId, Throwable cause) {
        super(message, httpStatus, null, debugId, cause);
    }
}
