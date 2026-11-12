package dev.backed.investigate;

import dev.backed.close.BreakStatus;
import dev.backed.ledger.Account;
import dev.backed.ledger.JournalLineDraft;
import dev.backed.ledger.JournalService;
import dev.backed.ledger.Money;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a drafted entry against PayPal's record and the rules in SPEC.md. Expected amounts are
 * recomputed here from the PayPal facts, independently of {@link DraftBuilder}.
 */
public final class DraftValidator {

    public static final String BREAK_OPEN = "Break is still open";
    public static final String DRAFTED = "An entry can be drafted for this treatment";
    public static final String BALANCES = "Entry balances";
    public static final String AMOUNTS = "Amounts equal PayPal's";
    public static final String ACCOUNTS = "Only accounts allowed for the treatment";
    public static final String QUOTES = "Evidence quotes are verbatim";
    public static final String NOT_NEGATIVE = "Customer balance stays at or above zero";
    public static final String NOTE_AMOUNTS = "Customer note uses only amounts from the evidence";

    private static final int MIN_QUOTE_LENGTH = 4;
    private static final Pattern DOLLARS = Pattern.compile("\\$\\s?\\d[\\d,]*(?:\\.\\d{1,2})?");

    public enum Stage {
        /** Creating a proposal: the break must be OPEN. */
        PROPOSE,
        /** Approving: the break may be OPEN or PROPOSED. */
        APPROVE
    }

    private DraftValidator() {
    }

    /** The full checklist for the treatment the model chose. */
    public static List<ValidationCheck> validate(DraftContext ctx, InvestigatorOutput output, DraftPlan plan,
                                                 String bundleJson, Stage stage) {
        List<ValidationCheck> checks = new ArrayList<>(ledgerChecks(ctx, plan, stage));
        checks.add(quotes(output.evidenceQuotes(), bundleJson));
        checks.add(noteAmounts(output.customerNote(), bundleJson));
        return checks;
    }

    /** Checks that do not depend on the model's words; used for the rejected alternative too. */
    public static List<ValidationCheck> ledgerChecks(DraftContext ctx, DraftPlan plan, Stage stage) {
        List<ValidationCheck> checks = new ArrayList<>();
        checks.add(breakOpen(ctx, stage));
        checks.add(plan.drafted()
                ? new ValidationCheck(DRAFTED, true, plan.treatment().label())
                : new ValidationCheck(DRAFTED, false, String.join(" ", plan.problems())));
        if (!plan.drafted()) {
            return checks;
        }
        List<String> problems = JournalService.validate(plan.toJournalDraft("validator"));
        checks.add(new ValidationCheck(BALANCES, problems.isEmpty(),
                problems.isEmpty() ? "Debits " + Money.format(plan.totalDebits()) + " = credits" : String.join("; ", problems)));
        checks.add(amounts(ctx, plan));
        checks.add(accounts(ctx, plan));
        long after = ctx.customerBalanceCents() + plan.customerBalanceEffectCents();
        boolean notNegative = plan.customerBalanceEffectCents() >= 0 || after >= 0;
        checks.add(new ValidationCheck(NOT_NEGATIVE, notNegative, notNegative
                ? (plan.customerBalanceEffectCents() == 0 ? "Balance unchanged"
                : "Balance " + Money.format(ctx.customerBalanceCents()) + " becomes " + Money.format(after))
                : "Returning " + Money.format(-plan.customerBalanceEffectCents()) + " would take "
                + ctx.customerName() + "'s balance below zero: the balance is only "
                + Money.format(ctx.customerBalanceCents()) + "."));
        return checks;
    }

    public static boolean allOk(List<ValidationCheck> checks) {
        return checks.stream().allMatch(ValidationCheck::ok);
    }

    public static List<String> failures(List<ValidationCheck> checks) {
        return checks.stream().filter(c -> !c.ok()).map(c -> c.name() + ": " + c.detail()).toList();
    }

    private static ValidationCheck breakOpen(DraftContext ctx, Stage stage) {
        BreakStatus s = ctx.brk().status();
        boolean ok = stage == Stage.PROPOSE ? s == BreakStatus.OPEN : s == BreakStatus.OPEN || s == BreakStatus.PROPOSED;
        return new ValidationCheck(BREAK_OPEN, ok, "Break #" + ctx.brk().id() + " is " + s);
    }

    private static ValidationCheck amounts(DraftContext ctx, DraftPlan plan) {
        PayPalMovement m = ctx.movement();
        List<String> wrong = new ArrayList<>();
        if (ctx.brk().amountCents() != m.grossCents()) {
            wrong.add("the break records " + Money.format(ctx.brk().amountCents()) + " but PayPal's "
                    + (ctx.isRefund() ? "refund " : "capture ") + m.id() + " is " + Money.format(m.grossCents()));
        }
        if (plan.totalDebits() != m.grossCents()) {
            wrong.add("the entry moves " + Money.format(plan.totalDebits()) + " but PayPal moved "
                    + Money.format(m.grossCents()));
        }
        long expectedCash = expectedCashEffect(ctx, plan.treatment());
        if (plan.paypalCashEffectCents() != expectedCash) {
            wrong.add("PAYPAL_CASH changes by " + Money.format(plan.paypalCashEffectCents())
                    + " but PayPal's net movement is " + Money.format(expectedCash));
        }
        return new ValidationCheck(AMOUNTS, wrong.isEmpty(), wrong.isEmpty()
                ? "Gross " + Money.format(m.grossCents()) + ", PAYPAL_CASH " + Money.format(expectedCash)
                : String.join("; ", wrong));
    }

    private static long expectedCashEffect(DraftContext ctx, Treatment t) {
        PayPalMovement m = ctx.movement();
        if (ctx.isRefund()) {
            long out = -m.net();
            return t == Treatment.DUPLICATE_REVERSAL && ctx.refundedCapture() != null
                    ? ctx.refundedCapture().net() + out : out;
        }
        long in = m.net();
        return t == Treatment.DUPLICATE_REVERSAL && ctx.captureRefund() != null ? in - ctx.captureRefund().net() : in;
    }

    private static ValidationCheck accounts(DraftContext ctx, DraftPlan plan) {
        Set<Account> allowed = switch (plan.treatment()) {
            case BALANCE_RETURNED -> EnumSet.of(Account.CUSTOMER_PREPAID, Account.PAYPAL_CASH, Account.PAYPAL_FEES);
            case GOODWILL -> EnumSet.of(Account.GOODWILL_EXPENSE, Account.PAYPAL_CASH, Account.PAYPAL_FEES);
            case DUPLICATE_REVERSAL -> EnumSet.of(Account.PAYPAL_CASH, Account.PAYPAL_FEES);
            case NEEDS_HUMAN -> EnumSet.noneOf(Account.class);
        };
        List<String> wrong = new ArrayList<>();
        for (JournalLineDraft l : plan.lines()) {
            if (!allowed.contains(l.account())) {
                wrong.add(l.account() + " is not allowed for " + plan.treatment().label().toLowerCase());
            }
            if (l.account() == Account.CUSTOMER_PREPAID && !l.customerId().equals(ctx.customerId())) {
                wrong.add("a CUSTOMER_PREPAID line names customer " + l.customerId() + ", not the break's customer");
            }
        }
        return new ValidationCheck(ACCOUNTS, wrong.isEmpty(),
                wrong.isEmpty() ? String.join(", ", allowed.stream().map(Account::code).toList()) : String.join("; ", wrong));
    }

    static ValidationCheck quotes(List<String> quotes, String bundleJson) {
        if (quotes.isEmpty()) {
            return new ValidationCheck(QUOTES, false, "The investigator cited no evidence.");
        }
        List<String> strings = stringValues(bundleJson);
        List<String> missing = new ArrayList<>();
        for (String q : quotes) {
            if (!quoteFound(q, bundleJson, strings)) {
                missing.add("\"" + q + "\"");
            }
        }
        return new ValidationCheck(QUOTES, missing.isEmpty(), missing.isEmpty()
                ? quotes.size() + " of " + quotes.size() + " found in the evidence"
                : "Not found verbatim in the evidence: " + String.join(", ", missing));
    }

    static boolean quoteFound(String quote, String bundleJson, List<String> strings) {
        if (quote == null || quote.strip().length() < MIN_QUOTE_LENGTH) {
            return false;
        }
        if (bundleJson.contains(quote)) {
            return true;
        }
        return strings.stream().anyMatch(s -> s.contains(quote));
    }

    private static ValidationCheck noteAmounts(String note, String bundleJson) {
        if (note == null || note.isBlank()) {
            return new ValidationCheck(NOTE_AMOUNTS, true, "No customer note");
        }
        List<String> invented = new ArrayList<>();
        Matcher m = DOLLARS.matcher(note);
        while (m.find()) {
            String amount = m.group().replace(" ", "");
            String normalised = normaliseDollars(amount);
            if (!bundleJson.contains(amount) && (normalised == null || !bundleJson.contains(normalised))) {
                invented.add(amount);
            }
        }
        return new ValidationCheck(NOTE_AMOUNTS, invented.isEmpty(),
                invented.isEmpty() ? "No invented amounts" : "Amounts not in the evidence: " + String.join(", ", invented));
    }

    /** "$5" or "$5.0" to "$5.00", so a note may drop trailing zeros. */
    private static String normaliseDollars(String text) {
        try {
            return Money.format(Money.parseCents(text));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Every string value in the bundle, so a quote may match text that JSON escaped. */
    static List<String> stringValues(String json) {
        List<String> out = new ArrayList<>();
        Json.tryParse(json).ifPresent(root -> collect(root, out));
        return out;
    }

    private static void collect(JsonNode node, List<String> out) {
        if (node.isString()) {
            out.add(node.asString());
        } else if (node.isContainer()) {
            for (JsonNode child : node) {
                collect(child, out);
            }
        }
    }
}
