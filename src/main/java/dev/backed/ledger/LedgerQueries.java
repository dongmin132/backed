package dev.backed.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only views of the journal. */
@Service
public class LedgerQueries {

    private final JdbcClient jdbc;

    public LedgerQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every account in chart order, including accounts with no activity. */
    public List<AccountBalance> accountBalances() {
        Map<Account, long[]> totals = new EnumMap<>(Account.class);
        for (Account a : Account.values()) {
            totals.put(a, new long[2]);
        }
        jdbc.sql("""
                        select account_code, coalesce(sum(debit_cents), 0) as d, coalesce(sum(credit_cents), 0) as c
                          from journal_lines group by account_code
                        """)
                .query((ResultSet rs) -> {
                    long[] t = totals.get(Account.valueOf(rs.getString("account_code")));
                    t[0] = rs.getLong("d");
                    t[1] = rs.getLong("c");
                });
        List<AccountBalance> out = new ArrayList<>();
        totals.forEach((a, t) -> out.add(new AccountBalance(a, t[0], t[1], a.balance(t[0], t[1]))));
        return out;
    }

    /** Signed balance of one account in its normal direction. */
    public long balanceOf(Account account) {
        return jdbc.sql("""
                        select coalesce(sum(debit_cents), 0) as d, coalesce(sum(credit_cents), 0) as c
                          from journal_lines where account_code = :code
                        """)
                .param("code", account.code())
                .query((rs, n) -> account.balance(rs.getLong("d"), rs.getLong("c")))
                .single();
    }

    /** CUSTOMER_PREPAID for one customer, from the journal (credits minus debits). */
    public long customerPrepaid(long customerId) {
        return jdbc.sql("""
                        select coalesce(sum(credit_cents) - sum(debit_cents), 0)
                          from journal_lines where account_code = 'CUSTOMER_PREPAID' and customer_id = :id
                        """)
                .param("id", customerId)
                .query(Long.class)
                .single();
    }

    /** Ids of entries whose lines do not balance or that have fewer than two lines. Should always be empty. */
    public List<Long> unbalancedEntryIds() {
        return jdbc.sql("""
                        select e.id
                          from journal_entries e
                          left join journal_lines l on l.entry_id = e.id
                         group by e.id
                        having coalesce(sum(l.debit_cents), 0) <> coalesce(sum(l.credit_cents), 0) or count(l.id) < 2
                         order by e.id
                        """)
                .query(Long.class)
                .list();
    }

    /** Movements on the customer's balance, newest first. */
    public List<CustomerHistoryItem> customerHistory(long customerId, int limit) {
        return jdbc.sql("""
                        select e.id, e.business_date, e.kind, e.memo, e.created_at,
                               sum(l.credit_cents) - sum(l.debit_cents) as amount
                          from journal_lines l
                          join journal_entries e on e.id = l.entry_id
                         where l.account_code = 'CUSTOMER_PREPAID' and l.customer_id = :id
                         group by e.id
                         order by e.created_at desc, e.id desc
                         limit :limit
                        """)
                .param("id", customerId)
                .param("limit", limit)
                .query((rs, n) -> new CustomerHistoryItem(
                        rs.getLong("id"),
                        rs.getObject("business_date", LocalDate.class),
                        rs.getString("kind"),
                        rs.getString("memo"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getLong("amount")))
                .list();
    }

    /** The newest entries with their lines, newest first. */
    public List<JournalEntryView> recentEntries(int limit) {
        return entries("order by e.id desc limit :limit", Map.of("limit", limit));
    }

    public List<JournalEntryView> entriesFor(String refType, String refId) {
        return entries("where e.ref_type = :refType and e.ref_id = :refId order by e.id",
                Map.of("refType", refType, "refId", refId));
    }

    /** Entries with a line for this customer on any account, newest first. */
    public List<JournalEntryView> entriesForCustomer(long customerId, int limit) {
        return entries("""
                        where e.id in (select entry_id from journal_lines where customer_id = :customerId)
                        order by e.id desc limit :limit
                        """,
                Map.of("customerId", customerId, "limit", limit));
    }

    private List<JournalEntryView> entries(String whereOrderLimit, Map<String, ?> params) {
        List<JournalEntryView> heads = jdbc.sql("select e.* from journal_entries e " + whereOrderLimit)
                .params(params)
                .query((rs, n) -> entryRow(rs))
                .list();
        if (heads.isEmpty()) {
            return List.of();
        }
        Map<Long, List<JournalEntryView.Line>> linesByEntry = new LinkedHashMap<>();
        jdbc.sql("""
                        select l.*, c.name as customer_name
                          from journal_lines l left join customers c on c.id = l.customer_id
                         where l.entry_id in (:ids)
                         order by l.entry_id, l.id
                        """)
                .param("ids", heads.stream().map(JournalEntryView::id).toList())
                .query((ResultSet rs) -> {
                    Long customerId = rs.getObject("customer_id", Long.class);
                    linesByEntry.computeIfAbsent(rs.getLong("entry_id"), k -> new ArrayList<>())
                            .add(new JournalEntryView.Line(
                                    Account.valueOf(rs.getString("account_code")),
                                    customerId,
                                    rs.getString("customer_name"),
                                    rs.getLong("debit_cents"),
                                    rs.getLong("credit_cents")));
                });
        return heads.stream()
                .map(h -> new JournalEntryView(h.id(), h.businessDate(), h.kind(), h.refType(), h.refId(), h.memo(),
                        h.createdBy(), h.approvedBy(), h.createdAt(), List.copyOf(linesByEntry.getOrDefault(h.id(), List.of()))))
                .toList();
    }

    private static JournalEntryView entryRow(ResultSet rs) throws SQLException {
        return new JournalEntryView(
                rs.getLong("id"),
                rs.getObject("business_date", LocalDate.class),
                rs.getString("kind"),
                rs.getString("ref_type"),
                rs.getString("ref_id"),
                rs.getString("memo"),
                rs.getString("created_by"),
                rs.getString("approved_by"),
                rs.getObject("created_at", OffsetDateTime.class),
                List.of());
    }
}
