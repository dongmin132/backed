package dev.backed.ledger;

import java.time.LocalDate;
import java.util.List;

/**
 * A journal entry before it is posted.
 *
 * @param businessDate the date to book on; null means the current business date
 * @param kind         see {@link EntryKind}
 * @param refType      the business object this entry is about, for example "topup"; null with refId
 * @param refId        that object's id; (kind, refType, refId) is unique across the journal
 * @param createdBy    the actor who caused the entry (a user name or a system actor)
 * @param approvedBy   the checker for approved corrections, otherwise null; must differ from createdBy
 */
public record JournalDraft(
        LocalDate businessDate,
        String kind,
        String refType,
        String refId,
        String memo,
        String createdBy,
        String approvedBy,
        List<JournalLineDraft> lines) {

    public JournalDraft {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public static JournalDraft of(String kind, String refType, String refId, String memo, String createdBy,
                                  JournalLineDraft... lines) {
        return new JournalDraft(null, kind, refType, refId, memo, createdBy, null, List.of(lines));
    }

    public JournalDraft withBusinessDate(LocalDate date) {
        return new JournalDraft(date, kind, refType, refId, memo, createdBy, approvedBy, lines);
    }

    public JournalDraft withApprovedBy(String checker) {
        return new JournalDraft(businessDate, kind, refType, refId, memo, createdBy, checker, lines);
    }

    public long totalDebits() {
        return lines.stream().mapToLong(JournalLineDraft::debitCents).sum();
    }

    public long totalCredits() {
        return lines.stream().mapToLong(JournalLineDraft::creditCents).sum();
    }
}
