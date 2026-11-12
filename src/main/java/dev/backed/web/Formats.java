package dev.backed.web;

import dev.backed.customer.Customer;
import dev.backed.customer.Customers;
import dev.backed.ledger.Money;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
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

    private final Customers customers;

    public Formats(Customers customers) {
        this.customers = customers;
    }

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

    /** An ISO-8601 timestamp held as text (for example inside stored JSON), formatted like {@link #time}. */
    public String timeText(String iso) {
        if (iso == null || iso.isBlank()) {
            return "";
        }
        try {
            return time(OffsetDateTime.parse(iso));
        } catch (DateTimeParseException e) {
            return iso;
        }
    }

    /** "1 open break", "2 open breaks". */
    public String count(long n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
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

    /** "IN_DOUBT" to "In doubt", "TOPUP_CAPTURED" to "Top-up captured". */
    public String label(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString().replace('_', ' ').toLowerCase(Locale.ROOT)
                .replace("topup", "top-up").replace("cashout", "cash-out");
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Who did something, as people read it: "customer:2" to "Maya (customer)", "system:relay" to "Cash-out relay". */
    public String actor(String actor) {
        if (actor == null) {
            return "";
        }
        if (actor.startsWith("customer:")) {
            try {
                long id = Long.parseLong(actor.substring("customer:".length()));
                return customers.byId(id).map(Customer::name).orElse("Customer " + id) + " (customer)";
            } catch (NumberFormatException e) {
                return actor;
            }
        }
        return switch (actor) {
            case "system:relay" -> "Cash-out relay";
            case "system:scheduler" -> "Scheduler";
            case "system:close" -> "Day close";
            case "system:webhook" -> "PayPal webhook";
            default -> actor;
        };
    }

    /** A top-up's state in the customer's words. */
    public String topupForCustomer(Object state) {
        return switch (String.valueOf(state)) {
            case "CAPTURED" -> "Added";
            case "IN_DOUBT", "CREATED" -> "Waiting for PayPal";
            case "DECLINED", "NOT_CAPTURED" -> "Not charged";
            default -> label(state);
        };
    }

    /** A cash-out's state in the customer's words. */
    public String cashoutForCustomer(Object state) {
        return switch (String.valueOf(state)) {
            case "REQUESTED" -> "On its way";
            case "SETTLED" -> "Refunded";
            case "PARTIALLY_SETTLED" -> "Partly refunded";
            case "COMPENSATED" -> "Returned to balance";
            default -> label(state);
        };
    }

    /** Badge tone on the customer's page: nothing there is an error the customer has to act on. */
    public String customerTone(Object state) {
        return switch (String.valueOf(state)) {
            case "CAPTURED", "SETTLED" -> "ok";
            case "IN_DOUBT", "CREATED", "REQUESTED", "PARTIALLY_SETTLED" -> "watch";
            default -> "plain";
        };
    }

    /** A ledger entry in the customer's words; amountCents is the change to their balance. */
    public String historyForCustomer(Object kind, long amountCents) {
        return switch (String.valueOf(kind)) {
            case "TOPUP_CAPTURED" -> "Top-up";
            case "SPEND" -> "Purchase";
            case "CASHOUT_REQUESTED" -> "Cash-out to your card";
            case "CASHOUT_LEG_FAILED" -> "Cash-out returned to your balance";
            case "CORRECTION" -> amountCents < 0 ? "Refund the shop sent to your card" : "Payment added by the shop";
            default -> label(kind);
        };
    }
}
