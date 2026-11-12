package dev.backed.close;

public enum BreakKind {
    /** PayPal has a refund the ledger has no cash-out leg or resolved break for. */
    UNBOOKED_REFUND,
    /** PayPal has a capture the ledger never credited. */
    UNBOOKED_CAPTURE
}
