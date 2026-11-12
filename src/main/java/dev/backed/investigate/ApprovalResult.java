package dev.backed.investigate;

import dev.backed.close.CloseOutcome;

import java.time.LocalDate;

/**
 * @param closeOutcome the re-run close, or null when it could not run
 * @param closeNote    why the close did not run, or null
 */
public record ApprovalResult(long entryId, LocalDate entryDate, CloseOutcome closeOutcome, String closeNote) {
}
