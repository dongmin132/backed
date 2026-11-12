package dev.backed.paypal;

/**
 * The request was sent but no usable response came back (read timeout, connection dropped).
 * It may have taken effect. Record IN_DOUBT and resolve by re-sending the same request id.
 */
public class PayPalOutcomeUnknownException extends PayPalException {

    public PayPalOutcomeUnknownException(String message) {
        super(message, 0, null, null, null);
    }

    public PayPalOutcomeUnknownException(String message, Throwable cause) {
        super(message, 0, null, null, cause);
    }
}
