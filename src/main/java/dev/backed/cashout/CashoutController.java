package dev.backed.cashout;

import dev.backed.config.Actors;
import dev.backed.customer.Customer;
import dev.backed.customer.Customers;
import dev.backed.ledger.Account;
import dev.backed.ledger.LedgerQueries;
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

/** The customer's cash-out action and the staff view of cash-outs and their refund legs. */
@Controller
public class CashoutController {

    private final CashoutService cashouts;
    private final CashoutRelay relay;
    private final CashoutQueries queries;
    private final Customers customers;
    private final LedgerQueries ledger;

    public CashoutController(CashoutService cashouts, CashoutRelay relay, CashoutQueries queries, Customers customers,
                             LedgerQueries ledger) {
        this.cashouts = cashouts;
        this.relay = relay;
        this.queries = queries;
        this.customers = customers;
        this.ledger = ledger;
    }

    /**
     * amount is a dollar amount; blank or "all" cashes out the whole balance. The refunds are sent
     * by the relay after this returns, so the message says they are in progress, not done.
     */
    @PostMapping("/c/{token}/cashout")
    public String customerCashout(@PathVariable String token,
                                  @RequestParam(defaultValue = "") String amount,
                                  @RequestParam(required = false) String requestId,
                                  RedirectAttributes redirect) {
        Customer customer = customers.byToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown link"));
        Long cents;
        String text = amount.strip();
        if (text.isEmpty() || text.equalsIgnoreCase("all")) {
            cents = null;
        } else {
            try {
                cents = Money.parseCents(text);
            } catch (IllegalArgumentException e) {
                redirect.addFlashAttribute("error", e.getMessage());
                return "redirect:/c/" + token;
            }
        }
        CashoutRequestResult result = cashouts.request(customer.id(), cents, Actors.customer(customer.id()), requestId);
        redirect.addFlashAttribute(result.accepted() ? "notice" : "error", result.message());
        return "redirect:/c/" + token;
    }

    @GetMapping("/cashouts")
    public String list(Model model) {
        model.addAttribute("cashouts", queries.recent(50));
        model.addAttribute("counts", queries.legCounts());
        model.addAttribute("clearingCents", ledger.balanceOf(Account.CASHOUT_CLEARING));
        return "cashout/cashouts";
    }

    @PostMapping("/cashouts/relay")
    public String runRelay(RedirectAttributes redirect) {
        RelayReport report = relay.runOnce();
        redirect.addFlashAttribute(report.failed() > 0 || report.inDoubt() > 0 || report.retryLater() > 0
                ? "error" : "notice", report.summary());
        return "redirect:/cashouts";
    }
}
