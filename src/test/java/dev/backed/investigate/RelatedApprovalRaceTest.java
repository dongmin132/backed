package dev.backed.investigate;

import dev.backed.close.Break;
import dev.backed.close.BreakKind;
import dev.backed.close.BreakStatus;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Refund break R (fully refunds capture C2) gets a GOODWILL proposal; capture break C2 gets a
 * DUPLICATE_REVERSAL proposal that also books R. The test holds a row lock on the capture proposal so
 * that its approval passes its unlocked checks first and then waits while the refund proposal is
 * approved. The waiting approval must be refused, so refund R is booked once.
 */
class RelatedApprovalRaceTest extends InvestigateTestBase {

    @Test
    void concurrentApprovalsCannotBookOneRefundTwice() throws Exception {
        long c = customerC().id();
        Break refundBreak = scenarios.duplicate(c);
        Break captureBreak = breaks.findByRef(BreakKind.UNBOOKED_CAPTURE, "CAP-C2").orElseThrow();

        investigator.respond(output(Treatment.GOODWILL, Treatment.BALANCE_RETURNED, "REF-C2"));
        long refundProposal = service.investigate(refundBreak.id(), "maker");
        investigator.respond(output(Treatment.DUPLICATE_REVERSAL, Treatment.BALANCE_RETURNED, "CAP-C2"));
        long captureProposal = service.investigate(captureBreak.id(), "maker");
        assertThat(proposals.findById(refundProposal).orElseThrow().status()).isEqualTo(ProposalStatus.PENDING_APPROVAL);
        assertThat(proposals.findById(captureProposal).orElseThrow().status()).isEqualTo(ProposalStatus.PENDING_APPROVAL);

        ExecutorService pool = Executors.newFixedThreadPool(1);
        try (Connection locker = dataSource.getConnection()) {
            locker.setAutoCommit(false);
            try (PreparedStatement ps = locker.prepareStatement("select id from proposals where id = ? for update")) {
                ps.setLong(1, captureProposal);
                ps.executeQuery();
            }
            Future<String> second = pool.submit(() -> {
                try {
                    service.approve(captureProposal, "checker");
                    return "approved";
                } catch (RuntimeException e) {
                    return "refused: " + e.getMessage();
                }
            });
            waitForALockWaiter();
            service.approve(refundProposal, "checker");
            locker.commit();
            String secondResult = second.get(30, TimeUnit.SECONDS);

            long paypalCashCreditsFromCorrections = jdbc.sql("""
                            select coalesce(sum(l.credit_cents), 0) from journal_lines l
                              join journal_entries e on e.id = l.entry_id
                             where e.kind = 'CORRECTION' and l.account_code = 'PAYPAL_CASH'
                            """).query(Long.class).single();
            assertThat(secondResult).startsWith("refused").contains("Nothing was posted");
            assertThat(paypalCashCreditsFromCorrections).isEqualTo(refundBreak.amountCents());
            assertThat(correctionEntries(captureBreak.id())).isZero();
            assertThat(breaks.findById(captureBreak.id()).orElseThrow().status()).isEqualTo(BreakStatus.PROPOSED);
            assertThat(proposals.findById(captureProposal).orElseThrow().status())
                    .isEqualTo(ProposalStatus.PENDING_APPROVAL);
        } finally {
            pool.shutdownNow();
        }
    }

    private void waitForALockWaiter() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            long waiting = jdbc.sql("select count(*) from pg_locks where not granted").query(Long.class).single();
            if (waiting > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("the second approval never reached the proposal lock");
    }
}
