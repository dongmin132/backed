package dev.backed.cashout;

/** What one relay run did. */
public record RelayReport(int sent, int refunded, int failed, int retryLater, int inDoubt, int skipped) {

    public static final RelayReport EMPTY = new RelayReport(0, 0, 0, 0, 0, 0);

    RelayReport plus(CashoutRelay.LegOutcome outcome) {
        return new RelayReport(sent + 1,
                refunded + (outcome == CashoutRelay.LegOutcome.REFUNDED ? 1 : 0),
                failed + (outcome == CashoutRelay.LegOutcome.FAILED ? 1 : 0),
                retryLater + (outcome == CashoutRelay.LegOutcome.RETRY_LATER ? 1 : 0),
                inDoubt + (outcome == CashoutRelay.LegOutcome.IN_DOUBT ? 1 : 0),
                skipped + (outcome == CashoutRelay.LegOutcome.SKIPPED ? 1 : 0));
    }

    public String summary() {
        if (sent == 0) {
            return "Nothing to send: no leg is waiting.";
        }
        StringBuilder s = new StringBuilder("Sent " + sent + (sent == 1 ? " leg" : " legs") + ": "
                + refunded + " refunded, " + failed + " failed and compensated, "
                + retryLater + " to retry, " + inDoubt + " in doubt");
        if (skipped > 0) {
            s.append(", ").append(skipped).append(" recorded by another run");
        }
        return s.append('.').toString();
    }
}
