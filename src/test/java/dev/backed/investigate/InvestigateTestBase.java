package dev.backed.investigate;

import dev.backed.close.BreakRepository;
import dev.backed.customer.CustomerMessages;
import dev.backed.support.AbstractDbTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

/** One shared context for the investigate tests: stub investigator, counting close runner, MockMvc. */
@AutoConfigureMockMvc
@Import(InvestigateTestConfig.class)
abstract class InvestigateTestBase extends AbstractDbTest {

    @Autowired
    protected InvestigateTestConfig.StubInvestigator investigator;
    @Autowired
    protected InvestigateTestConfig.RecordingCloseRunner closeRunner;
    @Autowired
    protected ProposalService service;
    @Autowired
    protected ProposalRepository proposals;
    @Autowired
    protected BreakRepository breaks;
    @Autowired
    protected CustomerMessages messages;
    @Autowired
    protected MockMvc mvc;

    protected Scenarios scenarios;

    @BeforeEach
    void resetInvestigateFakes() {
        investigator.reset();
        closeRunner.reset();
        scenarios = new Scenarios(jdbc, journal, breaks, messages, tx);
    }

    protected static InvestigatorOutput output(Treatment treatment, Treatment rejected, String... quotes) {
        return new InvestigatorOutput(treatment, List.of(quotes), "Reasoning for the approver.", rejected,
                "It fits the evidence less well.", "HIGH", "We have looked into your refund.", "stub");
    }

    protected long correctionEntries(long breakId) {
        return jdbc.sql("select count(*) from journal_entries where kind = 'CORRECTION' and ref_type = 'break' and ref_id = :id")
                .param("id", String.valueOf(breakId))
                .query(Long.class)
                .single();
    }
}
