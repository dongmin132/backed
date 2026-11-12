package dev.backed.investigate;

import dev.backed.close.Break;
import dev.backed.close.BreakKind;
import dev.backed.config.Roles;
import dev.backed.customer.Customer;
import dev.backed.customer.Customers;
import dev.backed.ledger.JournalEntryView;
import dev.backed.ledger.LedgerQueries;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the read-only {@link ProposalPage} that the proposal page and the day report both render. */
@Component
public class ProposalPages {

    private final ProposalRepository proposals;
    private final Customers customers;
    private final LedgerQueries ledger;

    public ProposalPages(ProposalRepository proposals, Customers customers, LedgerQueries ledger) {
        this.proposals = proposals;
        this.customers = customers;
        this.ledger = ledger;
    }

    /** The newest proposal of each given break, as the viewer sees it, keyed by break id. Breaks with none are left out. */
    public Map<Long, ProposalPage> latestFor(List<Break> breaks, Authentication auth) {
        Map<Long, Long> latest = proposals.latestByBreak();
        Map<Long, ProposalPage> out = new LinkedHashMap<>();
        for (Break b : breaks) {
            Long id = latest.get(b.id());
            if (id != null) {
                proposals.findById(id).ifPresent(p -> out.put(b.id(), of(p, b, auth)));
            }
        }
        return out;
    }

    public ProposalPage of(Proposal p, Break b, Authentication auth) {
        InvestigatorOutput output = p.aiOutput() == null ? null : Json.read(p.aiOutput(), InvestigatorOutput.class);
        List<String> quotes = output == null ? List.of() : output.evidenceQuotes();
        DraftPlan chosen = p.draft() == null ? null : Json.read(p.draft(), DraftPlan.class);
        ProposalService.AlternativeDraft alternative = p.alternativeDraft() == null ? null
                : Json.read(p.alternativeDraft(), ProposalService.AlternativeDraft.class);
        List<ValidationCheck> checks = p.validationChecks() == null ? List.of()
                : List.of(Json.read(p.validationChecks(), ValidationCheck[].class));

        PayPalMovement paypal = (b.kind() == BreakKind.UNBOOKED_REFUND
                ? PayPalMovement.refundFrom(b.evidenceJson())
                : PayPalMovement.captureFrom(b.evidenceJson())).orElse(null);
        List<Highlighter.Segment> note = paypal == null || paypal.noteToPayer() == null ? List.of()
                : Highlighter.segments(paypal.noteToPayer(), quotes);

        String bundle = p.evidenceBundle() == null ? "" : p.evidenceBundle();
        List<ProposalPage.Message> messages = new ArrayList<>();
        Json.tryParse(bundle).ifPresent(root -> {
            for (JsonNode m : root.path("customerMessages")) {
                messages.add(new ProposalPage.Message(m.path("sentAt").asString(""),
                        Highlighter.segments(m.path("body").asString(""), quotes)));
            }
        });
        List<String> strings = DraftValidator.stringValues(bundle);
        List<ProposalPage.Quote> quoteViews = quotes.stream()
                .map(q -> new ProposalPage.Quote(q, DraftValidator.quoteFound(q, bundle, strings)))
                .toList();

        JournalEntryView posted = p.postedEntryId() == null ? null
                : ledger.entriesFor(DraftPlan.REF_TYPE, String.valueOf(b.id())).stream()
                .filter(e -> e.id() == p.postedEntryId()).findFirst().orElse(null);
        String customerName = b.customerId() == null ? null
                : customers.byId(b.customerId()).map(Customer::name).orElse(null);
        String viewer = auth == null ? null : auth.getName();
        return new ProposalPage(p, b, customerName, output, chosen, checks, alternative, paypal, note, messages,
                quoteViews, Highlighter.segments(bundle, quotes), posted,
                viewer != null && viewer.equals(p.createdBy()), hasRole(auth, Roles.CHECKER));
    }

    private static boolean hasRole(Authentication auth, String role) {
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> ("ROLE_" + role).equals(a.getAuthority()));
    }
}
