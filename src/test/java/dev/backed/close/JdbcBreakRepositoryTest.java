package dev.backed.close;

import dev.backed.ledger.Account;
import dev.backed.ledger.JournalDraft;
import dev.backed.ledger.JournalLineDraft;
import dev.backed.support.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcBreakRepositoryTest extends AbstractDbTest {

    @Autowired
    BreakRepository breaks;

    @Test
    void insertIfAbsentKeepsOneBreakPerPayPalRef() {
        long a = customerA().id();
        Break first = breaks.insertIfAbsent(new NewBreak(null, BreakKind.UNBOOKED_REFUND, a, null, "REF-1", 2_000, "{}"));
        Break second = breaks.insertIfAbsent(new NewBreak(null, BreakKind.UNBOOKED_REFUND, a, null, "REF-1", 2_000, "{}"));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(first.status()).isEqualTo(BreakStatus.OPEN);
        assertThat(breaks.findOpen()).hasSize(1);
        assertThat(breaks.findOpenForCustomer(a)).hasSize(1);
        assertThat(breaks.findOpenForCustomer(customerB().id())).isEmpty();
    }

    @Test
    void statusTransitions() {
        Break b = breaks.insertIfAbsent(new NewBreak(null, BreakKind.UNBOOKED_REFUND, customerA().id(), null,
                "REF-2", 500, "{}"));

        assertThat(breaks.markProposed(b.id())).isTrue();
        assertThat(breaks.markProposed(b.id())).isFalse();
        assertThat(breaks.markOpen(b.id())).isTrue();

        long entryId = tx.execute(s -> journal.post(JournalDraft.of("CORRECTION", "break", String.valueOf(b.id()),
                "goodwill", "maker",
                JournalLineDraft.debit(Account.GOODWILL_EXPENSE, 500),
                JournalLineDraft.credit(Account.PAYPAL_CASH, 500)).withApprovedBy("checker")));
        assertThat(breaks.markResolved(b.id(), entryId)).isTrue();
        assertThat(breaks.markResolved(b.id(), entryId)).isFalse();

        Break resolved = breaks.findById(b.id()).orElseThrow();
        assertThat(resolved.status()).isEqualTo(BreakStatus.RESOLVED);
        assertThat(resolved.resolvedEntryId()).isEqualTo(entryId);
        assertThat(breaks.findOpen()).isEmpty();
    }
}
