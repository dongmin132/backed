package dev.backed.investigate;

import dev.backed.close.Break;
import dev.backed.close.BreakStatus;
import dev.backed.ledger.Account;
import dev.backed.ledger.JournalLineDraft;
import dev.backed.paypal.FakePayPalGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.backed.investigate.Treatment.BALANCE_RETURNED;
import static dev.backed.investigate.Treatment.DUPLICATE_REVERSAL;
import static dev.backed.investigate.Treatment.GOODWILL;
import static dev.backed.investigate.Treatment.NEEDS_HUMAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestigationTest extends InvestigateTestBase {

    @Test
    void goodwillProposalIsValidatedAndAwaitsApproval() {
        Break b = scenarios.goodwill(customerA().id());
        investigator.respond(output(GOODWILL, BALANCE_RETURNED, Scenarios.COLD_COFFEE_NOTE, "My latte was cold"));

        long id = service.investigate(b.id(), "maker");

        Proposal p = proposals.findById(id).orElseThrow();
        assertThat(p.status()).isEqualTo(ProposalStatus.PENDING_APPROVAL);
        assertThat(p.validationOk()).isTrue();
        assertThat(p.createdBy()).isEqualTo("maker");
        assertThat(breaks.findById(b.id()).orElseThrow().status()).isEqualTo(BreakStatus.PROPOSED);

        DraftPlan draft = Json.read(p.draft(), DraftPlan.class);
        assertThat(draft.lines()).containsExactly(
                JournalLineDraft.debit(Account.GOODWILL_EXPENSE, 500),
                JournalLineDraft.credit(Account.PAYPAL_CASH, 500));
        assertThat(draft.customerBalanceEffectCents()).isZero();
        assertThat(draft.shopResultEffectCents()).isEqualTo(-500);

        ProposalService.AlternativeDraft alt = Json.read(p.alternativeDraft(), ProposalService.AlternativeDraft.class);
        assertThat(alt.plan().treatment()).isEqualTo(BALANCE_RETURNED);
        assertThat(alt.plan().customerBalanceEffectCents()).isEqualTo(-500);
        assertThat(alt.plan().shopResultEffectCents()).isZero();

        // The bundle the model saw is stored with the proposal and holds every kind of evidence.
        assertThat(p.evidenceBundle()).isEqualTo(investigator.lastBundle())
                .contains(Scenarios.COLD_COFFEE_NOTE, "My latte was cold", "CAP-A1", "\"topups\"",
                        "\"ledgerHistory\"", "\"otherOpenBreaks\"");
        assertThat(Json.tryParse(p.evidenceBundle()).orElseThrow().path("customer").path("currentBalance").asString())
                .isEqualTo("$20.00");
    }

    @Test
    void aQuoteThatIsNotInTheBundleFailsValidation() {
        Break b = scenarios.goodwill(customerA().id());
        investigator.respond(output(GOODWILL, BALANCE_RETURNED, Scenarios.COLD_COFFEE_NOTE,
                "The customer asked for a refund of the latte"));

        long id = service.investigate(b.id(), "maker");

        Proposal p = proposals.findById(id).orElseThrow();
        assertThat(p.status()).isEqualTo(ProposalStatus.REJECTED_BY_VALIDATOR);
        assertThat(p.validationOk()).isFalse();
        assertThat(p.validationErrors()).contains(DraftValidator.QUOTES, "The customer asked for a refund of the latte");
        assertThat(breaks.findById(b.id()).orElseThrow().status()).isEqualTo(BreakStatus.OPEN);
    }

    @Test
    void anAmountThatDiffersFromPayPalFailsValidation() {
        long a = customerA().id();
        scenarios.topup(a, 2_000, "CAP-A1", Scenarios.T0);
        // The close recorded $6.00 but PayPal's refund says $5.00.
        Break b = breaks.insertIfAbsent(new dev.backed.close.NewBreak(null, dev.backed.close.BreakKind.UNBOOKED_REFUND,
                a, null, "REF-A9", 600, Json.write(new dev.backed.paypal.RefundSnapshot("REF-A9", "CAP-A1",
                "COMPLETED", 500, 0L, 500L, Scenarios.COLD_COFFEE_NOTE, Scenarios.T0))));
        investigator.respond(output(GOODWILL, BALANCE_RETURNED, Scenarios.COLD_COFFEE_NOTE));

        Proposal p = proposals.findById(service.investigate(b.id(), "maker")).orElseThrow();

        assertThat(p.status()).isEqualTo(ProposalStatus.REJECTED_BY_VALIDATOR);
        assertThat(p.validationErrors()).contains(DraftValidator.AMOUNTS, "the break records $6.00");
    }

    @Test
    void aDraftWhoseLinesDifferFromPayPalFailsTheAmountCheck() {
        Break b = scenarios.goodwill(customerA().id());
        DraftContext ctx = new DraftContext(b, customerA().id(), "Demo Customer A", 2_000,
                PayPalMovement.refundFrom(b.evidenceJson()).orElseThrow(), null, false, null, null, null, false);
        DraftPlan honest = DraftBuilder.build(ctx, GOODWILL);
        assertThat(DraftValidator.allOk(DraftValidator.ledgerChecks(ctx, honest, DraftValidator.Stage.PROPOSE))).isTrue();

        DraftPlan tampered = DraftPlan.of(GOODWILL, b.id(), List.of(
                JournalLineDraft.debit(Account.GOODWILL_EXPENSE, 450),
                JournalLineDraft.credit(Account.PAYPAL_CASH, 450)), honest.memo(), List.of(), null);
        List<ValidationCheck> checks = DraftValidator.ledgerChecks(ctx, tampered, DraftValidator.Stage.PROPOSE);

        assertThat(checks).filteredOn(c -> !c.ok()).extracting(ValidationCheck::name).containsExactly(DraftValidator.AMOUNTS);
        assertThat(DraftValidator.failures(checks).getFirst()).contains("moves $4.50 but PayPal moved $5.00");
    }

    @Test
    void balanceReturnedLargerThanTheBalanceFailsValidation() {
        long b = customerB().id();
        Break brk = scenarios.balanceReturned(b);
        scenarios.spend(b, 2_500);
        investigator.respond(output(BALANCE_RETURNED, GOODWILL, Scenarios.MOVING_NOTE));

        Proposal p = proposals.findById(service.investigate(brk.id(), "maker")).orElseThrow();

        assertThat(p.status()).isEqualTo(ProposalStatus.REJECTED_BY_VALIDATOR);
        assertThat(p.validationErrors()).contains(DraftValidator.NOT_NEGATIVE, "below zero", "$5.00");
        assertThat(balanceOf(b)).isEqualTo(500);
    }

    @Test
    void duplicateReversalBooksTheUncreditedCaptureAndItsRefund() {
        long c = customerC().id();
        Break brk = scenarios.duplicate(c);
        investigator.respond(output(DUPLICATE_REVERSAL, BALANCE_RETURNED, Scenarios.CHARGED_TWICE, "CAP-C2"));

        Proposal p = proposals.findById(service.investigate(brk.id(), "maker")).orElseThrow();

        assertThat(p.status()).as(p.validationErrors()).isEqualTo(ProposalStatus.PENDING_APPROVAL);
        long fee = FakePayPalGateway.fee(1_500);
        DraftPlan draft = Json.read(p.draft(), DraftPlan.class);
        assertThat(draft.lines()).containsExactly(
                JournalLineDraft.debit(Account.PAYPAL_CASH, 1_500 - fee),
                JournalLineDraft.debit(Account.PAYPAL_FEES, fee),
                JournalLineDraft.credit(Account.PAYPAL_CASH, 1_500));
        assertThat(draft.customerBalanceEffectCents()).isZero();
        assertThat(draft.shopResultEffectCents()).isEqualTo(-fee);
        assertThat(draft.alsoResolves().paypalRef()).isEqualTo("CAP-C2");
        assertThat(Json.tryParse(p.evidenceBundle()).orElseThrow().path("refundedCapture").path("creditedByLedger").asBoolean())
                .isFalse();
        assertThat(p.evidenceBundle()).contains("CAP-C1", Scenarios.CHARGED_TWICE);
    }

    @Test
    void duplicateReversalIsRejectedWhenTheLedgerCreditedTheRefundedCapture() {
        long c = customerC().id();
        scenarios.topup(c, 1_500, "CAP-C1", Scenarios.T0);
        Break brk = scenarios.refundBreak(c, "REF-C1", "CAP-C1", 1_500, null, Scenarios.T0.plusHours(1));
        investigator.respond(output(DUPLICATE_REVERSAL, BALANCE_RETURNED, "CAP-C1"));

        Proposal p = proposals.findById(service.investigate(brk.id(), "maker")).orElseThrow();

        assertThat(p.status()).isEqualTo(ProposalStatus.REJECTED_BY_VALIDATOR);
        assertThat(p.validationErrors()).contains("was credited by the ledger");
    }

    @Test
    void needsHumanIsStoredWithoutADraft() {
        Break brk = scenarios.goodwill(customerA().id());
        investigator.respond(output(NEEDS_HUMAN, GOODWILL, Scenarios.COLD_COFFEE_NOTE));

        Proposal p = proposals.findById(service.investigate(brk.id(), "maker")).orElseThrow();

        assertThat(p.status()).isEqualTo(ProposalStatus.REJECTED_BY_VALIDATOR);
        assertThat(Json.read(p.draft(), DraftPlan.class).lines()).isEmpty();
        assertThat(p.validationErrors()).contains("human decision");
    }

    @Test
    void investigatingIsRefusedWhenTheInvestigatorIsNotConfigured() {
        Break brk = scenarios.goodwill(customerA().id());
        investigator.setConfigured(false);

        assertThatThrownBy(() -> service.investigate(brk.id(), "maker"))
                .isInstanceOf(InvestigationException.class)
                .hasMessage("AI investigator is not configured");
        assertThat(investigator.calls()).isZero();
    }

    @Test
    void aProposedBreakCannotBeInvestigatedAgainUntilDismissed() {
        Break brk = scenarios.goodwill(customerA().id());
        investigator.respond(output(GOODWILL, BALANCE_RETURNED, Scenarios.COLD_COFFEE_NOTE));
        long first = service.investigate(brk.id(), "maker");

        assertThatThrownBy(() -> service.investigate(brk.id(), "maker")).hasMessageContaining("dismiss");

        service.dismiss(first, "checker");
        assertThat(breaks.findById(brk.id()).orElseThrow().status()).isEqualTo(BreakStatus.OPEN);
        assertThat(service.investigate(brk.id(), "maker")).isNotEqualTo(first);
    }

    @Test
    void quoteMatchingToleratesJsonEscaping() {
        String bundle = Json.write(java.util.Map.of("note", "He said \"twice\" at the till"));
        List<String> strings = DraftValidator.stringValues(bundle);
        assertThat(DraftValidator.quoteFound("said \"twice\" at", bundle, strings)).isTrue();
        assertThat(DraftValidator.quoteFound("said twice", bundle, strings)).isFalse();
        assertThat(DraftValidator.quoteFound("at", bundle, strings)).isFalse();
    }
}
