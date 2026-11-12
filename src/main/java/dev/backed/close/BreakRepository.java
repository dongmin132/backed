package dev.backed.close;

import java.util.List;
import java.util.Optional;

public interface BreakRepository {

    /**
     * Stores the break unless one with the same kind and paypalRef exists (in any status).
     * Returns the stored break, new or existing.
     */
    Break insertIfAbsent(NewBreak newBreak);

    Optional<Break> findById(long id);

    Optional<Break> findByRef(BreakKind kind, String paypalRef);

    /** Breaks that are OPEN or PROPOSED, oldest first. */
    List<Break> findOpen();

    /** OPEN or PROPOSED breaks for one customer, oldest first. */
    List<Break> findOpenForCustomer(long customerId);

    /** Every break, newest first. */
    List<Break> findRecent(int limit);

    /** OPEN to PROPOSED. Returns false when the break was not OPEN. */
    boolean markProposed(long id);

    /** PROPOSED back to OPEN, for example after a proposal is dismissed. Returns false when not PROPOSED. */
    boolean markOpen(long id);

    /** OPEN or PROPOSED to RESOLVED by the given journal entry. Returns false when already RESOLVED. */
    boolean markResolved(long id, long entryId);
}
