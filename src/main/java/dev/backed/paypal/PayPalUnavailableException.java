package dev.backed.paypal;

/** 5xx or connection refused: the request did not take effect. Retry later with the same request id. */
public class PayPalUnavailableException extends PayPalException {

    public PayPalUnavailableException(String message, int httpStatus, String debugId) {
        super(message, httpStatus, null, debugId, null);
    }

    public PayPalUnavailableException(String message, int httpStatus, String debugId, Throwable cause) {
        super(message, httpStatus, null, debugId, cause);
    }
}
