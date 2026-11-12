package dev.backed.cashout;

import dev.backed.ledger.Account;
import dev.backed.ledger.BalanceService;
import dev.backed.ledger.BusinessDay;
import dev.backed.ledger.EntryKind;
import dev.backed.ledger.JournalDraft;
import dev.backed.ledger.JournalLineDraft;
import dev.backed.ledger.JournalService;
import dev.backed.ledger.Money;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Accepts cash-out requests. The request transaction is the outbox: it takes the balance, writes
 * the cash-out and its PENDING legs and posts the clearing entry, and never calls PayPal.
 * {@link CashoutRelay} sends the legs afterwards.
 */
@Service
public class CashoutService {

    /** Sent with every leg. Fixed, so a re-send with the same request id carries identical arguments. */
    public static final String NOTE_TO_PAYER = "Cash-out of your prepaid balance";

    /** A capture of the customer's that still has money we can refund, newest first. */
    public record RefundableCapture(UUID topupId, String captureId, long refundableCents) {
    }

    public record PlannedLeg(int legNo, UUID topupId, String captureId, long amountCents) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final BalanceService balances;
    private final JournalService journal;
    private final BusinessDay businessDay;

    public CashoutService(JdbcClient jdbc, TransactionTemplate tx, BalanceService balances, JournalService journal,
                          BusinessDay businessDay) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.balances = balances;
        this.journal = journal;
        this.businessDay = businessDay;
    }

    /** Cash-out with a fresh request id. amountCents null means the whole balance. */
    public CashoutRequestResult request(long customerId, Long amountCents, String actor) {
        return request(customerId, amountCents, actor, null);
    }

    /**
     * Takes the amount from the balance and splits it into refund legs over the customer's
     * captures, newest first. Refused, with nothing written, when the balance is too low or the
     * captures cannot cover the amount.
     *
     * @param amountCents null for the whole balance
     * @param requestId   idempotency key for the request; null mints one
     */
    public CashoutRequestResult request(long customerId, Long amountCents, String actor, String requestId) {
        if (amountCents != null && amountCents <= 0) {
            throw new IllegalArgumentException("Amount must be more than zero");
        }
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor is required");
        }
        String rid = requestId == null || requestId.isBlank() ? "cashout-req-" + UUID.randomUUID() : requestId.strip();
        Optional<CashoutRequestResult> earlier = existing(rid);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        try {
            return tx.execute(status -> {
                // Locks the balance row so concurrent cash-outs for one customer allocate legs one
                // after the other and never promise the same refundable cents twice.
                long balance = jdbc.sql("""
                                select balance_cents from customer_balances where customer_id = :id for update
                                """)
                        .param("id", customerId)
                        .query(Long.class)
                        .optional()
                        .orElse(0L);
                long amount = amountCents == null ? balance : amountCents;
                if (amount <= 0) {
                    return refused(amount, "There is no balance to cash out.");
                }
                if (amount > balance) {
                    return refused(amount, "Cannot cash out " + Money.format(amount) + ": the balance is "
                            + Money.format(balance) + ".");
                }
                List<PlannedLeg> legs = allocate(refundableCaptures(customerId), amount);
                long covered = legs.stream().mapToLong(PlannedLeg::amountCents).sum();
                if (covered < amount) {
                    return refused(amount, "Cannot cash out " + Money.format(amount) + ": only "
                            + Money.format(covered) + " can be refunded to the card it was paid with.");
                }
                if (!balances.debitIfSufficient(customerId, amount)) {
                    throw new IllegalStateException("balance changed while its row was locked");
                }
                UUID cashoutId = UUID.randomUUID();
                LocalDate date = businessDay.current();
                jdbc.sql("""
                                insert into cashouts (id, customer_id, amount_cents, request_id, state, business_date, created_by)
                                values (:id, :customerId, :amount, :requestId, 'REQUESTED', :date, :actor)
                                """)
                        .param("id", cashoutId)
                        .param("customerId", customerId)
                        .param("amount", amount)
                        .param("requestId", rid)
                        .param("date", date)
                        .param("actor", actor)
                        .update();
                for (PlannedLeg leg : legs) {
                    jdbc.sql("""
                                    insert into cashout_legs (id, cashout_id, leg_no, topup_id, capture_id, amount_cents,
                                                              request_id, state)
                                    values (:id, :cashoutId, :legNo, :topupId, :captureId, :amount, :requestId, 'PENDING')
                                    """)
                            .param("id", UUID.randomUUID())
                            .param("cashoutId", cashoutId)
                            .param("legNo", leg.legNo())
                            .param("topupId", leg.topupId())
                            .param("captureId", leg.captureId())
                            .param("amount", leg.amountCents())
                            .param("requestId", legRequestId(cashoutId, leg.legNo()))
                            .update();
                }
                journal.post(JournalDraft.of(EntryKind.CASHOUT_REQUESTED, "cashout", cashoutId.toString(),
                                "Cash-out requested, " + legs.size() + (legs.size() == 1 ? " refund" : " refunds"), actor,
                                JournalLineDraft.debit(Account.CUSTOMER_PREPAID, customerId, amount),
                                JournalLineDraft.credit(Account.CASHOUT_CLEARING, amount))
                        .withBusinessDate(date));
                return new CashoutRequestResult(CashoutRequestResult.Outcome.ACCEPTED, cashoutId, amount, legs.size(),
                        "Cash-out of " + Money.format(amount) + " accepted. Refund in progress: "
                                + legs.size() + (legs.size() == 1 ? " refund" : " refunds")
                                + " to the card you paid with.");
            });
        } catch (DuplicateKeyException e) {
            return existing(rid).orElseThrow(() -> e);
        }
    }

    /** PayPal-Request-Id of a leg. Derived from the cash-out, so it is the same on every re-send. */
    public static String legRequestId(UUID cashoutId, int legNo) {
        return "cashout-" + cashoutId + "-leg-" + legNo;
    }

    /**
     * The customer's captured top-ups, newest first, each with what is still refundable on it from
     * our own records: the captured gross, less legs that are queued, in doubt or done, less refunds
     * PayPal showed us outside the ledger (UNBOOKED_REFUND breaks).
     */
    List<RefundableCapture> refundableCaptures(long customerId) {
        return jdbc.sql("""
                        select t.id as topup_id, t.paypal_capture_id as capture_id,
                               t.amount_cents
                               - coalesce((select sum(l.amount_cents) from cashout_legs l
                                            where l.capture_id = t.paypal_capture_id
                                              and l.state in ('PENDING', 'IN_DOUBT', 'DONE')), 0)
                               - coalesce((select sum(b.amount_cents) from breaks b
                                            where b.topup_id = t.id and b.kind = 'UNBOOKED_REFUND'), 0)
                               as refundable_cents
                          from topups t
                         where t.customer_id = :customerId
                           and t.state = 'CAPTURED'
                           and t.paypal_capture_id is not null
                         order by t.created_at desc, t.id desc
                        """)
                .param("customerId", customerId)
                .query(RefundableCapture.class)
                .list();
    }

    static List<PlannedLeg> allocate(List<RefundableCapture> newestFirst, long amountCents) {
        List<PlannedLeg> legs = new ArrayList<>();
        long remaining = amountCents;
        for (RefundableCapture capture : newestFirst) {
            if (remaining == 0) {
                break;
            }
            if (capture.refundableCents() <= 0) {
                continue;
            }
            long take = Math.min(remaining, capture.refundableCents());
            legs.add(new PlannedLeg(legs.size() + 1, capture.topupId(), capture.captureId(), take));
            remaining -= take;
        }
        return legs;
    }

    private Optional<CashoutRequestResult> existing(String requestId) {
        return jdbc.sql("""
                        select c.id, c.amount_cents, (select count(*) from cashout_legs l where l.cashout_id = c.id) as legs
                          from cashouts c where c.request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query((rs, n) -> new CashoutRequestResult(CashoutRequestResult.Outcome.ALREADY_REQUESTED,
                        rs.getObject("id", UUID.class), rs.getLong("amount_cents"), rs.getInt("legs"),
                        "This cash-out of " + Money.format(rs.getLong("amount_cents"))
                                + " was already requested. Nothing new was taken."))
                .optional();
    }

    private static CashoutRequestResult refused(long amount, String message) {
        return new CashoutRequestResult(CashoutRequestResult.Outcome.REFUSED, null, Math.max(amount, 0), 0,
                message + " Nothing was taken from the balance.");
    }
}
