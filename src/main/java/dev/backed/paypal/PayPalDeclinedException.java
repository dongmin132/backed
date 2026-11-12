package dev.backed.paypal;

/** PayPal answered with a 4xx business error. The request will never succeed; do not retry it. */
public class PayPalDeclinedException extends PayPalException {

    public PayPalDeclinedException(String message, int httpStatus, String issue, String debugId) {
        super(message, httpStatus, issue, debugId, null);
    }

    public PayPalDeclinedException(String message, int httpStatus, String issue, String debugId, Throwable cause) {
        super(message, httpStatus, issue, debugId, cause);
    }
}
