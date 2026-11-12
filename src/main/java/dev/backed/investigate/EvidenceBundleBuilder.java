package dev.backed.investigate;

import dev.backed.close.Break;
import dev.backed.close.BreakKind;
import dev.backed.close.BreakRepository;
import dev.backed.close.BreakStatus;
import dev.backed.customer.Customer;
import dev.backed.customer.CustomerMessage;
import dev.backed.customer.CustomerMessages;
import dev.backed.customer.Customers;
import dev.backed.ledger.BalanceService;
import dev.backed.ledger.CustomerHistoryItem;
import dev.backed.ledger.LedgerQueries;
import dev.backed.ledger.Money;
import dev.backed.paypal.PayPalGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the evidence bundle for a break. May call PayPal to read a capture, so it must not run
 * inside a database transaction.
 */
@Component
public class EvidenceBundleBuilder {

    private static final Logger log = LoggerFactory.getLogger(EvidenceBundleBuilder.class);
    private static final int HISTORY_LIMIT = 20;
    private static final int MESSAGE_LIMIT = 20;

    private final JdbcClient jdbc;
    private final BreakRepository breaks;
    private final Customers customers;
    private final CustomerMessages messages;
    private final BalanceService balances;
    private final LedgerQueries ledger;
    private final ObjectProvider<PayPalGateway> payPal;

    public EvidenceBundleBuilder(JdbcClient jdbc, BreakRepository breaks, Customers customers,
                                 CustomerMessages messages, BalanceService balances, LedgerQueries ledger,
                                 ObjectProvider<PayPalGateway> payPal) {
        this.jdbc = jdbc;
        this.breaks = breaks;
        this.customers = customers;
        this.messages = messages;
        this.balances = balances;
        this.ledger = ledger;
        this.payPal = payPal;
    }

    public EvidenceBundle build(Break b) {
        DraftContext ctx = context(b);
        ObjectNode root = Json.object();

        ObjectNode kase = root.putObject("case");
        kase.put("breakId", b.id());
        kase.put("kind", b.kind().name());
        kase.put("meaning", b.kind() == BreakKind.UNBOOKED_REFUND
                ? "PayPal shows a refund to the customer's card that the shop's ledger never recorded."
                : "PayPal shows a capture from the customer's card that the shop's ledger never credited.");
        kase.put("paypalRef", b.paypalRef());
        kase.put("amount", Money.format(b.amountCents()));
        kase.put("foundAt", time(b.createdAt()));

        Optional<tools.jackson.databind.JsonNode> stored = Json.tryParse(b.evidenceJson());
        if (stored.isPresent()) {
            root.set("paypalRecord", stored.get());
        } else {
            root.put("paypalRecord", b.evidenceJson());
        }

        if (ctx.isRefund() && ctx.movement() != null && ctx.movement().captureId() != null) {
            ObjectNode cap = root.putObject("refundedCapture");
            cap.put("captureId", ctx.movement().captureId());
            if (ctx.refundedCapture() != null) {
                cap.setAll(ctx.refundedCapture().toJson());
                cap.remove("id");
            } else {
                cap.put("details", "not available");
            }
            cap.put("creditedByLedger", ctx.refundedCaptureCredited());
        }
        if (!ctx.isRefund() && ctx.movement() != null) {
            root.put("captureCreditedByLedger", ctx.captureCredited());
            ArrayNode refunds = root.putArray("refundsOfThisCapture");
            if (ctx.captureRefund() != null) {
                refunds.add(ctx.captureRefund().toJson());
            }
        }

        if (ctx.customerId() == null) {
            root.putNull("customer");
        } else {
            long customerId = ctx.customerId();
            ObjectNode c = root.putObject("customer");
            c.put("id", customerId);
            c.put("name", ctx.customerName());
            c.put("currentBalance", Money.format(ctx.customerBalanceCents()));

            ArrayNode history = root.putArray("ledgerHistory");
            for (CustomerHistoryItem h : ledger.customerHistory(customerId, HISTORY_LIMIT)) {
                ObjectNode row = history.addObject();
                row.put("entryId", h.entryId());
                row.put("at", time(h.createdAt()));
                row.put("kind", h.kind());
                row.put("balanceChange", h.amountCents() > 0 ? "+" + Money.format(h.amountCents()) : Money.format(h.amountCents()));
                if (h.memo() != null) {
                    row.put("memo", h.memo());
                }
            }

            ArrayNode msgs = root.putArray("customerMessages");
            for (CustomerMessage m : messages.recent(customerId, MESSAGE_LIMIT)) {
                ObjectNode row = msgs.addObject();
                row.put("sentAt", time(m.createdAt()));
                row.put("body", m.body());
            }

            ArrayNode others = root.putArray("otherOpenBreaks");
            for (Break o : breaks.findOpenForCustomer(customerId)) {
                if (o.id() == b.id()) {
                    continue;
                }
                ObjectNode row = others.addObject();
                row.put("breakId", o.id());
                row.put("kind", o.kind().name());
                row.put("paypalRef", o.paypalRef());
                row.put("amount", Money.format(o.amountCents()));
                row.put("status", o.status().name());
                row.put("foundAt", time(o.createdAt()));
            }

            ArrayNode topups = root.putArray("topups");
            jdbc.sql("""
                            select id, state, amount_cents, paypal_order_id, paypal_capture_id, created_at
                              from topups where customer_id = :id order by created_at desc limit 20
                            """)
                    .param("id", customerId)
                    .query((rs, n) -> {
                        ObjectNode row = topups.addObject();
                        row.put("topupId", rs.getObject("id", UUID.class).toString());
                        row.put("state", rs.getString("state"));
                        row.put("amount", Money.format(rs.getLong("amount_cents")));
                        putIfPresent(row, "paypalOrderId", rs.getString("paypal_order_id"));
                        putIfPresent(row, "paypalCaptureId", rs.getString("paypal_capture_id"));
                        row.put("createdAt", time(rs.getObject("created_at", OffsetDateTime.class)));
                        return row;
                    })
                    .list();
        }
        return new EvidenceBundle(Json.write(root), ctx);
    }

    /** The facts for drafting, read fresh from the database (and PayPal when a capture is unknown). */
    public DraftContext context(Break b) {
        PayPalMovement movement = (b.kind() == BreakKind.UNBOOKED_REFUND
                ? PayPalMovement.refundFrom(b.evidenceJson())
                : PayPalMovement.captureFrom(b.evidenceJson())).orElse(null);

        PayPalMovement refundedCapture = null;
        boolean refundedCaptureCredited = false;
        Break refundedCaptureBreak = null;
        PayPalMovement captureRefund = null;
        Break captureRefundBreak = null;
        boolean captureCredited = false;

        if (b.kind() == BreakKind.UNBOOKED_REFUND && movement != null && movement.captureId() != null) {
            String captureId = movement.captureId();
            refundedCaptureBreak = breaks.findByRef(BreakKind.UNBOOKED_CAPTURE, captureId).orElse(null);
            refundedCapture = PayPalMovement.embeddedCapture(b.evidenceJson())
                    .or(() -> Optional.ofNullable(breaks.findByRef(BreakKind.UNBOOKED_CAPTURE, captureId).orElse(null))
                            .flatMap(cb -> PayPalMovement.captureFrom(cb.evidenceJson())))
                    .or(() -> captureFromTopups(captureId))
                    .or(() -> captureFromPayPal(captureId))
                    .orElse(null);
            refundedCaptureCredited = credited(captureId);
        }
        if (b.kind() == BreakKind.UNBOOKED_CAPTURE && movement != null) {
            captureCredited = credited(movement.id());
            for (Break other : breaks.findOpen()) {
                if (other.kind() != BreakKind.UNBOOKED_REFUND) {
                    continue;
                }
                Optional<PayPalMovement> refund = PayPalMovement.refundFrom(other.evidenceJson());
                if (refund.isPresent() && movement.id().equals(refund.get().captureId())) {
                    captureRefund = refund.get();
                    captureRefundBreak = other;
                    break;
                }
            }
        }

        Long customerId = resolveCustomer(b, movement);
        String customerName = null;
        long balance = 0;
        if (customerId != null) {
            customerName = customers.byId(customerId).map(Customer::name).orElse(null);
            balance = balances.balanceOf(customerId);
        }
        return new DraftContext(b, customerId, customerName, balance, movement, refundedCapture,
                refundedCaptureCredited, refundedCaptureBreak, captureRefund, captureRefundBreak, captureCredited);
    }

    private Long resolveCustomer(Break b, PayPalMovement movement) {
        if (b.customerId() != null) {
            return b.customerId();
        }
        if (b.topupId() != null) {
            Optional<Long> byTopup = jdbc.sql("select customer_id from topups where id = :id")
                    .param("id", b.topupId()).query(Long.class).optional();
            if (byTopup.isPresent()) {
                return byTopup.get();
            }
        }
        if (movement != null && movement.captureId() != null) {
            return jdbc.sql("select customer_id from topups where paypal_capture_id = :cap")
                    .param("cap", movement.captureId()).query(Long.class).optional().orElse(null);
        }
        return null;
    }

    private boolean credited(String captureId) {
        return jdbc.sql("select count(*) from topups where paypal_capture_id = :cap and state = 'CAPTURED'")
                .param("cap", captureId).query(Long.class).single() > 0;
    }

    private Optional<PayPalMovement> captureFromTopups(String captureId) {
        return jdbc.sql("""
                        select paypal_capture_id, paypal_order_id, amount_cents, fee_cents, net_cents, created_at
                          from topups where paypal_capture_id = :cap
                        """)
                .param("cap", captureId)
                .query((rs, n) -> new PayPalMovement(captureId, captureId, rs.getString("paypal_order_id"), "COMPLETED",
                        rs.getLong("amount_cents"), rs.getObject("fee_cents", Long.class),
                        rs.getObject("net_cents", Long.class), null,
                        time(rs.getObject("created_at", OffsetDateTime.class)), null))
                .optional();
    }

    private Optional<PayPalMovement> captureFromPayPal(String captureId) {
        PayPalGateway gateway = payPal.getIfAvailable();
        if (gateway == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(PayPalMovement.of(gateway.getCapture(captureId)));
        } catch (RuntimeException e) {
            log.warn("Could not read capture {} from PayPal: {}", captureId, e.getMessage());
            return Optional.empty();
        }
    }

    private static void putIfPresent(ObjectNode node, String name, String value) {
        if (value != null) {
            node.put(name, value);
        }
    }

    static String time(OffsetDateTime t) {
        return t == null ? null : t.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    static boolean isOpen(Break b) {
        return b.status() == BreakStatus.OPEN || b.status() == BreakStatus.PROPOSED;
    }
}
