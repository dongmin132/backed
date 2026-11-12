package dev.backed.cashout;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Read-only views of cash-outs and their legs. */
@Service
public class CashoutQueries {

    public record LegView(UUID id, UUID cashoutId, int legNo, UUID topupId, String captureId, long amountCents,
                          String requestId, String state, String paypalRefundId, int attempts, String lastError,
                          OffsetDateTime nextAttemptAt, OffsetDateTime claimedUntil, OffsetDateTime updatedAt) {
    }

    public record CashoutView(UUID id, long customerId, String customerName, long amountCents, String state,
                              String requestId, LocalDate businessDate, String createdBy, OffsetDateTime createdAt,
                              List<LegView> legs) {
    }

    public record CashoutHead(UUID id, long customerId, String customerName, long amountCents, String state,
                               String requestId, LocalDate businessDate, String createdBy, OffsetDateTime createdAt) {
    }

    public record LegCounts(long pending, long inDoubt, long done, long failed) {
    }

    private static final String LEG_COLUMNS = """
            id, cashout_id, leg_no, topup_id, capture_id, amount_cents, request_id, state, paypal_refund_id,
            attempts, last_error, next_attempt_at, claimed_until, updated_at
            """;

    private final JdbcClient jdbc;

    public CashoutQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Newest cash-outs first, each with its legs in leg order. */
    public List<CashoutView> recent(int limit) {
        List<CashoutHead> heads = jdbc.sql("""
                        select c.id, c.customer_id, cu.name as customer_name, c.amount_cents, c.state, c.request_id,
                               c.business_date, c.created_by, c.created_at
                          from cashouts c join customers cu on cu.id = c.customer_id
                         order by c.created_at desc, c.id
                         limit :limit
                        """)
                .param("limit", limit)
                .query(CashoutHead.class)
                .list();
        if (heads.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<LegView>> legs = jdbc.sql("select " + LEG_COLUMNS
                        + " from cashout_legs where cashout_id in (:ids) order by cashout_id, leg_no")
                .param("ids", heads.stream().map(CashoutHead::id).toList())
                .query(LegView.class)
                .list()
                .stream()
                .collect(Collectors.groupingBy(LegView::cashoutId, LinkedHashMap::new, Collectors.toList()));
        return heads.stream()
                .map(h -> new CashoutView(h.id(), h.customerId(), h.customerName(), h.amountCents(), h.state(),
                        h.requestId(), h.businessDate(), h.createdBy(), h.createdAt(), legs.getOrDefault(h.id(), List.of())))
                .toList();
    }

    public List<LegView> legsOf(UUID cashoutId) {
        return jdbc.sql("select " + LEG_COLUMNS + " from cashout_legs where cashout_id = :id order by leg_no")
                .param("id", cashoutId)
                .query(LegView.class)
                .list();
    }

    public LegCounts legCounts() {
        return jdbc.sql("""
                        select count(*) filter (where state = 'PENDING') as pending,
                               count(*) filter (where state = 'IN_DOUBT') as in_doubt,
                               count(*) filter (where state = 'DONE') as done,
                               count(*) filter (where state = 'FAILED') as failed
                          from cashout_legs
                        """)
                .query(LegCounts.class)
                .single();
    }
}
