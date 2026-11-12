package dev.backed.close;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** The day_closes table: one row per close run. */
@Repository
public class CloseRuns {

    public record CloseRun(long id, LocalDate businessDate, CloseStatus status, Long owedCents,
                           Long ledgerPaypalCashCents, Long paypalDerivedCashCents, Long unexplainedCents,
                           String summaryJson, String runBy, OffsetDateTime runAt) {
    }

    private final JdbcClient jdbc;

    public CloseRuns(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long start(LocalDate businessDate, String actor) {
        return jdbc.sql("""
                        insert into day_closes (business_date, status, run_by) values (:date, 'RUNNING', :actor)
                        returning id
                        """)
                .param("date", businessDate)
                .param("actor", actor)
                .query(Long.class)
                .single();
    }

    void finish(long id, CloseStatus status, Long owed, Long ledgerCash, Long derivedCash, Long unexplained,
                String summaryJson) {
        jdbc.sql("""
                        update day_closes
                           set status = :status, owed_cents = :owed, ledger_paypal_cash_cents = :ledgerCash,
                               paypal_derived_cash_cents = :derived, unexplained_cents = :unexplained,
                               summary = :summary
                         where id = :id
                        """)
                .param("id", id)
                .param("status", status.name())
                .param("owed", owed)
                .param("ledgerCash", ledgerCash)
                .param("derived", derivedCash)
                .param("unexplained", unexplained)
                .param("summary", summaryJson)
                .update();
    }

    public Optional<CloseRun> findById(long id) {
        return jdbc.sql("select * from day_closes where id = :id").param("id", id).query(CloseRuns::map).optional();
    }

    /** The newest run for a business date. */
    public Optional<CloseRun> latestFor(LocalDate businessDate) {
        return jdbc.sql("select * from day_closes where business_date = :date order by run_at desc, id desc limit 1")
                .param("date", businessDate)
                .query(CloseRuns::map)
                .optional();
    }

    public Optional<CloseRun> latest() {
        return jdbc.sql("select * from day_closes order by run_at desc, id desc limit 1")
                .query(CloseRuns::map)
                .optional();
    }

    /** Newest first. */
    public List<CloseRun> recent(int limit) {
        return jdbc.sql("select * from day_closes order by run_at desc, id desc limit :limit")
                .param("limit", limit)
                .query(CloseRuns::map)
                .list();
    }

    private static CloseRun map(ResultSet rs, int n) throws SQLException {
        return new CloseRun(
                rs.getLong("id"),
                rs.getObject("business_date", LocalDate.class),
                CloseStatus.valueOf(rs.getString("status")),
                rs.getObject("owed_cents", Long.class),
                rs.getObject("ledger_paypal_cash_cents", Long.class),
                rs.getObject("paypal_derived_cash_cents", Long.class),
                rs.getObject("unexplained_cents", Long.class),
                rs.getString("summary"),
                rs.getString("run_by"),
                rs.getObject("run_at", OffsetDateTime.class));
    }
}
