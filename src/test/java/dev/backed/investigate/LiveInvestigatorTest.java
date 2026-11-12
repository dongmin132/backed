package dev.backed.investigate;

import dev.backed.close.Break;
import dev.backed.close.BreakRepository;
import dev.backed.customer.CustomerMessages;
import dev.backed.support.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sends the three situations to the real model, on whichever provider the usual selection picks
 * (BACKED_AI_PROVIDER, else OpenAI when OPENAI_API_KEY is set, else Claude). Skipped unless
 * OPENAI_API_KEY or ANTHROPIC_API_KEY is exported in the shell (a key only in .env does not run it);
 * it makes paid API calls, three per run.
 */
@EnabledIf("aKeyIsExported")
@TestPropertySource(properties = {
        // AbstractDbTest blanks these; put back what application.properties would read.
        "backed.ai.provider=${BACKED_AI_PROVIDER:}",
        "backed.ai.model=${BACKED_AI_MODEL:}",
        "backed.openai.api-key=${OPENAI_API_KEY:}",
        "backed.anthropic.api-key=${ANTHROPIC_API_KEY:}"
})
class LiveInvestigatorTest extends AbstractDbTest {

    @Autowired
    ProposalService service;
    @Autowired
    ProposalRepository proposals;
    @Autowired
    BreakRepository breaks;
    @Autowired
    CustomerMessages messages;

    static boolean aKeyIsExported() {
        return exported("OPENAI_API_KEY") || exported("ANTHROPIC_API_KEY");
    }

    private static boolean exported(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }

    @Test
    void theModelTellsTheThreeSituationsApart() {
        assertThat(service.investigatorConfigured()).as(service.investigatorNotConfiguredReason()).isTrue();
        System.out.printf("LIVE investigator: %s%n", service.investigatorDescription());

        Scenarios s = new Scenarios(jdbc, journal, breaks, messages, tx);
        Map<String, Break> cases = new LinkedHashMap<>();
        cases.put("cold coffee goodwill|GOODWILL", s.goodwill(customerA().id()));
        cases.put("moving away balance returned|BALANCE_RETURNED", s.balanceReturned(customerB().id()));
        cases.put("charged twice duplicate reversal|DUPLICATE_REVERSAL", s.duplicate(customerC().id()));

        int matches = 0;
        for (Map.Entry<String, Break> c : cases.entrySet()) {
            String situation = c.getKey().split("\\|")[0];
            Treatment expected = Treatment.valueOf(c.getKey().split("\\|")[1]);
            long started = System.nanoTime();
            long id = service.investigate(c.getValue().id(), "maker");
            long millis = (System.nanoTime() - started) / 1_000_000;
            Proposal p = proposals.findById(id).orElseThrow();
            InvestigatorOutput out = Json.read(p.aiOutput(), InvestigatorOutput.class);
            boolean match = expected == p.treatment();
            matches += match ? 1 : 0;
            System.out.printf("LIVE %s: expected=%s actual=%s %s confidence=%s status=%s model=%s (%d ms)%n",
                    situation, expected, p.treatment(), match ? "MATCH" : "MISMATCH", out.confidence(), p.status(),
                    p.modelLabel(), millis);
            for (String quote : out.evidenceQuotes()) {
                System.out.printf("  quote: %s%n", quote);
            }
            System.out.printf("  rejected: %s because %s%n", out.rejectedAlternative(), out.rejectedBecause());
            if (p.validationErrors() != null) {
                System.out.printf("  validator: %s%n", p.validationErrors().replace('\n', ' '));
            }
            assertThat(p.treatment()).isNotNull();
            assertThat(p.aiProvider()).isNotBlank();
            assertThat(p.aiModel()).isNotBlank();
        }
        System.out.printf("LIVE %d of %d situations matched the expected treatment%n", matches, cases.size());
    }
}
