package dev.backed.close;

import dev.backed.config.Actors;
import dev.backed.customer.Customer;
import dev.backed.customer.Customers;
import dev.backed.ledger.BusinessDay;
import dev.backed.ledger.Money;
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

    public CloseController(DayCloseService close, CloseRuns runs, BreakRepository breaks, Customers customers,
                           BusinessDay businessDay) {
        this.close = close;
        this.runs = runs;
        this.breaks = breaks;
        this.customers = customers;
        this.businessDay = businessDay;
    }

    @GetMapping
    public String page(Model model) {
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
        return "close/close";
    }

    @PostMapping("/run")
    public String run(Principal principal, RedirectAttributes redirect) {
        CloseOutcome outcome = close.runClose(Actors.of(principal));
        if (outcome.status() == CloseStatus.CLOSED) {
            redirect.addFlashAttribute("notice", "The books for " + outcome.businessDate() + " are closed. Nothing is unexplained.");
        } else if (!outcome.notes().isEmpty() && outcome.notes().getFirst().startsWith("PayPal could not be read")) {
            redirect.addFlashAttribute("error", "The day is broken. " + outcome.notes().getFirst());
        } else {
            StringBuilder s = new StringBuilder("The day is broken: ");
            s.append(outcome.openBreakIds().size()).append(" open break(s), ");
            s.append(outcome.inDoubtCount()).append(" in doubt, unexplained ");
            s.append(Money.format(outcome.unexplainedCents())).append('.');
            redirect.addFlashAttribute("error", s.toString());
        }
        return "redirect:/close";
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
