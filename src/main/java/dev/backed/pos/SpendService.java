package dev.backed.pos;

import dev.backed.ledger.Account;
import dev.backed.ledger.BalanceService;
import dev.backed.ledger.BusinessDay;
import dev.backed.ledger.EntryKind;
import dev.backed.ledger.JournalDraft;
import dev.backed.ledger.JournalLineDraft;
import dev.backed.ledger.JournalService;
import dev.backed.ledger.Money;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spending a prepaid balance at the counter. */
@Service
public class SpendService {

    static final int MEMO_MAX = 200;
    private static final String SPEND_COLUMNS = """
            s.id, s.customer_id, c.name as customer_name, s.amount_cents, s.memo, s.state, s.request_id,
            s.entry_id, s.created_by, s.created_at
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final BalanceService balances;
    private final JournalService journal;
    private final BusinessDay businessDay;

    public SpendService(JdbcClient jdbc, TransactionTemplate tx, BalanceService balances, JournalService journal,
                        BusinessDay businessDay) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.balances = balances;
        this.journal = journal;
        this.businessDay = businessDay;
    }

    /** A sale with a fresh request id. */
    public SpendResult spend(long customerId, long amountCents, String memo, String actor) {
        return spend(customerId, amountCents, memo, actor, "spend-" + UUID.randomUUID());
    }

    /**
     * Takes amountCents from the customer's balance and books the sale, all in one transaction.
     * When the balance is too low nothing is written. A request id that was already used returns
     * the earlier sale, so a resubmitted form does not charge twice.
     */
    public SpendResult spend(long customerId, long amountCents, String memo, String actor, String requestId) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("Amount must be more than zero");
        }
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor is required");
        }
        String rid = requestId == null || requestId.isBlank() ? "spend-" + UUID.randomUUID() : requestId.strip();
        String cleanMemo = cleanMemo(memo);
        Optional<SpendRow> earlier = byRequestId(rid);
        if (earlier.isPresent()) {
            return alreadyDone(earlier.get());
        }
        try {
            return tx.execute(status -> {
                if (!balances.debitIfSufficient(customerId, amountCents)) {
                    long balance = balances.balanceOf(customerId);
                    return new SpendResult(SpendResult.Outcome.REFUSED, null, customerId, amountCents, balance,
                            "Refused: the balance is " + Money.format(balance) + ", the sale is "
                                    + Money.format(amountCents) + ". Nothing was charged.");
                }
                UUID id = UUID.randomUUID();
                LocalDate date = businessDay.current();
                long entryId = journal.post(JournalDraft.of(EntryKind.SPEND, "spend", id.toString(),
                                cleanMemo == null ? "Counter sale" : cleanMemo, actor,
                                JournalLineDraft.debit(Account.CUSTOMER_PREPAID, customerId, amountCents),
                                JournalLineDraft.credit(Account.SALES_REVENUE, amountCents))
                        .withBusinessDate(date));
                jdbc.sql("""
                                insert into spends (id, customer_id, amount_cents, request_id, state, memo, entry_id,
                                                    business_date, created_by)
                                values (:id, :customerId, :amount, :requestId, 'DONE', :memo, :entryId, :date, :actor)
                                """)
                        .param("id", id)
                        .param("customerId", customerId)
                        .param("amount", amountCents)
                        .param("requestId", rid)
                        .param("memo", cleanMemo)
                        .param("entryId", entryId)
                        .param("date", date)
                        .param("actor", actor)
                        .update();
                long balance = balances.balanceOf(customerId);
                return new SpendResult(SpendResult.Outcome.DONE, id, customerId, amountCents, balance,
                        "Charged " + Money.format(amountCents) + ". Balance left: " + Money.format(balance) + ".");
            });
        } catch (DuplicateKeyException e) {
            // Two submissions with the same request id raced; the loser rolled back entirely.
            return byRequestId(rid).map(this::alreadyDone).orElseThrow(() -> e);
        }
    }

    public List<SpendRow> recent(int limit) {
        return jdbc.sql("select " + SPEND_COLUMNS + """
                          from spends s join customers c on c.id = s.customer_id
                         order by s.created_at desc, s.id
                         limit :limit
                        """)
                .param("limit", limit)
                .query(SpendRow.class)
                .list();
    }

    public Optional<SpendRow> byRequestId(String requestId) {
        return jdbc.sql("select " + SPEND_COLUMNS + """
                          from spends s join customers c on c.id = s.customer_id
                         where s.request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query(SpendRow.class)
                .optional();
    }

    private SpendResult alreadyDone(SpendRow row) {
        return new SpendResult(SpendResult.Outcome.ALREADY_DONE, row.id(), row.customerId(), row.amountCents(),
                balances.balanceOf(row.customerId()),
                "This sale of " + Money.format(row.amountCents()) + " was already recorded. Nothing new was charged.");
    }

    private static String cleanMemo(String memo) {
        if (memo == null || memo.isBlank()) {
            return null;
        }
        String m = memo.strip();
        return m.length() > MEMO_MAX ? m.substring(0, MEMO_MAX) : m;
    }
}
