package dev.backed.web;

import dev.backed.ledger.LedgerQueries;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LedgerController {

    private final LedgerQueries ledger;

    public LedgerController(LedgerQueries ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/ledger")
    public String ledger(Model model) {
        model.addAttribute("accounts", ledger.accountBalances());
        model.addAttribute("entries", ledger.recentEntries(200));
        model.addAttribute("unbalanced", ledger.unbalancedEntryIds());
        return "web/ledger";
    }
}
