package dev.backed.investigate;

/** What an off-book PayPal movement was. Stored in proposals.treatment. */
public enum Treatment {
    BALANCE_RETURNED("Balance returned"),
    GOODWILL("Goodwill"),
    DUPLICATE_REVERSAL("Duplicate reversal"),
    NEEDS_HUMAN("Needs a human");

    private final String label;

    Treatment(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Treatment parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return valueOf(value.trim().toUpperCase());
    }
}
