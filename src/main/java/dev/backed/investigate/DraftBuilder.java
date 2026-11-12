package dev.backed.investigate;

import dev.backed.close.BreakKind;
import dev.backed.close.BreakStatus;
import dev.backed.close.NewBreak;
import dev.backed.ledger.Account;
import dev.backed.ledger.JournalLineDraft;
import dev.backed.ledger.Money;

import java.util.ArrayList;
import java.util.List;

/**
 * Derives the correcting journal lines from a treatment and PayPal's amounts (SPEC.md, Postings).
 * The model never supplies an amount: every number here comes from the PayPal record.
 *
 * <p>A refund R reduces PAYPAL_CASH by its net amount; any fee PayPal hands back is credited to
 * PAYPAL_FEES. A capture C adds its net amount to PAYPAL_CASH and its fee to PAYPAL_FEES.
 */
public final class DraftBuilder {

    private DraftBuilder() {
    }

    public static DraftPlan build(DraftContext ctx, Treatment treatment) {
        long breakId = ctx.brk().id();
        List<String> problems = new ArrayList<>();
        if (treatment == null || treatment == Treatment.NEEDS_HUMAN) {
            problems.add("The investigator asked for a human decision; there is no entry to draft.");
            return DraftPlan.of(treatment, breakId, List.of(), null, problems, null);
        }
        PayPalMovement m = ctx.movement();
        if (m == null) {
            problems.add("The break's evidence does not contain a readable PayPal "
                    + (ctx.isRefund() ? "refund" : "capture") + " with an amount.");
            return DraftPlan.of(treatment, breakId, List.of(), null, problems, null);
        }
        return ctx.isRefund() ? forRefund(ctx, treatment, m, problems) : forCapture(ctx, treatment, m, problems);
    }

    private static DraftPlan forRefund(DraftContext ctx, Treatment t, PayPalMovement refund, List<String> problems) {
        long breakId = ctx.brk().id();
        checkBreakdown(refund, "refund", problems);
        List<JournalLineDraft> lines = new ArrayList<>();
        NewBreak alsoResolves = null;
        String memo;
        switch (t) {
            case BALANCE_RETURNED -> {
                if (ctx.customerId() == null) {
                    problems.add("No customer is linked to this refund, so no balance can be reduced.");
                    break;
                }
                lines.add(JournalLineDraft.debit(Account.CUSTOMER_PREPAID, ctx.customerId(), refund.grossCents()));
                refundLines(refund, lines);
            }
            case GOODWILL -> {
                lines.add(JournalLineDraft.debit(Account.GOODWILL_EXPENSE, refund.grossCents()));
                refundLines(refund, lines);
            }
            case DUPLICATE_REVERSAL -> {
                PayPalMovement capture = ctx.refundedCapture();
                if (capture == null) {
                    problems.add("PayPal's record of the refunded capture " + refund.captureId() + " is not available.");
                    break;
                }
                if (ctx.refundedCaptureCredited()) {
                    problems.add("The refunded capture " + capture.id() + " was credited by the ledger, so this refund "
                            + "is not the reversal of an uncredited duplicate.");
                    break;
                }
                if (ctx.refundedCaptureBreak() != null && ctx.refundedCaptureBreak().status() == BreakStatus.RESOLVED) {
                    problems.add("The capture " + capture.id() + " is already booked by resolved break #"
                            + ctx.refundedCaptureBreak().id() + ".");
                    break;
                }
                if (capture.grossCents() != refund.grossCents()) {
                    problems.add("The refund (" + Money.format(refund.grossCents()) + ") does not reverse the whole "
                            + "capture (" + Money.format(capture.grossCents()) + ").");
                    break;
                }
                checkBreakdown(capture, "capture", problems);
                captureLines(capture, lines);
                refundLines(refund, lines);
                alsoResolves = new NewBreak(null, BreakKind.UNBOOKED_CAPTURE, ctx.customerId(), null, capture.id(),
                        capture.grossCents(), Json.write(capture.toJson()));
            }
            default -> problems.add("Treatment " + t + " does not apply to a refund.");
        }
        memo = "Break #" + breakId + ": off-book refund " + refund.id() + " booked as " + t.label().toLowerCase();
        return DraftPlan.of(t, breakId, problems.isEmpty() ? lines : List.of(), memo, problems, alsoResolves);
    }

    private static DraftPlan forCapture(DraftContext ctx, Treatment t, PayPalMovement capture, List<String> problems) {
        long breakId = ctx.brk().id();
        checkBreakdown(capture, "capture", problems);
        List<JournalLineDraft> lines = new ArrayList<>();
        NewBreak alsoResolves = null;
        if (ctx.captureCredited()) {
            problems.add("The ledger already credited capture " + capture.id() + " through a top-up.");
        }
        switch (t) {
            case BALANCE_RETURNED -> {
                if (ctx.customerId() == null) {
                    problems.add("No customer is linked to this capture, so there is no balance to credit.");
                    break;
                }
                captureLines(capture, lines);
                lines.add(JournalLineDraft.credit(Account.CUSTOMER_PREPAID, ctx.customerId(), capture.grossCents()));
            }
            case DUPLICATE_REVERSAL -> {
                PayPalMovement refund = ctx.captureRefund();
                if (refund == null) {
                    problems.add("No refund of capture " + capture.id() + " is on record. Refund the duplicate at "
                            + "PayPal first, then investigate the refund break.");
                    break;
                }
                if (refund.grossCents() != capture.grossCents()) {
                    problems.add("The refund (" + Money.format(refund.grossCents()) + ") does not reverse the whole "
                            + "capture (" + Money.format(capture.grossCents()) + ").");
                    break;
                }
                checkBreakdown(refund, "refund", problems);
                captureLines(capture, lines);
                refundLines(refund, lines);
                alsoResolves = new NewBreak(null, BreakKind.UNBOOKED_REFUND, ctx.customerId(), null, refund.id(),
                        refund.grossCents(), ctx.captureRefundBreak().evidenceJson());
            }
            default -> problems.add("Treatment " + t + " does not apply to a capture.");
        }
        String memo = "Break #" + breakId + ": unbooked capture " + capture.id() + " booked as " + t.label().toLowerCase();
        return DraftPlan.of(t, breakId, problems.isEmpty() ? lines : List.of(), memo, problems, alsoResolves);
    }

    /** Cr PAYPAL_CASH net, Cr PAYPAL_FEES for any fee PayPal returned. */
    private static void refundLines(PayPalMovement refund, List<JournalLineDraft> lines) {
        lines.add(JournalLineDraft.credit(Account.PAYPAL_CASH, refund.net()));
        if (refund.fee() > 0) {
            lines.add(JournalLineDraft.credit(Account.PAYPAL_FEES, refund.fee()));
        }
    }

    /** Dr PAYPAL_CASH net, Dr PAYPAL_FEES fee. */
    private static void captureLines(PayPalMovement capture, List<JournalLineDraft> lines) {
        lines.add(JournalLineDraft.debit(Account.PAYPAL_CASH, capture.net()));
        if (capture.fee() > 0) {
            lines.add(JournalLineDraft.debit(Account.PAYPAL_FEES, capture.fee()));
        }
    }

    private static void checkBreakdown(PayPalMovement m, String what, List<String> problems) {
        if (m.grossCents() <= 0) {
            problems.add("PayPal's " + what + " amount is not positive.");
        } else if (m.fee() < 0 || m.net() <= 0 || m.net() + m.fee() != m.grossCents()) {
            problems.add("PayPal's " + what + " breakdown does not add up: gross " + Money.format(m.grossCents())
                    + ", fee " + Money.format(m.fee()) + ", net " + Money.format(m.net()) + ".");
        }
    }
}
