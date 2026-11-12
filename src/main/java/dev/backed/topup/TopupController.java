package dev.backed.topup;

import dev.backed.config.Actors;
import dev.backed.customer.Customer;
import dev.backed.customer.Customers;
import dev.backed.ledger.Money;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Controller
public class TopupController {

    private final TopupService service;
    private final Topups topups;
    private final Customers customers;

    public TopupController(TopupService service, Topups topups, Customers customers) {
        this.service = service;
        this.topups = topups;
        this.customers = customers;
    }

    /** The customer page's "Add money" form. */
    @PostMapping("/c/{token}/topup")
    public String topUp(@PathVariable String token, @RequestParam(defaultValue = "") String amount,
                        RedirectAttributes redirect) {
        Customer customer = customers.byToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown link"));
        String back = "redirect:/c/" + token;
        long cents;
        try {
            cents = Money.parseCents(amount);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return back;
        }
        try {
            Topup t = service.topUp(customer.id(), cents, Actors.customer(customer.id()));
            switch (t.state()) {
                case CAPTURED -> redirect.addFlashAttribute("notice", "Added " + Money.format(t.amountCents())
                        + " to your balance. PayPal confirmed the payment.");
                case IN_DOUBT, CREATED -> redirect.addFlashAttribute("pending", "Processing: PayPal has not confirmed "
                        + "your " + Money.format(t.amountCents()) + " payment yet. Your balance will update once "
                        + "it does. Please do not pay again.");
                case DECLINED, NOT_CAPTURED -> redirect.addFlashAttribute("error", "Not added. " + customerReason(t));
            }
        } catch (TopupRefusedException | IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return back;
    }

    /** The failure as the customer should read it: staff details (configuration, request ids) stay on /topups. */
    private static String customerReason(Topup t) {
        String reason = t.failureReason() == null ? "" : t.failureReason();
        if (reason.startsWith("Declined by PayPal")) {
            return "PayPal declined the card. Nothing was charged.";
        }
        if (reason.startsWith("PayPal could not be reached")) {
            return "PayPal could not be reached. Nothing was charged; please try again later.";
        }
        return "Nothing was charged.";
    }

    @GetMapping("/topups")
    public String list(Model model) {
        List<Topup> rows = topups.recent(200);
        Map<TopupState, Long> counts = new EnumMap<>(TopupState.class);
        long inDoubtCents = 0;
        for (TopupState s : TopupState.values()) {
            counts.put(s, 0L);
        }
        for (Topup t : rows) {
            counts.merge(t.state(), 1L, Long::sum);
            if (t.state() == TopupState.IN_DOUBT) {
                inDoubtCents += t.amountCents();
            }
        }
        model.addAttribute("topups", rows);
        model.addAttribute("inDoubtCount", counts.get(TopupState.IN_DOUBT));
        model.addAttribute("capturedCount", counts.get(TopupState.CAPTURED));
        model.addAttribute("notChargedCount", counts.get(TopupState.DECLINED) + counts.get(TopupState.NOT_CAPTURED));
        model.addAttribute("inDoubtCents", inDoubtCents);
        return "topup/list";
    }

    @PostMapping("/topups/{id}/resolve")
    public String resolve(@PathVariable UUID id, Principal principal, RedirectAttributes redirect) {
        Topup before = topups.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such top-up"));
        Topup after = service.resolve(id, Actors.of(principal));
        String label = "Top-up of " + Money.format(after.amountCents()) + " for " + after.customerName();
        if (before.state() == after.state() && after.state() != TopupState.IN_DOUBT
                && after.state() != TopupState.CREATED) {
            redirect.addFlashAttribute("notice", label + " was already "
                    + after.state().name().toLowerCase().replace('_', ' ') + ". Nothing to do.");
        } else {
            switch (after.state()) {
                case CAPTURED -> redirect.addFlashAttribute("notice", label + " resolved: PayPal has capture "
                        + after.paypalCaptureId() + ". Customer credited once"
                        + (before.state() == TopupState.IN_DOUBT ? " and the in-doubt suspense reversed." : "."));
                case NOT_CAPTURED, DECLINED -> redirect.addFlashAttribute("notice", label
                        + " resolved: PayPal did not capture it. Nothing credited. " + after.failureReason());
                case IN_DOUBT, CREATED -> redirect.addFlashAttribute("pending", label + " is still unconfirmed. "
                        + Objects.toString(after.failureReason(), "Its first PayPal call may still be in flight."));
            }
        }
        return "redirect:/topups";
    }
}
