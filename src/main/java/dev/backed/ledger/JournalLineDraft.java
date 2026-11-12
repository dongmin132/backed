package dev.backed.ledger;

/**
 * One line of a journal draft. Exactly one of debitCents and creditCents is positive.
 * customerId is required on CUSTOMER_PREPAID lines and optional elsewhere.
 */
public record JournalLineDraft(Account account, Long customerId, long debitCents, long creditCents) {

    public static JournalLineDraft debit(Account account, long cents) {
        return new JournalLineDraft(account, null, cents, 0);
    }

    public static JournalLineDraft credit(Account account, long cents) {
        return new JournalLineDraft(account, null, 0, cents);
    }

    public static JournalLineDraft debit(Account account, long customerId, long cents) {
        return new JournalLineDraft(account, customerId, cents, 0);
    }

    public static JournalLineDraft credit(Account account, long customerId, long cents) {
        return new JournalLineDraft(account, customerId, 0, cents);
    }
}
