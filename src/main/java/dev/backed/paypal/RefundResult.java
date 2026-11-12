package dev.backed.paypal;

/**
 * The result of {@link PayPalGateway#refundCapture}.
 *
 * @param status   PayPal's refund status, for example COMPLETED or PENDING
 * @param feeCents paypal_fee from seller_payable_breakdown, null when not returned
 * @param netCents net_amount from seller_payable_breakdown, null when not returned
 */
public record RefundResult(
        String refundId,
        String captureId,
        String status,
        long amountCents,
        Long feeCents,
        Long netCents,
        String noteToPayer) {

    public boolean completed() {
        return "COMPLETED".equals(status);
    }
}
