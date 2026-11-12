package dev.backed.paypal;

import java.time.OffsetDateTime;

/** A capture as PayPal reports it now. status is PayPal's, for example COMPLETED or PARTIALLY_REFUNDED. */
public record CaptureSnapshot(
        String captureId,
        String orderId,
        String status,
        long amountCents,
        Long feeCents,
        Long netCents,
        String customId,
        OffsetDateTime createTime) {
}
