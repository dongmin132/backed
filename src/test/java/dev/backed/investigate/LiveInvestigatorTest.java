package dev.backed.investigate;

import dev.backed.close.Break;
import dev.backed.close.BreakRepository;
import dev.backed.customer.CustomerMessages;
import dev.backed.support.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sends the three situations to the real model. Skipped unless ANTHROPIC_API_KEY is set; it makes
 * paid API calls, so it never runs in the normal build.
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class LiveInvestigatorTest extends AbstractDbTest {

    @Autowired
    ProposalService service;
    @Autowired
    ProposalRepository proposals;
    @Autowired
    BreakRepository breaks;
    @Autowired
    CustomerMessages messages;

    @Test
    void theModelTellsTheThreeSituationsApart() {
        Scenarios s = new Scenarios(jdbc, journal, breaks, messages, tx);
        Map<String, Break> cases = new LinkedHashMap<>();
        cases.put("GOODWILL", s.goodwill(customerA().id()));
        cases.put("BALANCE_RETURNED", s.balanceReturned(customerB().id()));
        cases.put("DUPLICATE_REVERSAL", s.duplicate(customerC().id()));

        cases.forEach((expected, brk) -> {
            long id = service.investigate(brk.id(), "maker");
            Proposal p = proposals.findById(id).orElseThrow();
            InvestigatorOutput out = Json.read(p.aiOutput(), InvestigatorOutput.class);
            System.out.printf("LIVE expected=%s got=%s confidence=%s status=%s model=%s%n  quotes=%s%n  errors=%s%n",
                    expected, p.treatment(), out.confidence(), p.status(), out.model(), out.evidenceQuotes(),
                    p.validationErrors());
            assertThat(p.treatment()).isNotNull();
        });
    }
}
