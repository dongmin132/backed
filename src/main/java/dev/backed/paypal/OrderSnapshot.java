package dev.backed.paypal;

import java.util.List;

/** An order with every capture and refund PayPal holds for it. */
public record OrderSnapshot(
        String orderId,
        String status,
        String customId,
        List<CaptureSnapshot> captures,
        List<RefundSnapshot> refunds) {

    public OrderSnapshot {
        captures = List.copyOf(captures);
        refunds = List.copyOf(refunds);
    }

    public List<RefundSnapshot> refundsOf(String captureId) {
        return refunds.stream().filter(r -> captureId.equals(r.captureId())).toList();
    }
}
