package dev.backed.cashout;

import dev.backed.ledger.Account;
import dev.backed.ledger.BalanceService;
import dev.backed.ledger.EntryKind;
import dev.backed.ledger.JournalDraft;
import dev.backed.ledger.LedgerQueries;
import dev.backed.paypal.CaptureResult;
import dev.backed.paypal.CaptureSnapshot;
import dev.backed.paypal.FakePayPalGateway.Call;
import dev.backed.paypal.FakePayPalGateway.Failure;
import dev.backed.paypal.FakePayPalGateway.Timing;
import dev.backed.paypal.FaultSwitch;
import dev.backed.paypal.RefundSnapshot;
import dev.backed.support.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static dev.backed.ledger.JournalLineDraft.credit;
import static dev.backed.ledger.JournalLineDraft.debit;
import static org.assertj.core.api.Assertions.assertThat;

class CashoutTest extends AbstractDbTest {

    @Autowired
    CashoutService cashouts;
    @Autowired
    CashoutRelay relay;
    @Autowired
    CashoutQueries queries;
    @Autowired
    BalanceService balances;
    @Autowired
    LedgerQueries ledger;

    @Test
    void legsGoNewestCaptureFirstAndOnlyThe5xxLegIsResentWithItsOriginalRequestId() {
        long a = customerA().id();
        String oldest = capturedTopup(a, 1_000);
        String middle = capturedTopup(a, 2_000);
        String newest = capturedTopup(a, 500);

        CashoutRequestResult request = cashouts.request(a, 3_000L, "customer:" + a);

        assertThat(request.outcome()).isEqualTo(CashoutRequestResult.Outcome.ACCEPTED);
        assertThat(balanceOf(a)).isEqualTo(500);
        assertThat(ledger.balanceOf(Account.CASHOUT_CLEARING)).isEqualTo(3_000);
        assertThat(fakePayPal.refundCalls()).as("the request transaction never calls PayPal").isEmpty();
        List<CashoutQueries.LegView> legs = queries.legsOf(request.cashoutId());
        assertThat(legs).extracting(CashoutQueries.LegView::captureId).containsExactly(newest, middle, oldest);
        assertThat(legs).extracting(CashoutQueries.LegView::amountCents).containsExactly(500L, 2_000L, 500L);
        assertThat(legs).extracting(CashoutQueries.LegView::state).containsOnly("PENDING");
        String leg2 = CashoutService.legRequestId(request.cashoutId(), 2);
        assertThat(legs.get(1).requestId()).isEqualTo(leg2);

        fakePayPal.failNextRefundOn(middle, Failure.UNAVAILABLE, Timing.BEFORE_EFFECT);
        RelayReport first = relay.runOnce();

        assertThat(first.refunded()).isEqualTo(2);
        assertThat(first.retryLater()).isEqualTo(1);
        assertThat(fakePayPal.refundCalls()).hasSize(3);
        CashoutQueries.LegView waiting = queries.legsOf(request.cashoutId()).get(1);
        assertThat(waiting.state()).isEqualTo("PENDING");
        assertThat(waiting.attempts()).isEqualTo(1);
        assertThat(waiting.lastError()).contains("503");
        assertThat(cashoutState(request.cashoutId())).isEqualTo("REQUESTED");

        RelayReport second = relay.runOnce();

        assertThat(second.sent()).isEqualTo(1);
        assertThat(second.refunded()).isEqualTo(1);
        List<Call> calls = fakePayPal.refundCalls();
        assertThat(calls).hasSize(4);
        assertThat(calls.get(3).requestId()).isEqualTo(leg2);
        assertThat(calls.get(3).target()).isEqualTo(middle);
        assertThat(calls.get(3).outcome()).isEqualTo("OK");
        assertThat(calls).filteredOn(c -> c.requestId().equals(leg2)).extracting(Call::outcome)
                .containsExactly("UNAVAILABLE", "OK");
        assertThat(calls).filteredOn(c -> !c.requestId().equals(leg2)).hasSize(2);
        assertThat(queries.legsOf(request.cashoutId())).extracting(CashoutQueries.LegView::state).containsOnly("DONE");
        assertThat(cashoutState(request.cashoutId())).isEqualTo("SETTLED");
        assertThat(fakePayPal.refundsOf(middle)).extracting(RefundSnapshot::amountCents).containsExactly(2_000L);

        assertThat(relay.runOnce().sent()).as("a DONE leg is never sent again").isZero();
        assertThat(fakePayPal.refundCalls()).hasSize(4);
        assertThat(ledger.balanceOf(Account.CASHOUT_CLEARING)).isZero();
        assertBooksBalance();
        assertPayPalCashMatchesPayPal();
    }

    @Test
    void declinedLegIsNeverRetriedAndItsAmountReturnsToTheBalance() {
        long a = customerA().id();
        String older = capturedTopup(a, 1_000);
        String newer = capturedTopup(a, 1_000);
        CashoutRequestResult request = cashouts.request(a, null, "customer:" + a);
        assertThat(request.amountCents()).isEqualTo(2_000);
        assertThat(balanceOf(a)).isZero();
        String leg2 = CashoutService.legRequestId(request.cashoutId(), 2);

        fakePayPal.failNextRefundOn(older, Failure.DECLINED, Timing.BEFORE_EFFECT);
        RelayReport report = relay.runOnce();

        assertThat(report.refunded()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(1);
        CashoutQueries.LegView failed = queries.legsOf(request.cashoutId()).get(1);
        assertThat(failed.captureId()).isEqualTo(older);
        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.lastError()).contains("Declined");
        assertThat(cashoutState(request.cashoutId())).isEqualTo("PARTIALLY_SETTLED");
        assertThat(balanceOf(a)).isEqualTo(1_000);
        assertThat(ledger.entriesFor("cashout_leg", failed.id().toString()))
                .extracting(e -> e.kind()).containsExactly(EntryKind.CASHOUT_LEG_FAILED);

        assertThat(relay.runOnce().sent()).isZero();
        assertThat(fakePayPal.refundCalls()).filteredOn(c -> c.requestId().equals(leg2)).hasSize(1);
        assertThat(fakePayPal.refundsOf(newer)).hasSize(1);
        assertThat(fakePayPal.refundsOf(older)).isEmpty();
        assertThat(ledger.balanceOf(Account.CASHOUT_CLEARING)).isZero();
        assertBooksBalance();
        assertPayPalCashMatchesPayPal();
    }

    @Test
    void injectedDeclineFromTheFaultSwitchFailsTheLegAndCompensates() {
        long a = customerA().id();
        capturedTopup(a, 800);
        CashoutRequestResult request = cashouts.request(a, null, "customer:" + a);

        faults.arm(FaultSwitch.Fault.DECLINE_NEXT_REFUND);
        relay.runOnce();

        assertThat(cashoutState(request.cashoutId())).isEqualTo("COMPENSATED");
        assertThat(balanceOf(a)).isEqualTo(800);
        assertThat(fakePayPal.refunds()).isEmpty();
        assertBooksBalance();
    }

    @Test
    void repeated5xxIsRetriedWithTheSameRequestIdThenLeftInDoubtNotCompensated() {
        long a = customerA().id();
        capturedTopup(a, 1_000);
        CashoutRequestResult request = cashouts.request(a, null, "customer:" + a);
        for (int i = 0; i < 5; i++) {
            fakePayPal.failNextRefund(Failure.UNAVAILABLE, Timing.BEFORE_EFFECT);
        }

        for (int run = 1; run <= 4; run++) {
            assertThat(relay.runOnce().retryLater()).isEqualTo(1);
            assertThat(queries.legsOf(request.cashoutId()).getFirst().state()).isEqualTo("PENDING");
        }
        RelayReport last = relay.runOnce();

        assertThat(last.inDoubt()).isEqualTo(1);
        CashoutQueries.LegView leg = queries.legsOf(request.cashoutId()).getFirst();
        assertThat(leg.state()).isEqualTo("IN_DOUBT");
        assertThat(leg.attempts()).isEqualTo(5);
        assertThat(leg.lastError()).startsWith("No answer after 5 attempts");
        assertThat(balanceOf(a)).as("a 5xx does not prove the refund did not happen").isZero();
        assertThat(cashoutState(request.cashoutId())).isEqualTo("REQUESTED");

        assertThat(relay.runOnce().refunded()).isEqualTo(1);
        assertThat(fakePayPal.refundCalls()).hasSize(6)
                .extracting(Call::requestId).containsOnly(CashoutService.legRequestId(request.cashoutId(), 1));
        assertThat(fakePayPal.refunds()).hasSize(1);
        assertThat(cashoutState(request.cashoutId())).isEqualTo("SETTLED");
        assertThat(balanceOf(a)).isZero();
        assertBooksBalance();
        assertPayPalCashMatchesPayPal();
    }

    /** PayPal refunds on attempt 1 but answers 503; four more 503s follow. The leg must not be compensated. */
    @Test
    void a5xxAfterTheRefundTookEffectFollowedBy5xxDoesNotCompensate() {
        long a = customerA().id();
        capturedTopup(a, 1_000);
        CashoutRequestResult request = cashouts.request(a, null, "customer:" + a);
        fakePayPal.failNextRefund(Failure.UNAVAILABLE, Timing.AFTER_EFFECT);
        relay.runOnce();
        for (int i = 0; i < 4; i++) {
            faults.arm(FaultSwitch.Fault.FAIL_NEXT_REFUND_WITH_5XX);
            relay.runOnce();
        }

        assertThat(fakePayPal.refunds()).hasSize(1);
        assertThat(queries.legsOf(request.cashoutId()).getFirst().state()).isEqualTo("IN_DOUBT");
        assertThat(balanceOf(a)).isZero();

        relay.runOnce();
        assertThat(queries.legsOf(request.cashoutId()).getFirst().state()).isEqualTo("DONE");
        assertThat(fakePayPal.refunds()).hasSize(1);
        assertBooksBalance();
        assertPayPalCashMatchesPayPal();
    }

    /**
     * Three 5xx attempts; attempt 4 reaches PayPal and refunds, then the process stops before it records
     * the result and the claim lease runs out; attempt 5 gets a 5xx. The leg must not be compensated.
     */
    @Test
    void crashAfterRefundThenA5xxAtTheAttemptLimitDoesNotCompensate() {
        long a = customerA().id();
        capturedTopup(a, 1_000);
        CashoutRequestResult request = cashouts.request(a, null, "customer:" + a);
        for (int i = 0; i < 3; i++) {
            faults.arm(FaultSwitch.Fault.FAIL_NEXT_REFUND_WITH_5XX);
            relay.runOnce();
        }
        List<CashoutRelay.ClaimedLeg> claimed = relay.claim(true);
        assertThat(claimed).hasSize(1);
        CashoutRelay.ClaimedLeg leg = claimed.getFirst();
        fakePayPal.refundCapture(leg.requestId(), leg.captureId(), leg.amountCents(), CashoutService.NOTE_TO_PAYER);
        jdbc.sql("update cashout_legs set claimed_until = now() - interval '1 second'").update();

        faults.arm(FaultSwitch.Fault.FAIL_NEXT_REFUND_WITH_5XX);
        relay.runOnce();

        CashoutQueries.LegView after = queries.legsOf(request.cashoutId()).getFirst();
        assertThat(after.state()).as("an expired claim leaves the outcome unknown").isEqualTo("IN_DOUBT");
        assertThat(after.attempts()).isEqualTo(5);
        assertThat(fakePayPal.refunds()).hasSize(1);
        assertThat(balanceOf(a)).as("PayPal already refunded the leg").isZero();

        relay.runOnce();
        assertThat(queries.legsOf(request.cashoutId()).getFirst().state()).isEqualTo("DONE");
        assertBooksBalance();
        assertPayPalCashMatchesPayPal();
    }

    /** An IN_DOUBT leg whose first request did refund, then a 4xx on the re-send: never compensated. */
    @Test
    void inDoubtLegIsNotCompensatedWhenTheResendIsDeclined() {
        long a = customerA().id();
        String capture = capturedTopup(a, 1_000);
        CashoutRequestResult request = cashouts.request(a, null, "customer:" + a);
        fakePayPal.failNextRefundOn(capture, Failure.UNKNOWN, Timing.AFTER_EFFECT);
        relay.runOnce();
        assertThat(queries.legsOf(request.cashoutId()).getFirst().state()).isEqualTo("IN_DOUBT");

        faults.arm(FaultSwitch.Fault.DECLINE_NEXT_REFUND);
        assertThat(relay.runOnce().inDoubt()).isEqualTo(1);

        CashoutQueries.LegView leg = queries.legsOf(request.cashoutId()).getFirst();
        assertThat(leg.state()).isEqualTo("IN_DOUBT");
        assertThat(leg.lastError()).contains("refused the re-send");
        assertThat(balanceOf(a)).as("the refund exists at PayPal, so the balance must not come back").isZero();

        relay.runOnce();
        assertThat(queries.legsOf(request.cashoutId()).getFirst().state()).isEqualTo("DONE");
        assertThat(fakePayPal.refunds()).hasSize(1);
        assertBooksBalance();
        assertPayPalCashMatchesPayPal();
    }

    @Test
    void scheduledRunWaitsForTheRetryDelayButRunOnceDoesNot() {
        long a = customerA().id();
        capturedTopup(a, 1_000);
        cashouts.request(a, null, "customer:" + a);
        fakePayPal.failNextRefund(Failure.UNAVAILABLE, Timing.BEFORE_EFFECT);

        assertThat(relay.runDue().retryLater()).isEqualTo(1);
        assertThat(relay.runDue().sent()).as("still inside the retry delay").isZero();
        assertThat(relay.runOnce().refunded()).isEqualTo(1);
    }

    @Test
    void outcomeUnknownThenResumeLeavesExactlyOneRefundAtPayPal() {
        long a = customerA().id();
        String capture = capturedTopup(a, 1_500);
        CashoutRequestResult request = cashouts.request(a, 1_500L, "customer:" + a);
        String leg1 = CashoutService.legRequestId(request.cashoutId(), 1);

        fakePayPal.failNextRefundOn(capture, Failure.UNKNOWN, Timing.AFTER_EFFECT);
        RelayReport first = relay.runOnce();

        assertThat(first.inDoubt()).isEqualTo(1);
        assertThat(queries.legsOf(request.cashoutId()).getFirst().state()).isEqualTo("IN_DOUBT");
        assertThat(fakePayPal.refunds()).as("PayPal did refund; only the answer was lost").hasSize(1);
        assertThat(ledger.balanceOf(Account.CASHOUT_CLEARING)).isEqualTo(1_500);

        RelayReport second = relay.runOnce();

        assertThat(second.refunded()).isEqualTo(1);
        CashoutQueries.LegView leg = queries.legsOf(request.cashoutId()).getFirst();
        assertThat(leg.state()).isEqualTo("DONE");
        assertThat(fakePayPal.refunds()).hasSize(1);
        assertThat(leg.paypalRefundId()).isEqualTo(fakePayPal.refunds().getFirst().refundId());
        assertThat(fakePayPal.refundCalls()).extracting(Call::requestId).containsExactly(leg1, leg1);
        assertThat(fakePayPal.refundCalls()).extracting(Call::outcome).containsExactly("UNKNOWN", "OK (replayed)");
        assertThat(cashoutState(request.cashoutId())).isEqualTo("SETTLED");
        assertThat(ledger.balanceOf(Account.CASHOUT_CLEARING)).isZero();
        assertBooksBalance();
        assertPayPalCashMatchesPayPal();
    }

    @Test
    void anInDoubtLegIsNeverFailedFor5xxBecauseItsFirstRequestMayHaveWorked() {
        long a = customerA().id();
        String capture = capturedTopup(a, 1_000);
        CashoutRequestResult request = cashouts.request(a, null, "customer:" + a);
        fakePayPal.failNextRefundOn(capture, Failure.UNKNOWN, Timing.AFTER_EFFECT);
        relay.runOnce();

        for (int run = 0; run < 6; run++) {
            faults.arm(FaultSwitch.Fault.FAIL_NEXT_REFUND_WITH_5XX);
            assertThat(relay.runOnce().retryLater()).isEqualTo(1);
        }

        CashoutQueries.LegView leg = queries.legsOf(request.cashoutId()).getFirst();
        assertThat(leg.state()).isEqualTo("IN_DOUBT");
        assertThat(balanceOf(a)).as("no compensation while the refund may exist").isZero();

        relay.runOnce();
        assertThat(queries.legsOf(request.cashoutId()).getFirst().state()).isEqualTo("DONE");
        assertThat(fakePayPal.refunds()).hasSize(1);
        assertBooksBalance();
    }

    @Test
    void concurrentRelayRunsNeverSendTheSameLegTwice() throws Exception {
        List<Long> people = List.of(customerA().id(), customerB().id(), customerC().id());
        int legsExpected = 0;
        for (int round = 0; round < 3; round++) {
            int legsThisRound = 0;
            for (long customer : people) {
                capturedTopup(customer, 1_000);
                capturedTopup(customer, 2_000);
                capturedTopup(customer, 3_000);
                CashoutRequestResult r = cashouts.request(customer, null, "customer:" + customer);
                legsThisRound += r.legCount();
            }
            assertThat(legsThisRound).isEqualTo(9);
            legsExpected += legsThisRound;
            int threads = 6;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<RelayReport>> runs = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return relay.runOnce();
                }));
            }
            start.countDown();
            int sent = 0;
            for (Future<RelayReport> run : runs) {
                sent += run.get(30, TimeUnit.SECONDS).sent();
            }
            pool.shutdownNow();
            assertThat(sent).isEqualTo(legsThisRound);
        }

        List<Call> calls = fakePayPal.refundCalls();
        assertThat(calls).hasSize(legsExpected);
        assertThat(calls.stream().map(Call::requestId).distinct()).hasSize(legsExpected);
        assertThat(calls).extracting(Call::outcome).containsOnly("OK");
        assertThat(jdbc.sql("select count(*) from cashout_legs where state = 'DONE' and attempts = 1")
                .query(Long.class).single()).isEqualTo(legsExpected);
        assertThat(jdbc.sql("select distinct state from cashouts").query(String.class).list()).containsExactly("SETTLED");
        assertBooksBalance();
        assertPayPalCashMatchesPayPal();
    }

    @Test
    void cashoutLargerThanRefundableIsRefusedAndWritesNothing() {
        long a = customerA().id();
        capturedTopup(a, 1_000);
        fund(a, 500);
        long entriesBefore = jdbc.sql("select count(*) from journal_entries").query(Long.class).single();

        CashoutRequestResult partOfIt = cashouts.request(a, 1_200L, "customer:" + a);
        CashoutRequestResult moreThanBalance = cashouts.request(a, 2_000L, "customer:" + a);
        CashoutRequestResult everything = cashouts.request(a, null, "customer:" + a);

        assertThat(partOfIt.outcome()).isEqualTo(CashoutRequestResult.Outcome.REFUSED);
        assertThat(partOfIt.message()).contains("only $10.00");
        assertThat(moreThanBalance.outcome()).isEqualTo(CashoutRequestResult.Outcome.REFUSED);
        assertThat(moreThanBalance.message()).contains("the balance is $15.00");
        assertThat(everything.outcome()).isEqualTo(CashoutRequestResult.Outcome.REFUSED);
        assertThat(balanceOf(a)).isEqualTo(1_500);
        assertThat(jdbc.sql("select count(*) from cashouts").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from cashout_legs").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from journal_entries").query(Long.class).single()).isEqualTo(entriesBefore);
        assertThat(relay.runOnce().sent()).isZero();
        assertThat(fakePayPal.refundCalls()).isEmpty();

        assertThat(cashouts.request(a, 1_000L, "customer:" + a).outcome())
                .isEqualTo(CashoutRequestResult.Outcome.ACCEPTED);
    }

    @Test
    void allocationSubtractsLegsAlreadyQueuedOnACapture() {
        long a = customerA().id();
        String older = capturedTopup(a, 1_000);
        String newer = capturedTopup(a, 500);

        CashoutRequestResult first = cashouts.request(a, 300L, "customer:" + a);
        CashoutRequestResult second = cashouts.request(a, 700L, "customer:" + a);

        assertThat(queries.legsOf(first.cashoutId())).extracting(l -> l.captureId() + ":" + l.amountCents())
                .containsExactly(newer + ":300");
        assertThat(queries.legsOf(second.cashoutId())).extracting(l -> l.captureId() + ":" + l.amountCents())
                .containsExactly(newer + ":200", older + ":500");
        assertThat(balanceOf(a)).isEqualTo(500);
    }

    @Test
    void sameRequestIdCreatesOneCashout() {
        long a = customerA().id();
        capturedTopup(a, 1_000);

        CashoutRequestResult first = cashouts.request(a, 400L, "customer:" + a, "form-1");
        CashoutRequestResult again = cashouts.request(a, 400L, "customer:" + a, "form-1");

        assertThat(again.outcome()).isEqualTo(CashoutRequestResult.Outcome.ALREADY_REQUESTED);
        assertThat(again.cashoutId()).isEqualTo(first.cashoutId());
        assertThat(balanceOf(a)).isEqualTo(600);
    }

    // ---- helpers ------------------------------------------------------------------------------

    /**
     * A top-up the way the top-up module leaves it: captured at the fake PayPal, a CAPTURED row,
     * and the captured entry posted and applied to the balance. Returns the capture id.
     */
    private String capturedTopup(long customerId, long cents) {
        UUID id = UUID.randomUUID();
        CaptureResult capture = fakePayPal.captureCard("topup-" + id, cents, id.toString(), null);
        tx.executeWithoutResult(s -> {
            jdbc.sql("""
                            insert into topups (id, customer_id, amount_cents, state, request_id, paypal_order_id,
                                                paypal_capture_id, fee_cents, net_cents, business_date, created_by,
                                                created_at)
                            values (:id, :customerId, :amount, 'CAPTURED', :requestId, :orderId, :captureId, :fee, :net,
                                    (select business_date from business_day), 'test', clock_timestamp())
                            """)
                    .param("id", id)
                    .param("customerId", customerId)
                    .param("amount", cents)
                    .param("requestId", "topup-" + id)
                    .param("orderId", capture.orderId())
                    .param("captureId", capture.captureId())
                    .param("fee", capture.feeCents())
                    .param("net", capture.netCents())
                    .update();
            journal.postAndApply(JournalDraft.of(EntryKind.TOPUP_CAPTURED, "topup", id.toString(), "test top-up",
                    "test",
                    debit(Account.PAYPAL_CASH, capture.netCents()),
                    debit(Account.PAYPAL_FEES, capture.feeCents()),
                    credit(Account.CUSTOMER_PREPAID, customerId, cents)));
        });
        return capture.captureId();
    }

    private String cashoutState(UUID cashoutId) {
        return jdbc.sql("select state from cashouts where id = :id").param("id", cashoutId)
                .query(String.class).single();
    }

    private void assertBooksBalance() {
        assertThat(ledger.unbalancedEntryIds()).isEmpty();
        assertThat(jdbc.sql("select coalesce(sum(debit_cents), 0) - coalesce(sum(credit_cents), 0) from journal_lines")
                .query(Long.class).single()).isZero();
        assertThat(ledger.balanceOf(Account.CUSTOMER_PREPAID)).isEqualTo(balances.totalOwed());
        for (var c : customers.all()) {
            assertThat(ledger.customerPrepaid(c.id())).isEqualTo(balances.balanceOf(c.id()));
        }
    }

    /** Ledger PAYPAL_CASH equals what PayPal's own records add up to: capture nets less refund nets. */
    private void assertPayPalCashMatchesPayPal() {
        long captured = fakePayPal.captures().stream().mapToLong(CaptureSnapshot::netCents).sum();
        long refunded = fakePayPal.refunds().stream().mapToLong(RefundSnapshot::netCents).sum();
        assertThat(ledger.balanceOf(Account.PAYPAL_CASH)).isEqualTo(captured - refunded);
    }
}
