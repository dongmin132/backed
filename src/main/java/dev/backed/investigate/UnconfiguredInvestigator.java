package dev.backed.investigate;

/** The investigator when no usable provider is set up. It never calls anything. */
final class UnconfiguredInvestigator implements Investigator {

    private final String reason;

    UnconfiguredInvestigator(String reason) {
        this.reason = reason;
    }

    @Override
    public boolean configured() {
        return false;
    }

    @Override
    public InvestigatorOutput investigate(String evidenceBundleJson) {
        throw new InvestigatorException(reason);
    }

    @Override
    public String notConfiguredReason() {
        return reason;
    }
}
