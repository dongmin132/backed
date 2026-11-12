package dev.backed.topup;

import dev.backed.ledger.Money;
import dev.backed.paypal.FaultInjectingPayPalGateway;
import dev.backed.paypal.FaultSwitch;
import dev.backed.paypal.PayPalException;
import dev.backed.paypal.PayPalGateway;
import dev.backed.paypal.RefundResult;
import dev.backed.paypal.SdkPayPalGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Demo fault switches, and a refund made at PayPal behind the ledger's back. The outside refund
 * stands in for someone refunding by hand in the PayPal dashboard: it goes straight to PayPal
 * (bypassing fault injection), uses a fresh request id, and writes nothing to the ledger, so the
 * next day close has something real to find.
 */
@Controller
public class FaultsController {

    private static final Logger log = LoggerFactory.getLogger(FaultsController.class);

    public record FaultRow(String name, String label, boolean armed) {
    }

    private final FaultSwitch faults;
    private final PayPalGateway gateway;
    private final Topups topups;
    private final Environment env;

    public FaultsController(FaultSwitch faults, PayPalGateway gateway, Topups topups, Environment env) {
        this.faults = faults;
        this.gateway = gateway;
        this.topups = topups;
        this.env = env;
    }

    @GetMapping("/admin/faults")
    public String page(Model model) {
        List<FaultRow> rows = new ArrayList<>();
        for (Map.Entry<FaultSwitch.Fault, Boolean> e : faults.snapshot().entrySet()) {
            rows.add(new FaultRow(e.getKey().name(), e.getKey().label(), e.getValue()));
        }
        model.addAttribute("faults", rows);
        model.addAttribute("captured", topups.captured(50));
        model.addAttribute("paypalMode", env.getProperty("backed.paypal.mode", "sandbox"));
        PayPalGateway direct = direct();
        model.addAttribute("credentialsConfigured",
                !(direct instanceof SdkPayPalGateway sdk) || sdk.isConfigured());
        return "topup/faults";
    }

    @PostMapping("/admin/faults")
    public String set(@RequestParam String fault, @RequestParam String action, Principal principal,
                      RedirectAttributes redirect) {
        FaultSwitch.Fault f;
        try {
            f = FaultSwitch.Fault.valueOf(fault);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", "Unknown fault " + fault);
            return "redirect:/admin/faults";
        }
        if ("arm".equals(action)) {
            faults.arm(f);
            log.warn("Fault armed by {}: {}", principal.getName(), f);
            redirect.addFlashAttribute("notice", "Armed: " + f.label() + ". It applies to the next matching "
                    + "PayPal call only.");
        } else {
            faults.disarm(f);
            log.info("Fault disarmed by {}: {}", principal.getName(), f);
            redirect.addFlashAttribute("notice", "Disarmed: " + f.label() + ".");
        }
        return "redirect:/admin/faults";
    }

    @PostMapping("/admin/faults/outside-refund")
    public String outsideRefund(@RequestParam String topupId, @RequestParam(defaultValue = "") String amount,
                                @RequestParam(defaultValue = "") String note, Principal principal,
                                RedirectAttributes redirect) {
        Topup topup;
        long cents;
        try {
            topup = parseId(topupId).flatMap(topups::findById)
                    .filter(t -> t.state() == TopupState.CAPTURED && t.paypalCaptureId() != null)
                    .orElseThrow(() -> new IllegalArgumentException("Pick a captured top-up from the list."));
            cents = Money.parseCents(amount);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/faults";
        }
        String noteToPayer = note.isBlank() ? null : note.strip();
        String requestId = "outside-" + UUID.randomUUID();
        try {
            RefundResult r = direct().refundCapture(requestId, topup.paypalCaptureId(), cents, noteToPayer);
            log.warn("Refund made outside the ledger by {}: refund {} of {} on capture {} (top-up {}), status {}",
                    principal.getName(), r.refundId(), Money.format(r.amountCents()), r.captureId(), topup.id(),
                    r.status());
            redirect.addFlashAttribute("notice", "PayPal refund " + r.refundId() + " (" + r.status().toLowerCase() + ") of "
                    + Money.format(r.amountCents()) + " made on capture " + r.captureId() + " for "
                    + topup.customerName() + ". Nothing was written to the ledger; the next close should find it.");
        } catch (PayPalException e) {
            redirect.addFlashAttribute("error", "PayPal refused the outside refund: " + e.getMessage());
        }
        return "redirect:/admin/faults";
    }

    private static Optional<UUID> parseId(String text) {
        try {
            return Optional.of(UUID.fromString(text.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private PayPalGateway direct() {
        return gateway instanceof FaultInjectingPayPalGateway f ? f.delegate() : gateway;
    }
}
