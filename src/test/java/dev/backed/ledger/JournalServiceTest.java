package dev.backed.ledger;

import dev.backed.support.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static dev.backed.ledger.JournalLineDraft.credit;
import static dev.backed.ledger.JournalLineDraft.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalServiceTest extends AbstractDbTest {

    @Autowired
    LedgerQueries ledger;
    @Autowired
    BusinessDay businessDay;

    @Test
    void postsBalancedEntryOnCurrentBusinessDate() {
        long a = customerA().id();
        long id = tx.execute(s -> journal.postAndApply(JournalDraft.of(EntryKind.TOPUP_CAPTURED, "topup", "t-1",
                "top-up", "customer:" + a,
                debit(Account.PAYPAL_CASH, 9_602),
                debit(Account.PAYPAL_FEES, 398),
                credit(Account.CUSTOMER_PREPAID, a, 10_000))));

        JournalEntryView entry = ledger.entriesFor("topup", "t-1").getFirst();
        assertThat(entry.id()).isEqualTo(id);
        assertThat(entry.businessDate()).isEqualTo(businessDay.current());
        assertThat(entry.lines()).hasSize(3);
        assertThat(entry.totalDebits()).isEqualTo(entry.totalCredits()).isEqualTo(10_000);
        assertThat(balanceOf(a)).isEqualTo(10_000);
        assertThat(ledger.balanceOf(Account.CUSTOMER_PREPAID)).isEqualTo(10_000);
        assertThat(ledger.balanceOf(Account.PAYPAL_CASH)).isEqualTo(9_602);
        assertThat(ledger.customerHistory(a, 10)).singleElement()
                .satisfies(h -> assertThat(h.amountCents()).isEqualTo(10_000));
        assertThat(ledger.unbalancedEntryIds()).isEmpty();
    }

    @Test
    void serviceRejectsUnbalancedEntryAndWritesNothing() {
        JournalDraft draft = JournalDraft.of("TEST", null, null, null, "maker",
                debit(Account.PAYPAL_CASH, 100),
                credit(Account.SALES_REVENUE, 99));

        assertThatThrownBy(() -> journal.post(draft))
                .isInstanceOf(InvalidJournalEntryException.class)
                .hasMessageContaining("do not equal");
        assertThat(jdbc.sql("select count(*) from journal_entries").query(Integer.class).single()).isZero();
    }

    @Test
    void serviceRejectsBadLines() {
        List<String> problems = JournalService.validate(JournalDraft.of("TEST", null, null, null, "maker",
                new JournalLineDraft(Account.CUSTOMER_PREPAID, null, 100, 0),
                new JournalLineDraft(Account.SALES_REVENUE, null, 0, -5),
                new JournalLineDraft(Account.SALES_REVENUE, null, 5, 5)));
        assertThat(problems).anyMatch(p -> p.contains("need a customer"))
                .anyMatch(p -> p.contains("negative"))
                .anyMatch(p -> p.contains("exactly one"));

        assertThat(JournalService.validate(JournalDraft.of("TEST", null, null, null, "maker",
                debit(Account.PAYPAL_CASH, 1)))).anyMatch(p -> p.contains("two lines"));

        assertThat(JournalService.validate(JournalDraft.of("TEST", null, null, null, "maker",
                debit(Account.GOODWILL_EXPENSE, 1), credit(Account.PAYPAL_CASH, 1)).withApprovedBy("maker")))
                .anyMatch(p -> p.contains("approvedBy"));
    }

    @Test
    void databaseTriggerRejectsUnbalancedEntryAtCommit() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            long entryId = insertEntry();
            insertLine(entryId, "PAYPAL_CASH", 100, 0);
            insertLine(entryId, "SALES_REVENUE", 0, 90);
        })).isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("does not balance");

        assertThat(jdbc.sql("select count(*) from journal_entries").query(Integer.class).single()).isZero();
    }

    @Test
    void databaseTriggerRejectsEntryWithOneLine() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            long entryId = insertEntry();
            insertLine(entryId, "PAYPAL_CASH", 100, 0);
        })).isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("at least 2");
    }

    @Test
    void databaseAcceptsBalancedEntryInsertedDirectly() {
        tx.executeWithoutResult(s -> {
            long entryId = insertEntry();
            insertLine(entryId, "PAYPAL_CASH", 100, 0);
            insertLine(entryId, "SALES_REVENUE", 0, 100);
        });
        assertThat(ledger.unbalancedEntryIds()).isEmpty();
    }

    @Test
    void journalIsAppendOnly() {
        tx.execute(s -> journal.post(JournalDraft.of("TEST", null, null, null, "maker",
                debit(Account.PAYPAL_CASH, 100), credit(Account.SALES_REVENUE, 100))));

        assertThatThrownBy(() -> jdbc.sql("update journal_lines set debit_cents = 200 where debit_cents = 100").update())
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("delete from journal_entries").update())
                .hasStackTraceContaining("append-only");
    }

    @Test
    void oneEntryPerKindAndReference() {
        long a = customerA().id();
        JournalDraft draft = JournalDraft.of(EntryKind.TOPUP_CAPTURED, "topup", "t-9", null, "system",
                debit(Account.PAYPAL_CASH, 100), credit(Account.CUSTOMER_PREPAID, a, 100));
        tx.execute(s -> journal.postAndApply(draft));

        assertThatThrownBy(() -> tx.execute(s -> journal.postAndApply(draft)))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(balanceOf(a)).isEqualTo(100);
    }

    @Test
    void databaseRejectsSelfApprovedEntry() {
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into journal_entries (business_date, kind, created_by, approved_by)
                        values (current_date, 'CORRECTION', 'maker', 'maker')
                        """).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void businessDateAdvances() {
        var today = businessDay.current();
        assertThat(businessDay.next()).isEqualTo(today.plusDays(1));
        assertThat(businessDay.advance()).isEqualTo(today.plusDays(1));
        assertThat(businessDay.current()).isEqualTo(today.plusDays(1));
    }

    private long insertEntry() {
        return jdbc.sql("""
                        insert into journal_entries (business_date, kind, created_by)
                        values (current_date, 'TEST', 'test') returning id
                        """)
                .query(Long.class)
                .single();
    }

    private void insertLine(long entryId, String account, long debit, long credit) {
        jdbc.sql("""
                        insert into journal_lines (entry_id, account_code, debit_cents, credit_cents)
                        values (:e, :a, :d, :c)
                        """)
                .param("e", entryId)
                .param("a", account)
                .param("d", debit)
                .param("c", credit)
                .update();
    }
}
