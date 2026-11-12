package dev.backed.investigate;

/** Reads one evidence bundle and picks a treatment. One call per break, no tool loop. */
public interface Investigator {

    /** False when no API key is set; the pages then say the investigator is not configured. */
    boolean configured();

    /**
     * @param evidenceBundleJson the bundle exactly as stored with the proposal
     * @throws InvestigatorException when the model cannot be reached or its answer is unusable
     */
    InvestigatorOutput investigate(String evidenceBundleJson);
}
