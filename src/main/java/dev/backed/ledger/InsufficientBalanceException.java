package dev.backed.ledger;

/** A conditional debit found less than the requested amount on the customer's balance. */
public class InsufficientBalanceException extends RuntimeException {

    private final long customerId;
    private final long requestedCents;

    public InsufficientBalanceException(long customerId, long requestedCents) {
        super("Customer " + customerId + " does not have " + Money.format(requestedCents));
        this.customerId = customerId;
        this.requestedCents = requestedCents;
    }

    public long customerId() {
        return customerId;
    }

    public long requestedCents() {
        return requestedCents;
    }
}
