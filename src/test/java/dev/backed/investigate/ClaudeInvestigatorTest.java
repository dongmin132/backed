package dev.backed.investigate;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The parts of the Claude adapter that need no network. */
class ClaudeInvestigatorTest {

    @Test
    void notConfiguredWithoutAKey() {
        assertThat(new ClaudeInvestigator("", "claude-opus-5-5", 60, "medium", true).configured()).isFalse();
        assertThat(new ClaudeInvestigator("  ", "claude-opus-5-5", 60, "medium", true).configured()).isFalse();
        assertThat(new ClaudeInvestigator("key", "claude-opus-5-5", 60, "medium", true).configured()).isTrue();
        assertThatThrownBy(() -> new ClaudeInvestigator("", "m", 60, "medium", true).investigate("{}"))
                .isInstanceOf(InvestigatorException.class)
                .hasMessage(ProposalService.NOT_CONFIGURED);
    }

    @Test
    void parsesTheStructuredOutput() {
        String json = """
                {"treatment":"DUPLICATE_REVERSAL","evidence_quotes":["I was charged twice at the counter","CAP-C2"],
                 "reasoning":"Two captures two minutes apart.","rejected_alternative":"BALANCE_RETURNED",
                 "rejected_because":"No note asks to close the tab.","confidence":"HIGH",
                 "customer_note":"We refunded the duplicate charge; your balance is unchanged."}
                """;
        InvestigatorOutput out = ClaudeInvestigator.parse(json, "claude-opus-5-5");

        assertThat(out.treatment()).isEqualTo(Treatment.DUPLICATE_REVERSAL);
        assertThat(out.evidenceQuotes()).containsExactly("I was charged twice at the counter", "CAP-C2");
        assertThat(out.rejectedAlternative()).isEqualTo(Treatment.BALANCE_RETURNED);
        assertThat(out.confidence()).isEqualTo("HIGH");
        assertThat(out.model()).isEqualTo("claude-opus-5-5");
    }

    @Test
    void rejectsUnusableOutput() {
        assertThatThrownBy(() -> ClaudeInvestigator.parse("not json", "m")).isInstanceOf(InvestigatorException.class);
        assertThatThrownBy(() -> ClaudeInvestigator.parse("{\"treatment\":\"REFUND_IT\"}", "m"))
                .isInstanceOf(InvestigatorException.class);
        assertThatThrownBy(() -> ClaudeInvestigator.parse("{\"evidence_quotes\":[]}", "m"))
                .isInstanceOf(InvestigatorException.class);
    }

    @Test
    void schemaRequiresEveryFieldAndAllowsNoExtras() {
        assertThat(ClaudeInvestigator.OUTPUT_SCHEMA).containsEntry("additionalProperties", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) ClaudeInvestigator.OUTPUT_SCHEMA.get("properties");
        @SuppressWarnings("unchecked")
        java.util.List<String> required = (java.util.List<String>) ClaudeInvestigator.OUTPUT_SCHEMA.get("required");
        assertThat(required).containsExactlyInAnyOrderElementsOf(properties.keySet());
        assertThat(properties).doesNotContainKeys("amount", "amount_cents");
    }
}
