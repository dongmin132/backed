package dev.backed.web;

import dev.backed.close.BreakRepository;
import dev.backed.ledger.Account;
import dev.backed.ledger.BalanceService;
import dev.backed.ledger.LedgerQueries;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Controller
public class DashboardController {

    public record CustomerRow(long id, String name, String token, long balanceCents) {
    }

    public record LastClose(long id, LocalDate businessDate, String status, Long unexplainedCents,
                            String runBy, OffsetDateTime runAt) {
    }

    private final JdbcClient jdbc;
    private final BalanceService balances;
    private final LedgerQueries ledger;
    private final BreakRepository breaks;

    public DashboardController(JdbcClient jdbc, BalanceService balances, LedgerQueries ledger, BreakRepository breaks) {
        this.jdbc = jdbc;
        this.balances = balances;
        this.ledger = ledger;
        this.breaks = breaks;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("customers", jdbc.sql("""
                        select c.id, c.name, c.token, coalesce(b.balance_cents, 0) as balance_cents
                          from customers c left join customer_balances b on b.customer_id = c.id
                         order by c.id
                        """)
                .query(CustomerRow.class)
                .list());
        model.addAttribute("owedCents", balances.totalOwed());
        model.addAttribute("prepaidControlCents", ledger.balanceOf(Account.CUSTOMER_PREPAID));
        model.addAttribute("paypalCashCents", ledger.balanceOf(Account.PAYPAL_CASH));
        model.addAttribute("openBreaks", breaks.findOpen().size());
        model.addAttribute("inDoubtTopups", jdbc.sql("select count(*) from topups where state = 'IN_DOUBT'")
                .query(Integer.class).single());
        model.addAttribute("lastClose", jdbc.sql("""
                        select id, business_date, status, unexplained_cents, run_by, run_at
                          from day_closes order by run_at desc, id desc limit 1
                        """)
                .query(LastClose.class)
                .optional()
                .orElse(null));
        return "web/dashboard";
    }
}
