package dev.backed.investigate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Duration;

/**
 * Registers exactly one {@link Investigator}.
 *
 * <ul>
 *   <li>{@code backed.ai.provider} ({@code BACKED_AI_PROVIDER}) is {@code openai} or {@code anthropic}: that
 *       provider, or "not configured" when its key is missing.</li>
 *   <li>Not set: OpenAI when {@code OPENAI_API_KEY} is present, otherwise Claude when
 *       {@code ANTHROPIC_API_KEY} is present, otherwise "not configured".</li>
 * </ul>
 * {@code backed.ai.model} ({@code BACKED_AI_MODEL}) overrides the chosen provider's default model.
 * Keys are read from {@code backed.openai.api-key} and {@code backed.anthropic.api-key}, which
 * application.properties fills from the two environment variables (or the git-ignored .env).
 */
@Configuration(proxyBeanMethods = false)
public class InvestigatorConfig {

    private static final Logger log = LoggerFactory.getLogger(InvestigatorConfig.class);

    /** The provider to use, or the reason there is none. Exactly one of the two is set. */
    record Choice(AiProvider provider, String problem) {
    }

    /**
     * @param explicit     backed.ai.provider, blank when not set
     * @param openAiKey    the OpenAI key, blank when not set
     * @param anthropicKey the Anthropic key, blank when not set
     */
    static Choice choose(String explicit, String openAiKey, String anthropicKey) {
        boolean hasOpenAi = openAiKey != null && !openAiKey.isBlank();
        boolean hasAnthropic = anthropicKey != null && !anthropicKey.isBlank();
        if (explicit != null && !explicit.isBlank()) {
            AiProvider wanted = AiProvider.byId(explicit);
            if (wanted == null) {
                return new Choice(null, "AI investigator is not configured: BACKED_AI_PROVIDER must be openai or "
                        + "anthropic, not \"" + explicit.strip() + "\".");
            }
            boolean hasKey = wanted == AiProvider.OPENAI ? hasOpenAi : hasAnthropic;
            return hasKey ? new Choice(wanted, null)
                    : new Choice(null, "AI investigator is not configured: BACKED_AI_PROVIDER is " + wanted.id()
                    + ", so set " + wanted.keyVariable() + ".");
        }
        if (hasOpenAi) {
            return new Choice(AiProvider.OPENAI, null);
        }
        if (hasAnthropic) {
            return new Choice(AiProvider.ANTHROPIC, null);
        }
        return new Choice(null, ProposalService.NOT_CONFIGURED);
    }

    @Bean
    public Investigator investigator(Environment env) {
        String openAiKey = env.getProperty("backed.openai.api-key", "").strip();
        String anthropicKey = env.getProperty("backed.anthropic.api-key", "").strip();
        Choice choice = choose(env.getProperty("backed.ai.provider", ""), openAiKey, anthropicKey);
        if (choice.provider() == null) {
            log.info("{}", choice.problem());
            return new UnconfiguredInvestigator(choice.problem());
        }
        String configuredModel = env.getProperty("backed.ai.model", "").strip();
        String model = configuredModel.isEmpty() ? choice.provider().defaultModel() : configuredModel;
        long readSeconds = env.getProperty("backed.ai.timeout-seconds", Long.class, 120L);
        long connectSeconds = env.getProperty("backed.ai.connect-timeout-seconds", Long.class, 10L);
        String effort = env.getProperty("backed.ai.effort", "medium");
        log.info("AI investigator: {} model {}", choice.provider().label(), model);
        return switch (choice.provider()) {
            case OPENAI -> new OpenAiInvestigator(openAiKey, model, effort,
                    env.getProperty("backed.openai.base-url", OpenAiInvestigator.DEFAULT_BASE_URL),
                    Duration.ofSeconds(connectSeconds), Duration.ofSeconds(readSeconds), Duration.ofSeconds(2));
            case ANTHROPIC -> new ClaudeInvestigator(anthropicKey, model, readSeconds, effort,
                    env.getProperty("backed.ai.fallbacks", Boolean.class, true));
        };
    }
}
