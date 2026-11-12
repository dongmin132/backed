package dev.backed.close;

import dev.backed.config.Actors;
import dev.backed.config.Roles;
import dev.backed.customer.Customer;
import dev.backed.customer.Customers;
import dev.backed.investigate.ProposalRepository;
import dev.backed.ledger.BusinessDay;
import dev.backed.ledger.Money;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/close")
public class CloseController {

    /** An open break as the close page shows it. */
    public record BreakView(long id, BreakKind kind, BreakStatus status, Long customerId, String customerName,
                            long amountCents, String paypalRef, String note, OffsetDateTime createdAt) {
    }

    private final DayCloseService close;
    private final CloseRuns runs;
    private final BreakRepository breaks;
    private final Customers customers;
    private final BusinessDay businessDay;
    private final ProposalRepository proposals;

    public CloseController(DayCloseService close, CloseRuns runs, BreakRepository breaks, Customers customers,
                           BusinessDay businessDay, ProposalRepository proposals) {
        this.close = close;
        this.runs = runs;
        this.breaks = breaks;
        this.customers = customers;
        this.businessDay = businessDay;
        this.proposals = proposals;
    }

    @GetMapping
    public String page(Model model, Authentication auth) {
        LocalDate today = businessDay.current();
        CloseRuns.CloseRun latest = runs.latestFor(today).orElse(null);
        CloseSummary summary = latest == null ? null : DayCloseService.readSummary(latest.summaryJson());
        Map<Long, String> names = new HashMap<>();
        List<BreakView> open = breaks.findOpen().stream()
                .map(b -> new BreakView(b.id(), b.kind(), b.status(), b.customerId(),
                        b.customerId() == null ? null : names.computeIfAbsent(b.customerId(),
                                id -> customers.byId(id).map(Customer::name).orElse("Customer " + id)),
                        b.amountCents(), b.paypalRef(), DayCloseService.noteOf(b.evidenceJson()), b.createdAt()))
                .toList();
        model.addAttribute("latest", latest);
        model.addAttribute("summary", summary);
        model.addAttribute("openBreaks", open);
        model.addAttribute("history", runs.recent(20));
        model.addAttribute("canAdvance", latest != null && latest.status() == CloseStatus.CLOSED);
        model.addAttribute("isMaker", auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + Roles.MAKER).equals(a.getAuthority())));
        model.addAttribute("pendingProposals", proposals.pendingByBreak());
        return "close/close";
    }

    @PostMapping("/run")
    public String run(Principal principal, RedirectAttributes redirect) {
        CloseOutcome outcome = close.runClose(Actors.of(principal));
        redirect.addFlashAttribute(outcome.status() == CloseStatus.CLOSED ? "notice" : "error", describe(outcome));
        return "redirect:/close";
    }

    /** One line for a close result, as the flash after a run or an approval shows it. */
    public static String describe(CloseOutcome outcome) {
        if (outcome.status() == CloseStatus.CLOSED) {
            return "The books for " + outcome.businessDate() + " are closed. Nothing is unexplained.";
        }
        if (!outcome.notes().isEmpty() && outcome.notes().getFirst().startsWith("PayPal could not be read")) {
            return "The day is broken: PayPal could not be read, so nothing was compared. The reason is below.";
        }
        int open = outcome.openBreakIds().size();
        int inDoubt = outcome.inDoubtCount();
        return "The day is broken: " + open + (open == 1 ? " open break, " : " open breaks, ")
                + inDoubt + (inDoubt == 1 ? " item" : " items") + " in doubt or being sent, unexplained "
                + Money.format(outcome.unexplainedCents()) + ".";
    }

    @PostMapping("/advance")
    public String advance(Principal principal, RedirectAttributes redirect) {
        try {
            LocalDate next = close.advance(Actors.of(principal));
            redirect.addFlashAttribute("notice", "The business date is now " + next + ".");
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/close";
    }
}
