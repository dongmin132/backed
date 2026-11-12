package dev.backed.close;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Something PayPal shows that the ledger cannot explain.
 *
 * @param closeId      the close run that first found it
 * @param paypalRef    PayPal's id for the refund or capture; unique per kind
 * @param amountCents  gross amount PayPal reports
 * @param evidenceJson the PayPal object as JSON text, as the close saw it
 */
public record Break(
        long id,
        Long closeId,
        BreakKind kind,
        Long customerId,
        UUID topupId,
        String paypalRef,
        long amountCents,
        String evidenceJson,
        BreakStatus status,
        Long resolvedEntryId,
        OffsetDateTime createdAt) {
}
