package dev.backed.web;

import dev.backed.ledger.Money;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/** Display helpers for templates, available as ${@fmt.money(cents)} and friends. */
@Component("fmt")
public class Formats {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'");
    private static final Set<String> GOOD = Set.of(
            "CLOSED", "CAPTURED", "DONE", "SETTLED", "APPROVED", "RESOLVED", "COMPLETED", "OK");
    private static final Set<String> BAD = Set.of(
            "BROKEN", "DECLINED", "FAILED", "REJECTED_BY_VALIDATOR", "NOT_CAPTURED", "COMPENSATED", "REFUSED");
    private static final Set<String> WATCH = Set.of(
            "IN_DOUBT", "PENDING", "PROPOSED", "PENDING_APPROVAL", "PARTIALLY_SETTLED", "RUNNING", "OPEN",
            "REQUESTED", "CREATED", "DRAFT", "NEEDS_HUMAN");

    public String money(Long cents) {
        return cents == null ? "" : Money.format(cents);
    }

    /** "+$1.00" or "-$1.00". */
    public String signed(Long cents) {
        if (cents == null) {
            return "";
        }
        return cents > 0 ? "+" + Money.format(cents) : Money.format(cents);
    }

    /** Plain decimal for form inputs, "12.50". */
    public String decimal(Long cents) {
        return cents == null ? "" : Money.toDecimalString(cents);
    }

    public String date(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    public String time(OffsetDateTime time) {
        return time == null ? "" : TIME.format(time.withOffsetSameInstant(ZoneOffset.UTC));
    }

    /** CSS modifier for a status badge: ok, bad, watch or plain. */
    public String tone(Object status) {
        if (status == null) {
            return "plain";
        }
        String s = status.toString();
        if (GOOD.contains(s)) {
            return "ok";
        }
        if (BAD.contains(s)) {
            return "bad";
        }
        if (WATCH.contains(s)) {
            return "watch";
        }
        return "plain";
    }

    /** "IN_DOUBT" to "In doubt". */
    public String label(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString().replace('_', ' ').toLowerCase();
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
