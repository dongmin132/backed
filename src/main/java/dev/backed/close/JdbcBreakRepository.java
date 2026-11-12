package dev.backed.close;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcBreakRepository implements BreakRepository {

    private final JdbcClient jdbc;

    public JdbcBreakRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Break insertIfAbsent(NewBreak b) {
        jdbc.sql("""
                        insert into breaks (close_id, kind, customer_id, topup_id, paypal_ref, amount_cents, evidence, status)
                        values (:closeId, :kind, :customerId, :topupId, :paypalRef, :amount, :evidence, 'OPEN')
                        on conflict (kind, paypal_ref) do nothing
                        """)
                .param("closeId", b.closeId())
                .param("kind", b.kind().name())
                .param("customerId", b.customerId())
                .param("topupId", b.topupId())
                .param("paypalRef", b.paypalRef())
                .param("amount", b.amountCents())
                .param("evidence", b.evidenceJson())
                .update();
        return findByRef(b.kind(), b.paypalRef()).orElseThrow();
    }

    @Override
    public Optional<Break> findById(long id) {
        return jdbc.sql("select * from breaks where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<Break> findByRef(BreakKind kind, String paypalRef) {
        return jdbc.sql("select * from breaks where kind = :kind and paypal_ref = :ref")
                .param("kind", kind.name())
                .param("ref", paypalRef)
                .query(this::map)
                .optional();
    }

    @Override
    public List<Break> findOpen() {
        return jdbc.sql("select * from breaks where status in ('OPEN', 'PROPOSED') order by id")
                .query(this::map)
                .list();
    }

    @Override
    public List<Break> findOpenForCustomer(long customerId) {
        return jdbc.sql("select * from breaks where status in ('OPEN', 'PROPOSED') and customer_id = :id order by id")
                .param("id", customerId)
                .query(this::map)
                .list();
    }

    @Override
    public List<Break> findRecent(int limit) {
        return jdbc.sql("select * from breaks order by id desc limit :limit")
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    @Override
    public boolean markProposed(long id) {
        return transition(id, "OPEN", "PROPOSED");
    }

    @Override
    public boolean markOpen(long id) {
        return transition(id, "PROPOSED", "OPEN");
    }

    @Override
    public boolean markResolved(long id, long entryId) {
        return jdbc.sql("""
                        update breaks set status = 'RESOLVED', resolved_entry_id = :entryId, updated_at = now()
                         where id = :id and status in ('OPEN', 'PROPOSED')
                        """)
                .param("id", id)
                .param("entryId", entryId)
                .update() == 1;
    }

    private boolean transition(long id, String from, String to) {
        return jdbc.sql("update breaks set status = :to, updated_at = now() where id = :id and status = :from")
                .param("id", id)
                .param("from", from)
                .param("to", to)
                .update() == 1;
    }

    private Break map(ResultSet rs, int rowNum) throws SQLException {
        return new Break(
                rs.getLong("id"),
                rs.getObject("close_id", Long.class),
                BreakKind.valueOf(rs.getString("kind")),
                rs.getObject("customer_id", Long.class),
                rs.getObject("topup_id", UUID.class),
                rs.getString("paypal_ref"),
                rs.getLong("amount_cents"),
                rs.getString("evidence"),
                BreakStatus.valueOf(rs.getString("status")),
                rs.getObject("resolved_entry_id", Long.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
