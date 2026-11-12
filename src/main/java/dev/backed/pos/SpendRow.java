package dev.backed.pos;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A recorded counter sale, for display. */
public record SpendRow(UUID id, long customerId, String customerName, long amountCents, String memo, String state,
                       String requestId, Long entryId, String createdBy, OffsetDateTime createdAt) {
}
