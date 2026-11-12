package dev.backed.investigate;

import dev.backed.close.Break;
import dev.backed.close.BreakKind;
import dev.backed.close.BreakRepository;
import dev.backed.close.CloseController;
import dev.backed.close.CloseStatus;
import dev.backed.config.Actors;
import dev.backed.config.Roles;
import dev.backed.customer.Customer;
import dev.backed.customer.Customers;
import dev.backed.ledger.JournalEntryView;
import dev.backed.ledger.LedgerQueries;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class ProposalController {

    private final ProposalService service;
    private final ProposalRepository proposals;
    private final BreakRepository breaks;
    private final Customers customers;
    private final LedgerQueries ledger;

    public ProposalController(ProposalService service, ProposalRepository proposals, BreakRepository breaks,
                              Customers customers, LedgerQueries ledger) {
        this.service = service;
        this.proposals = proposals;
        this.breaks = breaks;
        this.customers = customers;
        this.ledger = ledger;
    }

    @GetMapping("/proposals")
    public String list(Model model, Authentication auth) {
        Map<Long, String> names = customers.all().stream().collect(Collectors.toMap(Customer::id, Customer::name));
        model.addAttribute("proposals", proposals.recent(200));
        model.addAttribute("openBreaks", breaks.findOpen());
        model.addAttribute("customerNames", names);
        model.addAttribute("aiConfigured", service.investigatorConfigured());
        model.addAttribute("canInvestigate", hasRole(auth, Roles.MAKER));
        return "investigate/proposals";
    }

    @GetMapping("/proposals/{id}")
    public String show(@PathVariable long id, Model model, Authentication auth) {
        Proposal p = proposals.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Break b = breaks.findById(p.breakId()).orElseThrow();
        model.addAttribute("page", page(p, b, auth));
        model.addAttribute("aiConfigured", service.investigatorConfigured());
        return "investigate/proposal";
    }

    @PostMapping("/breaks/{id}/investigate")
    public String investigate(@PathVariable long id, Authentication auth, RedirectAttributes redirect) {
        if (!hasRole(auth, Roles.MAKER)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a maker can request an investigation");
        }
        try {
            long proposalId = service.investigate(id, Actors.of(auth));
            redirect.addFlashAttribute("notice", "The investigator reviewed break #" + id + ".");
            return "redirect:/proposals/" + proposalId;
        } catch (InvestigationException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/proposals";
        }
    }

    @PostMapping("/proposals/{id}/approve")
    public String approve(@PathVariable long id, Authentication auth, RedirectAttributes redirect) {
        requireProposal(id);
        try {
            ApprovalResult r = service.approve(id, Actors.of(auth));
            String posted = "Approved. Journal entry #" + r.entryId() + " posted for " + r.entryDate() + ". ";
            if (r.closeOutcome() == null) {
                redirect.addFlashAttribute("pending", posted + r.closeNote());
            } else if (r.closeOutcome().status() == CloseStatus.CLOSED) {
                redirect.addFlashAttribute("notice", posted + "The close ran again: "
                        + CloseController.describe(r.closeOutcome()));
            } else {
                redirect.addFlashAttribute("pending", posted + "The close ran again and is still broken: "
                        + CloseController.describe(r.closeOutcome()).replace("The day is broken: ", ""));
            }
        } catch (InvestigationException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/proposals/" + id;
    }

    @PostMapping("/proposals/{id}/dismiss")
    public String dismiss(@PathVariable long id, Authentication auth, RedirectAttributes redirect) {
        requireProposal(id);
        try {
            service.dismiss(id, Actors.of(auth));
            redirect.addFlashAttribute("notice", "Proposal #" + id + " dismissed.");
        } catch (InvestigationException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/proposals/" + id;
    }

    private ProposalPage page(Proposal p, Break b, Authentication auth) {
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

    private void requireProposal(long id) {
        if (proposals.findById(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static boolean hasRole(Authentication auth, String role) {
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> ("ROLE_" + role).equals(a.getAuthority()));
    }
}
