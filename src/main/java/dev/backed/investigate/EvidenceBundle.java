package dev.backed.investigate;

/** The JSON document the model reads, and the facts code uses to draft and check the entry. */
public record EvidenceBundle(String json, DraftContext context) {
}
