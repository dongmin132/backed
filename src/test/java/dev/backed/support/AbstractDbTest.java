package dev.backed.support;

import dev.backed.customer.Customer;
import dev.backed.customer.Customers;
import dev.backed.ledger.Account;
import dev.backed.ledger.JournalDraft;
import dev.backed.ledger.JournalLineDraft;
import dev.backed.ledger.JournalService;
import dev.backed.paypal.FakePayPalGateway;
import dev.backed.paypal.FaultSwitch;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

/**
 * Base for tests that need the database. The Spring context starts once (and is cached across
 * subclasses with the same configuration); before each test every table is truncated and the seed
 * data (accounts, business date, three demo customers with zero balance) is loaded again, and the
 * fake PayPal and the fault switch are reset.
 *
 * <p>Scheduling is off, PayPal is {@link FakePayPalGateway} behind the fault-injecting wrapper.
 * Add @AutoConfigureMockMvc on a subclass to drive controllers.
 */
@SpringBootTest(properties = {
        "backed.scheduling.enabled=false",
        "backed.paypal.mode=fake"
})
@Import(FakePayPalConfig.class)
public abstract class AbstractDbTest {

    public static final String TOKEN_A = "demo-a-7f3k9q2m";
    public static final String TOKEN_B = "demo-b-4p8w1x6z";
    public static final String TOKEN_C = "demo-c-9h2n5r3t";

    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected DataSource dataSource;
    @Autowired
    protected TransactionTemplate tx;
    @Autowired
    protected Customers customers;
    @Autowired
    protected JournalService journal;
    @Autowired
    protected FakePayPalGateway fakePayPal;
    @Autowired
    protected FaultSwitch faults;

    @BeforeEach
    void resetDatabase() {
        List<String> tables = jdbc.sql("""
                        select quote_ident(table_name) from information_schema.tables
                         where table_schema = current_schema() and table_type = 'BASE TABLE'
                           and table_name <> 'flyway_schema_history'
                        """)
                .query(String.class)
                .list();
        jdbc.sql("truncate table " + String.join(", ", tables) + " restart identity cascade").update();
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V2__seed.sql")).execute(dataSource);
        fakePayPal.reset();
        faults.reset();
    }

    protected Customer customerA() {
        return customers.byToken(TOKEN_A).orElseThrow();
    }

    protected Customer customerB() {
        return customers.byToken(TOKEN_B).orElseThrow();
    }

    protected Customer customerC() {
        return customers.byToken(TOKEN_C).orElseThrow();
    }

    /**
     * Gives a customer a balance the way a test needs it: a balanced entry (Dr PAYPAL_CASH,
     * Cr CUSTOMER_PREPAID) applied to customer_balances. No PayPal capture exists for it, so do not
     * use it in tests that reconcile against PayPal; run a real top-up through the fake there.
     */
    protected void fund(long customerId, long cents) {
        tx.executeWithoutResult(s -> journal.postAndApply(JournalDraft.of(
                "TEST_FUNDING", "test", UUID.randomUUID().toString(), "test funding", "test",
                JournalLineDraft.debit(Account.PAYPAL_CASH, cents),
                JournalLineDraft.credit(Account.CUSTOMER_PREPAID, customerId, cents))));
    }

    protected long balanceOf(long customerId) {
        return jdbc.sql("select balance_cents from customer_balances where customer_id = :id")
                .param("id", customerId)
                .query(Long.class)
                .single();
    }
}
