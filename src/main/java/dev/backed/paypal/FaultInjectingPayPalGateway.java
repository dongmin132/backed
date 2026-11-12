package dev.backed.paypal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps the real gateway and applies the armed {@link FaultSwitch} flags. The bean that other
 * modules inject as PayPalGateway is this wrapper around the SDK adapter (or the fake in tests).
 * Every injected fault is logged at WARN with the prefix "FAULT INJECTED".
 */
public class FaultInjectingPayPalGateway implements PayPalGateway {

    private static final Logger log = LoggerFactory.getLogger(FaultInjectingPayPalGateway.class);

    private final PayPalGateway delegate;
    private final FaultSwitch faults;

    public FaultInjectingPayPalGateway(PayPalGateway delegate, FaultSwitch faults) {
        this.delegate = delegate;
        this.faults = faults;
    }

    /** The wrapped gateway, for calls that must bypass fault injection (a refund made outside the ledger). */
    public PayPalGateway delegate() {
        return delegate;
    }

    @Override
    public CaptureResult captureCard(String requestId, long amountCents, String customId, TestCard card,
                                     OrderCreated onOrderCreated) {
        boolean drop = faults.consume(FaultSwitch.Fault.DROP_NEXT_CAPTURE_RESPONSE);
        CaptureResult result;
        try {
            result = delegate.captureCard(requestId, amountCents, customId, card, onOrderCreated);
        } catch (RuntimeException e) {
            if (drop) {
                log.warn("FAULT INJECTED: drop-capture-response was armed for request {}, but PayPal failed the "
                        + "capture itself ({}); the real failure is passed on", requestId, e.toString());
            }
            throw e;
        }
        if (drop) {
            log.warn("FAULT INJECTED: PayPal accepted capture request {} (order {}, capture {}); dropping the "
                    + "response and reporting outcome unknown", requestId, result.orderId(), result.captureId());
            throw new PayPalOutcomeUnknownException(
                    "Injected fault: capture response dropped after PayPal accepted request " + requestId);
        }
        return result;
    }

    @Override
    public RefundResult refundCapture(String requestId, String captureId, long amountCents, String noteToPayer) {
        if (faults.consume(FaultSwitch.Fault.FAIL_NEXT_REFUND_WITH_5XX)) {
            log.warn("FAULT INJECTED: refund request {} on capture {} failed with 503 before reaching PayPal",
                    requestId, captureId);
            throw new PayPalUnavailableException("Injected fault: refund failed with 503", 503, null);
        }
        if (faults.consume(FaultSwitch.Fault.DECLINE_NEXT_REFUND)) {
            log.warn("FAULT INJECTED: refund request {} on capture {} declined with 422 before reaching PayPal",
                    requestId, captureId);
            throw new PayPalDeclinedException("Injected fault: refund declined", 422, "INJECTED_DECLINE", null);
        }
        return delegate.refundCapture(requestId, captureId, amountCents, noteToPayer);
    }

    @Override
    public OrderSnapshot getOrder(String orderId) {
        return delegate.getOrder(orderId);
    }

    @Override
    public CaptureSnapshot getCapture(String captureId) {
        return delegate.getCapture(captureId);
    }

    @Override
    public boolean configured() {
        return delegate.configured();
    }
}
