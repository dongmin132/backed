package dev.backed.paypal;

/**
 * The result of {@link PayPalGateway#captureCard}.
 *
 * @param status    PayPal's capture status, for example COMPLETED or PENDING
 * @param feeCents  PayPal fee from seller_receivable_breakdown, null when PayPal did not return it
 * @param netCents  net amount from seller_receivable_breakdown, null when PayPal did not return it
 */
public record CaptureResult(
        String orderId,
        String captureId,
        String status,
        long amountCents,
        Long feeCents,
        Long netCents,
        String customId) {

    public boolean completed() {
        return "COMPLETED".equals(status);
    }
}
