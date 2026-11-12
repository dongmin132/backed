package dev.backed.investigate;

import java.time.OffsetDateTime;

/** A proposals row. JSON columns are kept as text. */
public record Proposal(
        long id,
        long breakId,
        Treatment treatment,
        String aiOutput,
        String draft,
        Boolean validationOk,
        String validationErrors,
        ProposalStatus status,
        String createdBy,
        String approvedBy,
        Long postedEntryId,
        OffsetDateTime createdAt,
        OffsetDateTime decidedAt,
        String decidedBy,
        String evidenceBundle,
        String alternativeDraft,
        String validationChecks) {
}
