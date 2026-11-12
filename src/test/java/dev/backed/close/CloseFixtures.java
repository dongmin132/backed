package dev.backed.close;

import dev.backed.config.Actors;
import dev.backed.ledger.Account;
import dev.backed.ledger.EntryKind;
import dev.backed.ledger.JournalDraft;
import dev.backed.ledger.JournalLineDraft;
import dev.backed.ledger.JournalService;
import dev.backed.ledger.Money;
import dev.backed.paypal.CaptureResult;
import dev.backed.paypal.FakePayPalGateway;
import dev.backed.paypal.RefundResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Builds top-up and cash-out rows the way those modules write them, so the close can be tested
 * before they exist: the money moves at the fake PayPal, the rows go straight into the tables and
 * the entries go through JournalService.
 */
public record CloseFixtures(JdbcClient jdbc, TransactionTemplate tx, JournalService journal, FakePayPalGateway paypal) {

    public record Topup(UUID id, long customerId, CaptureResult capture) {
    }

    public Topup capturedTopup(long customerId, long cents) {
        UUID id = UUID.randomUUID();
        CaptureResult capture = paypal.captureCard("topup-" + id, cents, id.toString(), null);
        tx.executeWithoutResult(s -> {
            insertTopup(id, customerId, cents, "CAPTURED", capture.orderId(), capture.captureId(),
                    capture.feeCents(), capture.netCents());
            journal.postAndApply(JournalDraft.of(EntryKind.TOPUP_CAPTURED, "topup", id.toString(), "top-up",
                    Actors.customer(customerId),
                    JournalLineDraft.debit(Account.PAYPAL_CASH, capture.netCents()),
                    JournalLineDraft.debit(Account.PAYPAL_FEES, capture.feeCents()),
                    JournalLineDraft.credit(Account.CUSTOMER_PREPAID, customerId, cents)));
        });
        return new Topup(id, customerId, capture);
    }

    /** A top-up whose capture response was lost: PayPal never answered, the ledger holds it in suspense. */
    public UUID inDoubtTopup(long customerId, long cents) {
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            insertTopup(id, customerId, cents, "IN_DOUBT", null, null, null, null);
            journal.post(JournalDraft.of(EntryKind.TOPUP_IN_DOUBT, "topup", id.toString(), "top-up in doubt",
                    Actors.customer(customerId),
                    JournalLineDraft.debit(Account.IN_DOUBT_RECEIVABLE, cents),
                    JournalLineDraft.credit(Account.SUSPENSE_IN_DOUBT, cents)));
        });
        return id;
    }

    public void insertTopup(UUID id, long customerId, long cents, String state, String orderId, String captureId,
                     Long fee, Long net) {
        jdbc.sql("""
                        insert into topups (id, customer_id, amount_cents, state, request_id, paypal_order_id,
                                            paypal_capture_id, fee_cents, net_cents, business_date, created_by)
                        values (:id, :customer, :amount, :state, :request, :orderId, :captureId, :fee, :net,
                                (select business_date from business_day), 'test')
                        """)
                .param("id", id)
                .param("customer", customerId)
                .param("amount", cents)
                .param("state", state)
                .param("request", "topup-" + id)
                .param("orderId", orderId)
                .param("captureId", captureId)
                .param("fee", fee)
                .param("net", net)
                .update();
    }

    /** A one-leg cash-out refunded through PayPal and fully booked. Returns the refund. */
    public RefundResult settledCashout(Topup topup, long cents) {
        UUID cashoutId = UUID.randomUUID();
        UUID legId = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            journal.postAndApply(JournalDraft.of(EntryKind.CASHOUT_REQUESTED, "cashout", cashoutId.toString(),
                    "cash-out", Actors.customer(topup.customerId()),
                    JournalLineDraft.debit(Account.CUSTOMER_PREPAID, topup.customerId(), cents),
                    JournalLineDraft.credit(Account.CASHOUT_CLEARING, cents)));
            insertCashout(cashoutId, topup.customerId(), cents, "REQUESTED");
            insertLeg(legId, cashoutId, topup, cents, "PENDING", null);
        });
        RefundResult refund = paypal.refundCapture("leg-" + legId, topup.capture().captureId(), cents, "Cash-out");
        tx.executeWithoutResult(s -> {
            jdbc.sql("update cashout_legs set state = 'DONE', paypal_refund_id = :refund where id = :id")
                    .param("refund", refund.refundId())
                    .param("id", legId)
                    .update();
            jdbc.sql("update cashouts set state = 'SETTLED' where id = :id").param("id", cashoutId).update();
            journal.post(JournalDraft.of(EntryKind.CASHOUT_LEG_REFUNDED, "cashout_leg", legId.toString(),
                    "cash-out leg refunded", Actors.SCHEDULER,
                    JournalLineDraft.debit(Account.CASHOUT_CLEARING, cents),
                    JournalLineDraft.credit(Account.PAYPAL_CASH, cents)));
        });
        return refund;
    }

    /** A cash-out whose only leg reached PayPal but whose response was lost: the leg is IN_DOUBT. */
    public RefundResult inDoubtCashout(Topup topup, long cents) {
        UUID cashoutId = UUID.randomUUID();
        UUID legId = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            journal.postAndApply(JournalDraft.of(EntryKind.CASHOUT_REQUESTED, "cashout", cashoutId.toString(),
                    "cash-out", Actors.customer(topup.customerId()),
                    JournalLineDraft.debit(Account.CUSTOMER_PREPAID, topup.customerId(), cents),
                    JournalLineDraft.credit(Account.CASHOUT_CLEARING, cents)));
            insertCashout(cashoutId, topup.customerId(), cents, "REQUESTED");
            insertLeg(legId, cashoutId, topup, cents, "IN_DOUBT", null);
        });
        return paypal.refundCapture("leg-" + legId, topup.capture().captureId(), cents, "Cash-out");
    }

    private void insertCashout(UUID id, long customerId, long cents, String state) {
        jdbc.sql("""
                        insert into cashouts (id, customer_id, amount_cents, request_id, state, business_date, created_by)
                        values (:id, :customer, :amount, :request, :state, (select business_date from business_day), 'test')
                        """)
                .param("id", id)
                .param("customer", customerId)
                .param("amount", cents)
                .param("request", "cashout-" + id)
                .param("state", state)
                .update();
    }

    private void insertLeg(UUID id, UUID cashoutId, Topup topup, long cents, String state, String refundId) {
        jdbc.sql("""
                        insert into cashout_legs (id, cashout_id, leg_no, topup_id, capture_id, amount_cents, request_id,
                                                  state, paypal_refund_id)
                        values (:id, :cashout, 1, :topup, :capture, :amount, :request, :state, :refund)
                        """)
                .param("id", id)
                .param("cashout", cashoutId)
                .param("topup", topup.id())
                .param("capture", topup.capture().captureId())
                .param("amount", cents)
                .param("request", "leg-" + id)
                .param("state", state)
                .param("refund", refundId)
                .update();
    }

    public static String captureCompletedEvent(String eventId, String captureId, long cents) {
        return """
                {"id":"%s","event_version":"1.0","create_time":"2026-11-12T10:00:00Z","resource_type":"capture",
                 "event_type":"PAYMENT.CAPTURE.COMPLETED","summary":"Payment completed for $ %s USD",
                 "resource":{"id":"%s","status":"COMPLETED","amount":{"currency_code":"USD","value":"%s"},
                   "links":[{"rel":"self","href":"https://api.sandbox.paypal.com/v2/payments/captures/%s"}]}}
                """.formatted(eventId, Money.toDecimalString(cents), captureId, Money.toDecimalString(cents), captureId);
    }

    public static String captureRefundedEvent(String eventId, String refundId, String captureId, long cents) {
        return """
                {"id":"%s","event_version":"1.0","create_time":"2026-11-12T10:00:00Z","resource_type":"refund",
                 "event_type":"PAYMENT.CAPTURE.REFUNDED","summary":"A $ %s USD capture payment was refunded",
                 "resource":{"id":"%s","status":"COMPLETED","amount":{"currency_code":"USD","value":"%s"},
                   "links":[{"rel":"self","href":"https://api.sandbox.paypal.com/v2/payments/refunds/%s"},
                            {"rel":"up","href":"https://api.sandbox.paypal.com/v2/payments/captures/%s"}]}}
                """.formatted(eventId, Money.toDecimalString(cents), refundId, Money.toDecimalString(cents), refundId,
                captureId);
    }
}
