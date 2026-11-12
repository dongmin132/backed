package dev.backed.pos;

import dev.backed.config.Actors;
import dev.backed.customer.Customer;
import dev.backed.customer.Customers;
import dev.backed.ledger.Money;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;

/** The counter: charge a customer's prepaid balance. */
@Controller
public class PosController {

    public record CustomerOption(long id, String name, long balanceCents) {
    }

    private final SpendService spends;
    private final Customers customers;
    private final JdbcClient jdbc;

    public PosController(SpendService spends, Customers customers, JdbcClient jdbc) {
        this.spends = spends;
        this.customers = customers;
        this.jdbc = jdbc;
    }

    @GetMapping("/pos")
    public String page(@RequestParam(required = false) Long customerId, Model model) {
        model.addAttribute("customers", jdbc.sql("""
                        select c.id, c.name, coalesce(b.balance_cents, 0) as balance_cents
                          from customers c left join customer_balances b on b.customer_id = c.id
                         order by c.id
                        """)
                .query(CustomerOption.class)
                .list());
        model.addAttribute("selectedCustomerId", customerId);
        model.addAttribute("requestId", "spend-" + UUID.randomUUID());
        model.addAttribute("spends", spends.recent(30));
        return "pos/pos";
    }

    @PostMapping("/pos")
    public String spend(@RequestParam long customerId,
                        @RequestParam(defaultValue = "") String amount,
                        @RequestParam(required = false) String memo,
                        @RequestParam(required = false) String requestId,
                        Principal principal,
                        RedirectAttributes redirect) {
        redirect.addAttribute("customerId", customerId);
        Optional<Customer> customer = customers.byId(customerId);
        if (customer.isEmpty()) {
            redirect.addFlashAttribute("error", "Pick a customer.");
            return "redirect:/pos";
        }
        long cents;
        try {
            cents = Money.parseCents(amount);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/pos";
        }
        SpendResult result = spends.spend(customerId, cents, memo, Actors.of(principal), requestId);
        redirect.addFlashAttribute(result.outcome() == SpendResult.Outcome.REFUSED ? "error" : "notice",
                customer.get().name() + ": " + result.message());
        return "redirect:/pos";
    }
}
