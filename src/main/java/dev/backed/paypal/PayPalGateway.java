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
 *   <li>{@link PayPalUnavailableException}: 5xx or connection refused. Nothing happened; retry later
 *       with the same request id.</li>
 *   <li>{@link PayPalOutcomeUnknownException}: read timeout or dropped response. The request may have
 *       taken effect. Mark the step IN_DOUBT and resolve it by re-sending the same request id.</li>
 * </ul>
 * Implementations must not be called inside a transaction that holds row locks.
 */
public interface PayPalGateway {

    /**
     * Creates an order with intent CAPTURE paid by the given card and captures it in the same call.
     *
     * @param requestId   PayPal-Request-Id; the same id returns the same order and capture
     * @param amountCents gross amount to charge
     * @param customId    stored on the purchase unit (use the top-up id) and echoed in snapshots
     */
    CaptureResult captureCard(String requestId, long amountCents, String customId, TestCard card);

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
}
