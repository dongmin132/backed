package dev.backed.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The customer_balances subledger. Every change is one SQL statement; balances are never read
 * and written back from Java. Call these inside the same transaction that posts the matching
 * journal entry so the subledger and CUSTOMER_PREPAID move together.
 */
@Service
public class BalanceService {

    private final JdbcClient jdbc;

    public BalanceService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void credit(long customerId, long cents) {
        requirePositive(cents);
        jdbc.sql("""
                        insert into customer_balances (customer_id, balance_cents) values (:id, :cents)
                        on conflict (customer_id) do update
                           set balance_cents = customer_balances.balance_cents + excluded.balance_cents,
                               updated_at = now()
                        """)
                .param("id", customerId)
                .param("cents", cents)
                .update();
    }

    /**
     * Takes cents from the balance only if at least that much is there.
     *
     * @return true when the debit happened, false when the balance was too low
     */
    public boolean debitIfSufficient(long customerId, long cents) {
        requirePositive(cents);
        int rows = jdbc.sql("""
                        update customer_balances
                           set balance_cents = balance_cents - :cents, updated_at = now()
                         where customer_id = :id and balance_cents >= :cents
                        """)
                .param("id", customerId)
                .param("cents", cents)
                .update();
        return rows == 1;
    }

    /** Like {@link #debitIfSufficient} but throws, so the caller's transaction rolls back. */
    public void debitOrThrow(long customerId, long cents) {
        if (!debitIfSufficient(customerId, cents)) {
            throw new InsufficientBalanceException(customerId, cents);
        }
    }

    public long balanceOf(long customerId) {
        return jdbc.sql("select balance_cents from customer_balances where customer_id = :id")
                .param("id", customerId)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    /** Sum of all customer balances: what the shop owes its customers. */
    public long totalOwed() {
        return jdbc.sql("select coalesce(sum(balance_cents), 0) from customer_balances")
                .query(Long.class)
                .single();
    }

    private static void requirePositive(long cents) {
        if (cents <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + cents);
        }
    }
}
