package dev.backed.ledger;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One movement on a customer's prepaid balance, taken from the CUSTOMER_PREPAID lines.
 * amountCents is positive when the balance went up and negative when it went down.
 */
public record CustomerHistoryItem(
        long entryId,
        LocalDate businessDate,
        String kind,
        String memo,
        OffsetDateTime createdAt,
        long amountCents) {
}
