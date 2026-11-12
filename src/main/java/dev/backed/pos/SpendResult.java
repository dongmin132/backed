package dev.backed.pos;

import java.util.UUID;

/**
 * What happened to a counter sale.
 *
 * @param spendId      the recorded spend, null when refused
 * @param balanceCents the customer's balance after the sale (or as found, when refused)
 */
public record SpendResult(Outcome outcome, UUID spendId, long customerId, long amountCents, long balanceCents,
                          String message) {

    public enum Outcome {
        /** Balance taken and the sale posted. */
        DONE,
        /** Balance too low; nothing was written. */
        REFUSED,
        /** A sale with the same request id was recorded earlier; nothing new was written. */
        ALREADY_DONE
    }

    public boolean done() {
        return outcome != Outcome.REFUSED;
    }
}
