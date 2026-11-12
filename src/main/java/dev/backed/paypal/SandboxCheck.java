package dev.backed.paypal;

import dev.backed.ledger.Money;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.PrintStream;
import java.util.UUID;

/**
 * Checks the assumptions Backed makes about PayPal against the live sandbox, then exits:
 * <pre>
 *   ./gradlew bootRun --args='--spring.profiles.active=sandbox-check'
 * </pre>
 * Needs PAYPAL_CLIENT_ID and PAYPAL_CLIENT_SECRET (environment or .env). It charges the configured
 * sandbox test card $10.00 and refunds $5.00 of it. Prints PASS or FAIL per check with PayPal's raw
 * statuses; the exit code is the number of failed checks. Never prints credentials.
 */
@Component
@Profile("sandbox-check")
public class SandboxCheck implements CommandLineRunner {

    private static final long CAPTURE_CENTS = 1_000;
    private static final long REFUND_1_CENTS = 300;
    private static final long REFUND_2_CENTS = 200;

    private final PayPalGateway gateway;
    private final TestCards cards;
    private final ConfigurableApplicationContext context;
    private final PrintStream out = System.out;
    private int passed;
    private int failed;

    public SandboxCheck(PayPalGateway gateway, TestCards cards, ConfigurableApplicationContext context) {
        this.gateway = gateway;
        this.cards = cards;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        try {
            check();
        } catch (RuntimeException e) {
            fail("unexpected", e.toString());
        }
        out.println();
        out.println("SANDBOX CHECK SUMMARY: " + passed + " passed, " + failed + " failed");
        int code = failed;
        System.exit(SpringApplication.exit(context, () -> code));
    }

    private void check() {
        PayPalGateway paypal = gateway instanceof FaultInjectingPayPalGateway f ? f.delegate() : gateway;
        String run = UUID.randomUUID().toString().substring(0, 8);
        TestCard card = cards.get("visa");
        out.println();
        out.println("PayPal sandbox check, run " + run + ", card " + card + ", gateway "
                + paypal.getClass().getSimpleName());

        // (a) single-step card capture
        String captureRequestId = "sandbox-check-" + run + "-capture";
        String customId = "sandbox-check-" + run;
        CaptureResult capture;
        try {
            capture = paypal.captureCard(captureRequestId, CAPTURE_CENTS, customId, card);
        } catch (PayPalException e) {
            fail("(a) card capture completes without payer action", describe(e));
            out.println("Skipping (b) to (e): they need a capture.");
            failed += 4;
            return;
        }
        report(capture.completed(), "(a) card capture completes without payer action",
                "order " + capture.orderId() + ", capture " + capture.captureId() + ", capture status "
                        + capture.status() + ", gross " + money(capture.amountCents()) + ", PayPal fee "
                        + money(capture.feeCents()) + ", net " + money(capture.netCents()) + ", custom_id "
                        + capture.customId());

        // (b) same PayPal-Request-Id returns the same order and capture, and PayPal holds one capture
        try {
            CaptureResult again = paypal.captureCard(captureRequestId, CAPTURE_CENTS, customId, card);
            OrderSnapshot order = paypal.getOrder(capture.orderId());
            boolean same = capture.orderId().equals(again.orderId()) && capture.captureId().equals(again.captureId());
            report(same && order.captures().size() == 1,
                    "(b) re-sending the same PayPal-Request-Id returns the same order and capture",
                    "re-send gave order " + again.orderId() + ", capture " + again.captureId() + " (status "
                            + again.status() + "); GET order status " + order.status() + " with "
                            + order.captures().size() + " capture(s)");
        } catch (PayPalException e) {
            fail("(b) re-sending the same PayPal-Request-Id returns the same order and capture", describe(e));
        }

        // (c) two partial refunds on one capture
        RefundResult first = null;
        try {
            first = paypal.refundCapture("sandbox-check-" + run + "-refund-1", capture.captureId(), REFUND_1_CENTS,
                    "Sandbox check partial refund 1 " + run);
            RefundResult second = paypal.refundCapture("sandbox-check-" + run + "-refund-2", capture.captureId(),
                    REFUND_2_CENTS, "Sandbox check partial refund 2 " + run);
            report(first.completed() && second.completed() && !first.refundId().equals(second.refundId()),
                    "(c) two partial refunds on one capture succeed",
                    "refund " + first.refundId() + " status " + first.status() + " amount "
                            + money(first.amountCents()) + " fee " + money(first.feeCents()) + " net "
                            + money(first.netCents()) + "; refund " + second.refundId() + " status "
                            + second.status() + " amount " + money(second.amountCents()) + " fee "
                            + money(second.feeCents()) + " net " + money(second.netCents()));
        } catch (PayPalException e) {
            fail("(c) two partial refunds on one capture succeed", describe(e));
        }

        // (d) a refund shows up in GET order with its note
        try {
            OrderSnapshot order = paypal.getOrder(capture.orderId());
            RefundResult wanted = first;
            RefundSnapshot found = wanted == null ? null : order.refunds().stream()
                    .filter(r -> r.refundId().equals(wanted.refundId()))
                    .findFirst()
                    .orElse(null);
            StringBuilder seen = new StringBuilder("GET order lists " + order.refunds().size() + " refund(s)");
            for (RefundSnapshot r : order.refunds()) {
                seen.append("; ").append(r.refundId()).append(" status ").append(r.status()).append(" amount ")
                        .append(money(r.amountCents())).append(" capture ").append(r.captureId())
                        .append(" note \"").append(r.noteToPayer()).append('"');
            }
            report(found != null && found.noteToPayer() != null && found.noteToPayer().equals(wanted.noteToPayer()),
                    "(d) a refund shows up in GET order with its note", seen.toString());
        } catch (PayPalException e) {
            fail("(d) a refund shows up in GET order with its note", describe(e));
        }

        try {
            CaptureSnapshot snap = paypal.getCapture(capture.captureId());
            out.println("     GET capture " + snap.captureId() + ": status " + snap.status() + ", gross "
                    + money(snap.amountCents()) + ", PayPal fee " + money(snap.feeCents()) + ", net "
                    + money(snap.netCents()) + ", order " + snap.orderId());
        } catch (PayPalException e) {
            out.println("     GET capture failed: " + describe(e));
        }

        // (e) a refund larger than what is left is rejected with a 4xx
        long tooMuch = CAPTURE_CENTS - REFUND_1_CENTS - REFUND_2_CENTS + 100;
        try {
            RefundResult r = paypal.refundCapture("sandbox-check-" + run + "-refund-too-much", capture.captureId(),
                    tooMuch, "Sandbox check refund above the refundable amount " + run);
            fail("(e) a refund above the refundable amount is rejected with a 4xx",
                    "PayPal accepted it: refund " + r.refundId() + " status " + r.status());
        } catch (PayPalDeclinedException e) {
            report(e.httpStatus() >= 400 && e.httpStatus() < 500,
                    "(e) a refund above the refundable amount is rejected with a 4xx",
                    "asked " + money(tooMuch) + "; " + describe(e));
        } catch (PayPalException e) {
            fail("(e) a refund above the refundable amount is rejected with a 4xx", describe(e));
        }
    }

    private void report(boolean ok, String what, String detail) {
        if (ok) {
            passed++;
            out.println("PASS " + what);
        } else {
            failed++;
            out.println("FAIL " + what);
        }
        out.println("     " + detail);
    }

    private void fail(String what, String detail) {
        report(false, what, detail);
    }

    private static String describe(PayPalException e) {
        return e.getClass().getSimpleName() + " HTTP " + e.httpStatus() + " issue " + e.issue() + " debug id "
                + e.debugId() + ": " + e.getMessage();
    }

    private static String money(Long cents) {
        return cents == null ? "(not reported)" : Money.format(cents);
    }
}
