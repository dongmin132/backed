package dev.backed.ledger;

import dev.backed.support.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static dev.backed.ledger.JournalLineDraft.credit;
import static dev.backed.ledger.JournalLineDraft.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BalanceServiceTest extends AbstractDbTest {

    @Autowired
    BalanceService balances;
    @Autowired
    LedgerQueries ledger;

    @Test
    void conditionalDebitRefusesOverdraft() {
        long a = customerA().id();
        balances.credit(a, 500);

        assertThat(balances.debitIfSufficient(a, 501)).isFalse();
        assertThat(balances.balanceOf(a)).isEqualTo(500);
        assertThat(balances.debitIfSufficient(a, 500)).isTrue();
        assertThat(balances.balanceOf(a)).isZero();
        assertThat(balances.debitIfSufficient(a, 1)).isFalse();
    }

    @Test
    void databaseRejectsNegativeBalance() {
        long a = customerA().id();
        assertThatThrownBy(() -> jdbc.sql("update customer_balances set balance_cents = -1 where customer_id = :id")
                .param("id", a).update())
                .hasStackTraceContaining("check");
    }

    @Test
    void postAndApplyRollsBackWhenBalanceIsShort() {
        long a = customerA().id();
        fund(a, 300);

        assertThatThrownBy(() -> tx.execute(s -> journal.postAndApply(JournalDraft.of(EntryKind.SPEND, "spend",
                "s-1", "coffee", "clerk",
                debit(Account.CUSTOMER_PREPAID, a, 301),
                credit(Account.SALES_REVENUE, 301)))))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(balanceOf(a)).isEqualTo(300);
        assertThat(ledger.entriesFor("spend", "s-1")).isEmpty();
    }

    @Test
    void twentyConcurrentOneDollarSpendsFromTenDollars() throws Exception {
        long a = customerA().id();
        fund(a, 1_000);
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    tx.execute(s -> journal.postAndApply(JournalDraft.of(EntryKind.SPEND, "spend",
                            UUID.randomUUID().toString(), "one dollar", "clerk",
                            debit(Account.CUSTOMER_PREPAID, a, 100),
                            credit(Account.SALES_REVENUE, 100))));
                    return true;
                } catch (InsufficientBalanceException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> f : results) {
            if (f.get(30, TimeUnit.SECONDS)) {
                succeeded++;
            }
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(10);
        assertThat(balanceOf(a)).isZero();
        assertThat(ledger.customerPrepaid(a)).isZero();
        assertThat(ledger.balanceOf(Account.SALES_REVENUE)).isEqualTo(1_000);
        assertThat(ledger.unbalancedEntryIds()).isEmpty();
    }
}
