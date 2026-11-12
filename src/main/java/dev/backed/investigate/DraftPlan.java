package dev.backed.investigate;

import dev.backed.close.NewBreak;
import dev.backed.ledger.Account;
import dev.backed.ledger.EntryKind;
import dev.backed.ledger.JournalDraft;
import dev.backed.ledger.JournalLineDraft;

import java.util.List;

/**
 * A correcting entry derived by code from a treatment and PayPal's amounts.
 *
 * @param problems                   why no entry can be drafted for this treatment; empty when lines are usable
 * @param customerBalanceEffectCents change to the customer's balance (+ up, - down)
 * @param shopResultEffectCents      change to the shop's profit: income minus expenses
 * @param paypalCashEffectCents      change to PAYPAL_CASH
 * @param alsoResolves               a second break the same entry explains (the other half of a duplicate), or null
 */
public record DraftPlan(
        Treatment treatment,
        long breakId,
        List<JournalLineDraft> lines,
        String memo,
        long customerBalanceEffectCents,
        long shopResultEffectCents,
        long paypalCashEffectCents,
        List<String> problems,
        NewBreak alsoResolves) {

    public static final String REF_TYPE = "break";

    public DraftPlan {
        lines = lines == null ? List.of() : List.copyOf(lines);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    static DraftPlan of(Treatment treatment, long breakId, List<JournalLineDraft> lines, String memo,
                        List<String> problems, NewBreak alsoResolves) {
        long customer = 0;
        long shop = 0;
        long cash = 0;
        for (JournalLineDraft l : lines) {
            Account a = l.account();
            if (a == Account.CUSTOMER_PREPAID) {
                customer += l.creditCents() - l.debitCents();
            }
            if (a == Account.PAYPAL_CASH) {
                cash += l.debitCents() - l.creditCents();
            }
            if (a.type() == Account.Type.INCOME) {
                shop += l.creditCents() - l.debitCents();
            } else if (a.type() == Account.Type.EXPENSE) {
                shop -= l.debitCents() - l.creditCents();
            }
        }
        return new DraftPlan(treatment, breakId, lines, memo, customer, shop, cash, problems, alsoResolves);
    }

    public boolean drafted() {
        return problems.isEmpty() && !lines.isEmpty();
    }

    /** The journal draft to post: kind CORRECTION, one per break, created by the maker. */
    public JournalDraft toJournalDraft(String maker) {
        return new JournalDraft(null, EntryKind.CORRECTION, REF_TYPE, String.valueOf(breakId), memo, maker, null, lines);
    }

    public long totalDebits() {
        return lines.stream().mapToLong(JournalLineDraft::debitCents).sum();
    }
}
