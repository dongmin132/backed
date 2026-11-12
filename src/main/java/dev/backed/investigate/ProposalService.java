package dev.backed.investigate;

import dev.backed.close.Break;
import dev.backed.close.BreakRepository;
import dev.backed.close.BreakStatus;
import dev.backed.close.CloseOutcome;
import dev.backed.close.CloseRunner;
import dev.backed.ledger.BusinessDay;
import dev.backed.ledger.InsufficientBalanceException;
import dev.backed.ledger.JournalService;
import dev.backed.ledger.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;

/**
 * Investigates breaks into proposals and runs the maker/checker decision on them. The model call
 * and any PayPal read happen before a transaction is opened.
 */
@Service
public class ProposalService {

    public static final String NOT_CONFIGURED = "AI investigator is not configured";

    private static final Logger log = LoggerFactory.getLogger(ProposalService.class);

    private final Investigator investigator;
    private final EvidenceBundleBuilder bundles;
    private final BreakRepository breaks;
    private final ProposalRepository proposals;
    private final JournalService journal;
    private final BusinessDay businessDay;
    private final TransactionTemplate tx;
    private final ObjectProvider<CloseRunner> closeRunner;

    public ProposalService(Investigator investigator, EvidenceBundleBuilder bundles, BreakRepository breaks,
                           ProposalRepository proposals, JournalService journal, BusinessDay businessDay,
                           TransactionTemplate tx, ObjectProvider<CloseRunner> closeRunner) {
        this.investigator = investigator;
        this.bundles = bundles;
        this.breaks = breaks;
        this.proposals = proposals;
        this.journal = journal;
        this.businessDay = businessDay;
        this.tx = tx;
        this.closeRunner = closeRunner;
    }

    public boolean investigatorConfigured() {
        return investigator.configured();
    }

    /**
     * Asks the investigator about an OPEN break and stores the proposal, validated. A proposal that
     * passes every check is PENDING_APPROVAL and its break becomes PROPOSED; otherwise it is stored
     * as REJECTED_BY_VALIDATOR with the reasons and the break stays OPEN.
     *
     * @return the new proposal's id
     */
    public long investigate(long breakId, String maker) {
        if (!investigator.configured()) {
            throw new InvestigationException(NOT_CONFIGURED);
        }
        Break b = breaks.findById(breakId)
                .orElseThrow(() -> new InvestigationException("Break #" + breakId + " does not exist."));
        if (b.status() != BreakStatus.OPEN) {
            throw new InvestigationException("Break #" + breakId + " is " + b.status().name().toLowerCase()
                    + (b.status() == BreakStatus.PROPOSED ? "; dismiss its pending proposal before investigating again." : "."));
        }
        EvidenceBundle bundle = bundles.build(b);
        InvestigatorOutput output;
        try {
            output = investigator.investigate(bundle.json());
        } catch (InvestigatorException e) {
            throw new InvestigationException("The AI investigator failed: " + e.getMessage(), e);
        }
        if (output == null || output.treatment() == null) {
            throw new InvestigationException("The AI investigator returned no treatment.");
        }
        DraftContext ctx = bundle.context();
        DraftPlan chosen = DraftBuilder.build(ctx, output.treatment());
        List<ValidationCheck> checks = DraftValidator.validate(ctx, output, chosen, bundle.json(), DraftValidator.Stage.PROPOSE);
        boolean ok = DraftValidator.allOk(checks);
        DraftPlan alternative = DraftBuilder.build(ctx, alternativeFor(ctx, output));
        AlternativeDraft alt = new AlternativeDraft(alternative,
                DraftValidator.ledgerChecks(ctx, alternative, DraftValidator.Stage.PROPOSE));
        List<String> failures = DraftValidator.failures(checks);

        Long id = tx.execute(status -> {
            if (ok && !breaks.markProposed(breakId)) {
                throw new InvestigationException("Break #" + breakId + " changed while it was being investigated; try again.");
            }
            return proposals.insert(breakId, output.treatment(), Json.write(output), Json.write(chosen), ok,
                    failures.isEmpty() ? null : String.join("\n", failures),
                    ok ? ProposalStatus.PENDING_APPROVAL : ProposalStatus.REJECTED_BY_VALIDATOR,
                    maker, bundle.json(), Json.write(alt), Json.write(checks));
        });
        log.info("Proposal #{} for break #{}: {} ({})", id, breakId, output.treatment(), ok ? "valid" : "rejected");
        return id;
    }

    /**
     * Approves a PENDING_APPROVAL proposal. Re-validates against fresh data, then in one transaction
     * posts the entry on the next business date (created_by = maker, approved_by = checker), applies
     * the balance change, resolves the break and marks the proposal APPROVED. The close is re-run
     * after the transaction commits.
     *
     * @throws InvestigationException when the approval is refused; nothing is posted
     */
    public ApprovalResult approve(long proposalId, String checker) {
        Proposal p = proposals.findById(proposalId)
                .orElseThrow(() -> new InvestigationException("Proposal #" + proposalId + " does not exist."));
        if (checker.equals(p.createdBy())) {
            throw new InvestigationException("You created proposal #" + proposalId
                    + ". A different user with the checker role must approve it.");
        }
        if (p.status() != ProposalStatus.PENDING_APPROVAL) {
            throw new InvestigationException("Proposal #" + proposalId + " is " + label(p.status())
                    + "; only a proposal pending approval can be approved.");
        }
        Break b = breaks.findById(p.breakId()).orElseThrow();
        if (!EvidenceBundleBuilder.isOpen(b)) {
            throw new InvestigationException("Break #" + b.id() + " is already resolved.");
        }

        DraftContext ctx = bundles.context(b);
        DraftPlan plan = DraftBuilder.build(ctx, p.treatment());
        InvestigatorOutput output = Json.read(p.aiOutput(), InvestigatorOutput.class);
        List<ValidationCheck> checks = DraftValidator.validate(ctx, output, plan, p.evidenceBundle(), DraftValidator.Stage.APPROVE);
        if (!DraftValidator.allOk(checks)) {
            throw new InvestigationException("Re-validation failed: " + String.join(" ", DraftValidator.failures(checks)));
        }
        DraftPlan proposed = Json.read(p.draft(), DraftPlan.class);
        if (!proposed.lines().equals(plan.lines())) {
            throw new InvestigationException("PayPal's record or the ledger changed since proposal #" + proposalId
                    + " was made, so its entry no longer matches. Dismiss it and investigate again.");
        }

        LocalDate entryDate = businessDay.next();
        long entryId;
        try {
            entryId = tx.execute(status -> {
                Proposal locked = proposals.lock(proposalId).orElseThrow();
                if (locked.status() != ProposalStatus.PENDING_APPROVAL) {
                    throw new InvestigationException("Proposal #" + proposalId + " is already " + label(locked.status()) + ".");
                }
                long entry = journal.postAndApply(plan.toJournalDraft(locked.createdBy())
                        .withBusinessDate(entryDate)
                        .withApprovedBy(checker));
                if (!breaks.markResolved(b.id(), entry)) {
                    throw new InvestigationException("Break #" + b.id() + " was resolved by someone else.");
                }
                if (plan.alsoResolves() != null) {
                    Break linked = breaks.insertIfAbsent(plan.alsoResolves());
                    if (linked.status() != BreakStatus.RESOLVED) {
                        breaks.markResolved(linked.id(), entry);
                    }
                }
                if (!proposals.markApproved(proposalId, checker, entry)) {
                    throw new InvestigationException("Proposal #" + proposalId + " changed during approval.");
                }
                return entry;
            });
        } catch (InsufficientBalanceException e) {
            throw new InvestigationException("The customer's balance is now below "
                    + Money.format(e.requestedCents()) + ", so the balance cannot be returned. Nothing was posted.");
        } catch (DataIntegrityViolationException e) {
            throw new InvestigationException("A correcting entry for break #" + b.id() + " already exists. Nothing was posted.");
        }

        CloseOutcome outcome = null;
        String closeNote = null;
        CloseRunner runner = closeRunner.getIfUnique();
        if (runner == null) {
            closeNote = "The close is not available here; run it from the Close page.";
        } else {
            try {
                outcome = runner.runClose(checker);
            } catch (RuntimeException e) {
                log.warn("Close after approving proposal #{} failed", proposalId, e);
                closeNote = "The close could not be re-run: " + e.getMessage();
            }
        }
        return new ApprovalResult(entryId, entryDate, outcome, closeNote);
    }

    /** Dismisses a proposal that is not yet decided; a PROPOSED break goes back to OPEN. */
    public void dismiss(long proposalId, String actor) {
        tx.executeWithoutResult(status -> {
            Proposal p = proposals.lock(proposalId)
                    .orElseThrow(() -> new InvestigationException("Proposal #" + proposalId + " does not exist."));
            if (!proposals.markDismissed(proposalId, actor)) {
                throw new InvestigationException("Proposal #" + proposalId + " is " + label(p.status()) + " and cannot be dismissed.");
            }
            if (p.status() == ProposalStatus.PENDING_APPROVAL) {
                breaks.markOpen(p.breakId());
            }
        });
    }

    static Treatment alternativeFor(DraftContext ctx, InvestigatorOutput output) {
        Treatment alt = output.rejectedAlternative();
        if (alt != null && alt != Treatment.NEEDS_HUMAN && alt != output.treatment()) {
            return alt;
        }
        if (ctx.isRefund()) {
            return output.treatment() == Treatment.GOODWILL ? Treatment.BALANCE_RETURNED : Treatment.GOODWILL;
        }
        return output.treatment() == Treatment.BALANCE_RETURNED ? Treatment.DUPLICATE_REVERSAL : Treatment.BALANCE_RETURNED;
    }

    private static String label(ProposalStatus status) {
        return status.name().toLowerCase().replace('_', ' ');
    }

    /** The draft the model did not choose, with the ledger checks it would face. */
    public record AlternativeDraft(DraftPlan plan, List<ValidationCheck> checks) {
    }
}
