package dev.backed.ledger;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record JournalEntryView(
        long id,
        LocalDate businessDate,
        String kind,
        String refType,
        String refId,
        String memo,
        String createdBy,
        String approvedBy,
        OffsetDateTime createdAt,
        List<Line> lines) {

    public record Line(Account account, Long customerId, String customerName, long debitCents, long creditCents) {
    }

    public long totalDebits() {
        return lines.stream().mapToLong(Line::debitCents).sum();
    }

    public long totalCredits() {
        return lines.stream().mapToLong(Line::creditCents).sum();
    }
}
