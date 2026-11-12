package dev.backed.cashout;

import java.util.UUID;

/**
 * What happened to a cash-out request.
 *
 * @param cashoutId null when refused
 * @param legCount  how many refunds the cash-out was split into
 */
public record CashoutRequestResult(Outcome outcome, UUID cashoutId, long amountCents, int legCount, String message) {

    public enum Outcome {
        /** Balance taken, legs queued for the relay. */
        ACCEPTED,
        /** Nothing was written. */
        REFUSED,
        /** A cash-out with the same request id exists; nothing new was written. */
        ALREADY_REQUESTED
    }

    public boolean accepted() {
        return outcome != Outcome.REFUSED;
    }
}
