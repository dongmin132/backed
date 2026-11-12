package dev.backed.ledger;

/**
 * Journal entry kinds used by the postings in SPEC.md. Kind is stored as text, so a module may
 * use its own value; these constants keep the common ones consistent. The database allows one
 * entry per (kind, ref_type, ref_id), which makes a repeated posting for the same business
 * object fail instead of double counting.
 */
public final class EntryKind {
    public static final String TOPUP_CAPTURED = "TOPUP_CAPTURED";
    public static final String TOPUP_IN_DOUBT = "TOPUP_IN_DOUBT";
    public static final String TOPUP_IN_DOUBT_RESOLVED = "TOPUP_IN_DOUBT_RESOLVED";
    public static final String SPEND = "SPEND";
    public static final String CASHOUT_REQUESTED = "CASHOUT_REQUESTED";
    public static final String CASHOUT_LEG_REFUNDED = "CASHOUT_LEG_REFUNDED";
    public static final String CASHOUT_LEG_FAILED = "CASHOUT_LEG_FAILED";
    public static final String CORRECTION = "CORRECTION";

    private EntryKind() {
    }
}
