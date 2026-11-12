package dev.backed.close;

import java.util.List;

/**
 * What one close run found, stored as JSON in day_closes.summary and rendered by the close page.
 *
 * @param checks             the three reconciliation checks
 * @param breaks             breaks still OPEN or PROPOSED after the run
 * @param steps              pre-close steps that ran, with how many items each handled
 * @param inDoubtTopups      top-ups still IN_DOUBT
 * @param inDoubtLegs        cash-out legs still IN_DOUBT
 * @param ordersChecked      PayPal orders read during the run
 * @param paypalError        why PayPal could not be read, or null
 * @param journalEntryCount  entries in the journal when the checks ran; advancing the day requires it unchanged
 * @param notes              other findings, one line each
 */
public record CloseSummary(
        List<Check> checks,
        List<BreakLine> breaks,
        List<StepRun> steps,
        int inDoubtTopups,
        int inDoubtLegs,
        int ordersChecked,
        String paypalError,
        long journalEntryCount,
        List<String> notes) {

    public CloseSummary {
        checks = checks == null ? List.of() : List.copyOf(checks);
        breaks = breaks == null ? List.of() : List.copyOf(breaks);
        steps = steps == null ? List.of() : List.copyOf(steps);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** One check: expected and actual in cents; null when it could not be computed. */
    public record Check(String key, String title, String expectedLabel, Long expectedCents,
                        String actualLabel, Long actualCents, boolean pass, String explanation) {

        public Long differenceCents() {
            return expectedCents == null || actualCents == null ? null : actualCents - expectedCents;
        }
    }

    public record BreakLine(long id, String kind, String status, Long customerId, String paypalRef,
                            long amountCents, String note, boolean foundThisRun) {
    }

    public record StepRun(String name, int processed, String error) {
    }
}
