package dev.backed.ledger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** USD cents parsing and formatting without floating point. */
public final class Money {

    /** Whole dollars with optional thousands commas ("1,234"), then up to two decimals. A decimal comma is refused. */
    private static final Pattern AMOUNT =
            Pattern.compile("\\$?\\s*(\\d{1,3}(?:,\\d{3}){1,2}|\\d{1,9})(?:\\.(\\d{1,2}))?");

    private Money() {
    }

    /**
     * Parses a user-typed dollar amount such as "12", "12.5", "12.50", "$12.50" or "1,234.50" into cents.
     *
     * @throws IllegalArgumentException when the text is not a positive amount with at most two decimals
     */
    public static long parseCents(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Enter an amount");
        }
        Matcher m = AMOUNT.matcher(text.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Enter an amount like 12.50");
        }
        long dollars = Long.parseLong(m.group(1).replace(",", ""));
        String frac = m.group(2) == null ? "0" : m.group(2);
        long cents = frac.length() == 1 ? Long.parseLong(frac) * 10 : Long.parseLong(frac);
        long total = Math.addExact(Math.multiplyExact(dollars, 100), cents);
        if (total <= 0) {
            throw new IllegalArgumentException("Amount must be more than zero");
        }
        return total;
    }

    /** Formats cents as "$1,234.56" or "-$1,234.56". */
    public static String format(long cents) {
        String sign = cents < 0 ? "-" : "";
        long abs = Math.abs(cents);
        return String.format("%s$%,d.%02d", sign, abs / 100, abs % 100);
    }

    /** Formats cents as a plain decimal string "1234.56", the shape PayPal expects in amount.value. */
    public static String toDecimalString(long cents) {
        if (cents < 0) {
            throw new IllegalArgumentException("negative amount");
        }
        return String.format("%d.%02d", cents / 100, cents % 100);
    }

    /** Parses PayPal's amount.value ("10.00", "10", "10.5") into cents. */
    public static long fromDecimalString(String value) {
        String v = value.trim();
        boolean negative = v.startsWith("-");
        if (negative) {
            v = v.substring(1);
        }
        int dot = v.indexOf('.');
        String whole = dot < 0 ? v : v.substring(0, dot);
        String frac = dot < 0 ? "" : v.substring(dot + 1);
        if (frac.length() > 2 || whole.isEmpty() || !whole.chars().allMatch(Character::isDigit)
                || !frac.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Not a USD amount: " + value);
        }
        long cents = Math.addExact(Math.multiplyExact(Long.parseLong(whole), 100),
                frac.isEmpty() ? 0 : Long.parseLong(frac.length() == 1 ? frac + "0" : frac));
        return negative ? -cents : cents;
    }
}
