package dev.backed.paypal;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-memory PayPal for tests. Thread-safe; every public method is synchronized.
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li>captureCard creates an order (status COMPLETED) with one COMPLETED capture. The fee is
 *       {@link #fee(long)}: 3.49% rounded half up plus 49 cents; net = amount - fee.</li>
 *   <li>refundCapture refunds part or all of a capture. A refund larger than what is still refundable
 *       throws {@link PayPalDeclinedException} (422, REFUND_AMOUNT_EXCEEDED); an unknown capture throws
 *       422 RESOURCE_NOT_FOUND. Refunds report feeCents 0 and netCents = amount. The capture's status
 *       becomes PARTIALLY_REFUNDED or REFUNDED.</li>
 *   <li>Request-id idempotency, like PayPal-Request-Id: once a write with a request id has taken effect,
 *       any later call with that id returns the original result and changes nothing. A call that failed
 *       before taking effect leaves the id unused, so the retry executes. Reusing an id with different
 *       arguments throws IllegalStateException (stricter than PayPal, to catch bugs).</li>
 *   <li>getOrder and getCapture return the current state, including refunds made with
 *       {@link #refundOutsideLedger}. Unknown ids throw 404 RESOURCE_NOT_FOUND.</li>
 * </ul>
 *
 * <h2>Scripting failures</h2>
 * Each script entry is consumed by the next matching call:
 * <ul>
 *   <li>{@code failNextCapture(Failure, Timing)}</li>
 *   <li>{@code failNextRefund(Failure, Timing)}: next refund on any capture</li>
 *   <li>{@code failNextRefundOn(captureId, Failure, Timing)}: next refund on that capture only</li>
 *   <li>{@code failNextGetOrder(Failure)}, {@code failNextGetCapture(Failure)}</li>
 *   <li>{@code declineCardNumber(number)}: every capture with that card is declined (422 INSTRUMENT_DECLINED)</li>
 *   <li>{@code failNextCaptureCallAfterOrderCreated(Failure)}: the next captureCard creates the order
 *       (status APPROVED, no capture), reports its id to the listener, then fails as the separate capture
 *       call would. A re-send with the same request id captures that same order.</li>
 * </ul>
 * Failure is DECLINED (PayPalDeclinedException 422), UNAVAILABLE (PayPalUnavailableException 503),
 * UNREACHABLE (PayPalUnavailableException with no HTTP status: the request never reached PayPal) or
 * UNKNOWN (PayPalOutcomeUnknownException). Timing BEFORE_EFFECT throws without changing anything;
 * AFTER_EFFECT applies the capture or refund first (and records the request id), then throws, which is
 * how a dropped response looks.
 *
 * <h2>Inspection</h2>
 * {@code calls()} lists every call in order (including failed ones) with its request id;
 * {@code captureCalls()}, {@code refundCalls()} filter it. {@code captures()}, {@code capture(id)},
 * {@code refunds()}, {@code refundsOf(captureId)}, {@code captureCount()} show PayPal's state.
 * {@code reset()} forgets everything.
 */
public class FakePayPalGateway implements PayPalGateway {

    public enum Failure { DECLINED, UNAVAILABLE, UNREACHABLE, UNKNOWN }

    public enum Timing { BEFORE_EFFECT, AFTER_EFFECT }

    public enum Op { CAPTURE, REFUND, GET_ORDER, GET_CAPTURE }

    /** One call as the fake saw it. outcome is "OK" or the failure that was thrown. */
    public record Call(Op op, String requestId, String target, long amountCents, String outcome) {
    }

    private record Script(Failure failure, Timing timing, String captureId) {
    }

    private record CaptureArgs(long amountCents, String customId, String cardNumber) {
    }

    private record RefundArgs(String captureId, long amountCents, String note) {
    }

    private static final class Order {
        final String id;
        final String customId;
        final List<String> captureIds = new ArrayList<>();

        Order(String id, String customId) {
            this.id = id;
            this.customId = customId;
        }
    }

    private static final class Capture {
        final String id;
        final String orderId;
        final long amountCents;
        final long feeCents;
        final String customId;
        final OffsetDateTime createTime;
        final List<String> refundIds = new ArrayList<>();

        Capture(String id, String orderId, long amountCents, long feeCents, String customId, OffsetDateTime createTime) {
            this.id = id;
            this.orderId = orderId;
            this.amountCents = amountCents;
            this.feeCents = feeCents;
            this.customId = customId;
            this.createTime = createTime;
        }
    }

    private final AtomicInteger sequence = new AtomicInteger();
    private final Map<String, Order> orders = new LinkedHashMap<>();
    private final Map<String, Capture> captures = new LinkedHashMap<>();
    private final Map<String, RefundSnapshot> refunds = new LinkedHashMap<>();
    private final Map<String, CaptureResult> captureByRequestId = new HashMap<>();
    private final Map<String, CaptureArgs> captureArgsByRequestId = new HashMap<>();
    private final Map<String, RefundResult> refundByRequestId = new HashMap<>();
    private final Map<String, RefundArgs> refundArgsByRequestId = new HashMap<>();
    private final Deque<Script> captureScripts = new ArrayDeque<>();
    private final Deque<Failure> captureCallScripts = new ArrayDeque<>();
    private final Map<String, Order> approvedOrderByRequestId = new HashMap<>();
    private final List<Script> refundScripts = new ArrayList<>();
    private final Deque<Failure> getOrderScripts = new ArrayDeque<>();
    private final Deque<Failure> getCaptureScripts = new ArrayDeque<>();
    private final List<String> declinedCards = new ArrayList<>();
    private final List<Call> calls = new ArrayList<>();

    /** PayPal-like fee: 3.49% rounded half up, plus 49 cents. */
    public static long fee(long amountCents) {
        return (amountCents * 349 + 5_000) / 10_000 + 49;
    }

    // ---- PayPalGateway ------------------------------------------------------------------------

    @Override
    public synchronized CaptureResult captureCard(String requestId, long amountCents, String customId, TestCard card,
                                                  OrderCreated onOrderCreated) {
        Objects.requireNonNull(requestId, "requestId");
        CaptureArgs args = new CaptureArgs(amountCents, customId, card == null ? null : card.number());
        CaptureResult earlier = captureByRequestId.get(requestId);
        if (earlier != null) {
            requireSameArgs(requestId, captureArgsByRequestId.get(requestId), args);
            calls.add(new Call(Op.CAPTURE, requestId, customId, amountCents, "OK (replayed)"));
            return earlier;
        }
        Script script = captureScripts.poll();
        if (script != null && script.timing() == Timing.BEFORE_EFFECT) {
            throw fail(Op.CAPTURE, requestId, customId, amountCents, script.failure());
        }
        if (amountCents <= 0) {
            throw decline(Op.CAPTURE, requestId, customId, amountCents, "INVALID_PARAMETER_VALUE");
        }
        if (card != null && declinedCards.contains(card.number())) {
            throw decline(Op.CAPTURE, requestId, customId, amountCents, "INSTRUMENT_DECLINED");
        }
        Failure captureCallFailure = captureCallScripts.poll();
        if (captureCallFailure != null) {
            Order approved = approvedOrderByRequestId.computeIfAbsent(requestId, k -> {
                Order o = new Order("FAKE-ORDER-" + sequence.incrementAndGet(), customId);
                orders.put(o.id, o);
                return o;
            });
            captureArgsByRequestId.put(requestId, args);
            onOrderCreated.orderCreated(approved.id);
            throw fail(Op.CAPTURE, requestId, customId, amountCents, captureCallFailure);
        }
        CaptureResult result = applyCapture(amountCents, customId, approvedOrderByRequestId.remove(requestId));
        captureByRequestId.put(requestId, result);
        captureArgsByRequestId.put(requestId, args);
        if (script != null) {
            throw fail(Op.CAPTURE, requestId, customId, amountCents, script.failure());
        }
        calls.add(new Call(Op.CAPTURE, requestId, customId, amountCents, "OK"));
        return result;
    }

    @Override
    public synchronized RefundResult refundCapture(String requestId, String captureId, long amountCents,
                                                   String noteToPayer) {
        Objects.requireNonNull(requestId, "requestId");
        RefundArgs args = new RefundArgs(captureId, amountCents, noteToPayer);
        RefundResult earlier = refundByRequestId.get(requestId);
        if (earlier != null) {
            requireSameArgs(requestId, refundArgsByRequestId.get(requestId), args);
            calls.add(new Call(Op.REFUND, requestId, captureId, amountCents, "OK (replayed)"));
            return earlier;
        }
        Script script = takeRefundScript(captureId);
        if (script != null && script.timing() == Timing.BEFORE_EFFECT) {
            throw fail(Op.REFUND, requestId, captureId, amountCents, script.failure());
        }
        RefundResult result = applyRefund(requestId, captureId, amountCents, noteToPayer);
        refundArgsByRequestId.put(requestId, args);
        if (script != null) {
            throw fail(Op.REFUND, requestId, captureId, amountCents, script.failure());
        }
        calls.add(new Call(Op.REFUND, requestId, captureId, amountCents, "OK"));
        return result;
    }

    @Override
    public synchronized OrderSnapshot getOrder(String orderId) {
        Failure failure = getOrderScripts.poll();
        if (failure != null) {
            throw fail(Op.GET_ORDER, null, orderId, 0, failure);
        }
        Order order = orders.get(orderId);
        if (order == null) {
            throw notFound(Op.GET_ORDER, orderId);
        }
        calls.add(new Call(Op.GET_ORDER, null, orderId, 0, "OK"));
        List<CaptureSnapshot> caps = order.captureIds.stream().map(id -> snapshot(captures.get(id))).toList();
        List<RefundSnapshot> refs = order.captureIds.stream()
                .flatMap(id -> captures.get(id).refundIds.stream())
                .map(refunds::get)
                .toList();
        return new OrderSnapshot(order.id, order.captureIds.isEmpty() ? "APPROVED" : "COMPLETED", order.customId, caps,
                refs);
    }

    @Override
    public synchronized CaptureSnapshot getCapture(String captureId) {
        Failure failure = getCaptureScripts.poll();
        if (failure != null) {
            throw fail(Op.GET_CAPTURE, null, captureId, 0, failure);
        }
        Capture capture = captures.get(captureId);
        if (capture == null) {
            throw notFound(Op.GET_CAPTURE, captureId);
        }
        calls.add(new Call(Op.GET_CAPTURE, null, captureId, 0, "OK"));
        return snapshot(capture);
    }

    // ---- Scripting ----------------------------------------------------------------------------

    public synchronized FakePayPalGateway failNextCapture(Failure failure, Timing timing) {
        captureScripts.add(new Script(failure, timing, null));
        return this;
    }

    public synchronized FakePayPalGateway failNextCaptureCallAfterOrderCreated(Failure failure) {
        captureCallScripts.add(failure);
        return this;
    }

    public synchronized FakePayPalGateway failNextRefund(Failure failure, Timing timing) {
        refundScripts.add(new Script(failure, timing, null));
        return this;
    }

    public synchronized FakePayPalGateway failNextRefundOn(String captureId, Failure failure, Timing timing) {
        refundScripts.add(new Script(failure, timing, Objects.requireNonNull(captureId)));
        return this;
    }

    public synchronized FakePayPalGateway failNextGetOrder(Failure failure) {
        getOrderScripts.add(failure);
        return this;
    }

    public synchronized FakePayPalGateway failNextGetCapture(Failure failure) {
        getCaptureScripts.add(failure);
        return this;
    }

    public synchronized FakePayPalGateway declineCardNumber(String cardNumber) {
        declinedCards.add(cardNumber);
        return this;
    }

    /**
     * A refund made directly at PayPal (for example in the dashboard), not through Backed. It gets
     * a fresh request id and does not appear in {@link #calls()}.
     */
    public synchronized RefundResult refundOutsideLedger(String captureId, long amountCents, String noteToPayer) {
        String requestId = "outside-" + sequence.incrementAndGet();
        RefundResult result = applyRefund(requestId, captureId, amountCents, noteToPayer);
        refundArgsByRequestId.put(requestId, new RefundArgs(captureId, amountCents, noteToPayer));
        return result;
    }

    /**
     * A capture PayPal has that Backed never recorded, on a new order with the given custom id.
     * Returns it as captureCard would have.
     */
    public synchronized CaptureResult captureOutsideLedger(long amountCents, String customId) {
        String requestId = "outside-" + sequence.incrementAndGet();
        CaptureResult result = applyCapture(amountCents, customId, null);
        captureByRequestId.put(requestId, result);
        captureArgsByRequestId.put(requestId, new CaptureArgs(amountCents, customId, null));
        return result;
    }

    // ---- Inspection ---------------------------------------------------------------------------

    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    public synchronized List<Call> captureCalls() {
        return calls.stream().filter(c -> c.op() == Op.CAPTURE).toList();
    }

    public synchronized List<Call> refundCalls() {
        return calls.stream().filter(c -> c.op() == Op.REFUND).toList();
    }

    /** Captures that exist at PayPal, in creation order. */
    public synchronized List<CaptureSnapshot> captures() {
        return captures.values().stream().map(this::snapshot).toList();
    }

    public synchronized int captureCount() {
        return captures.size();
    }

    public synchronized Optional<CaptureSnapshot> capture(String captureId) {
        return Optional.ofNullable(captures.get(captureId)).map(this::snapshot);
    }

    /** Refunds that exist at PayPal, in creation order, including ones made outside the ledger. */
    public synchronized List<RefundSnapshot> refunds() {
        return List.copyOf(refunds.values());
    }

    public synchronized List<RefundSnapshot> refundsOf(String captureId) {
        Capture capture = captures.get(captureId);
        return capture == null ? List.of() : capture.refundIds.stream().map(refunds::get).toList();
    }

    /** Amount still refundable on a capture. */
    public synchronized long refundableCents(String captureId) {
        Capture capture = captures.get(captureId);
        return capture == null ? 0 : capture.amountCents - refundedCents(capture);
    }

    public synchronized void reset() {
        orders.clear();
        captures.clear();
        refunds.clear();
        captureByRequestId.clear();
        captureArgsByRequestId.clear();
        refundByRequestId.clear();
        refundArgsByRequestId.clear();
        captureScripts.clear();
        captureCallScripts.clear();
        approvedOrderByRequestId.clear();
        refundScripts.clear();
        getOrderScripts.clear();
        getCaptureScripts.clear();
        declinedCards.clear();
        calls.clear();
    }

    // ---- Internals ----------------------------------------------------------------------------

    private CaptureResult applyCapture(long amountCents, String customId, Order existing) {
        Order order = existing != null ? existing : new Order("FAKE-ORDER-" + sequence.incrementAndGet(), customId);
        long fee = fee(amountCents);
        Capture capture = new Capture("FAKE-CAPTURE-" + sequence.incrementAndGet(), order.id, amountCents, fee,
                customId, now());
        order.captureIds.add(capture.id);
        orders.put(order.id, order);
        captures.put(capture.id, capture);
        return new CaptureResult(order.id, capture.id, "COMPLETED", amountCents, fee, amountCents - fee, customId);
    }

    private RefundResult applyRefund(String requestId, String captureId, long amountCents, String note) {
        Capture capture = captures.get(captureId);
        if (capture == null) {
            throw decline(Op.REFUND, requestId, captureId, amountCents, "RESOURCE_NOT_FOUND");
        }
        if (amountCents <= 0) {
            throw decline(Op.REFUND, requestId, captureId, amountCents, "INVALID_PARAMETER_VALUE");
        }
        if (amountCents > capture.amountCents - refundedCents(capture)) {
            throw decline(Op.REFUND, requestId, captureId, amountCents, "REFUND_AMOUNT_EXCEEDED");
        }
        String refundId = "FAKE-REFUND-" + sequence.incrementAndGet();
        RefundSnapshot snapshot = new RefundSnapshot(refundId, captureId, "COMPLETED", amountCents, 0L, amountCents,
                note, now());
        refunds.put(refundId, snapshot);
        capture.refundIds.add(refundId);
        RefundResult result = new RefundResult(refundId, captureId, "COMPLETED", amountCents, 0L, amountCents, note);
        refundByRequestId.put(requestId, result);
        return result;
    }

    private Script takeRefundScript(String captureId) {
        for (int i = 0; i < refundScripts.size(); i++) {
            Script s = refundScripts.get(i);
            if (s.captureId() == null || s.captureId().equals(captureId)) {
                return refundScripts.remove(i);
            }
        }
        return null;
    }

    private long refundedCents(Capture capture) {
        return capture.refundIds.stream().map(refunds::get)
                .filter(r -> "COMPLETED".equals(r.status()))
                .mapToLong(RefundSnapshot::amountCents)
                .sum();
    }

    private CaptureSnapshot snapshot(Capture c) {
        long refunded = refundedCents(c);
        String status = refunded == 0 ? "COMPLETED" : refunded == c.amountCents ? "REFUNDED" : "PARTIALLY_REFUNDED";
        return new CaptureSnapshot(c.id, c.orderId, status, c.amountCents, c.feeCents, c.amountCents - c.feeCents,
                c.customId, c.createTime);
    }

    private PayPalException fail(Op op, String requestId, String target, long amount, Failure failure) {
        calls.add(new Call(op, requestId, target, amount, failure.name()));
        return switch (failure) {
            case DECLINED -> new PayPalDeclinedException("Fake PayPal: declined", 422, "SCRIPTED_DECLINE", "fake-debug");
            case UNAVAILABLE -> new PayPalUnavailableException("Fake PayPal: 503 Service Unavailable", 503, "fake-debug");
            case UNREACHABLE -> new PayPalUnavailableException("Fake PayPal: connection refused", 0, null);
            case UNKNOWN -> new PayPalOutcomeUnknownException("Fake PayPal: read timed out");
        };
    }

    private PayPalDeclinedException decline(Op op, String requestId, String target, long amount, String issue) {
        calls.add(new Call(op, requestId, target, amount, "DECLINED " + issue));
        return new PayPalDeclinedException("Fake PayPal: " + issue, 422, issue, "fake-debug");
    }

    private PayPalDeclinedException notFound(Op op, String id) {
        calls.add(new Call(op, null, id, 0, "NOT_FOUND"));
        return new PayPalDeclinedException("Fake PayPal: " + id + " not found", 404, "RESOURCE_NOT_FOUND", "fake-debug");
    }

    private static void requireSameArgs(String requestId, Object original, Object now) {
        if (!Objects.equals(original, now)) {
            throw new IllegalStateException("Request id " + requestId + " reused with different arguments: "
                    + original + " then " + now);
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
