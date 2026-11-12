package dev.backed.investigate;

import dev.backed.close.Break;
import dev.backed.ledger.JournalEntryView;

import java.util.List;

/** Everything the proposal page shows. */
public record ProposalPage(
        Proposal proposal,
        Break brk,
        String customerName,
        InvestigatorOutput output,
        DraftPlan chosen,
        List<ValidationCheck> checks,
        ProposalService.AlternativeDraft alternative,
        PayPalMovement paypal,
        List<Highlighter.Segment> noteSegments,
        List<Message> messages,
        List<Quote> quotes,
        List<Highlighter.Segment> bundleSegments,
        JournalEntryView postedEntry,
        boolean viewerIsMaker,
        boolean viewerIsChecker) {

    public record Message(String sentAt, List<Highlighter.Segment> body) {
    }

    public record Quote(String text, boolean found) {
    }

    public boolean pending() {
        return proposal.status() == ProposalStatus.PENDING_APPROVAL;
    }

    public boolean canDecide() {
        return pending() && viewerIsChecker && !viewerIsMaker;
    }

    public List<String> rejectionReasons() {
        String errors = proposal.validationErrors();
        return errors == null || errors.isBlank() ? List.of() : List.of(errors.split("\n"));
    }

    public boolean canDismiss() {
        return viewerIsChecker && (pending() || proposal.status() == ProposalStatus.REJECTED_BY_VALIDATOR);
    }
}
