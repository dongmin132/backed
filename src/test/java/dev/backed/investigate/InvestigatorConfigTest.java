package dev.backed.investigate;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider selection: for every combination of keys and backed.ai.provider, exactly one
 * Investigator bean exists and it is the expected one. Every property is set explicitly, so keys
 * exported in the shell do not leak in. Nothing is called.
 */
class InvestigatorConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(InvestigatorConfig.class);

    private ApplicationContextRunner with(String provider, String openAiKey, String anthropicKey, String model) {
        return runner.withPropertyValues(
                "backed.ai.provider=" + provider,
                "backed.openai.api-key=" + openAiKey,
                "backed.anthropic.api-key=" + anthropicKey,
                "backed.ai.model=" + model);
    }

    @Test
    void noKeysAndNoProviderIsNotConfigured() {
        with("", "", "", "").run(ctx -> {
            assertThat(ctx).hasSingleBean(Investigator.class);
            Investigator i = ctx.getBean(Investigator.class);
            assertThat(i).isInstanceOf(UnconfiguredInvestigator.class);
            assertThat(i.configured()).isFalse();
            assertThat(i.notConfiguredReason()).isEqualTo(ProposalService.NOT_CONFIGURED)
                    .contains("OPENAI_API_KEY", "ANTHROPIC_API_KEY");
        });
    }

    @Test
    void onlyAnOpenAiKeyUsesOpenAiWithItsDefaultModel() {
        with("", "test-openai", "", "").run(ctx -> {
            assertThat(ctx).hasSingleBean(Investigator.class);
            Investigator i = ctx.getBean(Investigator.class);
            assertThat(i).isInstanceOf(OpenAiInvestigator.class);
            assertThat(i.configured()).isTrue();
            assertThat(i.provider()).isEqualTo("openai");
            assertThat(i.model()).isEqualTo(AiProvider.OPENAI.defaultModel()).isEqualTo("gpt-5.4-mini");
        });
    }

    @Test
    void onlyAnAnthropicKeyUsesClaudeWithItsDefaultModel() {
        with("", "", "test-anthropic", "").run(ctx -> {
            assertThat(ctx).hasSingleBean(Investigator.class);
            Investigator i = ctx.getBean(Investigator.class);
            assertThat(i).isInstanceOf(ClaudeInvestigator.class);
            assertThat(i.configured()).isTrue();
            assertThat(i.provider()).isEqualTo("anthropic");
            assertThat(i.model()).isEqualTo(AiProvider.ANTHROPIC.defaultModel()).isEqualTo("claude-opus-5-5");
        });
    }

    @Test
    void bothKeysPreferOpenAi() {
        with("", "test-openai", "test-anthropic", "").run(ctx -> {
            assertThat(ctx).hasSingleBean(Investigator.class);
            assertThat(ctx.getBean(Investigator.class)).isInstanceOf(OpenAiInvestigator.class);
        });
    }

    @Test
    void anExplicitProviderWinsOverTheDefaultOrder() {
        with("anthropic", "test-openai", "test-anthropic", "").run(ctx -> {
            assertThat(ctx).hasSingleBean(Investigator.class);
            assertThat(ctx.getBean(Investigator.class)).isInstanceOf(ClaudeInvestigator.class);
        });
        with("openai", "test-openai", "test-anthropic", "").run(ctx -> {
            assertThat(ctx).hasSingleBean(Investigator.class);
            assertThat(ctx.getBean(Investigator.class)).isInstanceOf(OpenAiInvestigator.class);
        });
        with(" OpenAI ", "test-openai", "", "").run(ctx ->
                assertThat(ctx.getBean(Investigator.class)).isInstanceOf(OpenAiInvestigator.class));
    }

    @Test
    void anExplicitProviderWithoutItsKeyIsNotConfiguredAndSaysWhichKey() {
        with("anthropic", "test-openai", "", "").run(ctx -> {
            assertThat(ctx).hasSingleBean(Investigator.class);
            Investigator i = ctx.getBean(Investigator.class);
            assertThat(i.configured()).isFalse();
            assertThat(i.notConfiguredReason())
                    .isEqualTo("AI investigator is not configured: BACKED_AI_PROVIDER is anthropic, so set ANTHROPIC_API_KEY.");
        });
        with("openai", "", "test-anthropic", "").run(ctx -> {
            assertThat(ctx).hasSingleBean(Investigator.class);
            Investigator i = ctx.getBean(Investigator.class);
            assertThat(i.configured()).isFalse();
            assertThat(i.notConfiguredReason()).contains("BACKED_AI_PROVIDER is openai, so set OPENAI_API_KEY");
        });
    }

    @Test
    void anUnknownProviderIsNotConfiguredAndTheAppStillStarts() {
        with("gemini", "test-openai", "test-anthropic", "").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(Investigator.class);
            Investigator i = ctx.getBean(Investigator.class);
            assertThat(i.configured()).isFalse();
            assertThat(i.notConfiguredReason()).contains("must be openai or anthropic, not \"gemini\"");
        });
    }

    @Test
    void theModelPropertyOverridesTheChosenProvidersDefault() {
        with("", "test-openai", "", "gpt-6-luna").run(ctx ->
                assertThat(ctx.getBean(Investigator.class).model()).isEqualTo("gpt-6-luna"));
        with("anthropic", "", "test-anthropic", "custom-claude-model").run(ctx ->
                assertThat(ctx.getBean(Investigator.class).model()).isEqualTo("custom-claude-model"));
    }

    @Test
    void chooseCoversEveryCombination() {
        assertThat(InvestigatorConfig.choose("", "", "").provider()).isNull();
        assertThat(InvestigatorConfig.choose("", "k", "").provider()).isEqualTo(AiProvider.OPENAI);
        assertThat(InvestigatorConfig.choose("", "", "k").provider()).isEqualTo(AiProvider.ANTHROPIC);
        assertThat(InvestigatorConfig.choose("", "k", "k").provider()).isEqualTo(AiProvider.OPENAI);
        assertThat(InvestigatorConfig.choose("openai", "k", "k").provider()).isEqualTo(AiProvider.OPENAI);
        assertThat(InvestigatorConfig.choose("anthropic", "k", "k").provider()).isEqualTo(AiProvider.ANTHROPIC);
        assertThat(InvestigatorConfig.choose("openai", "", "k").provider()).isNull();
        assertThat(InvestigatorConfig.choose("anthropic", "k", "").provider()).isNull();
        assertThat(InvestigatorConfig.choose("openai", "", "").problem()).contains("OPENAI_API_KEY");
        assertThat(InvestigatorConfig.choose("anthropic", "", "").problem()).contains("ANTHROPIC_API_KEY");
        assertThat(InvestigatorConfig.choose(null, " ", "\t").provider()).isNull();
    }

    @Test
    void describeNamesProviderAndModel() {
        assertThat(AiProvider.describe("openai", "gpt-5.4-mini")).isEqualTo("OpenAI gpt-5.4-mini");
        assertThat(AiProvider.describe("anthropic", "claude-opus-5-5")).isEqualTo("Claude claude-opus-5-5");
        assertThat(AiProvider.describe(null, "m")).isEqualTo("m");
        assertThat(AiProvider.describe(null, null)).isEmpty();
    }
}
