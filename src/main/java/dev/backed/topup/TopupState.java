package dev.backed.topup;

/**
 * CREATED: stored with its request id, PayPal call not answered yet.
 * CAPTURED: PayPal confirmed the capture; the customer is credited.
 * DECLINED: PayPal refused, or could not be reached; nothing was charged.
 * IN_DOUBT: the request may have reached PayPal but no answer came back.
 * NOT_CAPTURED: an in-doubt top-up PayPal turned out not to have captured.
 */
public enum TopupState {
    CREATED, CAPTURED, DECLINED, IN_DOUBT, NOT_CAPTURED;

    public boolean unresolved() {
        return this == CREATED || this == IN_DOUBT;
    }
}
