package dev.backed.investigate;

/**
 * Reads one evidence bundle and picks a treatment. One call per break, no tool loop. Exactly one
 * implementation is active, chosen by {@link InvestigatorConfig}: OpenAI, Claude, or not configured.
 */
public interface Investigator {

    /** False when no API key is set; the pages then say the investigator is not configured. */
    boolean configured();

    /**
     * @param evidenceBundleJson the bundle exactly as stored with the proposal
     * @throws InvestigatorException when the model cannot be reached or its answer is unusable
     */
    InvestigatorOutput investigate(String evidenceBundleJson);

    /** What to tell a person when {@link #configured()} is false: which variable to set. */
    default String notConfiguredReason() {
        return ProposalService.NOT_CONFIGURED;
    }

    /** The provider id stored with each proposal ("openai", "anthropic"), or null when unknown. */
    default String provider() {
        return null;
    }

    /** The model this investigator asks for; the answer records the model that actually replied. */
    default String model() {
        return null;
    }
}
