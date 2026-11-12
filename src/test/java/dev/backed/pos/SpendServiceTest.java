package dev.backed.pos;

import dev.backed.ledger.Account;
import dev.backed.ledger.BalanceService;
import dev.backed.ledger.LedgerQueries;
import dev.backed.support.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpendServiceTest extends AbstractDbTest {

    @Autowired
    SpendService spends;
    @Autowired
    BalanceService balances;
    @Autowired
    LedgerQueries ledger;

    @Test
    void twentyThreadsSpendOneDollarFromTenDollarsAndExactlyTenSucceed() throws Exception {
        long a = customerA().id();
        fund(a, 1_000);

        List<SpendResult> results = runConcurrently(20, () -> spends.spend(a, 100, "one dollar", "clerk"));

        assertThat(results.stream().filter(r -> r.outcome() == SpendResult.Outcome.DONE)).hasSize(10);
        assertThat(results.stream().filter(r -> r.outcome() == SpendResult.Outcome.REFUSED)).hasSize(10);
        assertThat(balanceOf(a)).isZero();
        assertThat(jdbc.sql("select count(*) from spends").query(Long.class).single()).isEqualTo(10);
        assertThat(ledger.balanceOf(Account.SALES_REVENUE)).isEqualTo(1_000);
        assertBooksBalance();
    }

    /**
     * Documents the race the conditional debit prevents. Each thread reads the balance, checks it
     * covers $1, then writes back what it read minus $1: the read-then-write pattern SPEC forbids.
     * All twenty threads read $10 before any writes, so all twenty sales are approved against a
     * $10 balance and the lost updates leave $9 on the account.
     */
    @Test
    void naiveReadThenWriteVersionOverdrawsTheBalance() throws Exception {
        long b = customerB().id();
        balances.credit(b, 1_000);
        int threads = 20;
        CyclicBarrier everyoneHasRead = new CyclicBarrier(threads);

        List<Boolean> approved = runConcurrently(threads, () -> {
            long read = jdbc.sql("select balance_cents from customer_balances where customer_id = :id")
                    .param("id", b).query(Long.class).single();
            boolean covers = read >= 100;
            everyoneHasRead.await(10, TimeUnit.SECONDS);
            if (covers) {
                jdbc.sql("update customer_balances set balance_cents = :value where customer_id = :id")
                        .param("value", read - 100).param("id", b).update();
            }
            return covers;
        });

        long approvedCents = approved.stream().filter(x -> x).count() * 100;
        assertThat(approvedCents).isEqualTo(2_000).isGreaterThan(1_000);
        assertThat(balanceOf(b)).isEqualTo(900);
    }

    @Test
    void refusedSpendWritesNothing() {
        long a = customerA().id();
        fund(a, 500);
        long entriesBefore = entryCount();

        SpendResult result = spends.spend(a, 501, "too much", "clerk");

        assertThat(result.outcome()).isEqualTo(SpendResult.Outcome.REFUSED);
        assertThat(result.message()).contains("$5.00").contains("$5.01");
        assertThat(balanceOf(a)).isEqualTo(500);
        assertThat(jdbc.sql("select count(*) from spends").query(Long.class).single()).isZero();
        assertThat(entryCount()).isEqualTo(entriesBefore);
    }

    @Test
    void spendPostsTheSaleAndLinksTheEntry() {
        long a = customerA().id();
        fund(a, 1_000);

        SpendResult result = spends.spend(a, 450, "  Coffee  ", "clerk");

        assertThat(result.outcome()).isEqualTo(SpendResult.Outcome.DONE);
        assertThat(result.balanceCents()).isEqualTo(550);
        SpendRow row = spends.recent(1).getFirst();
        assertThat(row.memo()).isEqualTo("Coffee");
        assertThat(row.createdBy()).isEqualTo("clerk");
        var entry = ledger.entriesFor("spend", row.id().toString()).getFirst();
        assertThat(entry.id()).isEqualTo(row.entryId());
        assertThat(entry.lines()).extracting(l -> l.account().code() + ":" + l.debitCents() + ":" + l.creditCents())
                .containsExactlyInAnyOrder("CUSTOMER_PREPAID:450:0", "SALES_REVENUE:0:450");
        assertBooksBalance();
    }

    @Test
    void sameRequestIdChargesOnce() {
        long a = customerA().id();
        fund(a, 1_000);

        SpendResult first = spends.spend(a, 300, "cake", "clerk", "spend-form-1");
        SpendResult again = spends.spend(a, 300, "cake", "clerk", "spend-form-1");

        assertThat(first.outcome()).isEqualTo(SpendResult.Outcome.DONE);
        assertThat(again.outcome()).isEqualTo(SpendResult.Outcome.ALREADY_DONE);
        assertThat(again.spendId()).isEqualTo(first.spendId());
        assertThat(balanceOf(a)).isEqualTo(700);
    }

    @Test
    void rejectsNonPositiveAmounts() {
        long a = customerA().id();
        assertThatThrownBy(() -> spends.spend(a, 0, null, "clerk")).isInstanceOf(IllegalArgumentException.class);
    }

    private void assertBooksBalance() {
        assertThat(ledger.unbalancedEntryIds()).isEmpty();
        assertThat(jdbc.sql("select coalesce(sum(debit_cents), 0) - coalesce(sum(credit_cents), 0) from journal_lines")
                .query(Long.class).single()).isZero();
        assertThat(ledger.balanceOf(Account.CUSTOMER_PREPAID)).isEqualTo(balances.totalOwed());
    }

    private long entryCount() {
        return jdbc.sql("select count(*) from journal_entries").query(Long.class).single();
    }

    interface Task<T> {
        T call() throws Exception;
    }

    private static <T> List<T> runConcurrently(int threads, Task<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
