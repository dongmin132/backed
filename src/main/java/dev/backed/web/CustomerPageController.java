package dev.backed.web;

import dev.backed.customer.Customer;
import dev.backed.customer.CustomerMessages;
import dev.backed.customer.Customers;
import dev.backed.ledger.BalanceService;
import dev.backed.ledger.LedgerQueries;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A customer's private page. The top-up and cash-out forms post to /c/{token}/topup and
 * /c/{token}/cashout, which the top-up and cash-out modules handle; they redirect back here with
 * a "notice" or "error" flash attribute.
 */
@Controller
public class CustomerPageController {

    public record TopupRow(UUID id, long amountCents, String state, String failureReason, OffsetDateTime createdAt) {
    }

    public record CashoutRow(UUID id, long amountCents, String state, OffsetDateTime createdAt) {
    }

    private final Customers customers;
    private final CustomerMessages messages;
    private final BalanceService balances;
    private final LedgerQueries ledger;
    private final JdbcClient jdbc;

    public CustomerPageController(Customers customers, CustomerMessages messages, BalanceService balances,
                                  LedgerQueries ledger, JdbcClient jdbc) {
        this.customers = customers;
        this.messages = messages;
        this.balances = balances;
        this.ledger = ledger;
        this.jdbc = jdbc;
    }

    @GetMapping("/c/{token}")
    public String page(@PathVariable String token, Model model) {
        Customer customer = find(token);
        model.addAttribute("customer", customer);
        model.addAttribute("balanceCents", balances.balanceOf(customer.id()));
        model.addAttribute("history", ledger.customerHistory(customer.id(), 50));
        model.addAttribute("messages", messages.recent(customer.id(), 10));
        model.addAttribute("topups", jdbc.sql("""
                        select id, amount_cents, state, failure_reason, created_at from topups
                         where customer_id = :id order by created_at desc limit 10
                        """)
                .param("id", customer.id())
                .query(TopupRow.class)
                .list());
        model.addAttribute("cashouts", jdbc.sql("""
                        select id, amount_cents, state, created_at from cashouts
                         where customer_id = :id order by created_at desc limit 10
                        """)
                .param("id", customer.id())
                .query(CashoutRow.class)
                .list());
        return "web/customer";
    }

    @PostMapping("/c/{token}/message")
    public String message(@PathVariable String token, @RequestParam(defaultValue = "") String body,
                          RedirectAttributes redirect) {
        Customer customer = find(token);
        try {
            messages.add(customer.id(), body);
            redirect.addFlashAttribute("notice", "Message sent to the shop.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/c/" + token;
    }

    private Customer find(String token) {
        return customers.byToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown link"));
    }
}
