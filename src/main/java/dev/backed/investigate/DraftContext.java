package dev.backed.investigate;

import dev.backed.close.Break;
import dev.backed.close.BreakKind;

/**
 * The facts code needs to build and check a correcting entry for one break. Amounts come only
 * from PayPal's records here, never from the model.
 *
 * @param movement                the break's own refund or capture; null when the evidence is unreadable
 * @param refundedCapture         for a refund break: the capture it refunds, when known
 * @param refundedCaptureCredited for a refund break: the ledger credited that capture to a customer
 * @param refundedCaptureBreak    for a refund break: an existing UNBOOKED_CAPTURE break for that capture
 * @param captureRefund           for a capture break: a refund of this capture found on a refund break
 * @param captureRefundBreak      the refund break that captureRefund came from
 * @param captureCredited         for a capture break: the ledger has in fact credited this capture
 */
public record DraftContext(
        Break brk,
        Long customerId,
        String customerName,
        long customerBalanceCents,
        PayPalMovement movement,
        PayPalMovement refundedCapture,
        boolean refundedCaptureCredited,
        Break refundedCaptureBreak,
        PayPalMovement captureRefund,
        Break captureRefundBreak,
        boolean captureCredited) {

    public boolean isRefund() {
        return brk.kind() == BreakKind.UNBOOKED_REFUND;
    }
}
