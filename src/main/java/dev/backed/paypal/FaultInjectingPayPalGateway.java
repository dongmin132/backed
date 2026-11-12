package dev.backed.paypal;

/**
 * Wraps the real gateway and applies the armed {@link FaultSwitch} flags. The bean that other
 * modules inject as PayPalGateway should be this wrapper around the SDK adapter.
 */
public class FaultInjectingPayPalGateway implements PayPalGateway {

    private final PayPalGateway delegate;
    private final FaultSwitch faults;

    public FaultInjectingPayPalGateway(PayPalGateway delegate, FaultSwitch faults) {
        this.delegate = delegate;
        this.faults = faults;
    }

    public PayPalGateway delegate() {
        return delegate;
    }

    @Override
    public CaptureResult captureCard(String requestId, long amountCents, String customId, TestCard card) {
        boolean drop = faults.consume(FaultSwitch.Fault.DROP_NEXT_CAPTURE_RESPONSE);
        CaptureResult result = delegate.captureCard(requestId, amountCents, customId, card);
        if (drop) {
            throw new PayPalOutcomeUnknownException(
                    "Injected fault: capture response dropped after PayPal accepted request " + requestId);
        }
        return result;
    }

    @Override
    public RefundResult refundCapture(String requestId, String captureId, long amountCents, String noteToPayer) {
        if (faults.consume(FaultSwitch.Fault.FAIL_NEXT_REFUND_WITH_5XX)) {
            throw new PayPalUnavailableException("Injected fault: refund failed with 503", 503, null);
        }
        if (faults.consume(FaultSwitch.Fault.DECLINE_NEXT_REFUND)) {
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
}
