package dev.backed.investigate;

import dev.backed.close.Break;
import dev.backed.close.BreakKind;
import dev.backed.close.BreakRepository;
import dev.backed.close.NewBreak;
import dev.backed.customer.CustomerMessages;
import dev.backed.ledger.Account;
import dev.backed.ledger.EntryKind;
import dev.backed.ledger.JournalDraft;
import dev.backed.ledger.JournalLineDraft;
import dev.backed.ledger.JournalService;
import dev.backed.paypal.CaptureSnapshot;
import dev.backed.paypal.FakePayPalGateway;
import dev.backed.paypal.RefundSnapshot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Builds the three situations the investigator must tell apart, the way the top-up and close
 * modules would leave them: captured top-ups in the ledger and breaks holding PayPal's records.
 */
public class Scenarios {

    public static final OffsetDateTime T0 = OffsetDateTime.of(2026, 11, 12, 10, 0, 0, 0, ZoneOffset.UTC);
    public static final String COLD_COFFEE_NOTE = "Sorry the coffee was cold, this one is on us";
    public static final String MOVING_NOTE = "Closing my tab, moving away";
    public static final String CHARGED_TWICE = "I was charged twice at the counter";

    private final JdbcClient jdbc;
    private final JournalService journal;
    private final BreakRepository breaks;
    private final CustomerMessages messages;
    private final TransactionTemplate tx;

    public Scenarios(JdbcClient jdbc, JournalService journal, BreakRepository breaks, CustomerMessages messages,
                     TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.journal = journal;
        this.breaks = breaks;
        this.messages = messages;
        this.tx = tx;
    }

    /** Customer A: $20.00 top-up, then a $5.00 refund made in the PayPal dashboard with an apology. */
    public Break goodwill(long customerA) {
        topup(customerA, 2_000, "CAP-A1", T0);
        messages.add(customerA, "My latte was cold this morning, a bit disappointing.");
        return refundBreak(customerA, "REF-A1", "CAP-A1", 500, COLD_COFFEE_NOTE, T0.plusHours(2));
    }

    /** Customer B: $30.00 top-up, then the whole $30.00 refunded with a note that they are leaving. */
    public Break balanceReturned(long customerB) {
        topup(customerB, 3_000, "CAP-B1", T0);
        return refundBreak(customerB, "REF-B1", "CAP-B1", 3_000, MOVING_NOTE, T0.plusHours(5));
    }

    /**
     * Customer C: two $15.00 captures two minutes apart; the ledger credited the first. The second was
     * refunded with no note, and the customer wrote that they were charged twice.
     */
    public Break duplicate(long customerC) {
        topup(customerC, 1_500, "CAP-C1", T0.plusMinutes(2));
        captureBreak(customerC, "CAP-C2", 1_500, T0.plusMinutes(4));
        messages.add(customerC, CHARGED_TWICE + " this morning, can you check?");
        return refundBreak(customerC, "REF-C2", "CAP-C2", 1_500, null, T0.plusHours(3));
    }

    /** A captured top-up credited to the customer, as the top-up module books it. */
    public UUID topup(long customerId, long cents, String captureId, OffsetDateTime at) {
        UUID id = UUID.randomUUID();
        long fee = FakePayPalGateway.fee(cents);
        tx.executeWithoutResult(s -> {
            jdbc.sql("""
                            insert into topups (id, customer_id, amount_cents, state, request_id, paypal_order_id,
                                                paypal_capture_id, fee_cents, net_cents, business_date, created_by,
                                                created_at, updated_at)
                            values (:id, :customer, :amount, 'CAPTURED', :req, :order, :cap, :fee, :net,
                                    current_date, :actor, :at, :at)
                            """)
                    .param("id", id)
                    .param("customer", customerId)
                    .param("amount", cents)
                    .param("req", "topup-" + id)
                    .param("order", "ORDER-" + captureId)
                    .param("cap", captureId)
                    .param("fee", fee)
                    .param("net", cents - fee)
                    .param("actor", "customer:" + customerId)
                    .param("at", at)
                    .update();
            journal.postAndApply(JournalDraft.of(EntryKind.TOPUP_CAPTURED, "topup", id.toString(),
                    "Top-up captured " + captureId, "customer:" + customerId,
                    JournalLineDraft.debit(Account.PAYPAL_CASH, cents - fee),
                    JournalLineDraft.debit(Account.PAYPAL_FEES, fee),
                    JournalLineDraft.credit(Account.CUSTOMER_PREPAID, customerId, cents)));
        });
        return id;
    }

    public void spend(long customerId, long cents) {
        tx.executeWithoutResult(s -> journal.postAndApply(JournalDraft.of(EntryKind.SPEND, "spend",
                UUID.randomUUID().toString(), "Counter sale", "clerk",
                JournalLineDraft.debit(Account.CUSTOMER_PREPAID, customerId, cents),
                JournalLineDraft.credit(Account.SALES_REVENUE, cents))));
    }

    public Break refundBreak(Long customerId, String refundId, String captureId, long cents, String note,
                             OffsetDateTime at) {
        RefundSnapshot refund = new RefundSnapshot(refundId, captureId, "COMPLETED", cents, 0L, cents, note, at);
        return breaks.insertIfAbsent(new NewBreak(null, BreakKind.UNBOOKED_REFUND, customerId, null, refundId, cents,
                Json.write(refund)));
    }

    public Break captureBreak(Long customerId, String captureId, long cents, OffsetDateTime at) {
        long fee = FakePayPalGateway.fee(cents);
        CaptureSnapshot capture = new CaptureSnapshot(captureId, "ORDER-" + captureId, "REFUNDED", cents, fee,
                cents - fee, "customer-" + customerId, at);
        return breaks.insertIfAbsent(new NewBreak(null, BreakKind.UNBOOKED_CAPTURE, customerId, null, captureId, cents,
                Json.write(capture)));
    }
}
