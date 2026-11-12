package dev.backed.topup;

import dev.backed.customer.Customer;
import dev.backed.customer.Customers;
import dev.backed.ledger.Account;
import dev.backed.ledger.BusinessDay;
import dev.backed.ledger.EntryKind;
import dev.backed.ledger.JournalDraft;
import dev.backed.ledger.JournalLineDraft;
import dev.backed.ledger.JournalService;
import dev.backed.ledger.Money;
import dev.backed.paypal.CaptureResult;
import dev.backed.paypal.CaptureSnapshot;
import dev.backed.paypal.OrderSnapshot;
import dev.backed.paypal.PayPalDeclinedException;
import dev.backed.paypal.PayPalException;
import dev.backed.paypal.PayPalGateway;
import dev.backed.paypal.PayPalOutcomeUnknownException;
import dev.backed.paypal.PayPalUnavailableException;
import dev.backed.paypal.TestCard;
import dev.backed.paypal.TestCards;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The top-up state machine (SPEC "State machines"):
 * {@code CREATED -> CAPTURED | DECLINED | IN_DOUBT}, {@code IN_DOUBT -> CAPTURED | NOT_CAPTURED}.
 *
 * <p>Each attempt has three steps, and only the first and last touch the database:
 * <ol>
 *   <li>a short transaction stores the CREATED row with its PayPal-Request-Id;</li>
 *   <li>PayPal is called with no transaction open;</li>
 *   <li>one transaction locks the row, checks its state, records the result and posts the journal
 *       entries (and the balance credit) together.</li>
 * </ol>
 * Resolving an in-doubt top-up re-sends the same request id, so PayPal returns the original capture
 * if it made one. The row lock, the state check, the unique capture id and the unique journal
 * (kind, ref) together make sure a capture is credited exactly once.
 */
@Service
public class TopupService {

    private static final Logger log = LoggerFactory.getLogger(TopupService.class);

    public static final long MIN_CENTS = 100;
    public static final long MAX_CENTS = 1_000_000;
    /** A CREATED row untouched this long is treated as an attempt whose outcome was lost (the app stopped). */
    static final long STALE_CREATED_SECONDS = 120;
    static final String REF_TYPE = "topup";

    private final Topups topups;
    private final Customers customers;
    private final TestCards cards;
    private final PayPalGateway paypal;
    private final JournalService journal;
    private final BusinessDay businessDay;
    private final TransactionTemplate tx;
    private final ReentrantLock[] resolveLocks = new ReentrantLock[64];

    public TopupService(Topups topups, Customers customers, TestCards cards, PayPalGateway paypal,
                        JournalService journal, BusinessDay businessDay, TransactionTemplate tx) {
        this.topups = topups;
        this.customers = customers;
        this.cards = cards;
        this.paypal = paypal;
        this.journal = journal;
        this.businessDay = businessDay;
        this.tx = tx;
        for (int i = 0; i < resolveLocks.length; i++) {
            resolveLocks[i] = new ReentrantLock();
        }
    }

    /**
     * Charges the customer's card at PayPal and credits the balance when PayPal confirms.
     *
     * @return the top-up after the attempt: CAPTURED, DECLINED or IN_DOUBT
     * @throws TopupRefusedException    when the customer has a top-up PayPal has not confirmed yet
     * @throws IllegalArgumentException when the amount is out of range or the customer is unknown
     */
    public Topup topUp(long customerId, long amountCents, String actor) {
        requireNoTransaction();
        if (amountCents < MIN_CENTS || amountCents > MAX_CENTS) {
            throw new IllegalArgumentException("A top-up must be between " + Money.format(MIN_CENTS) + " and "
                    + Money.format(MAX_CENTS) + ".");
        }
        Customer customer = customers.byId(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown customer " + customerId));
        TestCard card = cards.get(customer.cardRef());
        UUID id = UUID.randomUUID();
        String requestId = "topup-" + id;
        try {
            tx.executeWithoutResult(s -> {
                topups.findUnresolvedForCustomer(customerId).ifPresent(open -> {
                    throw refusal(open);
                });
                topups.insertCreated(id, customerId, amountCents, requestId, businessDay.current(), actor);
            });
        } catch (DuplicateKeyException e) {
            // A concurrent request for the same customer won the one-unresolved-per-customer index.
            throw topups.findUnresolvedForCustomer(customerId).map(this::refusal)
                    .orElseGet(() -> new TopupRefusedException("Another top-up is being processed. Try again shortly."));
        }
        log.info("Top-up {} for customer {} of {} stored with request id {}; calling PayPal",
                id, customerId, Money.format(amountCents), requestId);
        return attempt(topups.findById(id).orElseThrow(), card, actor, true);
    }

    /**
     * Settles an IN_DOUBT top-up (or a CREATED one whose attempt was lost) by asking PayPal. When the
     * order is not known, the original request is re-sent with the same PayPal-Request-Id. Safe to call
     * repeatedly and concurrently.
     *
     * @return the top-up afterwards; still IN_DOUBT if PayPal could not answer
     */
    public Topup resolve(UUID topupId, String actor) {
        requireNoTransaction();
        // Within this process, one resolve per top-up at a time: a second caller waits, then sees the
        // settled state and does not call PayPal. Across processes the row lock and state check still
        // guarantee a single credit.
        ReentrantLock lock = resolveLocks[Math.floorMod(topupId.hashCode(), resolveLocks.length)];
        lock.lock();
        try {
            return resolveLocked(topupId, actor);
        } finally {
            lock.unlock();
        }
    }

    private Topup resolveLocked(UUID topupId, String actor) {
        Topup t = topups.findById(topupId)
                .orElseThrow(() -> new IllegalArgumentException("No top-up " + topupId));
        if (!t.state().unresolved()) {
            return t;
        }
        if (t.state() == TopupState.CREATED && !isStale(t)) {
            return t;
        }
        log.info("Resolving top-up {} ({}) for customer {}", t.id(), t.state(), t.customerId());
        if (t.paypalOrderId() != null) {
            return resolveFromOrder(t, actor);
        }
        Customer customer = customers.byId(t.customerId()).orElseThrow();
        return attempt(t, cards.get(customer.cardRef()), actor, false);
    }

    /** Resolves every IN_DOUBT top-up and every stale CREATED one. Returns how many reached a final state. */
    public int resolveAll(String actor) {
        requireNoTransaction();
        int settled = 0;
        for (UUID id : topups.idsToResolve(STALE_CREATED_SECONDS)) {
            try {
                if (!resolve(id, actor).state().unresolved()) {
                    settled++;
                }
            } catch (RuntimeException e) {
                log.error("Could not resolve top-up {}", id, e);
            }
        }
        return settled;
    }

    // ---- one PayPal attempt -------------------------------------------------------------------

    private Topup attempt(Topup t, TestCard card, String actor, boolean firstAttempt) {
        CaptureResult result;
        try {
            result = paypal.captureCard(t.requestId(), t.amountCents(), t.id().toString(), card);
        } catch (PayPalDeclinedException e) {
            log.info("PayPal declined top-up {}: {}", t.id(), e.getMessage());
            return recordNotCaptured(t.id(), declinedReason(e), actor);
        } catch (PayPalUnavailableException e) {
            log.warn("PayPal unavailable for top-up {}: {}", t.id(), e.getMessage());
            if (firstAttempt) {
                // The one request we sent did not take effect, so nothing was charged.
                return recordNotCaptured(t.id(), "PayPal could not be reached (" + brief(e)
                        + "). Nothing was charged; you can try again.", actor);
            }
            return recordInDoubt(t.id(), "Still unconfirmed: PayPal could not be reached on re-send (" + brief(e)
                    + "). It will be asked again with the same request id.", actor);
        } catch (PayPalOutcomeUnknownException e) {
            log.warn("PayPal outcome unknown for top-up {} (request id {}): {}", t.id(), t.requestId(), e.getMessage());
            return recordInDoubt(t.id(), "No answer from PayPal (" + brief(e)
                    + "). It will be asked again with the same request id.", actor);
        } catch (RuntimeException e) {
            log.error("Unexpected failure calling PayPal for top-up {}; treating the outcome as unknown", t.id(), e);
            return recordInDoubt(t.id(), "Unexpected error while calling PayPal (" + e.getClass().getSimpleName()
                    + "). It will be asked again with the same request id.", actor);
        }
        return recordResult(t.id(), result, actor);
    }

    private Topup recordResult(UUID id, CaptureResult result, String actor) {
        if (!result.completed()) {
            log.warn("PayPal capture {} for top-up {} has status {}", result.captureId(), id, result.status());
            return recordPending(id, result.orderId(), result.captureId(),
                    "PayPal reports the capture as " + result.status() + ". It will be checked again with PayPal.",
                    actor);
        }
        CaptureResult withFee = result;
        if (result.feeCents() == null && result.captureId() != null) {
            try {
                CaptureSnapshot snap = paypal.getCapture(result.captureId());
                withFee = new CaptureResult(result.orderId(), result.captureId(), result.status(),
                        result.amountCents(), snap.feeCents(), snap.netCents(), result.customId());
            } catch (PayPalException e) {
                log.warn("Capture {} came without a fee and GET capture failed: {}", result.captureId(), e.getMessage());
            }
        }
        return recordCaptured(id, withFee, actor);
    }

    private Topup resolveFromOrder(Topup t, String actor) {
        OrderSnapshot order;
        try {
            order = paypal.getOrder(t.paypalOrderId());
        } catch (PayPalDeclinedException e) {
            return recordNotCaptured(t.id(), declinedReason(e), actor);
        } catch (PayPalException e) {
            return recordInDoubt(t.id(), "Still unconfirmed: PayPal order " + t.paypalOrderId()
                    + " could not be read (" + brief(e) + ").", actor);
        }
        CaptureSnapshot capture = order.captures().stream()
                .filter(c -> t.paypalCaptureId() == null || t.paypalCaptureId().equals(c.captureId()))
                .findFirst()
                .orElse(null);
        if (capture == null) {
            return recordInDoubt(t.id(), "PayPal order " + order.orderId() + " is " + order.status()
                    + " with no capture yet.", actor);
        }
        String status = capture.status();
        if ("DECLINED".equals(status) || "FAILED".equals(status)) {
            return recordNotCaptured(t.id(), "PayPal capture " + capture.captureId() + " is " + status
                    + ". Nothing was charged.", actor);
        }
        if ("PENDING".equals(status)) {
            return recordPending(t.id(), order.orderId(), capture.captureId(),
                    "PayPal reports the capture as PENDING. It will be checked again with PayPal.", actor);
        }
        // COMPLETED, or already (partially) refunded: the money was captured.
        return recordResult(t.id(), new CaptureResult(order.orderId(), capture.captureId(), "COMPLETED",
                capture.amountCents(), capture.feeCents(), capture.netCents(), capture.customId()), actor);
    }

    // ---- recording (one transaction each, row locked) -----------------------------------------

    private Topup recordCaptured(UUID id, CaptureResult r, String actor) {
        return tx.execute(s -> {
            Topup t = topups.lockById(id).orElseThrow();
            if (t.state() == TopupState.CAPTURED) {
                log.info("Top-up {} is already CAPTURED; capture {} not credited again", id, r.captureId());
                return t;
            }
            if (!t.state().unresolved()) {
                log.error("PayPal reports capture {} for top-up {} which is already {}; leaving it for the close",
                        r.captureId(), id, t.state());
                return t;
            }
            long gross = r.amountCents();
            long fee = r.feeCents() == null ? 0 : r.feeCents();
            long net = gross - fee;
            if (gross != t.amountCents()) {
                log.warn("Top-up {} asked for {} but PayPal captured {}; booking PayPal's amount",
                        id, Money.format(t.amountCents()), Money.format(gross));
            }
            if (r.feeCents() == null) {
                log.warn("Top-up {} capture {} has no PayPal fee; booking a zero fee", id, r.captureId());
            }
            if (t.state() == TopupState.IN_DOUBT) {
                journal.post(suspenseReversal(t, "captured at PayPal", actor));
            }
            List<JournalLineDraft> lines = new ArrayList<>();
            if (net > 0) {
                lines.add(JournalLineDraft.debit(Account.PAYPAL_CASH, net));
            } else if (net < 0) {
                lines.add(JournalLineDraft.credit(Account.PAYPAL_CASH, -net));
            }
            if (fee > 0) {
                lines.add(JournalLineDraft.debit(Account.PAYPAL_FEES, fee));
            }
            lines.add(JournalLineDraft.credit(Account.CUSTOMER_PREPAID, t.customerId(), gross));
            journal.postAndApply(new JournalDraft(null, EntryKind.TOPUP_CAPTURED, REF_TYPE, id.toString(),
                    "Top-up captured at PayPal, capture " + r.captureId(), actor, null, lines));
            if (!topups.markCaptured(id, t.state(), r.orderId(), r.captureId(), fee, net)) {
                throw new IllegalStateException("Top-up " + id + " changed state while locked");
            }
            log.info("Top-up {} CAPTURED: capture {}, gross {}, fee {}, credited customer {}",
                    id, r.captureId(), Money.format(gross), Money.format(fee), t.customerId());
            return topups.findById(id).orElseThrow();
        });
    }

    private Topup recordInDoubt(UUID id, String reason, String actor) {
        return tx.execute(s -> {
            Topup t = topups.lockById(id).orElseThrow();
            if (t.state() == TopupState.CREATED) {
                topups.markState(id, TopupState.CREATED, TopupState.IN_DOUBT, reason);
                journal.post(JournalDraft.of(EntryKind.TOPUP_IN_DOUBT, REF_TYPE, id.toString(),
                        "Top-up sent to PayPal, outcome unknown", actor,
                        JournalLineDraft.debit(Account.IN_DOUBT_RECEIVABLE, t.amountCents()),
                        JournalLineDraft.credit(Account.SUSPENSE_IN_DOUBT, t.amountCents())));
                log.warn("Top-up {} is IN_DOUBT: {}", id, reason);
            } else if (t.state() == TopupState.IN_DOUBT) {
                topups.touch(id, reason);
            }
            return topups.findById(id).orElseThrow();
        });
    }

    private Topup recordPending(UUID id, String orderId, String captureId, String reason, String actor) {
        Topup t = recordInDoubt(id, reason, actor);
        if (t.state() == TopupState.IN_DOUBT) {
            tx.executeWithoutResult(s -> topups.recordPaypalIds(id, orderId, captureId));
            return topups.findById(id).orElseThrow();
        }
        return t;
    }

    private Topup recordNotCaptured(UUID id, String reason, String actor) {
        return tx.execute(s -> {
            Topup t = topups.lockById(id).orElseThrow();
            if (t.state() == TopupState.CREATED) {
                topups.markState(id, TopupState.CREATED, TopupState.DECLINED, reason);
            } else if (t.state() == TopupState.IN_DOUBT) {
                journal.post(suspenseReversal(t, "not captured at PayPal", actor));
                topups.markState(id, TopupState.IN_DOUBT, TopupState.NOT_CAPTURED, reason);
                log.info("Top-up {} NOT_CAPTURED: {}", id, reason);
            }
            return topups.findById(id).orElseThrow();
        });
    }

    private static JournalDraft suspenseReversal(Topup t, String outcome, String actor) {
        return JournalDraft.of(EntryKind.TOPUP_IN_DOUBT_RESOLVED, REF_TYPE, t.id().toString(),
                "In-doubt top-up resolved: " + outcome, actor,
                JournalLineDraft.debit(Account.SUSPENSE_IN_DOUBT, t.amountCents()),
                JournalLineDraft.credit(Account.IN_DOUBT_RECEIVABLE, t.amountCents()));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private TopupRefusedException refusal(Topup open) {
        if (open.state() == TopupState.IN_DOUBT) {
            return new TopupRefusedException("Your top-up of " + Money.format(open.amountCents())
                    + " is still waiting for PayPal to confirm it. To make sure you are not charged twice, "
                    + "you can add money again once it is settled.");
        }
        return new TopupRefusedException("Your top-up of " + Money.format(open.amountCents())
                + " is still being processed. Try again shortly.");
    }

    private static boolean isStale(Topup t) {
        return t.updatedAt() != null
                && t.updatedAt().toInstant().isBefore(java.time.Instant.now().minusSeconds(STALE_CREATED_SECONDS));
    }

    private static String declinedReason(PayPalDeclinedException e) {
        String issue = e.issue() != null ? e.issue() : "HTTP " + e.httpStatus();
        return "Declined by PayPal (" + issue + "). Nothing was charged.";
    }

    private static String brief(PayPalException e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return m.length() > 240 ? m.substring(0, 240) + "..." : m;
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Top-ups call PayPal and must not run inside a database transaction");
        }
    }
}
