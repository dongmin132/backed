package dev.backed.topup;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL for the topups table. State changes are guarded by the expected current state. */
@Repository
public class Topups {

    private static final String SELECT = """
            select t.*, c.name as customer_name
              from topups t join customers c on c.id = t.customer_id
            """;

    private final JdbcClient jdbc;

    public Topups(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a CREATED row. Fails with a duplicate key when the customer already has an unresolved top-up. */
    void insertCreated(UUID id, long customerId, long amountCents, String requestId, LocalDate businessDate,
                       String createdBy) {
        jdbc.sql("""
                        insert into topups (id, customer_id, amount_cents, state, request_id, business_date, created_by)
                        values (:id, :customerId, :amount, 'CREATED', :requestId, :date, :createdBy)
                        """)
                .param("id", id)
                .param("customerId", customerId)
                .param("amount", amountCents)
                .param("requestId", requestId)
                .param("date", businessDate)
                .param("createdBy", createdBy)
                .update();
    }

    public Optional<Topup> findById(UUID id) {
        return jdbc.sql(SELECT + " where t.id = :id").param("id", id).query(Topups::map).optional();
    }

    /** Locks the row until the surrounding transaction ends. */
    Optional<Topup> lockById(UUID id) {
        return jdbc.sql(SELECT + " where t.id = :id for update of t").param("id", id).query(Topups::map).optional();
    }

    public Optional<Topup> findUnresolvedForCustomer(long customerId) {
        return jdbc.sql(SELECT + " where t.customer_id = :id and t.state in ('CREATED', 'IN_DOUBT')")
                .param("id", customerId)
                .query(Topups::map)
                .optional();
    }

    public List<Topup> recent(int limit) {
        return jdbc.sql(SELECT + " order by t.created_at desc, t.id limit :limit")
                .param("limit", limit)
                .query(Topups::map)
                .list();
    }

    public List<Topup> captured(int limit) {
        return jdbc.sql(SELECT + " where t.state = 'CAPTURED' order by t.created_at desc, t.id limit :limit")
                .param("limit", limit)
                .query(Topups::map)
                .list();
    }

    /** IN_DOUBT top-ups, plus CREATED ones untouched for longer than staleSeconds (the app stopped mid-call). */
    public List<UUID> idsToResolve(long staleSeconds) {
        return jdbc.sql("""
                        select id from topups
                         where state = 'IN_DOUBT'
                            or (state = 'CREATED' and updated_at < now() - :stale * interval '1 second')
                         order by created_at
                        """)
                .param("stale", staleSeconds)
                .query((rs, n) -> rs.getObject("id", UUID.class))
                .list();
    }

    boolean markCaptured(UUID id, TopupState from, String orderId, String captureId, long feeCents, long netCents) {
        return jdbc.sql("""
                        update topups
                           set state = 'CAPTURED', paypal_order_id = :orderId, paypal_capture_id = :captureId,
                               fee_cents = :fee, net_cents = :net, failure_reason = null, updated_at = now()
                         where id = :id and state = :from
                        """)
                .param("id", id)
                .param("from", from.name())
                .param("orderId", orderId)
                .param("captureId", captureId)
                .param("fee", feeCents)
                .param("net", netCents)
                .update() == 1;
    }

    boolean markState(UUID id, TopupState from, TopupState to, String reason) {
        return jdbc.sql("""
                        update topups set state = :to, failure_reason = :reason, updated_at = now()
                         where id = :id and state = :from
                        """)
                .param("id", id)
                .param("from", from.name())
                .param("to", to.name())
                .param("reason", reason)
                .update() == 1;
    }

    /** Records what PayPal told us about a capture that is not final yet (for example PENDING). */
    void recordPaypalIds(UUID id, String orderId, String captureId) {
        jdbc.sql("""
                        update topups set paypal_order_id = coalesce(:orderId, paypal_order_id),
                                          paypal_capture_id = coalesce(:captureId, paypal_capture_id), updated_at = now()
                         where id = :id
                        """)
                .param("id", id)
                .param("orderId", orderId)
                .param("captureId", captureId)
                .update();
    }

    void touch(UUID id, String reason) {
        jdbc.sql("update topups set failure_reason = :reason, updated_at = now() where id = :id")
                .param("id", id)
                .param("reason", reason)
                .update();
    }

    private static Topup map(ResultSet rs, int row) throws SQLException {
        return new Topup(
                rs.getObject("id", UUID.class),
                rs.getLong("customer_id"),
                rs.getString("customer_name"),
                rs.getLong("amount_cents"),
                TopupState.valueOf(rs.getString("state")),
                rs.getString("request_id"),
                rs.getString("paypal_order_id"),
                rs.getString("paypal_capture_id"),
                (Long) rs.getObject("fee_cents", Long.class),
                (Long) rs.getObject("net_cents", Long.class),
                rs.getString("failure_reason"),
                rs.getObject("business_date", LocalDate.class),
                rs.getString("created_by"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
