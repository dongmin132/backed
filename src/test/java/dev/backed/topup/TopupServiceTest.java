package dev.backed.topup;

import dev.backed.customer.Customers;
import dev.backed.ledger.Account;
import dev.backed.ledger.BalanceService;
import dev.backed.ledger.BusinessDay;
import dev.backed.ledger.EntryKind;
import dev.backed.ledger.LedgerQueries;
import dev.backed.paypal.FakePayPalGateway;
import dev.backed.paypal.FakePayPalGateway.Failure;
import dev.backed.paypal.FakePayPalGateway.Timing;
import dev.backed.paypal.FaultSwitch.Fault;
import dev.backed.paypal.PayPalGateway;
import dev.backed.paypal.TestCards;
import dev.backed.support.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopupServiceTest extends AbstractDbTest {

    @Autowired
    TopupService service;
    @Autowired
    Topups topups;
    @Autowired
    ResolveInDoubtTopupsStep closeStep;
    @Autowired
    LedgerQueries ledger;
    @Autowired
    BalanceService balances;
    @Autowired
    TestCards cards;
    @Autowired
    Customers customerRepo;
    @Autowired
    PayPalGateway gateway;
    @Autowired
    BusinessDay businessDay;

    @Test
    void capturedTopUpCreditsGrossAndBooksPayPalFee() {
        long a = customerA().id();
        Topup t = service.topUp(a, 2_500, "customer:" + a);

        long fee = FakePayPalGateway.fee(2_500);
        assertThat(t.state()).isEqualTo(TopupState.CAPTURED);
        assertThat(t.feeCents()).isEqualTo(fee);
        assertThat(t.netCents()).isEqualTo(2_500 - fee);
        assertThat(t.paypalCaptureId()).isEqualTo(fakePayPal.captures().get(0).captureId());
        assertThat(balanceOf(a)).isEqualTo(2_500);
        assertThat(ledger.balanceOf(Account.PAYPAL_CASH)).isEqualTo(2_500 - fee);
        assertThat(ledger.balanceOf(Account.PAYPAL_FEES)).isEqualTo(fee);
        assertThat(fakePayPal.captureCalls()).singleElement()
                .satisfies(c -> assertThat(c.requestId()).isEqualTo(t.requestId()));
        assertThat(entryKinds(t.id())).containsExactlyEntriesOf(Map.of(EntryKind.TOPUP_CAPTURED, 1L));
        assertLedgerConsistent();
    }

    @Test
    void lostCaptureResponseGoesInDoubtAndResolveCreditsOnceWithSameRequestId() {
        long a = customerA().id();
        faults.arm(Fault.DROP_NEXT_CAPTURE_RESPONSE);

        Topup t = service.topUp(a, 2_500, "customer:" + a);

        assertThat(t.state()).isEqualTo(TopupState.IN_DOUBT);
        assertThat(fakePayPal.captureCount()).as("PayPal really has the capture").isEqualTo(1);
        assertThat(balanceOf(a)).isZero();
        assertThat(ledger.balanceOf(Account.IN_DOUBT_RECEIVABLE)).isEqualTo(2_500);
        assertThat(ledger.balanceOf(Account.SUSPENSE_IN_DOUBT)).isEqualTo(2_500);
        assertLedgerConsistent();

        Topup resolved = service.resolve(t.id(), "maker");

        assertThat(resolved.state()).isEqualTo(TopupState.CAPTURED);
        assertThat(resolved.paypalCaptureId()).isEqualTo(fakePayPal.captures().get(0).captureId());
        assertThat(fakePayPal.captureCount()).isEqualTo(1);
        assertThat(fakePayPal.captureCalls()).hasSize(2)
                .allSatisfy(c -> assertThat(c.requestId()).isEqualTo(t.requestId()));
        assertThat(balanceOf(a)).isEqualTo(2_500);
        assertThat(ledger.balanceOf(Account.IN_DOUBT_RECEIVABLE)).isZero();
        assertThat(ledger.balanceOf(Account.SUSPENSE_IN_DOUBT)).isZero();
        assertThat(entryKinds(t.id())).containsExactlyInAnyOrderEntriesOf(Map.of(
                EntryKind.TOPUP_IN_DOUBT, 1L, EntryKind.TOPUP_IN_DOUBT_RESOLVED, 1L, EntryKind.TOPUP_CAPTURED, 1L));
        assertLedgerConsistent();

        Topup again = service.resolve(t.id(), "checker");
        assertThat(again.state()).isEqualTo(TopupState.CAPTURED);
        assertThat(fakePayPal.captureCalls()).as("a settled top-up is not sent again").hasSize(2);
        assertThat(balanceOf(a)).isEqualTo(2_500);
        assertLedgerConsistent();
    }

    @Test
    void readTimeoutAfterPayPalCapturedIsResolvedTheSameWay() {
        long a = customerA().id();
        fakePayPal.failNextCapture(Failure.UNKNOWN, Timing.AFTER_EFFECT);

        Topup t = service.topUp(a, 4_000, "customer:" + a);
        assertThat(t.state()).isEqualTo(TopupState.IN_DOUBT);

        assertThat(service.resolve(t.id(), "maker").state()).isEqualTo(TopupState.CAPTURED);
        assertThat(fakePayPal.captureCount()).isEqualTo(1);
        assertThat(balanceOf(a)).isEqualTo(4_000);
        assertLedgerConsistent();
    }

    @Test
    void newTopUpIsRefusedWhileOneIsInDoubt() {
        long a = customerA().id();
        faults.arm(Fault.DROP_NEXT_CAPTURE_RESPONSE);
        Topup first = service.topUp(a, 2_500, "customer:" + a);
        assertThat(first.state()).isEqualTo(TopupState.IN_DOUBT);

        assertThatThrownBy(() -> service.topUp(a, 1_000, "customer:" + a))
                .isInstanceOf(TopupRefusedException.class)
                .hasMessageContaining("$25.00")
                .hasMessageContaining("charged twice");
        assertThat(fakePayPal.captureCalls()).as("the refused top-up never reached PayPal").hasSize(1);
        assertThat(jdbc.sql("select count(*) from topups where customer_id = :id").param("id", a)
                .query(Long.class).single()).isEqualTo(1);

        long b = customerB().id();
        assertThat(service.topUp(b, 1_000, "customer:" + b).state())
                .as("other customers are not blocked").isEqualTo(TopupState.CAPTURED);

        service.resolve(first.id(), "maker");
        assertThat(service.topUp(a, 1_000, "customer:" + a).state()).isEqualTo(TopupState.CAPTURED);
        assertThat(balanceOf(a)).isEqualTo(3_500);
        assertLedgerConsistent();
    }

    @Test
    void declinedCardLeavesNoLedgerEntryAndNoBalance() {
        long a = customerA().id();
        fakePayPal.declineCardNumber(cards.get(customerA().cardRef()).number());

        Topup t = service.topUp(a, 2_500, "customer:" + a);

        assertThat(t.state()).isEqualTo(TopupState.DECLINED);
        assertThat(t.failureReason()).contains("INSTRUMENT_DECLINED").contains("Nothing was charged");
        assertThat(fakePayPal.captureCount()).isZero();
        assertThat(balanceOf(a)).isZero();
        assertThat(jdbc.sql("select count(*) from journal_entries").query(Long.class).single()).isZero();
        assertThat(service.topUp(a, 2_500, "customer:" + a).state())
                .as("a declined top-up does not block the next one").isEqualTo(TopupState.DECLINED);
        assertLedgerConsistent();
    }

    @Test
    void paypalUnreachableOnFirstAttemptChargesNothingAndDoesNotBlock() {
        long a = customerA().id();
        fakePayPal.failNextCapture(Failure.UNAVAILABLE, Timing.BEFORE_EFFECT);

        Topup t = service.topUp(a, 2_500, "customer:" + a);

        assertThat(t.state()).isEqualTo(TopupState.DECLINED);
        assertThat(t.failureReason()).contains("could not be reached").contains("Nothing was charged");
        assertThat(jdbc.sql("select count(*) from journal_entries").query(Long.class).single()).isZero();
        assertThat(service.topUp(a, 2_500, "customer:" + a).state()).isEqualTo(TopupState.CAPTURED);
        assertLedgerConsistent();
    }

    @Test
    void concurrentResolvesCreditOnce() throws Exception {
        long a = customerA().id();
        faults.arm(Fault.DROP_NEXT_CAPTURE_RESPONSE);
        Topup t = service.topUp(a, 2_500, "customer:" + a);
        assertThat(t.state()).isEqualTo(TopupState.IN_DOUBT);

        // A second service instance has its own in-process lock, like another app instance would,
        // so only the database row lock and state check stand between the two credits.
        TopupService otherInstance = new TopupService(topups, customerRepo, cards, gateway, journal, businessDay, tx);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Topup>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            TopupService s = i % 2 == 0 ? service : otherInstance;
            String actor = i % 2 == 0 ? "maker" : "checker";
            results.add(pool.submit(() -> {
                start.await();
                return s.resolve(t.id(), actor);
            }));
        }
        start.countDown();
        for (Future<Topup> f : results) {
            assertThat(f.get(30, TimeUnit.SECONDS).state()).isEqualTo(TopupState.CAPTURED);
        }
        pool.shutdown();

        assertThat(balanceOf(a)).isEqualTo(2_500);
        assertThat(fakePayPal.captureCount()).isEqualTo(1);
        assertThat(fakePayPal.captureCalls()).allSatisfy(c -> assertThat(c.requestId()).isEqualTo(t.requestId()));
        assertThat(entryKinds(t.id())).containsExactlyInAnyOrderEntriesOf(Map.of(
                EntryKind.TOPUP_IN_DOUBT, 1L, EntryKind.TOPUP_IN_DOUBT_RESOLVED, 1L, EntryKind.TOPUP_CAPTURED, 1L));
        assertLedgerConsistent();
    }

    @Test
    void requestThatNeverReachedPayPalIsCapturedOnceByTheResend() {
        long a = customerA().id();
        fakePayPal.failNextCapture(Failure.UNKNOWN, Timing.BEFORE_EFFECT);
        Topup t = service.topUp(a, 2_500, "customer:" + a);
        assertThat(t.state()).isEqualTo(TopupState.IN_DOUBT);
        assertThat(fakePayPal.captureCount()).isZero();

        assertThat(service.resolve(t.id(), "maker").state()).isEqualTo(TopupState.CAPTURED);
        assertThat(fakePayPal.captureCount()).isEqualTo(1);
        assertThat(balanceOf(a)).isEqualTo(2_500);
        assertLedgerConsistent();
    }

    @Test
    void inDoubtThatPayPalRefusesOnResendIsNotCapturedAndOnlyTheSuspenseIsReversed() {
        long a = customerA().id();
        fakePayPal.failNextCapture(Failure.UNKNOWN, Timing.BEFORE_EFFECT);
        Topup t = service.topUp(a, 2_500, "customer:" + a);
        fakePayPal.declineCardNumber(cards.get(customerA().cardRef()).number());

        Topup resolved = service.resolve(t.id(), "maker");

        assertThat(resolved.state()).isEqualTo(TopupState.NOT_CAPTURED);
        assertThat(balanceOf(a)).isZero();
        assertThat(ledger.balanceOf(Account.IN_DOUBT_RECEIVABLE)).isZero();
        assertThat(ledger.balanceOf(Account.SUSPENSE_IN_DOUBT)).isZero();
        assertThat(entryKinds(t.id())).containsExactlyInAnyOrderEntriesOf(Map.of(
                EntryKind.TOPUP_IN_DOUBT, 1L, EntryKind.TOPUP_IN_DOUBT_RESOLVED, 1L));
        assertThat(service.resolve(t.id(), "maker").state()).isEqualTo(TopupState.NOT_CAPTURED);
        assertLedgerConsistent();
    }

    @Test
    void resolveLeavesTopUpInDoubtWhilePayPalCannotAnswer() {
        long a = customerA().id();
        // The fake replays a request id that already took effect before it looks at failure scripts,
        // so the first call must not have reached PayPal for the re-send to fail.
        fakePayPal.failNextCapture(Failure.UNKNOWN, Timing.BEFORE_EFFECT);
        Topup t = service.topUp(a, 2_500, "customer:" + a);
        fakePayPal.failNextCapture(Failure.UNAVAILABLE, Timing.BEFORE_EFFECT);

        Topup still = service.resolve(t.id(), "maker");

        assertThat(still.state()).isEqualTo(TopupState.IN_DOUBT);
        assertThat(still.failureReason()).contains("could not be reached");
        assertThat(entryKinds(t.id())).containsExactlyEntriesOf(Map.of(EntryKind.TOPUP_IN_DOUBT, 1L));
        assertLedgerConsistent();

        assertThat(service.resolve(t.id(), "maker").state()).isEqualTo(TopupState.CAPTURED);
        assertThat(fakePayPal.captureCount()).isEqualTo(1);
        assertThat(balanceOf(a)).isEqualTo(2_500);
        assertLedgerConsistent();
    }

    @Test
    void closeStepResolvesEveryInDoubtTopUp() {
        long a = customerA().id();
        long b = customerB().id();
        faults.arm(Fault.DROP_NEXT_CAPTURE_RESPONSE);
        Topup ta = service.topUp(a, 2_500, "customer:" + a);
        fakePayPal.failNextCapture(Failure.UNKNOWN, Timing.AFTER_EFFECT);
        Topup tb = service.topUp(b, 1_200, "customer:" + b);
        assertThat(List.of(ta.state(), tb.state())).containsOnly(TopupState.IN_DOUBT);

        assertThat(closeStep.run()).isEqualTo(2);

        assertThat(topups.findById(ta.id()).orElseThrow().state()).isEqualTo(TopupState.CAPTURED);
        assertThat(topups.findById(tb.id()).orElseThrow().state()).isEqualTo(TopupState.CAPTURED);
        assertThat(balanceOf(a)).isEqualTo(2_500);
        assertThat(balanceOf(b)).isEqualTo(1_200);
        assertThat(fakePayPal.captureCount()).isEqualTo(2);
        assertThat(closeStep.run()).isZero();
        assertLedgerConsistent();
    }

    @Test
    void createdRowLeftByACrashIsResolvedByResendingItsRequestId() {
        long a = customerA().id();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into topups (id, customer_id, amount_cents, state, request_id, business_date, created_by,
                                            created_at, updated_at)
                        values (:id, :c, 1500, 'CREATED', :req, current_date, 'customer:1',
                                now() - interval '10 minutes', now() - interval '10 minutes')
                        """)
                .param("id", id).param("c", a).param("req", "topup-" + id)
                .update();

        assertThatThrownBy(() -> service.topUp(a, 1_000, "customer:" + a)).isInstanceOf(TopupRefusedException.class);
        assertThat(closeStep.run()).isEqualTo(1);

        Topup t = topups.findById(id).orElseThrow();
        assertThat(t.state()).isEqualTo(TopupState.CAPTURED);
        assertThat(fakePayPal.captureCalls()).singleElement()
                .satisfies(c -> assertThat(c.requestId()).isEqualTo("topup-" + id));
        assertThat(entryKinds(id)).containsExactlyEntriesOf(Map.of(EntryKind.TOPUP_CAPTURED, 1L));
        assertThat(balanceOf(a)).isEqualTo(1_500);
        assertLedgerConsistent();
    }

    @Test
    void guards() {
        long a = customerA().id();
        assertThatThrownBy(() -> service.topUp(a, 99, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.topUp(a, TopupService.MAX_CENTS + 1, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> service.topUp(a, 1_000, "x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");
        assertThat(fakePayPal.calls()).isEmpty();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private Map<String, Long> entryKinds(UUID topupId) {
        return jdbc.sql("""
                        select kind, count(*) as n from journal_entries
                         where ref_type = 'topup' and ref_id = :id group by kind
                        """)
                .param("id", topupId.toString())
                .query((rs, n) -> Map.entry(rs.getString("kind"), rs.getLong("n")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Ledger invariants after a scenario: entries balance, subledger matches, suspense and cash match top-ups. */
    private void assertLedgerConsistent() {
        assertThat(ledger.unbalancedEntryIds()).isEmpty();
        assertThat(balances.totalOwed()).isEqualTo(ledger.balanceOf(Account.CUSTOMER_PREPAID));
        long inDoubt = jdbc.sql("select coalesce(sum(amount_cents), 0) from topups where state = 'IN_DOUBT'")
                .query(Long.class).single();
        assertThat(ledger.balanceOf(Account.IN_DOUBT_RECEIVABLE)).isEqualTo(inDoubt);
        assertThat(ledger.balanceOf(Account.SUSPENSE_IN_DOUBT)).isEqualTo(inDoubt);
        long net = jdbc.sql("select coalesce(sum(net_cents), 0) from topups where state = 'CAPTURED'")
                .query(Long.class).single();
        long fees = jdbc.sql("select coalesce(sum(fee_cents), 0) from topups where state = 'CAPTURED'")
                .query(Long.class).single();
        assertThat(ledger.balanceOf(Account.PAYPAL_CASH)).isEqualTo(net);
        assertThat(ledger.balanceOf(Account.PAYPAL_FEES)).isEqualTo(fees);
        if (inDoubt == 0) {
            long paypalNet = fakePayPal.captures().stream().mapToLong(c -> c.netCents()).sum();
            assertThat(net).as("with nothing in doubt, ledger cash equals what PayPal holds").isEqualTo(paypalNet);
        }
    }
}
