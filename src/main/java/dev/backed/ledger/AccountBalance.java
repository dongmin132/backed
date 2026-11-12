package dev.backed.ledger;

/** Totals for one account. balanceCents is signed in the account's normal direction. */
public record AccountBalance(Account account, long debitCents, long creditCents, long balanceCents) {
}
