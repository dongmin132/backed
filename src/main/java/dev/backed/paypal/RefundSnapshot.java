package dev.backed.paypal;

import java.time.OffsetDateTime;

/**
 * A refund as PayPal reports it now.
 *
 * @param captureId   the capture it refunds (from the refund's "up" link)
 * @param feeCents    paypal_fee from seller_payable_breakdown, null when not returned
 * @param netCents    net_amount from seller_payable_breakdown, null when not returned
 * @param noteToPayer the note entered when the refund was made, in Backed or in the PayPal dashboard
 */
public record RefundSnapshot(
        String refundId,
        String captureId,
        String status,
        long amountCents,
        Long feeCents,
        Long netCents,
        String noteToPayer,
        OffsetDateTime createTime) {
}
