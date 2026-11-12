package dev.backed.webhook;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Read side of webhook_events. */
@Repository
public class WebhookEvents {

    public static final String CAPTURE_COMPLETED = "PAYMENT.CAPTURE.COMPLETED";
    public static final String CAPTURE_REFUNDED = "PAYMENT.CAPTURE.REFUNDED";

    public record EventRow(String eventId, String eventType, String resourceId, OffsetDateTime receivedAt,
                           OffsetDateTime processedAt) {
    }

    private final JdbcClient jdbc;

    public WebhookEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<EventRow> recent(int limit) {
        return jdbc.sql("""
                        select event_id, event_type, resource_id, received_at, processed_at
                          from webhook_events order by received_at desc, event_id limit :limit
                        """)
                .param("limit", limit)
                .query(EventRow.class)
                .list();
    }

    public int count() {
        return jdbc.sql("select count(*) from webhook_events").query(Integer.class).single();
    }

    /**
     * Capture ids that capture and refund webhooks mention, so the close can look at captures that
     * no top-up points to (for example a payment taken in the PayPal dashboard).
     */
    public Set<String> referencedCaptureIds() {
        Set<String> ids = new LinkedHashSet<>();
        jdbc.sql("select event_type, payload from webhook_events where event_type in (:types) order by received_at")
                .param("types", List.of(CAPTURE_COMPLETED, CAPTURE_REFUNDED))
                .query((rs, n) -> captureIdOf(rs.getString("event_type"), rs.getString("payload")))
                .list()
                .forEach(id -> {
                    if (id != null && !id.isBlank()) {
                        ids.add(id);
                    }
                });
        return ids;
    }

    /** The capture an event is about: the resource itself for a capture, the "up" link for a refund. */
    static String captureIdOf(String eventType, String payload) {
        JsonNode resource;
        try {
            resource = JsonMapper.shared().readTree(payload).path("resource");
        } catch (RuntimeException e) {
            return null;
        }
        if (CAPTURE_COMPLETED.equals(eventType)) {
            return Json.text(resource, "id");
        }
        if (CAPTURE_REFUNDED.equals(eventType)) {
            for (JsonNode link : resource.path("links")) {
                if ("up".equals(Json.text(link, "rel"))) {
                    String href = Json.text(link, "href");
                    if (href != null && href.contains("/captures/")) {
                        return href.substring(href.lastIndexOf('/') + 1);
                    }
                }
            }
        }
        return null;
    }
}
