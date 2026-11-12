package dev.backed.investigate;

import dev.backed.close.Break;
import dev.backed.close.BreakRepository;
import dev.backed.close.CloseController;
import dev.backed.close.CloseStatus;
import dev.backed.config.Actors;
import dev.backed.config.Roles;
import dev.backed.customer.Customer;
import dev.backed.customer.Customers;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class ProposalController {

    private final ProposalService service;
    private final ProposalRepository proposals;
    private final BreakRepository breaks;
    private final Customers customers;
    private final ProposalPages pages;

    public ProposalController(ProposalService service, ProposalRepository proposals, BreakRepository breaks,
                              Customers customers, ProposalPages pages) {
        this.service = service;
        this.proposals = proposals;
        this.breaks = breaks;
        this.customers = customers;
        this.pages = pages;
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

    /** The day close page posts its forms with returnTo=close; only that exact value sends the person back there. */
    static final String RETURN_TO_CLOSE = "close";

    private static boolean backToClose(String returnTo) {
        return RETURN_TO_CLOSE.equals(returnTo);
    }

    @PostMapping("/breaks/{id}/investigate")
    public String investigate(@PathVariable long id, @RequestParam(required = false) String returnTo,
                              Authentication auth, RedirectAttributes redirect) {
        if (!hasRole(auth, Roles.MAKER)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a maker can request an investigation");
        }
        try {
            long proposalId = service.investigate(id, Actors.of(auth));
            redirect.addFlashAttribute("notice", "The investigator reviewed break #" + id + ".");
            return backToClose(returnTo) ? "redirect:/close#break-" + id : "redirect:/proposals/" + proposalId;
        } catch (InvestigationException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return backToClose(returnTo) ? "redirect:/close#break-" + id : "redirect:/proposals";
        }
    }

    /** Back to the day close, on the next break that waits for this person's decision, if there is one. */
    private String closeFor(Authentication auth) {
        List<Break> open = breaks.findOpen();
        return pages.latestFor(open, auth).entrySet().stream()
                .filter(e -> e.getValue().canDecide())
                .map(e -> "redirect:/close#break-" + e.getKey())
                .findFirst()
                .orElse("redirect:/close");
    }

    @PostMapping("/proposals/{id}/approve")
    public String approve(@PathVariable long id, @RequestParam(required = false) String returnTo,
                          Authentication auth, RedirectAttributes redirect) {
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
        return backToClose(returnTo) ? closeFor(auth) : "redirect:/proposals/" + id;
    }

    @PostMapping("/proposals/{id}/dismiss")
    public String dismiss(@PathVariable long id, @RequestParam(required = false) String returnTo,
                          Authentication auth, RedirectAttributes redirect) {
        requireProposal(id);
        try {
            service.dismiss(id, Actors.of(auth));
            redirect.addFlashAttribute("notice", "Proposal #" + id + " dismissed.");
        } catch (InvestigationException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return backToClose(returnTo) ? closeFor(auth) : "redirect:/proposals/" + id;
    }

    private ProposalPage page(Proposal p, Break b, Authentication auth) {
        return pages.of(p, b, auth);
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
