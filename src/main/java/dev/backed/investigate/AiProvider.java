package dev.backed.investigate;

import java.util.Locale;

/** The model providers the investigator can run on. The id is what backed.ai.provider takes and what proposals store. */
public enum AiProvider {
    OPENAI("openai", "OpenAI", "OPENAI_API_KEY", "gpt-6.1-sol"),
    ANTHROPIC("anthropic", "Claude", "ANTHROPIC_API_KEY", "claude-opus-5-5");

    private final String id;
    private final String label;
    private final String keyVariable;
    private final String defaultModel;

    AiProvider(String id, String label, String keyVariable, String defaultModel) {
        this.id = id;
        this.label = label;
        this.keyVariable = keyVariable;
        this.defaultModel = defaultModel;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    /** The environment variable that holds this provider's API key. */
    public String keyVariable() {
        return keyVariable;
    }

    /** The model used when backed.ai.model is not set. */
    public String defaultModel() {
        return defaultModel;
    }

    /** The provider for an id ("openai", "anthropic", any case), or null when it is not one. */
    public static AiProvider byId(String id) {
        if (id == null) {
            return null;
        }
        String wanted = id.strip().toLowerCase(Locale.ROOT);
        for (AiProvider p : values()) {
            if (p.id.equals(wanted)) {
                return p;
            }
        }
        return null;
    }

    /** "OpenAI gpt-6.1-sol" for the pages; an unknown provider id is shown as stored. */
    public static String describe(String providerId, String model) {
        AiProvider p = byId(providerId);
        String name = p != null ? p.label : providerId;
        String m = model == null ? "" : model.strip();
        if (name == null || name.isBlank()) {
            return m;
        }
        return m.isEmpty() ? name : name + " " + m;
    }
}
