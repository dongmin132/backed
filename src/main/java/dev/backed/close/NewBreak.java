package dev.backed.close;

import java.util.UUID;

/** A break the close found, before it is stored. */
public record NewBreak(
        Long closeId,
        BreakKind kind,
        Long customerId,
        UUID topupId,
        String paypalRef,
        long amountCents,
        String evidenceJson) {
}
