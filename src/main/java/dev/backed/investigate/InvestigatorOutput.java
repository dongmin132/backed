package dev.backed.investigate;

import java.util.List;

/**
 * What the investigator decided for one break. It carries no amounts: code derives every amount
 * from PayPal's data.
 *
 * @param evidenceQuotes      passages copied verbatim from the evidence bundle
 * @param rejectedAlternative the most plausible treatment it did not choose
 * @param confidence          LOW, MEDIUM or HIGH
 * @param customerNote        a short note to the customer
 * @param model               the model that produced it, for the audit trail
 */
public record InvestigatorOutput(
        Treatment treatment,
        List<String> evidenceQuotes,
        String reasoning,
        Treatment rejectedAlternative,
        String rejectedBecause,
        String confidence,
        String customerNote,
        String model) {

    public InvestigatorOutput {
        evidenceQuotes = evidenceQuotes == null ? List.of() : List.copyOf(evidenceQuotes);
    }
}
