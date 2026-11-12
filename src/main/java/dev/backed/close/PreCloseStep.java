package dev.backed.close;

/**
 * Work the day close runs before it reconciles, such as resolving IN_DOUBT top-ups or cash-out
 * legs by asking PayPal. Every bean of this type runs on each close, in @Order order. A step calls
 * PayPal outside any database transaction and must be safe to run repeatedly.
 */
public interface PreCloseStep {

    /** Short label shown in the close summary. */
    String name();

    /** Runs the step. Returns how many items it resolved or processed. */
    int run();
}
