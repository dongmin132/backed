package dev.backed.close;

import java.time.LocalDate;
import java.util.List;

/**
 * The result of one close run, mirroring its day_closes row.
 *
 * @param owedCents              sum of customer_balances
 * @param ledgerPaypalCashCents  PAYPAL_CASH balance in the journal
 * @param paypalDerivedCashCents cash derived from PayPal's own captures and refunds
 * @param unexplainedCents       ledger minus PayPal-derived cash
 * @param openBreakIds           breaks still OPEN or PROPOSED after this run
 * @param inDoubtCount           top-ups and cash-out legs still IN_DOUBT
 * @param notes                  human-readable findings, for example failed checks
 */
public record CloseOutcome(
        long closeId,
        LocalDate businessDate,
        CloseStatus status,
        long owedCents,
        long ledgerPaypalCashCents,
        long paypalDerivedCashCents,
        long unexplainedCents,
        List<Long> openBreakIds,
        int inDoubtCount,
        List<String> notes) {

    public CloseOutcome {
        openBreakIds = List.copyOf(openBreakIds);
        notes = List.copyOf(notes);
    }
}
