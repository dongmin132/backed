package dev.backed.investigate;

/** One line of the validator's checklist. */
public record ValidationCheck(String name, boolean ok, String detail) {
}
