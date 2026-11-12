package dev.backed.investigate;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaFallbacksParam;
import com.anthropic.models.beta.messages.BetaJsonOutputFormat;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;
import org.springframework.beans.factory.DisposableBean;

import java.time.Duration;
import java.util.List;

/**
 * The investigator backed by Claude through the Anthropic Java SDK: one Messages call per break,
 * structured JSON output, no tools. Configured only when an API key is present. Created by
 * {@link InvestigatorConfig} when the provider is anthropic.
 */
public class ClaudeInvestigator implements Investigator, DisposableBean {

    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final String effort;
    private final boolean fallbacks;
    private volatile AnthropicClient client;

    public ClaudeInvestigator(String apiKey, String model, long timeoutSeconds, String effort, boolean fallbacks) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.effort = effort == null ? "" : effort.strip();
        this.fallbacks = fallbacks;
    }

    @Override
    public boolean configured() {
        return !apiKey.isEmpty();
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public String provider() {
        return AiProvider.ANTHROPIC.id();
    }

    @Override
    public InvestigatorOutput investigate(String evidenceBundleJson) {
        if (!configured()) {
            throw new InvestigatorException(ProposalService.NOT_CONFIGURED);
        }
        BetaOutputConfig.Builder output = BetaOutputConfig.builder()
                .format(BetaJsonOutputFormat.builder().schema(schema()).build());
        if (!effort.isEmpty()) {
            output.effort(BetaOutputConfig.Effort.of(effort));
        }
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(InvestigatorPrompt.SYSTEM_PROMPT)
                .addUserMessage(InvestigatorPrompt.userMessage(evidenceBundleJson))
                .outputConfig(output.build());
        if (fallbacks) {
            // Server-side refusal fallback: a declined request is retried on another model by the API.
            params.addBeta(FALLBACK_BETA).fallbacks(BetaFallbacksParam.ofDefault());
        }
        BetaMessage message;
        try {
            message = client().beta().messages().create(params.build());
        } catch (AnthropicException e) {
            throw new InvestigatorException("Claude API call failed: " + e.getMessage(), e);
        }
        BetaStopReason stop = message.stopReason().orElse(null);
        if (BetaStopReason.REFUSAL.equals(stop)) {
            throw new InvestigatorException("Claude declined to answer.");
        }
        if (BetaStopReason.MAX_TOKENS.equals(stop)) {
            throw new InvestigatorException("Claude's answer was cut off.");
        }
        return parse(finalText(message.content()), message.model().toString());
    }

    /** The text after the last fallback block, which is what the answering model wrote. */
    static String finalText(List<BetaContentBlock> content) {
        StringBuilder text = new StringBuilder();
        for (BetaContentBlock block : content) {
            if (block.isFallback()) {
                text.setLength(0);
            }
            block.text().ifPresent(t -> text.append(t.text()));
        }
        return text.toString();
    }

    /** Parses the structured output into an InvestigatorOutput. */
    static InvestigatorOutput parse(String json, String model) {
        return InvestigatorPrompt.parse(json, model, "Claude");
    }

    private static BetaJsonOutputFormat.Schema schema() {
        BetaJsonOutputFormat.Schema.Builder b = BetaJsonOutputFormat.Schema.builder();
        InvestigatorPrompt.OUTPUT_SCHEMA.forEach((k, v) -> b.putAdditionalProperty(k, JsonValue.from(v)));
        return b.build();
    }

    private AnthropicClient client() {
        AnthropicClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    // maxRetries 1: at most one retry on overload or a transient error, then give up
                    c = AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(timeout).maxRetries(1).build();
                    client = c;
                }
            }
        }
        return c;
    }

    @Override
    public void destroy() {
        AnthropicClient c = client;
        if (c != null) {
            c.close();
        }
    }
}
