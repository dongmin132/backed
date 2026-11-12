package dev.backed.close;

import dev.backed.ledger.Account;
import dev.backed.ledger.EntryKind;
import dev.backed.ledger.JournalDraft;
import dev.backed.ledger.JournalLineDraft;
import dev.backed.ledger.LedgerQueries;
import dev.backed.paypal.CaptureResult;
import dev.backed.paypal.FakePayPalGateway;
import dev.backed.paypal.FaultSwitch;
import dev.backed.paypal.RefundResult;
import dev.backed.support.AbstractDbTest;
import dev.backed.webhook.WebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DayCloseServiceTest extends AbstractDbTest {

    @Autowired
    DayCloseService close;
    @Autowired
    BreakRepository breaks;
    @Autowired
    CloseRuns runs;
    @Autowired
    LedgerQueries ledger;
    @Autowired
    WebhookService webhooks;

    CloseFixtures fx;

    @BeforeEach
    void fixtures() {
        fx = new CloseFixtures(jdbc, tx, journal, fakePayPal);
    }

    @Test
    void emptyBooksClose() {
        CloseOutcome outcome = close.runClose("maker");

        assertThat(outcome.status()).isEqualTo(CloseStatus.CLOSED);
        assertThat(outcome.unexplainedCents()).isZero();
        assertThat(runs.findById(outcome.closeId()).orElseThrow().status()).isEqualTo(CloseStatus.CLOSED);
    }

    @Test
    void cleanBooksCloseAsClosed() {
        long a = customerA().id();
        long b = customerB().id();
        CloseFixtures.Topup topupA = fx.capturedTopup(a, 5_000);
        fx.capturedTopup(b, 2_000);
        tx.executeWithoutResult(s -> journal.postAndApply(JournalDraft.of(EntryKind.SPEND, "spend",
                UUID.randomUUID().toString(), "coffee", "clerk",
                JournalLineDraft.debit(Account.CUSTOMER_PREPAID, a, 500),
                JournalLineDraft.credit(Account.SALES_REVENUE, 500))));
        RefundResult cashout = fx.settledCashout(topupA, 1_000);

        CloseOutcome outcome = close.runClose("maker");

        long expectedCash = (5_000 - FakePayPalGateway.fee(5_000)) + (2_000 - FakePayPalGateway.fee(2_000)) - 1_000;
        assertThat(outcome.status()).isEqualTo(CloseStatus.CLOSED);
        assertThat(outcome.owedCents()).isEqualTo(5_000 + 2_000 - 500 - 1_000);
        assertThat(outcome.ledgerPaypalCashCents()).isEqualTo(expectedCash);
        assertThat(outcome.paypalDerivedCashCents()).isEqualTo(expectedCash);
        assertThat(outcome.unexplainedCents()).isZero();
        assertThat(outcome.openBreakIds()).isEmpty();
        assertThat(outcome.inDoubtCount()).isZero();
        assertThat(breaks.findByRef(BreakKind.UNBOOKED_REFUND, cashout.refundId())).isEmpty();

        CloseRuns.CloseRun run = runs.findById(outcome.closeId()).orElseThrow();
        CloseSummary summary = DayCloseService.readSummary(run.summaryJson());
        assertThat(summary).isNotNull();
        assertThat(summary.checks()).hasSize(3).allMatch(CloseSummary.Check::pass);
        assertThat(summary.ordersChecked()).isEqualTo(2);
        assertThat(run.unexplainedCents()).isZero();
        assertThat(run.paypalDerivedCashCents()).isEqualTo(expectedCash);
    }

    @Test
    void offBookRefundBreaksTheDayOnceAndTheApprovedCorrectionClosesIt() {
        long a = customerA().id();
        CloseFixtures.Topup topup = fx.capturedTopup(a, 5_000);
        RefundResult outside = fakePayPal.refundOutsideLedger(topup.capture().captureId(), 2_000,
                "Refunded by hand: customer asked for the rest of the class pack back");

        CloseOutcome first = close.runClose("maker");

        assertThat(first.status()).isEqualTo(CloseStatus.BROKEN);
        assertThat(first.unexplainedCents()).isEqualTo(2_000);
        assertThat(first.openBreakIds()).hasSize(1);
        Break found = breaks.findById(first.openBreakIds().getFirst()).orElseThrow();
        assertThat(found.kind()).isEqualTo(BreakKind.UNBOOKED_REFUND);
        assertThat(found.paypalRef()).isEqualTo(outside.refundId());
        assertThat(found.customerId()).isEqualTo(a);
        assertThat(found.topupId()).isEqualTo(topup.id());
        assertThat(found.amountCents()).isEqualTo(2_000);
        assertThat(found.closeId()).isEqualTo(first.closeId());
        assertThat(found.evidenceJson())
                .contains("customer asked for the rest of the class pack back")
                .contains(topup.capture().captureId())
                .contains(topup.id().toString());
        assertThat(DayCloseService.noteOf(found.evidenceJson()))
                .isEqualTo("Refunded by hand: customer asked for the rest of the class pack back");
        CloseSummary summary = DayCloseService.readSummary(runs.findById(first.closeId()).orElseThrow().summaryJson());
        assertThat(summary.checks()).filteredOn(c -> c.key().equals("paypal_cash")).singleElement()
                .satisfies(c -> {
                    assertThat(c.pass()).isFalse();
                    assertThat(c.differenceCents()).isEqualTo(2_000);
                });

        CloseOutcome second = close.runClose("maker");

        assertThat(second.status()).isEqualTo(CloseStatus.BROKEN);
        assertThat(second.openBreakIds()).containsExactly(found.id());
        assertThat(jdbc.sql("select count(*) from breaks").query(Integer.class).single()).isEqualTo(1);

        long entryId = tx.execute(s -> journal.postAndApply(new JournalDraft(null, EntryKind.CORRECTION, "break",
                String.valueOf(found.id()), "balance returned by hand", "maker", "checker",
                java.util.List.of(JournalLineDraft.debit(Account.CUSTOMER_PREPAID, a, 2_000),
                        JournalLineDraft.credit(Account.PAYPAL_CASH, 2_000)))));
        assertThat(breaks.markResolved(found.id(), entryId)).isTrue();

        CloseOutcome third = close.runClose("maker");

        assertThat(third.status()).isEqualTo(CloseStatus.CLOSED);
        assertThat(third.unexplainedCents()).isZero();
        assertThat(third.owedCents()).isEqualTo(3_000);
        assertThat(third.openBreakIds()).isEmpty();
        assertThat(close.runClose("maker").status()).isEqualTo(CloseStatus.CLOSED);
        assertThat(jdbc.sql("select count(*) from breaks").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void inDoubtTopupKeepsTheDayBroken() {
        fx.capturedTopup(customerA().id(), 1_000);
        fx.inDoubtTopup(customerB().id(), 2_500);
        // The resolve step asks PayPal first; PayPal is down, so the top-up stays in doubt.
        fakePayPal.failNextCapture(FakePayPalGateway.Failure.UNAVAILABLE, FakePayPalGateway.Timing.BEFORE_EFFECT);

        CloseOutcome outcome = close.runClose("maker");

        assertThat(outcome.status()).isEqualTo(CloseStatus.BROKEN);
        assertThat(outcome.inDoubtCount()).isEqualTo(1);
        assertThat(outcome.unexplainedCents()).isZero();
        assertThat(outcome.openBreakIds()).isEmpty();

        CloseOutcome next = close.runClose("maker");

        assertThat(next.status()).isEqualTo(CloseStatus.CLOSED);
        assertThat(next.inDoubtCount()).isZero();
        assertThat(next.unexplainedCents()).isZero();
        assertThat(jdbc.sql("select state from topups where customer_id = :c").param("c", customerB().id())
                .query(String.class).single()).isEqualTo("CAPTURED");
    }

    @Test
    void refundFromAnInDoubtLegIsLeftToLegResolutionNotRaisedAsABreak() {
        CloseFixtures.Topup topup = fx.capturedTopup(customerA().id(), 4_000);
        fx.inDoubtCashout(topup, 1_500);
        // The relay step re-sends the leg first; that call gets a 5xx, so the leg stays in doubt.
        faults.arm(FaultSwitch.Fault.FAIL_NEXT_REFUND_WITH_5XX);

        CloseOutcome outcome = close.runClose("maker");

        assertThat(outcome.status()).isEqualTo(CloseStatus.BROKEN);
        assertThat(outcome.inDoubtCount()).isEqualTo(1);
        assertThat(outcome.openBreakIds()).isEmpty();
        assertThat(jdbc.sql("select count(*) from breaks").query(Integer.class).single()).isZero();

        CloseOutcome next = close.runClose("maker");

        assertThat(next.status()).isEqualTo(CloseStatus.CLOSED);
        assertThat(next.inDoubtCount()).isZero();
        assertThat(next.unexplainedCents()).isZero();
        assertThat(fakePayPal.refunds()).hasSize(1);
        assertThat(jdbc.sql("select count(*) from breaks").query(Integer.class).single()).isZero();
    }

    /**
     * "Run close" clicked while the scheduled relay is in the middle of a leg's PayPal call: PayPal
     * already holds the refund, the leg is still PENDING and claimed. The leg counts as unresolved.
     */
    @Test
    void legBeingSentByTheRelayCountsAsUnresolved() {
        CloseFixtures.Topup topup = fx.capturedTopup(customerA().id(), 5_000);
        fx.inDoubtCashout(topup, 2_000);
        jdbc.sql("update cashout_legs set state = 'PENDING', claimed_until = now() + interval '60 seconds'").update();

        CloseOutcome outcome = close.runClose("maker");

        assertThat(outcome.status()).isEqualTo(CloseStatus.BROKEN);
        assertThat(outcome.openBreakIds()).isEmpty();
        assertThat(outcome.inDoubtCount()).isEqualTo(1);
        assertThat(outcome.notes()).anyMatch(n -> n.contains("being sent"));
    }

    @Test
    void captureOnADeclinedTopupIsAnUnbookedCapture() {
        long c = customerC().id();
        UUID id = UUID.randomUUID();
        CaptureResult capture = fakePayPal.captureCard("topup-" + id, 3_000, id.toString(), null);
        tx.executeWithoutResult(s -> fx.insertTopup(id, c, 3_000, "NOT_CAPTURED", capture.orderId(), null, null, null));

        CloseOutcome outcome = close.runClose("maker");

        assertThat(outcome.status()).isEqualTo(CloseStatus.BROKEN);
        assertThat(outcome.unexplainedCents()).isEqualTo(-capture.netCents());
        Break found = breaks.findByRef(BreakKind.UNBOOKED_CAPTURE, capture.captureId()).orElseThrow();
        assertThat(found.customerId()).isEqualTo(c);
        assertThat(found.topupId()).isEqualTo(id);
        assertThat(found.amountCents()).isEqualTo(3_000);
        assertThat(found.evidenceJson()).contains("NOT_CAPTURED");
    }

    @Test
    void captureKnownOnlyFromAWebhookIsAnUnbookedCapture() {
        CaptureResult walkIn = fakePayPal.captureOutsideLedger(1_200, "dashboard-sale");
        webhooks.receive(CloseFixtures.captureCompletedEvent("WH-CAP-1", walkIn.captureId(), 1_200));

        CloseOutcome outcome = close.runClose("maker");

        assertThat(outcome.status()).isEqualTo(CloseStatus.BROKEN);
        Break found = breaks.findByRef(BreakKind.UNBOOKED_CAPTURE, walkIn.captureId()).orElseThrow();
        assertThat(found.customerId()).isNull();
        assertThat(outcome.unexplainedCents()).isEqualTo(-walkIn.netCents());
    }

    @Test
    void webhookNamingACaptureUnknownToPayPalIsIgnored() {
        webhooks.receive(CloseFixtures.captureCompletedEvent("WH-SIM-1", "SIMULATOR-CAPTURE", 100));

        CloseOutcome outcome = close.runClose("maker");

        assertThat(outcome.status()).isEqualTo(CloseStatus.CLOSED);
        assertThat(outcome.notes()).anyMatch(n -> n.contains("SIMULATOR-CAPTURE"));
    }

    @Test
    void payPalErrorRecordsABrokenCloseAndChangesNothing() {
        CloseFixtures.Topup topup = fx.capturedTopup(customerA().id(), 5_000);
        fakePayPal.refundOutsideLedger(topup.capture().captureId(), 1_000, "by hand");
        fakePayPal.failNextGetOrder(FakePayPalGateway.Failure.UNAVAILABLE);

        CloseOutcome outcome = close.runClose("maker");

        assertThat(outcome.status()).isEqualTo(CloseStatus.BROKEN);
        assertThat(outcome.notes()).anyMatch(n -> n.contains("PayPal could not be read") && n.contains("503"));
        assertThat(jdbc.sql("select count(*) from breaks").query(Integer.class).single()).isZero();
        CloseRuns.CloseRun run = runs.findById(outcome.closeId()).orElseThrow();
        assertThat(run.status()).isEqualTo(CloseStatus.BROKEN);
        assertThat(run.paypalDerivedCashCents()).isNull();
        assertThat(run.unexplainedCents()).isNull();
        assertThat(DayCloseService.readSummary(run.summaryJson()).paypalError()).contains("503");
        assertThat(ledger.unbalancedEntryIds()).isEmpty();

        CloseOutcome retry = close.runClose("maker");
        assertThat(retry.status()).isEqualTo(CloseStatus.BROKEN);
        assertThat(retry.openBreakIds()).hasSize(1);
        assertThat(retry.unexplainedCents()).isEqualTo(1_000);
    }

    @Test
    void advanceNeedsAFreshClosedClose() {
        var today = jdbc.sql("select business_date from business_day").query(java.time.LocalDate.class).single();
        assertThatThrownBy(() -> close.advance("maker")).hasMessageContaining("Run the close");

        fx.inDoubtTopup(customerA().id(), 1_000);
        fakePayPal.failNextCapture(FakePayPalGateway.Failure.UNAVAILABLE, FakePayPalGateway.Timing.BEFORE_EFFECT);
        close.runClose("maker");
        assertThatThrownBy(() -> close.advance("maker")).hasMessageContaining("BROKEN");

        jdbc.sql("update topups set state = 'NOT_CAPTURED'").update();
        tx.executeWithoutResult(s -> journal.post(JournalDraft.of(EntryKind.TOPUP_IN_DOUBT_RESOLVED, "topup", "x",
                "resolved", "system:scheduler",
                JournalLineDraft.debit(Account.SUSPENSE_IN_DOUBT, 1_000),
                JournalLineDraft.credit(Account.IN_DOUBT_RECEIVABLE, 1_000))));
        assertThat(close.runClose("maker").status()).isEqualTo(CloseStatus.CLOSED);

        tx.executeWithoutResult(s -> journal.post(JournalDraft.of("TEST_ENTRY", "test", "after-close",
                "an entry written after the close", "test",
                JournalLineDraft.debit(Account.GOODWILL_EXPENSE, 100),
                JournalLineDraft.credit(Account.SALES_REVENUE, 100))));
        assertThatThrownBy(() -> close.advance("maker")).hasMessageContaining("books changed");

        assertThat(close.runClose("maker").status()).isEqualTo(CloseStatus.CLOSED);
        assertThat(close.advance("maker")).isEqualTo(today.plusDays(1));
        assertThatThrownBy(() -> close.advance("maker")).hasMessageContaining("Run the close");
    }
}
