package dev.backed.investigate;

import java.time.OffsetDateTime;

/** A proposals row. JSON columns are kept as text. aiProvider and aiModel name who made the call. */
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
        String validationChecks,
        String aiProvider,
        String aiModel) {

    /** "OpenAI gpt-5.4-mini", or empty for a proposal made before the model was recorded. */
    public String modelLabel() {
        return AiProvider.describe(aiProvider, aiModel);
    }
}
