package dev.backed.close;

import dev.backed.ledger.Account;
import dev.backed.ledger.BalanceService;
import dev.backed.ledger.BusinessDay;
import dev.backed.ledger.LedgerQueries;
import dev.backed.ledger.Money;
import dev.backed.paypal.CaptureSnapshot;
import dev.backed.paypal.OrderSnapshot;
import dev.backed.paypal.PayPalDeclinedException;
import dev.backed.paypal.PayPalException;
import dev.backed.paypal.PayPalGateway;
import dev.backed.paypal.RefundSnapshot;
import dev.backed.webhook.WebhookEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The day close (SPEC "Day close"). Runs the pre-close steps, reads every relevant order from
 * PayPal outside any transaction, then compares PayPal's captures and refunds with the ledger.
 * Anything PayPal shows that the ledger cannot explain becomes a break. Nothing here posts to the
 * journal; corrections come from the investigator after maker and checker approval.
 */
@Service
public class DayCloseService implements CloseRunner {

    private static final Logger log = LoggerFactory.getLogger(DayCloseService.class);

    /** Capture statuses for which PayPal has received the money (later refunds are counted separately). */
    private static final Set<String> CAPTURE_HAS_MONEY = Set.of("COMPLETED", "PARTIALLY_REFUNDED", "REFUNDED");
    private static final Set<String> TOPUP_UNRESOLVED = Set.of("CREATED", "IN_DOUBT");
    private static final Set<String> LEG_UNRESOLVED = Set.of("PENDING", "IN_DOUBT");

    record TopupRow(UUID id, long customerId, String state, String orderId, String captureId, long amountCents) {
    }

    record LegRow(UUID id, String captureId, long amountCents, String state, String refundId) {
    }

    /** What PayPal returned. Read completely before anything is written. */
    record PayPalView(Map<String, OrderSnapshot> orders, List<CaptureSnapshot> captures, List<String> notes) {
    }

    /** The ledger side, read in one snapshot after PayPal was read. */
    record LedgerView(Map<UUID, TopupRow> topupsById, Map<String, TopupRow> topupsByOrder,
                      Map<String, TopupRow> topupsByCapture, List<LegRow> legs, Set<String> resolvedRefs,
                      long totalDebits, long totalCredits, List<Long> unbalanced, long owed, long prepaid,
                      long ledgerCash, int inDoubtTopups, int inDoubtLegs, long journalEntryCount) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate snapshot;
    private final ObjectProvider<PayPalGateway> paypal;
    private final ObjectProvider<PreCloseStep> steps;
    private final BreakRepository breaks;
    private final CloseRuns runs;
    private final BusinessDay businessDay;
    private final WebhookEvents webhookEvents;
    private final LedgerQueries ledger;
    private final BalanceService balances;

    public DayCloseService(JdbcClient jdbc, PlatformTransactionManager txManager, ObjectProvider<PayPalGateway> paypal,
                           ObjectProvider<PreCloseStep> steps, BreakRepository breaks, CloseRuns runs,
                           BusinessDay businessDay, WebhookEvents webhookEvents, LedgerQueries ledger,
                           BalanceService balances) {
        this.jdbc = jdbc;
        this.snapshot = new TransactionTemplate(txManager);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setReadOnly(true);
        this.paypal = paypal;
        this.steps = steps;
        this.breaks = breaks;
        this.runs = runs;
        this.businessDay = businessDay;
        this.webhookEvents = webhookEvents;
        this.ledger = ledger;
        this.balances = balances;
    }

    @Override
    public CloseOutcome runClose(String actor) {
        LocalDate date = businessDay.current();
        long closeId = runs.start(date, actor);
        try {
            return reconcile(closeId, date);
        } catch (RuntimeException e) {
            log.error("Close {} for {} failed", closeId, date, e);
            String reason = "The close stopped: " + e.getMessage();
            CloseSummary summary = new CloseSummary(List.of(), List.of(), List.of(), 0, 0, 0, null, -1, List.of(reason));
            runs.finish(closeId, CloseStatus.BROKEN, null, null, null, null, toJson(summary));
            return new CloseOutcome(closeId, date, CloseStatus.BROKEN, 0, 0, 0, 0,
                    breaks.findOpen().stream().map(Break::id).toList(), 0, List.of(reason));
        }
    }

    /**
     * Moves the business date forward, allowed only when the newest close of the current date is
     * CLOSED and no journal entry has been written since that close read the books.
     *
     * @throws IllegalStateException when the day may not be advanced, with the reason
     */
    @Transactional
    public LocalDate advance(String actor) {
        LocalDate date = jdbc.sql("select business_date from business_day where id for update")
                .query(LocalDate.class)
                .single();
        CloseRuns.CloseRun run = runs.latestFor(date)
                .orElseThrow(() -> new IllegalStateException("Run the close for " + date + " first."));
        if (run.status() != CloseStatus.CLOSED) {
            throw new IllegalStateException("The latest close for " + date + " is " + run.status()
                    + ". Resolve it and run the close again.");
        }
        CloseSummary summary = readSummary(run.summaryJson());
        long entries = jdbc.sql("select count(*) from journal_entries").query(Long.class).single();
        if (summary == null || summary.journalEntryCount() != entries) {
            throw new IllegalStateException("The books changed after the close. Run the close again.");
        }
        LocalDate next = businessDay.advance();
        log.info("Business date advanced from {} to {} by {}", date, next, actor);
        return next;
    }

    /** Parses a stored summary; null when absent or unreadable. */
    public static CloseSummary readSummary(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonMapper.shared().readValue(json, CloseSummary.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The note to payer stored in a break's evidence, or null. */
    public static String noteOf(String evidenceJson) {
        if (evidenceJson == null || evidenceJson.isBlank()) {
            return null;
        }
        try {
            JsonNode note = JsonMapper.shared().readTree(evidenceJson).path("refund").get("note_to_payer");
            return note == null || note.isNull() ? null : note.asString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private CloseOutcome reconcile(long closeId, LocalDate date) {
        List<String> notes = new ArrayList<>();
        List<CloseSummary.StepRun> stepRuns = runSteps(notes);

        List<String> orderIds = jdbc.sql("""
                        select distinct paypal_order_id from topups where paypal_order_id is not null order by 1
                        """)
                .query(String.class)
                .list();

        PayPalView view = null;
        String paypalError = null;
        PayPalGateway gateway = paypal.getIfAvailable();
        if (gateway == null) {
            paypalError = "PayPal is not configured in this app.";
        } else {
            try {
                view = readPayPal(gateway, orderIds);
                notes.addAll(view.notes());
            } catch (RuntimeException e) {
                paypalError = describe(e);
                log.warn("Close {} could not read PayPal: {}", closeId, paypalError);
            }
        }

        LedgerView books = readLedger();

        long derivedCash = 0;
        List<NewBreak> found = new ArrayList<>();
        if (view != null) {
            derivedCash = compare(closeId, view, books, found, notes);
        }
        for (NewBreak candidate : found) {
            Break stored = breaks.insertIfAbsent(candidate);
            if (stored.closeId() != null && stored.closeId() == closeId) {
                log.info("Close {} found break {} {} {}", closeId, stored.kind(), stored.paypalRef(),
                        Money.format(stored.amountCents()));
            }
        }

        List<Break> open = breaks.findOpen();
        int inDoubt = books.inDoubtTopups() + books.inDoubtLegs();
        Long derived = view == null ? null : derivedCash;
        Long unexplained = view == null ? null : books.ledgerCash() - derivedCash;

        List<CloseSummary.Check> checks = List.of(
                new CloseSummary.Check("balanced", "Every entry balances",
                        "Total debits", books.totalDebits(), "Total credits", books.totalCredits(),
                        books.totalDebits() == books.totalCredits() && books.unbalanced().isEmpty(),
                        books.unbalanced().isEmpty() ? "Debits equal credits in every entry."
                                : "Entries that do not balance: " + books.unbalanced()),
                new CloseSummary.Check("subledger", "Customer balances match the ledger",
                        "CUSTOMER_PREPAID in the ledger", books.prepaid(), "Sum of customer balances", books.owed(),
                        books.prepaid() == books.owed(),
                        books.prepaid() == books.owed() ? "The subledger agrees with the control account."
                                : "Difference " + Money.format(books.owed() - books.prepaid())),
                new CloseSummary.Check("paypal_cash", "Ledger cash matches PayPal",
                        "Cash per PayPal's records", derived, "PAYPAL_CASH in the ledger", books.ledgerCash(),
                        unexplained != null && unexplained == 0,
                        unexplained == null ? "Not compared, because PayPal could not be read."
                                : unexplained == 0 ? "Nothing unexplained."
                                : "Unexplained " + Money.format(unexplained)
                                + " (ledger minus PayPal's captures net of fees, less refunds)."));

        boolean checksPass = checks.stream().allMatch(CloseSummary.Check::pass);
        CloseStatus status = checksPass && open.isEmpty() && inDoubt == 0 && paypalError == null
                ? CloseStatus.CLOSED : CloseStatus.BROKEN;
        if (!open.isEmpty()) {
            notes.add(open.size() + (open.size() == 1 ? " break is" : " breaks are") + " not resolved.");
        }
        if (inDoubt > 0) {
            notes.add(count(books.inDoubtTopups(), "top-up") + " and " + count(books.inDoubtLegs(), "cash-out leg")
                    + " still in doubt or being sent to PayPal. Run the close again once they are settled.");
        }

        List<CloseSummary.BreakLine> lines = open.stream()
                .map(b -> new CloseSummary.BreakLine(b.id(), b.kind().name(), b.status().name(), b.customerId(),
                        b.paypalRef(), b.amountCents(), noteOf(b.evidenceJson()),
                        b.closeId() != null && b.closeId() == closeId))
                .toList();
        CloseSummary summary = new CloseSummary(checks, lines, stepRuns, books.inDoubtTopups(), books.inDoubtLegs(),
                view == null ? 0 : view.orders().size(), paypalError, books.journalEntryCount(), notes);
        runs.finish(closeId, status, books.owed(), books.ledgerCash(), derived, unexplained, toJson(summary));
        log.info("Close {} for {}: {} (unexplained {})", closeId, date, status, unexplained);

        // CloseOutcome holds primitives: when PayPal could not be read nothing is confirmed, so the
        // PayPal-derived figure is 0 and all of the ledger cash counts as unexplained.
        return new CloseOutcome(closeId, date, status, books.owed(), books.ledgerCash(),
                derived == null ? 0 : derived, unexplained == null ? books.ledgerCash() : unexplained,
                open.stream().map(Break::id).toList(), inDoubt,
                paypalError == null ? notes : prepend("PayPal could not be read: " + paypalError, notes));
    }

    private List<CloseSummary.StepRun> runSteps(List<String> notes) {
        List<CloseSummary.StepRun> out = new ArrayList<>();
        steps.orderedStream().forEach(step -> {
            try {
                out.add(new CloseSummary.StepRun(step.name(), step.run(), null));
            } catch (RuntimeException e) {
                log.warn("Pre-close step {} failed", step.name(), e);
                out.add(new CloseSummary.StepRun(step.name(), 0, describe(e)));
                notes.add("Step \"" + step.name() + "\" failed: " + describe(e));
            }
        });
        return out;
    }

    private PayPalView readPayPal(PayPalGateway gateway, List<String> orderIds) {
        Map<String, OrderSnapshot> orders = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (String orderId : orderIds) {
            OrderSnapshot order = gateway.getOrder(orderId);
            orders.put(orderId, order);
            order.captures().forEach(c -> seen.add(c.captureId()));
        }
        List<CaptureSnapshot> loose = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        // Webhooks can name captures no top-up points to, such as a payment taken in the PayPal dashboard.
        for (String captureId : webhookEvents.referencedCaptureIds()) {
            if (seen.contains(captureId)) {
                continue;
            }
            CaptureSnapshot capture;
            try {
                capture = gateway.getCapture(captureId);
            } catch (PayPalDeclinedException e) {
                if (e.httpStatus() == 404) {
                    notes.add("Webhook capture " + captureId + " does not exist at PayPal; ignored.");
                    seen.add(captureId);
                    continue;
                }
                throw e;
            }
            if (capture.orderId() != null && !orders.containsKey(capture.orderId())) {
                OrderSnapshot order = gateway.getOrder(capture.orderId());
                orders.put(order.orderId(), order);
                order.captures().forEach(c -> seen.add(c.captureId()));
            }
            if (seen.add(captureId)) {
                loose.add(capture);
            }
        }
        return new PayPalView(orders, loose, notes);
    }

    private LedgerView readLedger() {
        return snapshot.execute(s -> {
            List<TopupRow> topups = jdbc.sql("""
                            select id, customer_id, state, paypal_order_id, paypal_capture_id, amount_cents from topups
                            """)
                    .query((rs, n) -> new TopupRow(rs.getObject("id", UUID.class), rs.getLong("customer_id"),
                            rs.getString("state"), rs.getString("paypal_order_id"), rs.getString("paypal_capture_id"),
                            rs.getLong("amount_cents")))
                    .list();
            Map<UUID, TopupRow> byId = new HashMap<>();
            Map<String, TopupRow> byOrder = new HashMap<>();
            Map<String, TopupRow> byCapture = new HashMap<>();
            for (TopupRow t : topups) {
                byId.put(t.id(), t);
                if (t.orderId() != null) {
                    byOrder.put(t.orderId(), t);
                }
                if (t.captureId() != null) {
                    byCapture.put(t.captureId(), t);
                }
            }
            List<LegRow> legs = jdbc.sql("select id, capture_id, amount_cents, state, paypal_refund_id from cashout_legs order by created_at, leg_no")
                    .query((rs, n) -> new LegRow(rs.getObject("id", UUID.class), rs.getString("capture_id"),
                            rs.getLong("amount_cents"), rs.getString("state"), rs.getString("paypal_refund_id")))
                    .list();
            Set<String> resolved = new HashSet<>(jdbc.sql("select kind || ':' || paypal_ref from breaks where status = 'RESOLVED'")
                    .query(String.class)
                    .list());
            long[] totals = jdbc.sql("select coalesce(sum(debit_cents), 0), coalesce(sum(credit_cents), 0) from journal_lines")
                    .query((rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)})
                    .single();
            int inDoubtTopups = jdbc.sql("select count(*) from topups where state = 'IN_DOUBT'").query(Integer.class).single();
            // A PENDING leg the relay holds right now may already be refunded at PayPal.
            int inDoubtLegs = jdbc.sql("""
                            select count(*) from cashout_legs
                             where state = 'IN_DOUBT' or (state = 'PENDING' and claimed_until > now())
                            """)
                    .query(Integer.class)
                    .single();
            long entryCount = jdbc.sql("select count(*) from journal_entries").query(Long.class).single();
            return new LedgerView(byId, byOrder, byCapture, legs, resolved, totals[0], totals[1],
                    ledger.unbalancedEntryIds(), balances.totalOwed(), ledger.balanceOf(Account.CUSTOMER_PREPAID),
                    ledger.balanceOf(Account.PAYPAL_CASH), inDoubtTopups, inDoubtLegs, entryCount);
        });
    }

    /** Finds breaks and returns the cash PayPal's records say the shop holds. */
    private long compare(long closeId, PayPalView view, LedgerView books, List<NewBreak> found, List<String> notes) {
        Set<String> bookedRefunds = new HashSet<>();
        List<LegRow> unresolvedLegs = new ArrayList<>();
        for (LegRow leg : books.legs()) {
            if ("DONE".equals(leg.state()) && leg.refundId() != null) {
                bookedRefunds.add(leg.refundId());
            } else if (LEG_UNRESOLVED.contains(leg.state())) {
                unresolvedLegs.add(leg);
            }
        }
        long cash = 0;
        for (OrderSnapshot order : view.orders().values()) {
            TopupRow owner = books.topupsByOrder().get(order.orderId());
            for (CaptureSnapshot capture : order.captures()) {
                cash += checkCapture(closeId, capture, owner, books, found, notes);
            }
            for (RefundSnapshot refund : order.refunds()) {
                CaptureSnapshot capture = order.captures().stream()
                        .filter(c -> c.captureId().equals(refund.captureId()))
                        .findFirst()
                        .orElse(null);
                cash -= checkRefund(closeId, refund, capture, owner, books, bookedRefunds, unresolvedLegs, found, notes);
            }
        }
        for (CaptureSnapshot capture : view.captures()) {
            cash += checkCapture(closeId, capture, null, books, found, notes);
            if (!"COMPLETED".equals(capture.status()) && CAPTURE_HAS_MONEY.contains(capture.status())) {
                notes.add("Capture " + capture.captureId() + " is " + capture.status()
                        + " but its refunds could not be read without its order.");
            }
        }
        return cash;
    }

    private long checkCapture(long closeId, CaptureSnapshot capture, TopupRow owner, LedgerView books,
                              List<NewBreak> found, List<String> notes) {
        if (!CAPTURE_HAS_MONEY.contains(capture.status())) {
            return 0;
        }
        long net = capture.netCents() != null ? capture.netCents()
                : capture.feeCents() != null ? capture.amountCents() - capture.feeCents() : capture.amountCents();
        TopupRow byCapture = books.topupsByCapture().get(capture.captureId());
        if (byCapture != null && "CAPTURED".equals(byCapture.state())) {
            return net;
        }
        if (books.resolvedRefs().contains(BreakKind.UNBOOKED_CAPTURE.name() + ":" + capture.captureId())) {
            return net;
        }
        TopupRow related = byCapture != null ? byCapture : owner != null ? owner : topupFromCustomId(capture.customId(), books);
        if (related != null && TOPUP_UNRESOLVED.contains(related.state())) {
            notes.add("Capture " + capture.captureId() + " belongs to top-up " + related.id() + ", which is still "
                    + related.state() + "; in-doubt resolution will book it.");
            return net;
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("source", "PayPal order " + capture.orderId());
        evidence.put("capture", captureJson(capture));
        evidence.put("order_id", capture.orderId());
        evidence.put("customer_id", related == null ? null : related.customerId());
        evidence.put("topup_id", related == null ? null : related.id().toString());
        evidence.put("topup_state", related == null ? null : related.state());
        evidence.put("ledger_capture_id", related == null ? null : related.captureId());
        found.add(new NewBreak(closeId, BreakKind.UNBOOKED_CAPTURE, related == null ? null : related.customerId(),
                related == null ? null : related.id(), capture.captureId(), capture.amountCents(), toJson(evidence)));
        return net;
    }

    private long checkRefund(long closeId, RefundSnapshot refund, CaptureSnapshot capture, TopupRow owner,
                             LedgerView books, Set<String> bookedRefunds, List<LegRow> unresolvedLegs,
                             List<NewBreak> found, List<String> notes) {
        if (!"COMPLETED".equals(refund.status())) {
            notes.add("Refund " + refund.refundId() + " is " + refund.status() + " at PayPal; not counted yet.");
            return 0;
        }
        long effect = refund.netCents() != null ? refund.netCents() : refund.amountCents();
        if (bookedRefunds.contains(refund.refundId())
                || books.resolvedRefs().contains(BreakKind.UNBOOKED_REFUND.name() + ":" + refund.refundId())) {
            return effect;
        }
        LegRow leg = takeMatchingLeg(refund, unresolvedLegs);
        if (leg != null) {
            notes.add("Refund " + refund.refundId() + " matches cash-out leg " + leg.id() + ", which is still "
                    + leg.state() + "; leg resolution will book it.");
            return effect;
        }
        TopupRow topup = books.topupsByCapture().get(refund.captureId());
        if (topup == null) {
            topup = owner;
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("source", "PayPal order " + (capture == null ? null : capture.orderId()));
        evidence.put("refund", refundJson(refund));
        evidence.put("capture", capture == null ? null : captureJson(capture));
        evidence.put("customer_id", topup == null ? null : topup.customerId());
        evidence.put("topup_id", topup == null ? null : topup.id().toString());
        evidence.put("topup_state", topup == null ? null : topup.state());
        found.add(new NewBreak(closeId, BreakKind.UNBOOKED_REFUND, topup == null ? null : topup.customerId(),
                topup == null ? null : topup.id(), refund.refundId(), refund.amountCents(), toJson(evidence)));
        return effect;
    }

    /** A refund that an unresolved leg probably made: same refund id, or same capture and amount. */
    private static LegRow takeMatchingLeg(RefundSnapshot refund, List<LegRow> unresolvedLegs) {
        LegRow match = unresolvedLegs.stream().filter(l -> refund.refundId().equals(l.refundId())).findFirst()
                .orElseGet(() -> unresolvedLegs.stream()
                        .filter(l -> l.refundId() == null && refund.captureId() != null
                                && refund.captureId().equals(l.captureId()) && l.amountCents() == refund.amountCents())
                        .findFirst()
                        .orElse(null));
        if (match != null) {
            unresolvedLegs.remove(match);
        }
        return match;
    }

    private static TopupRow topupFromCustomId(String customId, LedgerView books) {
        if (customId == null || customId.isBlank()) {
            return null;
        }
        try {
            return books.topupsById().get(UUID.fromString(customId.trim()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Map<String, Object> captureJson(CaptureSnapshot c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.captureId());
        m.put("order_id", c.orderId());
        m.put("status", c.status());
        m.put("amount", Money.toDecimalString(c.amountCents()));
        m.put("amount_cents", c.amountCents());
        m.put("fee_cents", c.feeCents());
        m.put("net_cents", c.netCents());
        m.put("custom_id", c.customId());
        m.put("create_time", c.createTime() == null ? null : c.createTime().toString());
        return m;
    }

    private static Map<String, Object> refundJson(RefundSnapshot r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.refundId());
        m.put("capture_id", r.captureId());
        m.put("status", r.status());
        m.put("amount", Money.toDecimalString(r.amountCents()));
        m.put("amount_cents", r.amountCents());
        m.put("fee_cents", r.feeCents());
        m.put("net_cents", r.netCents());
        m.put("note_to_payer", r.noteToPayer());
        m.put("create_time", r.createTime() == null ? null : r.createTime().toString());
        return m;
    }

    private static String describe(RuntimeException e) {
        if (e instanceof PayPalException p) {
            StringBuilder s = new StringBuilder(p.getClass().getSimpleName().replace("PayPal", "").replace("Exception", ""));
            if (p.httpStatus() > 0) {
                s.append(" HTTP ").append(p.httpStatus());
            }
            if (p.issue() != null) {
                s.append(' ').append(p.issue());
            }
            s.append(": ").append(p.getMessage());
            if (p.debugId() != null) {
                s.append(" (debug id ").append(p.debugId()).append(')');
            }
            return s.toString();
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static List<String> prepend(String first, List<String> rest) {
        Set<String> out = new LinkedHashSet<>();
        out.add(first);
        out.addAll(rest);
        return List.copyOf(out);
    }

    private static String toJson(Object value) {
        return JsonMapper.shared().writeValueAsString(value);
    }
}
