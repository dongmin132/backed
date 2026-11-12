package dev.backed.topup;

/** A new top-up was refused before anything was sent to PayPal. The message is safe to show the customer. */
public class TopupRefusedException extends RuntimeException {

    public TopupRefusedException(String message) {
        super(message);
    }
}
