package dev.backed.investigate;

import dev.backed.close.Break;
import dev.backed.close.BreakKind;
import dev.backed.close.BreakStatus;
import dev.backed.ledger.Account;
import dev.backed.ledger.BusinessDay;
import dev.backed.ledger.JournalDraft;
import dev.backed.ledger.JournalEntryView;
import dev.backed.ledger.JournalLineDraft;
import dev.backed.ledger.LedgerQueries;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static dev.backed.investigate.Treatment.BALANCE_RETURNED;
import static dev.backed.investigate.Treatment.DUPLICATE_REVERSAL;
import static dev.backed.investigate.Treatment.GOODWILL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalTest extends InvestigateTestBase {

    @Autowired
    LedgerQueries ledger;
    @Autowired
    BusinessDay businessDay;

    private long goodwillProposal() {
        Break b = scenarios.goodwill(customerA().id());
        investigator.respond(output(GOODWILL, BALANCE_RETURNED, Scenarios.COLD_COFFEE_NOTE));
        return service.investigate(b.id(), "maker");
    }

    @Test
    void selfApprovalIsRejected() {
        long id = goodwillProposal();

        assertThatThrownBy(() -> service.approve(id, "maker"))
                .isInstanceOf(InvestigationException.class)
                .hasMessageContaining("A different user");

        assertThat(proposals.findById(id).orElseThrow().status()).isEqualTo(ProposalStatus.PENDING_APPROVAL);
        assertThat(correctionEntries(proposals.findById(id).orElseThrow().breakId())).isZero();
        assertThat(closeRunner.runs()).isZero();
    }

    @Test
    void approvalPostsABalancedEntryOnTheNextBusinessDateAndResolvesTheBreak() {
        long id = goodwillProposal();
        long a = customerA().id();
        long breakId = proposals.findById(id).orElseThrow().breakId();

        ApprovalResult result = service.approve(id, "checker");

        JournalEntryView entry = ledger.entriesFor(DraftPlan.REF_TYPE, String.valueOf(breakId)).getFirst();
        assertThat(entry.id()).isEqualTo(result.entryId());
        assertThat(entry.businessDate()).isEqualTo(businessDay.current().plusDays(1));
        assertThat(entry.createdBy()).isEqualTo("maker");
        assertThat(entry.approvedBy()).isEqualTo("checker");
        assertThat(entry.lines()).extracting(JournalEntryView.Line::account)
                .containsExactly(Account.GOODWILL_EXPENSE, Account.PAYPAL_CASH);
        assertThat(ledger.unbalancedEntryIds()).isEmpty();

        Break b = breaks.findById(breakId).orElseThrow();
        assertThat(b.status()).isEqualTo(BreakStatus.RESOLVED);
        assertThat(b.resolvedEntryId()).isEqualTo(result.entryId());
        Proposal p = proposals.findById(id).orElseThrow();
        assertThat(p.status()).isEqualTo(ProposalStatus.APPROVED);
        assertThat(p.approvedBy()).isEqualTo("checker");
        assertThat(p.postedEntryId()).isEqualTo(result.entryId());
        assertThat(closeRunner.runs()).isEqualTo(1);
        assertThat(result.closeOutcome()).isNotNull();

        // Goodwill: the customer keeps the full balance; the shop bears the cost.
        assertThat(balanceOf(a)).isEqualTo(2_000);
        assertThat(ledger.balanceOf(Account.GOODWILL_EXPENSE)).isEqualTo(500);
        assertThat(ledger.customerPrepaid(a)).isEqualTo(2_000);
    }

    @Test
    void balanceReturnedReducesTheBalanceWhileGoodwillDoesNot() {
        long b = customerB().id();
        Break brk = scenarios.balanceReturned(b);
        investigator.respond(output(BALANCE_RETURNED, GOODWILL, Scenarios.MOVING_NOTE));
        long id = service.investigate(brk.id(), "maker");
        assertThat(balanceOf(b)).isEqualTo(3_000);

        service.approve(id, "checker");

        assertThat(balanceOf(b)).isZero();
        assertThat(ledger.customerPrepaid(b)).isZero();
        assertThat(ledger.balanceOf(Account.GOODWILL_EXPENSE)).isZero();

        long a = customerA().id();
        service.approve(goodwillProposal(), "checker");
        assertThat(balanceOf(a)).isEqualTo(2_000);
        assertThat(ledger.unbalancedEntryIds()).isEmpty();
    }

    @Test
    void duplicateReversalLeavesTheBalanceAndResolvesBothHalves() {
        long c = customerC().id();
        Break refundBreak = scenarios.duplicate(c);
        investigator.respond(output(DUPLICATE_REVERSAL, BALANCE_RETURNED, Scenarios.CHARGED_TWICE));
        long id = service.investigate(refundBreak.id(), "maker");

        service.approve(id, "checker");

        assertThat(balanceOf(c)).isEqualTo(1_500);
        assertThat(breaks.findById(refundBreak.id()).orElseThrow().status()).isEqualTo(BreakStatus.RESOLVED);
        assertThat(breaks.findByRef(BreakKind.UNBOOKED_CAPTURE, "CAP-C2").orElseThrow().status())
                .isEqualTo(BreakStatus.RESOLVED);
        assertThat(ledger.unbalancedEntryIds()).isEmpty();
    }

    @Test
    void concurrentDoubleApprovalPostsOnce() throws Exception {
        long id = goodwillProposal();
        long breakId = proposals.findById(id).orElseThrow().breakId();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (String checker : List.of("checker", "checker")) {
                Callable<Boolean> approve = () -> {
                    start.await();
                    try {
                        service.approve(id, checker);
                        return true;
                    } catch (InvestigationException e) {
                        return false;
                    }
                };
                results.add(pool.submit(approve));
            }
            start.countDown();
            int successes = 0;
            for (Future<Boolean> f : results) {
                successes += f.get() ? 1 : 0;
            }
            assertThat(successes).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(correctionEntries(breakId)).isEqualTo(1);
        assertThat(balanceOf(customerA().id())).isEqualTo(2_000);
        assertThat(closeRunner.runs()).isEqualTo(1);
    }

    @Test
    void onlyAPendingProposalOnAnOpenBreakCanBeApproved() {
        long dismissed = goodwillProposal();
        service.dismiss(dismissed, "checker");
        assertThatThrownBy(() -> service.approve(dismissed, "checker")).hasMessageContaining("dismissed");

        Break brk = breaks.findById(proposals.findById(dismissed).orElseThrow().breakId()).orElseThrow();
        investigator.respond(output(GOODWILL, BALANCE_RETURNED, "not in the evidence at all"));
        long rejected = service.investigate(brk.id(), "maker");
        assertThatThrownBy(() -> service.approve(rejected, "checker")).hasMessageContaining("rejected by validator");

        investigator.respond(output(GOODWILL, BALANCE_RETURNED, Scenarios.COLD_COFFEE_NOTE));
        long pending = service.investigate(brk.id(), "maker");
        // Someone books the refund another way before the checker gets to it.
        long other = tx.execute(s -> journal.post(JournalDraft.of("MANUAL", "manual", "1", "booked by hand", "maker",
                JournalLineDraft.debit(Account.GOODWILL_EXPENSE, 500),
                JournalLineDraft.credit(Account.PAYPAL_CASH, 500))));
        breaks.markResolved(brk.id(), other);
        assertThatThrownBy(() -> service.approve(pending, "checker")).hasMessageContaining("already resolved");
        assertThat(correctionEntries(brk.id())).isZero();
    }

    @Test
    void reValidationAtApprovalCatchesABalanceSpentInTheMeantime() {
        long b = customerB().id();
        Break brk = scenarios.balanceReturned(b);
        investigator.respond(output(BALANCE_RETURNED, GOODWILL, Scenarios.MOVING_NOTE));
        long id = service.investigate(brk.id(), "maker");
        scenarios.spend(b, 1_000);

        assertThatThrownBy(() -> service.approve(id, "checker")).hasMessageContaining("below zero");
        assertThat(correctionEntries(brk.id())).isZero();
        assertThat(balanceOf(b)).isEqualTo(2_000);
    }
}
