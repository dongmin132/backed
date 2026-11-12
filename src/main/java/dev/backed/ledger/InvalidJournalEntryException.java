package dev.backed.ledger;

import java.util.List;

/** The draft breaks a ledger rule; nothing was written. */
public class InvalidJournalEntryException extends RuntimeException {

    private final List<String> problems;

    public InvalidJournalEntryException(List<String> problems) {
        super("Invalid journal entry: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
