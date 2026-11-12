package dev.backed.paypal;

/**
 * The port every module uses to move money at PayPal. Amounts are USD cents.
 *
 * <p>Every write takes a caller-supplied request id that is sent as PayPal-Request-Id. The caller
 * stores the id before calling and reuses it on every retry of the same business intent; PayPal
 * then returns the original result instead of acting twice.
 *
 * <p>Failures are classified for the caller:
 * <ul>
 *   <li>{@link PayPalDeclinedException}: PayPal refused (4xx business error). Never retry.</li>
 *   <li>{@link PayPalUnavailableException}: 5xx, 408, 409, 429 or connection refused. Retry later with
 *       the same request id. Only when {@link PayPalUnavailableException#reachedPayPal()} is false is it
 *       certain that nothing happened; a write answered with an HTTP status may have taken effect.</li>
 *   <li>{@link PayPalOutcomeUnknownException}: read timeout or dropped response. The request may have
 *       taken effect. Mark the step IN_DOUBT and resolve it by re-sending the same request id.</li>
 * </ul>
 * Implementations must not be called inside a transaction that holds row locks.
 */
public interface PayPalGateway {

    /** Told the order id as soon as PayPal has created an order that still has to be captured. */
    @FunctionalInterface
    interface OrderCreated {
        void orderCreated(String orderId);
    }

    /**
     * Creates an order with intent CAPTURE paid by the given card and captures it in the same call.
     *
     * @param requestId   PayPal-Request-Id; the same id returns the same order and capture
     * @param amountCents gross amount to charge
     * @param customId    stored on the purchase unit (use the top-up id) and echoed in snapshots
     */
    default CaptureResult captureCard(String requestId, long amountCents, String customId, TestCard card) {
        return captureCard(requestId, amountCents, customId, card, orderId -> {
        });
    }

    /**
     * As {@link #captureCard(String, long, String, TestCard)}, and when PayPal leaves the new order
     * APPROVED (so a separate capture call follows), reports its id to {@code onOrderCreated} before
     * that capture call is sent. The caller stores it so a failed capture can be checked with GET order.
     */
    CaptureResult captureCard(String requestId, long amountCents, String customId, TestCard card,
                              OrderCreated onOrderCreated);

    /**
     * Refunds part or all of a capture.
     *
     * @param requestId   PayPal-Request-Id; the same id returns the same refund
     * @param noteToPayer shown to the payer and returned in snapshots
     */
    RefundResult refundCapture(String requestId, String captureId, long amountCents, String noteToPayer);

    /** The order with every capture and refund PayPal has for it, including refunds made outside Backed. */
    OrderSnapshot getOrder(String orderId);

    CaptureSnapshot getCapture(String captureId);

    /** False when the gateway has no credentials, so every call will fail; the pages then say so. */
    default boolean configured() {
        return true;
    }
}
