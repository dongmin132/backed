package dev.backed.ledger;

/** The chart of accounts. Codes match the rows seeded in the accounts table. */
public enum Account {
    PAYPAL_CASH(Type.ASSET),
    IN_DOUBT_RECEIVABLE(Type.ASSET),
    CUSTOMER_PREPAID(Type.LIABILITY),
    SUSPENSE_IN_DOUBT(Type.LIABILITY),
    CASHOUT_CLEARING(Type.LIABILITY),
    SALES_REVENUE(Type.INCOME),
    PAYPAL_FEES(Type.EXPENSE),
    GOODWILL_EXPENSE(Type.EXPENSE);

    public enum Type { ASSET, LIABILITY, INCOME, EXPENSE }

    private final Type type;

    Account(Type type) {
        this.type = type;
    }

    public Type type() {
        return type;
    }

    public String code() {
        return name();
    }

    /** True when the account's balance grows with debits (assets and expenses). */
    public boolean debitNormal() {
        return type == Type.ASSET || type == Type.EXPENSE;
    }

    /** Signed balance in this account's normal direction. */
    public long balance(long debitCents, long creditCents) {
        return debitNormal() ? debitCents - creditCents : creditCents - debitCents;
    }
}
