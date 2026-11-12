package dev.backed.topup;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One top-up row. requestId is the PayPal-Request-Id, stored before PayPal is called and reused on
 * every re-send. customerName is filled by list queries only.
 */
public record Topup(
        UUID id,
        long customerId,
        String customerName,
        long amountCents,
        TopupState state,
        String requestId,
        String paypalOrderId,
        String paypalCaptureId,
        Long feeCents,
        Long netCents,
        String failureReason,
        LocalDate businessDate,
        String createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
