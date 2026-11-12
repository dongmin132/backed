package dev.backed.paypal;

import dev.backed.paypal.FakePayPalGateway.Failure;
import dev.backed.paypal.FakePayPalGateway.Timing;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Plain unit tests: no Spring context. */
class FakePayPalGatewayTest {

    private static final TestCard CARD = new TestCard("4111111111111111", "2030-12", "123", "Sandbox");

    private final FakePayPalGateway fake = new FakePayPalGateway();

    @Test
    void captureComputesFeeAndIsIdempotentOnRequestId() {
        CaptureResult first = fake.captureCard("req-1", 10_000, "topup-1", CARD);
        CaptureResult again = fake.captureCard("req-1", 10_000, "topup-1", CARD);

        assertThat(again).isEqualTo(first);
        assertThat(first.feeCents()).isEqualTo(FakePayPalGateway.fee(10_000)).isEqualTo(398);
        assertThat(first.netCents()).isEqualTo(10_000 - 398);
        assertThat(fake.captureCount()).isEqualTo(1);
    }

    @Test
    void reusingRequestIdWithDifferentArgumentsIsABug() {
        fake.captureCard("req-1", 10_000, "topup-1", CARD);
        assertThatThrownBy(() -> fake.captureCard("req-1", 5_000, "topup-1", CARD))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lostResponseAfterEffectThenResendReturnsSameCapture() {
        fake.failNextCapture(Failure.UNKNOWN, Timing.AFTER_EFFECT);

        assertThatThrownBy(() -> fake.captureCard("req-1", 2_500, "topup-1", CARD))
                .isInstanceOf(PayPalOutcomeUnknownException.class);
        assertThat(fake.captureCount()).isEqualTo(1);

        CaptureResult resent = fake.captureCard("req-1", 2_500, "topup-1", CARD);
        assertThat(fake.captureCount()).isEqualTo(1);
        assertThat(fake.captures().getFirst().captureId()).isEqualTo(resent.captureId());
        assertThat(fake.captureCalls()).extracting(FakePayPalGateway.Call::requestId).containsOnly("req-1");
    }

    @Test
    void failureBeforeEffectLeavesNothingAndRetryExecutes() {
        fake.failNextCapture(Failure.UNAVAILABLE, Timing.BEFORE_EFFECT);
        assertThatThrownBy(() -> fake.captureCard("req-1", 2_500, "topup-1", CARD))
                .isInstanceOf(PayPalUnavailableException.class);
        assertThat(fake.captureCount()).isZero();

        fake.captureCard("req-1", 2_500, "topup-1", CARD);
        assertThat(fake.captureCount()).isEqualTo(1);
    }

    @Test
    void declinedCard() {
        fake.declineCardNumber(CARD.number());
        assertThatThrownBy(() -> fake.captureCard("req-1", 2_500, "topup-1", CARD))
                .isInstanceOf(PayPalDeclinedException.class)
                .extracting(e -> ((PayPalDeclinedException) e).issue()).isEqualTo("INSTRUMENT_DECLINED");
    }

    @Test
    void refundsAreLimitedToWhatIsRefundable() {
        CaptureResult c = fake.captureCard("req-1", 1_000, "topup-1", CARD);
        fake.refundCapture("ref-1", c.captureId(), 600, "cash-out");

        assertThat(fake.refundableCents(c.captureId())).isEqualTo(400);
        assertThat(fake.getCapture(c.captureId()).status()).isEqualTo("PARTIALLY_REFUNDED");
        assertThatThrownBy(() -> fake.refundCapture("ref-2", c.captureId(), 401, "too much"))
                .isInstanceOf(PayPalDeclinedException.class)
                .extracting(e -> ((PayPalDeclinedException) e).issue()).isEqualTo("REFUND_AMOUNT_EXCEEDED");

        assertThat(fake.refundCapture("ref-1", c.captureId(), 600, "cash-out").refundId())
                .isEqualTo(fake.refundsOf(c.captureId()).getFirst().refundId());
        fake.refundCapture("ref-3", c.captureId(), 400, "rest");
        assertThat(fake.getCapture(c.captureId()).status()).isEqualTo("REFUNDED");
    }

    @Test
    void scriptedRefundFailureCanTargetOneCapture() {
        CaptureResult c1 = fake.captureCard("req-1", 1_000, "topup-1", CARD);
        CaptureResult c2 = fake.captureCard("req-2", 1_000, "topup-2", CARD);
        fake.failNextRefundOn(c2.captureId(), Failure.UNAVAILABLE, Timing.BEFORE_EFFECT);

        fake.refundCapture("leg-1", c1.captureId(), 500, null);
        assertThatThrownBy(() -> fake.refundCapture("leg-2", c2.captureId(), 500, null))
                .isInstanceOf(PayPalUnavailableException.class);
        fake.refundCapture("leg-2", c2.captureId(), 500, null);

        assertThat(fake.refundCalls()).extracting(FakePayPalGateway.Call::requestId, FakePayPalGateway.Call::outcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("leg-1", "OK"),
                        org.assertj.core.groups.Tuple.tuple("leg-2", "UNAVAILABLE"),
                        org.assertj.core.groups.Tuple.tuple("leg-2", "OK"));
        assertThat(fake.refunds()).hasSize(2);
    }

    @Test
    void outsideRefundShowsUpInOrderSnapshot() {
        CaptureResult c = fake.captureCard("req-1", 5_000, "topup-1", CARD);
        RefundResult outside = fake.refundOutsideLedger(c.captureId(), 2_000, "Sorry about the wait");

        OrderSnapshot order = fake.getOrder(c.orderId());
        assertThat(order.customId()).isEqualTo("topup-1");
        assertThat(order.captures()).singleElement()
                .satisfies(cap -> assertThat(cap.status()).isEqualTo("PARTIALLY_REFUNDED"));
        assertThat(order.refundsOf(c.captureId())).singleElement().satisfies(r -> {
            assertThat(r.refundId()).isEqualTo(outside.refundId());
            assertThat(r.amountCents()).isEqualTo(2_000);
            assertThat(r.noteToPayer()).isEqualTo("Sorry about the wait");
        });
        assertThat(fake.refundCalls()).isEmpty();
    }

    @Test
    void unknownOrderIsNotFound() {
        assertThatThrownBy(() -> fake.getOrder("nope"))
                .isInstanceOf(PayPalDeclinedException.class)
                .extracting(e -> ((PayPalDeclinedException) e).httpStatus()).isEqualTo(404);
    }

    @Test
    void faultSwitchDropsCaptureResponseAfterPayPalAccepted() {
        FaultSwitch faults = new FaultSwitch();
        PayPalGateway gateway = new FaultInjectingPayPalGateway(fake, faults);
        faults.arm(FaultSwitch.Fault.DROP_NEXT_CAPTURE_RESPONSE);

        assertThatThrownBy(() -> gateway.captureCard("req-1", 1_000, "topup-1", CARD))
                .isInstanceOf(PayPalOutcomeUnknownException.class);
        assertThat(fake.captureCount()).isEqualTo(1);
        assertThat(faults.isArmed(FaultSwitch.Fault.DROP_NEXT_CAPTURE_RESPONSE)).isFalse();

        CaptureResult resent = gateway.captureCard("req-1", 1_000, "topup-1", CARD);
        assertThat(fake.captureCount()).isEqualTo(1);
        assertThat(resent.captureId()).isEqualTo(fake.captures().getFirst().captureId());
    }

    @Test
    void faultSwitchRefundFaultsNeverReachPayPal() {
        FaultSwitch faults = new FaultSwitch();
        PayPalGateway gateway = new FaultInjectingPayPalGateway(fake, faults);
        CaptureResult c = gateway.captureCard("req-1", 1_000, "topup-1", CARD);

        faults.arm(FaultSwitch.Fault.FAIL_NEXT_REFUND_WITH_5XX);
        assertThatThrownBy(() -> gateway.refundCapture("leg-1", c.captureId(), 100, null))
                .isInstanceOf(PayPalUnavailableException.class);
        faults.arm(FaultSwitch.Fault.DECLINE_NEXT_REFUND);
        assertThatThrownBy(() -> gateway.refundCapture("leg-1", c.captureId(), 100, null))
                .isInstanceOf(PayPalDeclinedException.class);
        assertThat(fake.refunds()).isEmpty();

        gateway.refundCapture("leg-1", c.captureId(), 100, null);
        assertThat(fake.refunds()).hasSize(1);
    }
}
