package dev.backed.investigate;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ProposalRepository {

    /** A proposal with its break and customer, for the list page. */
    public record Summary(
            long id,
            long breakId,
            String breakKind,
            String paypalRef,
            long amountCents,
            String customerName,
            Treatment treatment,
            ProposalStatus status,
            String createdBy,
            String decidedBy,
            Long postedEntryId,
            OffsetDateTime createdAt) {
    }

    private final JdbcClient jdbc;

    public ProposalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long breakId, Treatment treatment, String aiOutput, String draft, boolean validationOk,
                       String validationErrors, ProposalStatus status, String createdBy, String evidenceBundle,
                       String alternativeDraft, String validationChecks) {
        return jdbc.sql("""
                        insert into proposals (break_id, treatment, ai_output, draft, validation_ok, validation_errors,
                                               status, created_by, evidence_bundle, alternative_draft, validation_checks)
                        values (:breakId, :treatment, :aiOutput, :draft, :ok, :errors,
                                :status, :createdBy, :bundle, :alternative, :checks)
                        returning id
                        """)
                .param("breakId", breakId)
                .param("treatment", treatment == null ? null : treatment.name())
                .param("aiOutput", aiOutput)
                .param("draft", draft)
                .param("ok", validationOk)
                .param("errors", validationErrors)
                .param("status", status.name())
                .param("createdBy", createdBy)
                .param("bundle", evidenceBundle)
                .param("alternative", alternativeDraft)
                .param("checks", validationChecks)
                .query(Long.class)
                .single();
    }

    /** The id of the proposal waiting for approval on each of the given breaks. */
    public Map<Long, Long> pendingByBreak() {
        Map<Long, Long> out = new HashMap<>();
        jdbc.sql("select break_id, max(id) as id from proposals where status = 'PENDING_APPROVAL' group by break_id")
                .query((ResultSet rs) -> {
                    out.put(rs.getLong("break_id"), rs.getLong("id"));
                });
        return out;
    }

    public Optional<Proposal> findById(long id) {
        return jdbc.sql("select * from proposals where id = :id").param("id", id).query(this::map).optional();
    }

    /** Locks the row until the transaction ends; concurrent approvals of one proposal queue here. */
    public Optional<Proposal> lock(long id) {
        return jdbc.sql("select * from proposals where id = :id for update").param("id", id).query(this::map).optional();
    }

    public List<Summary> recent(int limit) {
        return jdbc.sql("""
                        select p.id, p.break_id, b.kind, b.paypal_ref, b.amount_cents, c.name as customer_name,
                               p.treatment, p.status, p.created_by, p.decided_by, p.posted_entry_id, p.created_at
                          from proposals p
                          join breaks b on b.id = p.break_id
                          left join customers c on c.id = b.customer_id
                         order by p.id desc
                         limit :limit
                        """)
                .param("limit", limit)
                .query((rs, n) -> new Summary(
                        rs.getLong("id"),
                        rs.getLong("break_id"),
                        rs.getString("kind"),
                        rs.getString("paypal_ref"),
                        rs.getLong("amount_cents"),
                        rs.getString("customer_name"),
                        Treatment.parse(rs.getString("treatment")),
                        ProposalStatus.valueOf(rs.getString("status")),
                        rs.getString("created_by"),
                        rs.getString("decided_by"),
                        rs.getObject("posted_entry_id", Long.class),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    public boolean markApproved(long id, String checker, long entryId) {
        return jdbc.sql("""
                        update proposals
                           set status = 'APPROVED', approved_by = :checker, decided_by = :checker,
                               posted_entry_id = :entryId, decided_at = now(), updated_at = now()
                         where id = :id and status = 'PENDING_APPROVAL'
                        """)
                .param("id", id)
                .param("checker", checker)
                .param("entryId", entryId)
                .update() == 1;
    }

    public boolean markDismissed(long id, String actor) {
        return jdbc.sql("""
                        update proposals
                           set status = 'DISMISSED', decided_by = :actor, decided_at = now(), updated_at = now()
                         where id = :id and status in ('PENDING_APPROVAL', 'REJECTED_BY_VALIDATOR', 'DRAFT')
                        """)
                .param("id", id)
                .param("actor", actor)
                .update() == 1;
    }

    private Proposal map(ResultSet rs, int rowNum) throws SQLException {
        return new Proposal(
                rs.getLong("id"),
                rs.getLong("break_id"),
                Treatment.parse(rs.getString("treatment")),
                rs.getString("ai_output"),
                rs.getString("draft"),
                rs.getObject("validation_ok", Boolean.class),
                rs.getString("validation_errors"),
                ProposalStatus.valueOf(rs.getString("status")),
                rs.getString("created_by"),
                rs.getString("approved_by"),
                rs.getObject("posted_entry_id", Long.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("decided_at", OffsetDateTime.class),
                rs.getString("decided_by"),
                rs.getString("evidence_bundle"),
                rs.getString("alternative_draft"),
                rs.getString("validation_checks"));
    }
}
