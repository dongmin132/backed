package dev.backed.close;

import dev.backed.config.Actors;
import dev.backed.config.Roles;
import dev.backed.customer.Customer;
import dev.backed.customer.Customers;
import dev.backed.investigate.ProposalPage;
import dev.backed.investigate.ProposalPages;
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
    /**
     * The day report's words: the result as one sentence with the amount inside it (lead, amount, tail), the
     * next thing to do (nextLead, a link to the break it is about, nextTail), the label of the breaks in the
     * contents ("Two refunds") and what the day still needs before it closes.
     */
    public record Report(String lead, String amount, String tail,
                         String nextLead, String nextLink, String nextHref, String nextTail,
                         String breaksLabel, String closing) {
    }

    private final ProposalRepository proposals;
    private final ProposalPages proposalPages;

    public CloseController(DayCloseService close, CloseRuns runs, BreakRepository breaks, Customers customers,
                           BusinessDay businessDay, ProposalRepository proposals, ProposalPages proposalPages) {
        this.close = close;
        this.runs = runs;
        this.breaks = breaks;
        this.customers = customers;
        this.businessDay = businessDay;
        this.proposals = proposals;
        this.proposalPages = proposalPages;
    }

    @GetMapping
    public String page(Model model, Authentication auth) {
        LocalDate today = businessDay.current();
        CloseRuns.CloseRun latest = runs.latestFor(today).orElse(null);
        CloseSummary summary = latest == null ? null : DayCloseService.readSummary(latest.summaryJson());
        Map<Long, String> names = new HashMap<>();
        List<Break> openRows = breaks.findOpen();
        List<BreakView> open = openRows.stream()
                .map(b -> new BreakView(b.id(), b.kind(), b.status(), b.customerId(),
                        b.customerId() == null ? null : names.computeIfAbsent(b.customerId(),
                                id -> customers.byId(id).map(Customer::name).orElse("Customer " + id)),
                        b.amountCents(), b.paypalRef(), DayCloseService.noteOf(b.evidenceJson()), b.createdAt()))
                .toList();
        model.addAttribute("latest", latest);
        model.addAttribute("summary", summary);
        model.addAttribute("openBreaks", open);
        model.addAttribute("history", runs.recent(20));
        boolean canAdvance = latest != null && latest.status() == CloseStatus.CLOSED;
        boolean isMaker = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + Roles.MAKER).equals(a.getAuthority()));
        // Each open break's newest proposal, read-only, so the report can show and approve it in place.
        Map<Long, ProposalPage> pages = proposalPages.latestFor(openRows, auth);
        model.addAttribute("canAdvance", canAdvance);
        model.addAttribute("isMaker", isMaker);
        model.addAttribute("pendingProposals", proposals.pendingByBreak());
        model.addAttribute("proposalPages", pages);
        model.addAttribute("report", report(latest, summary, open, pages, isMaker, canAdvance));
        return "close/close";
    }

    private static final String[] COUNT = {"No", "One", "Two", "Three", "Four", "Five", "Six"};

    private static String count(int n) {
        return n < COUNT.length ? COUNT[n] : String.valueOf(n);
    }

    /** "Maya's $4.00", or "the $4.00 refund" when the customer is not known. */
    private static String whose(BreakView b) {
        String amount = Money.format(b.amountCents());
        if (b.customerName() == null || b.customerName().isBlank()) {
            return "the " + amount + (b.kind() == BreakKind.UNBOOKED_REFUND ? " refund" : " payment");
        }
        return b.customerName().trim().split("\\s+")[0] + "’s " + amount;
    }

    static Report report(CloseRuns.CloseRun latest, CloseSummary summary, List<BreakView> open,
                         Map<Long, ProposalPage> pages, boolean isMaker, boolean canAdvance) {
        if (latest == null) {
            return new Report("Today has not been checked against PayPal yet.", null, null,
                    "No close has run for this business date yet. "
                            + (isMaker ? "Run the close to compare every PayPal order with your books, to the cent."
                            : "A maker runs it from this page."), null, null, null, "", "");
        }
        if (latest.status() == CloseStatus.RUNNING) {
            return new Report("The close is still running.", null, null,
                    "Refresh in a moment to see the result.", null, null, null, "", "");
        }
        if (latest.status() == CloseStatus.CLOSED) {
            return new Report("Your books and PayPal agree to the cent, at ",
                    Money.format(latest.paypalDerivedCashCents()), ".",
                    "The day is closed." + (isMaker && canAdvance ? " Move on to the next day at the end of this report." : ""),
                    null, null, null, "", "");
        }
        Long gap = latest.unexplainedCents();
        if (gap == null || latest.ledgerPaypalCashCents() == null || latest.paypalDerivedCashCents() == null) {
            return new Report("PayPal could not be read, so nothing was compared.", null, null,
                    "Run the close again in a minute. The reason is just below.", null, null, null, "",
                    "The day closes once PayPal can be read and everything matches.");
        }

        List<CloseSummary.BreakLine> lines = summary == null ? List.of() : summary.breaks();
        int n = lines.size();
        boolean allRefunds = n > 0 && lines.stream().allMatch(l -> BreakKind.UNBOOKED_REFUND.name().equals(l.kind()));
        boolean allPayments = n > 0 && lines.stream().allMatch(l -> BreakKind.UNBOOKED_CAPTURE.name().equals(l.kind()));
        String noun = allRefunds ? (n == 1 ? "refund" : "refunds")
                : allPayments ? (n == 1 ? "payment" : "payments") : (n == 1 ? "PayPal item" : "PayPal items");
        String breaksLabel = n == 0 ? "" : count(n) + " " + noun;
        int waiting = summary == null ? 0 : summary.inDoubtTopups() + summary.inDoubtLegs();
        String waitingText = waiting == 1 ? "One payment is" : count(waiting) + " payments are";

        long book = latest.ledgerPaypalCashCents();
        long paypal = latest.paypalDerivedCashCents();
        long stepSum = lines.stream()
                .mapToLong(l -> BreakKind.UNBOOKED_REFUND.name().equals(l.kind()) ? -l.amountCents() : l.amountCents())
                .sum();
        long rest = paypal - book - stepSum;
        String side = book > paypal ? " above PayPal" : " below PayPal";
        String amount = Money.format(Math.abs(book - paypal));

        String lead;
        String tail;
        if (book == paypal) {
            lead = "Your books match PayPal to the cent, but the day is not closed yet.";
            amount = null;
            tail = null;
        } else if (n > 0 && rest == 0) {
            lead = count(n) + " " + noun + " missing from your books " + (n == 1 ? "leaves" : "leave") + " them ";
            tail = side + ".";
        } else if (n > 0) {
            lead = "Your books are ";
            tail = side + ". " + count(n) + " " + noun + " missing from them explain "
                    + Money.format(Math.abs(stepSum)) + " of it.";
        } else {
            lead = "Your books are ";
            tail = side + (waiting > 0 ? ", and " + waitingText.toLowerCase() + " still waiting on PayPal."
                    : ", and nothing explains it yet.");
        }

        // What to do next, for the person reading.
        String nextLead = null;
        String nextLink = null;
        String nextHref = null;
        String nextTail = null;
        BreakView decide = open.stream().filter(b -> pages.containsKey(b.id()) && pages.get(b.id()).canDecide())
                .findFirst().orElse(null);
        List<BreakView> unasked = open.stream()
                .filter(b -> b.status() == BreakStatus.OPEN
                        && (!pages.containsKey(b.id()) || !pages.get(b.id()).pending()))
                .toList();
        BreakView waitingOnChecker = open.stream()
                .filter(b -> pages.containsKey(b.id()) && pages.get(b.id()).pending())
                .findFirst().orElse(null);
        if (decide != null) {
            nextLead = "Next: ";
            nextLink = "approve " + whose(decide);
            nextHref = "#break-" + decide.id();
            nextTail = open.size() > 1 ? ". " + count(open.size() - 1) + " more below." : ".";
        } else if (isMaker && !unasked.isEmpty()) {
            nextLead = "Next: ";
            nextLink = "ask the investigator about " + whose(unasked.getFirst());
            nextHref = "#break-" + unasked.getFirst().id();
            nextTail = unasked.size() > 1 ? ", then the " + (unasked.size() == 2 ? "other one" : "others") + " below." : ".";
        } else if (waitingOnChecker != null) {
            nextLead = "";
            nextLink = whose(waitingOnChecker);
            nextHref = "#break-" + waitingOnChecker.id();
            nextTail = " is waiting for a checker to approve it.";
        } else if (!open.isEmpty()) {
            nextLead = "A maker asks the investigator about each one, then a checker approves it here.";
        } else if (waiting > 0) {
            nextLead = waitingText + " still waiting on PayPal. Please don’t charge again. "
                    + "This updates by itself once PayPal confirms.";
        } else {
            nextLead = "Run the close again to finish the day.";
        }

        String closing;
        if (!open.isEmpty()) {
            String word = allRefunds ? "refund" : allPayments ? "payment" : "item";
            String them = open.size() == 1 ? "this " + word + " is"
                    : (open.size() == 2 ? "both " : "all " + count(open.size()).toLowerCase() + " ") + word + "s are";
            closing = "The day closes when " + them + " approved. Each approval runs the close again by itself.";
        } else if (waiting > 0) {
            closing = "The day closes once PayPal confirms what is waiting and the close runs again.";
        } else {
            closing = "Run the close again and the day closes once books and PayPal agree.";
        }
        return new Report(lead, book == paypal ? null : amount, tail, nextLead, nextLink, nextHref, nextTail,
                breaksLabel, closing);
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
