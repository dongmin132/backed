package dev.backed.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores PayPal webhook events and processes each one at most once. Processing never posts to the
 * journal: it only notes whether the ledger already knows the capture or refund. Anything it does
 * not know is picked up by the next day close, which asks PayPal directly and raises a break.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    public enum Outcome { PROCESSED, DUPLICATE }

    /** What happened to one delivery. detail is a short human-readable finding. */
    public record Receipt(String eventId, String eventType, Outcome outcome, String detail) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public WebhookService(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /**
     * Records the event and processes it if this is the first delivery of its id.
     *
     * @throws IllegalArgumentException when the body is not a PayPal event with an id and an event_type
     */
    public Receipt receive(String rawBody) {
        JsonNode root;
        try {
            root = JsonMapper.shared().readTree(rawBody == null ? "" : rawBody);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Body is not JSON");
        }
        String eventId = Json.text(root, "id");
        String eventType = Json.text(root, "event_type");
        if (eventId == null || eventId.isBlank() || eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("Event needs an id and an event_type");
        }
        String resourceId = Json.text(root.path("resource"), "id");

        // The insert and the processing commit together. A concurrent delivery of the same id waits
        // on the primary key until this commits, then inserts nothing and reports a duplicate.
        Receipt receipt = tx.execute(status -> {
            int inserted = jdbc.sql("""
                            insert into webhook_events (event_id, event_type, resource_id, payload)
                            values (:id, :type, :resourceId, :payload)
                            on conflict (event_id) do nothing
                            """)
                    .param("id", eventId)
                    .param("type", eventType)
                    .param("resourceId", resourceId)
                    .param("payload", rawBody)
                    .update();
            if (inserted == 0) {
                return new Receipt(eventId, eventType, Outcome.DUPLICATE, "already received");
            }
            String detail = process(eventType, root, resourceId);
            jdbc.sql("update webhook_events set processed_at = now() where event_id = :id and processed_at is null")
                    .param("id", eventId)
                    .update();
            return new Receipt(eventId, eventType, Outcome.PROCESSED, detail);
        });
        log.info("PayPal webhook {} {}: {} ({})", eventType, eventId, receipt.outcome(), receipt.detail());
        return receipt;
    }

    private String process(String eventType, JsonNode root, String resourceId) {
        if (WebhookEvents.CAPTURE_COMPLETED.equals(eventType)) {
            boolean known = resourceId != null && jdbc.sql("""
                            select exists (select 1 from topups where paypal_capture_id = :id and state = 'CAPTURED')
                            """)
                    .param("id", resourceId)
                    .query(Boolean.class)
                    .single();
            return known
                    ? "capture " + resourceId + " is in the ledger"
                    : "capture " + resourceId + " is not in the ledger; the next close will check it";
        }
        if (WebhookEvents.CAPTURE_REFUNDED.equals(eventType)) {
            boolean known = resourceId != null && jdbc.sql("""
                            select exists (select 1 from cashout_legs where paypal_refund_id = :id and state = 'DONE')
                                or exists (select 1 from breaks where paypal_ref = :id and status = 'RESOLVED')
                            """)
                    .param("id", resourceId)
                    .query(Boolean.class)
                    .single();
            String capture = WebhookEvents.captureIdOf(eventType, root.toString());
            return known
                    ? "refund " + resourceId + " is in the ledger"
                    : "refund " + resourceId + (capture == null ? "" : " on capture " + capture)
                    + " is not in the ledger; the next close will check it";
        }
        return "event type not used";
    }
}
