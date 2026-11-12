package dev.backed.cashout;

import dev.backed.ledger.Account;
import dev.backed.ledger.EntryKind;
import dev.backed.ledger.JournalDraft;
import dev.backed.ledger.JournalLineDraft;
import dev.backed.ledger.JournalService;
import dev.backed.ledger.Money;
import dev.backed.paypal.PayPalDeclinedException;
import dev.backed.paypal.PayPalException;
import dev.backed.paypal.PayPalGateway;
import dev.backed.paypal.PayPalOutcomeUnknownException;
import dev.backed.paypal.PayPalUnavailableException;
import dev.backed.paypal.RefundResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Sends cash-out legs to PayPal. A run claims legs in a short transaction, calls PayPal with no
 * transaction open, then records each result in its own transaction.
 *
 * <p>Classification: a refund is recorded as DONE and booked; a decline (4xx) of a PENDING leg fails
 * it at once and gives the money back to the balance; 5xx leaves the leg waiting with a growing delay
 * until {@code backed.cashout.max-attempts}, then marks it IN_DOUBT, because a 5xx does not prove the
 * refund did not happen; an unknown outcome marks the leg IN_DOUBT and the next run re-sends the same
 * request id, which PayPal answers with the original refund if it happened. A leg whose previous claim
 * expired without a recorded result is IN_DOUBT too. An IN_DOUBT leg is never failed or compensated:
 * only a refund from PayPal for its request id settles it, and a 4xx on the re-send leaves it for a
 * person to check.
 *
 * <p>The relay never reads the security context; it books everything as {@link #ACTOR}.
 */
@Service
public class CashoutRelay {

    public static final String ACTOR = "system:relay";

    private static final Logger log = LoggerFactory.getLogger(CashoutRelay.class);
    private static final Set<String> REFUND_FAILED_STATUSES = Set.of("FAILED", "CANCELLED", "DENIED");
    private static final int BATCH = 100;

    enum LegOutcome { REFUNDED, FAILED, RETRY_LATER, IN_DOUBT, SKIPPED }

    /** A leg this run holds, as it was when claimed. attempts already counts this attempt. */
    public record ClaimedLeg(UUID id, UUID cashoutId, int legNo, long customerId, String captureId, long amountCents,
                             String requestId, String state, int attempts) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final PayPalGateway paypal;
    private final JournalService journal;
    private final int maxAttempts;
    private final Duration retryDelay;
    private final Duration claimLease;

    public CashoutRelay(JdbcClient jdbc, TransactionTemplate tx, PayPalGateway paypal, JournalService journal,
                        @Value("${backed.cashout.max-attempts:5}") int maxAttempts,
                        @Value("${backed.cashout.retry-delay:PT10S}") Duration retryDelay,
                        @Value("${backed.cashout.claim-lease:PT2M}") Duration claimLease) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.paypal = paypal;
        this.journal = journal;
        this.maxAttempts = maxAttempts;
        this.retryDelay = retryDelay;
        this.claimLease = claimLease;
    }

    /**
     * Sends every PENDING and IN_DOUBT leg that no other run holds, without waiting for retry
     * delays. Used by the "Run relay now" button, the day close and tests.
     */
    public RelayReport runOnce() {
        return run(true);
    }

    /** Sends the legs whose retry delay has passed. */
    public RelayReport runDue() {
        return run(false);
    }

    @Scheduled(fixedDelayString = "${backed.cashout.relay-interval-ms:3000}",
            initialDelayString = "${backed.cashout.relay-initial-delay-ms:5000}")
    public void scheduledRun() {
        try {
            RelayReport report = runDue();
            if (report.sent() > 0) {
                log.info("Cash-out relay: {}", report.summary());
            }
        } catch (RuntimeException e) {
            log.warn("Cash-out relay run failed; it will run again", e);
        }
    }

    private RelayReport run(boolean ignoreRetryDelay) {
        RelayReport report = RelayReport.EMPTY;
        for (ClaimedLeg leg : claim(ignoreRetryDelay)) {
            report = report.plus(send(leg));
        }
        return report;
    }

    List<ClaimedLeg> claim(boolean ignoreRetryDelay) {
        List<ClaimedLeg> legs = tx.execute(status -> jdbc.sql("""
                        update cashout_legs l
                           set attempts = l.attempts + 1,
                               state = case when l.claimed_until is not null then 'IN_DOUBT' else l.state end,
                               last_error = case when l.claimed_until is not null
                                                 then 'The previous attempt never recorded its result; it may have refunded.'
                                                 else l.last_error end,
                               claimed_until = now() + :leaseMillis * interval '1 millisecond',
                               updated_at = now()
                          from (select id from cashout_legs
                                 where state in ('PENDING', 'IN_DOUBT')
                                   and (claimed_until is null or claimed_until < now())
                                   and (:ignoreRetryDelay or next_attempt_at <= now())
                                 order by next_attempt_at, created_at, leg_no
                                 limit :batch
                                 for update skip locked) picked
                         where l.id = picked.id
                        returning l.id, l.cashout_id, l.leg_no,
                                  (select c.customer_id from cashouts c where c.id = l.cashout_id) as customer_id,
                                  l.capture_id, l.amount_cents, l.request_id, l.state, l.attempts
                        """)
                .param("leaseMillis", claimLease.toMillis())
                .param("ignoreRetryDelay", ignoreRetryDelay)
                .param("batch", BATCH)
                .query(ClaimedLeg.class)
                .list());
        List<ClaimedLeg> ordered = new ArrayList<>(legs == null ? List.of() : legs);
        ordered.sort(Comparator.comparing(ClaimedLeg::cashoutId).thenComparingInt(ClaimedLeg::legNo));
        return ordered;
    }

    /** One PayPal call for a claimed leg, outside any transaction, then the result is recorded. */
    LegOutcome send(ClaimedLeg leg) {
        RefundResult refund;
        try {
            refund = paypal.refundCapture(leg.requestId(), leg.captureId(), leg.amountCents(),
                    CashoutService.NOTE_TO_PAYER);
        } catch (PayPalDeclinedException e) {
            if ("IN_DOUBT".equals(leg.state())) {
                // A 4xx on the re-send says nothing about the first request, which may have refunded.
                return reschedule(leg, "IN_DOUBT", "PayPal refused the re-send (" + describe(e) + "). The first "
                                + "request may already have refunded, so the balance is not returned. Check capture "
                                + leg.captureId() + " at PayPal if this persists.",
                        retryDelay.multipliedBy(leg.attempts()), LegOutcome.IN_DOUBT);
            }
            return recordFailed(leg, "Declined by PayPal: " + describe(e));
        } catch (PayPalUnavailableException e) {
            return recordUnavailable(leg, describe(e));
        } catch (PayPalOutcomeUnknownException e) {
            return recordInDoubt(leg, "Outcome unknown: " + describe(e));
        } catch (RuntimeException e) {
            // Anything unclassified might have happened at PayPal; the same request id settles it.
            log.warn("Unexpected error refunding leg {}", leg.requestId(), e);
            return recordInDoubt(leg, "Outcome unknown, unexpected error: " + e);
        }
        if (refund == null || REFUND_FAILED_STATUSES.contains(refund.status())) {
            return recordFailed(leg, "PayPal refund status " + (refund == null ? "missing" : refund.status()));
        }
        return recordRefunded(leg, refund);
    }

    private LegOutcome recordRefunded(ClaimedLeg leg, RefundResult refund) {
        return inLegTransaction(leg, () -> {
            int rows = jdbc.sql("""
                            update cashout_legs
                               set state = 'DONE', paypal_refund_id = :refundId, last_error = :note,
                                   claimed_until = null, updated_at = now()
                             where id = :id and state in ('PENDING', 'IN_DOUBT')
                            """)
                    .param("refundId", refund.refundId())
                    .param("note", refund.completed() ? null : "PayPal refund status " + refund.status())
                    .param("id", leg.id())
                    .update();
            if (rows == 0) {
                return LegOutcome.SKIPPED;
            }
            journal.post(JournalDraft.of(EntryKind.CASHOUT_LEG_REFUNDED, "cashout_leg", leg.id().toString(),
                    "Cash-out leg " + leg.legNo() + " refunded, PayPal refund " + refund.refundId(), ACTOR,
                    refundLines(leg.amountCents(), refund).toArray(JournalLineDraft[]::new)));
            return LegOutcome.REFUNDED;
        });
    }

    /**
     * Dr CASHOUT_CLEARING for the leg, Cr PAYPAL_CASH for what PayPal took from the shop. When PayPal
     * reports a fee returned on the refund (net + fee = gross), PAYPAL_CASH falls by the net and the
     * returned fee is credited back to PAYPAL_FEES; otherwise PAYPAL_CASH falls by the gross.
     */
    static List<JournalLineDraft> refundLines(long legCents, RefundResult refund) {
        Long fee = refund.feeCents();
        Long net = refund.netCents();
        if (fee != null && net != null && fee > 0 && net > 0 && fee + net == legCents) {
            return List.of(JournalLineDraft.debit(Account.CASHOUT_CLEARING, legCents),
                    JournalLineDraft.credit(Account.PAYPAL_CASH, net),
                    JournalLineDraft.credit(Account.PAYPAL_FEES, fee));
        }
        return List.of(JournalLineDraft.debit(Account.CASHOUT_CLEARING, legCents),
                JournalLineDraft.credit(Account.PAYPAL_CASH, legCents));
    }

    private LegOutcome recordFailed(ClaimedLeg leg, String reason) {
        return inLegTransaction(leg, () -> {
            int rows = jdbc.sql("""
                            update cashout_legs
                               set state = 'FAILED', last_error = :reason, claimed_until = null, updated_at = now()
                             where id = :id and attempts = :attempts and state in ('PENDING', 'IN_DOUBT')
                            """)
                    .param("reason", truncate(reason))
                    .param("id", leg.id())
                    .param("attempts", leg.attempts())
                    .update();
            if (rows == 0) {
                return LegOutcome.SKIPPED;
            }
            journal.postAndApply(JournalDraft.of(EntryKind.CASHOUT_LEG_FAILED, "cashout_leg", leg.id().toString(),
                    "Cash-out leg " + leg.legNo() + " failed, " + Money.format(leg.amountCents())
                            + " returned to the balance", ACTOR,
                    JournalLineDraft.debit(Account.CASHOUT_CLEARING, leg.amountCents()),
                    JournalLineDraft.credit(Account.CUSTOMER_PREPAID, leg.customerId(), leg.amountCents())));
            return LegOutcome.FAILED;
        });
    }

    private LegOutcome recordUnavailable(ClaimedLeg leg, String reason) {
        if ("PENDING".equals(leg.state()) && leg.attempts() >= maxAttempts) {
            return reschedule(leg, "IN_DOUBT", "No answer after " + leg.attempts() + " attempts: " + reason
                            + ". A 5xx does not prove the refund did not happen, so the balance is not returned;"
                            + " the same request id is sent again until PayPal answers.",
                    retryDelay.multipliedBy(leg.attempts()), LegOutcome.IN_DOUBT);
        }
        // An IN_DOUBT leg stays IN_DOUBT: this 5xx says nothing about its first request.
        return reschedule(leg, leg.state(), "PayPal unavailable: " + reason, retryDelay.multipliedBy(leg.attempts()),
                LegOutcome.RETRY_LATER);
    }

    private LegOutcome recordInDoubt(ClaimedLeg leg, String reason) {
        return reschedule(leg, "IN_DOUBT", reason, retryDelay, LegOutcome.IN_DOUBT);
    }

    private LegOutcome reschedule(ClaimedLeg leg, String state, String reason, Duration delay, LegOutcome outcome) {
        return inLegTransaction(leg, () -> {
            int rows = jdbc.sql("""
                            update cashout_legs
                               set state = :state, last_error = :reason, claimed_until = null,
                                   next_attempt_at = now() + :delayMillis * interval '1 millisecond', updated_at = now()
                             where id = :id and attempts = :attempts and state in ('PENDING', 'IN_DOUBT')
                            """)
                    .param("state", state)
                    .param("reason", truncate(reason))
                    .param("delayMillis", delay.toMillis())
                    .param("id", leg.id())
                    .param("attempts", leg.attempts())
                    .update();
            return rows == 0 ? LegOutcome.SKIPPED : outcome;
        });
    }

    /**
     * Records a leg result with the cash-out row locked, so results for two legs of one cash-out
     * are written one after the other and the roll-up always sees every leg's final state.
     */
    private LegOutcome inLegTransaction(ClaimedLeg leg, Supplier<LegOutcome> work) {
        LegOutcome outcome = tx.execute(status -> {
            jdbc.sql("select id from cashouts where id = :id for update")
                    .param("id", leg.cashoutId())
                    .query(UUID.class)
                    .single();
            LegOutcome result = work.get();
            if (result == LegOutcome.REFUNDED || result == LegOutcome.FAILED) {
                rollUp(leg.cashoutId());
            }
            return result;
        });
        log.info("Cash-out leg {} ({}): {}", leg.requestId(), Money.format(leg.amountCents()), outcome);
        return outcome;
    }

    private void rollUp(UUID cashoutId) {
        Map<String, Long> counts = new HashMap<>();
        jdbc.sql("select state, count(*) as n from cashout_legs where cashout_id = :id group by state")
                .param("id", cashoutId)
                .query((ResultSet rs) -> {
                    counts.put(rs.getString("state"), rs.getLong("n"));
                });
        long open = counts.getOrDefault("PENDING", 0L) + counts.getOrDefault("IN_DOUBT", 0L);
        if (open > 0) {
            return;
        }
        long done = counts.getOrDefault("DONE", 0L);
        long failed = counts.getOrDefault("FAILED", 0L);
        String state = failed == 0 ? "SETTLED" : done == 0 ? "COMPENSATED" : "PARTIALLY_SETTLED";
        jdbc.sql("update cashouts set state = :state, updated_at = now() where id = :id and state = 'REQUESTED'")
                .param("state", state)
                .param("id", cashoutId)
                .update();
    }

    private static String describe(PayPalException e) {
        StringBuilder s = new StringBuilder(String.valueOf(e.getMessage()));
        if (e.httpStatus() > 0) {
            s.append(" (HTTP ").append(e.httpStatus());
            if (e.issue() != null) {
                s.append(' ').append(e.issue());
            }
            s.append(')');
        }
        if (e.debugId() != null) {
            s.append(" debug id ").append(e.debugId());
        }
        return s.toString();
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
