package dev.backed.investigate;

import dev.backed.ledger.Money;
import dev.backed.paypal.CaptureSnapshot;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;

/**
 * A PayPal refund or capture as read from a break's evidence or from PayPal.
 *
 * @param id          the refund id or capture id
 * @param captureId   for a refund, the capture it refunds; for a capture, its own id
 * @param grossCents  the amount PayPal moved
 * @param feeCents    PayPal's fee from the breakdown, null when PayPal did not report one
 * @param netCents    the net amount from the breakdown, null when PayPal did not report one
 * @param noteToPayer the note on a refund
 */
public record PayPalMovement(
        String id,
        String captureId,
        String orderId,
        String status,
        long grossCents,
        Long feeCents,
        Long netCents,
        String noteToPayer,
        String createTime,
        String customId) {

    /** Fee, zero when PayPal did not report one. */
    public long fee() {
        return feeCents == null ? 0 : feeCents;
    }

    /** Net amount: what PayPal reported, otherwise gross minus fee. */
    public long net() {
        return netCents != null ? netCents : grossCents - fee();
    }

    public static PayPalMovement of(CaptureSnapshot c) {
        return new PayPalMovement(c.captureId(), c.captureId(), c.orderId(), c.status(), c.amountCents(),
                c.feeCents(), c.netCents(), null,
                c.createTime() == null ? null : c.createTime().toString(), c.customId());
    }

    ObjectNode toJson() {
        ObjectNode n = Json.object();
        n.put("id", id);
        if (captureId != null && !captureId.equals(id)) {
            n.put("captureId", captureId);
        }
        if (orderId != null) {
            n.put("orderId", orderId);
        }
        if (status != null) {
            n.put("status", status);
        }
        n.put("amount", Money.format(grossCents));
        n.put("amountCents", grossCents);
        if (feeCents != null) {
            n.put("fee", Money.format(feeCents));
        }
        if (netCents != null) {
            n.put("net", Money.format(netCents));
        }
        if (noteToPayer != null) {
            n.put("noteToPayer", noteToPayer);
        }
        if (createTime != null) {
            n.put("createTime", createTime);
        }
        if (customId != null) {
            n.put("customId", customId);
        }
        return n;
    }

    /**
     * Reads a refund from evidence. Accepts the RefundSnapshot record as JSON, the same nested under
     * "refund", or PayPal's own shape (amount.value, seller_payable_breakdown).
     */
    static Optional<PayPalMovement> refundFrom(String evidenceJson) {
        return Json.tryParse(evidenceJson).flatMap(root -> {
            JsonNode n = root.has("refund") && root.get("refund").isObject() ? root.get("refund") : root;
            String id = text(n, "refundId", "refund_id", "id");
            String captureId = text(n, "captureId", "capture_id");
            Long gross = cents(n, "amountCents", "amount_cents", "grossCents");
            if (id == null || gross == null) {
                return Optional.empty();
            }
            JsonNode breakdown = n.path("seller_payable_breakdown");
            Long fee = firstNonNull(cents(n, "feeCents", "fee_cents"), decimal(breakdown.path("paypal_fee")));
            Long net = firstNonNull(cents(n, "netCents", "net_cents"), decimal(breakdown.path("net_amount")));
            return Optional.of(new PayPalMovement(id, captureId, text(n, "orderId", "order_id"),
                    text(n, "status"), gross, fee, net, text(n, "noteToPayer", "note_to_payer"),
                    text(n, "createTime", "create_time"), text(n, "customId", "custom_id")));
        });
    }

    /** Reads a capture from evidence: CaptureSnapshot JSON, nested under "capture", or PayPal's shape. */
    static Optional<PayPalMovement> captureFrom(String evidenceJson) {
        return Json.tryParse(evidenceJson).flatMap(PayPalMovement::captureFrom);
    }

    static Optional<PayPalMovement> captureFrom(JsonNode root) {
        JsonNode n = root.has("capture") && root.get("capture").isObject() ? root.get("capture") : root;
        String id = text(n, "captureId", "capture_id", "id");
        Long gross = cents(n, "amountCents", "amount_cents", "grossCents");
        if (id == null || gross == null) {
            return Optional.empty();
        }
        JsonNode breakdown = n.path("seller_receivable_breakdown");
        Long fee = firstNonNull(cents(n, "feeCents", "fee_cents"), decimal(breakdown.path("paypal_fee")));
        Long net = firstNonNull(cents(n, "netCents", "net_cents"), decimal(breakdown.path("net_amount")));
        return Optional.of(new PayPalMovement(id, id, text(n, "orderId", "order_id"), text(n, "status"),
                gross, fee, net, null, text(n, "createTime", "create_time"), text(n, "customId", "custom_id")));
    }

    /** The capture embedded in a refund's evidence under "capture", if the close put one there. */
    static Optional<PayPalMovement> embeddedCapture(String evidenceJson) {
        return Json.tryParse(evidenceJson)
                .filter(root -> root.has("capture") && root.get("capture").isObject())
                .flatMap(root -> captureFrom(root.get("capture")));
    }

    private static String text(JsonNode n, String... names) {
        for (String name : names) {
            JsonNode v = n.get(name);
            if (v != null && !v.isNull() && !v.isObject() && !v.isArray()) {
                String s = v.asString();
                if (!s.isBlank()) {
                    return s;
                }
            }
        }
        return null;
    }

    private static Long cents(JsonNode n, String... names) {
        for (String name : names) {
            JsonNode v = n.get(name);
            if (v != null && v.isIntegralNumber()) {
                return v.asLong();
            }
        }
        JsonNode amount = n.get("amount");
        if (amount != null) {
            return decimal(amount);
        }
        return null;
    }

    /** PayPal money object {"currency_code":"USD","value":"10.00"} or a bare decimal string. */
    private static Long decimal(JsonNode money) {
        if (money == null || money.isMissingNode() || money.isNull()) {
            return null;
        }
        JsonNode value = money.isObject() ? money.get("value") : money;
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return Money.fromDecimalString(value.asString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Long firstNonNull(Long a, Long b) {
        return a != null ? a : b;
    }
}
