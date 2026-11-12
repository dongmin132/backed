package dev.backed.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Posts journal entries. Joins the caller's transaction when there is one. The database checks
 * balance again at commit through a deferred constraint trigger.
 */
@Service
public class JournalService {

    private final JdbcClient jdbc;
    private final BusinessDay businessDay;
    private final BalanceService balances;

    public JournalService(JdbcClient jdbc, BusinessDay businessDay, BalanceService balances) {
        this.jdbc = jdbc;
        this.businessDay = businessDay;
        this.balances = balances;
    }

    /** Problems with the draft, empty when it can be posted. Does not touch the database. */
    public static List<String> validate(JournalDraft draft) {
        List<String> problems = new ArrayList<>();
        if (draft.kind() == null || draft.kind().isBlank()) {
            problems.add("kind is required");
        }
        if (draft.createdBy() == null || draft.createdBy().isBlank()) {
            problems.add("createdBy is required");
        }
        if (draft.approvedBy() != null && draft.approvedBy().equals(draft.createdBy())) {
            problems.add("approvedBy must differ from createdBy");
        }
        if ((draft.refType() == null) != (draft.refId() == null)) {
            problems.add("refType and refId go together");
        }
        if (draft.lines().size() < 2) {
            problems.add("an entry needs at least two lines");
        }
        for (int i = 0; i < draft.lines().size(); i++) {
            JournalLineDraft line = draft.lines().get(i);
            String where = "line " + (i + 1);
            if (line.account() == null) {
                problems.add(where + ": account is required");
                continue;
            }
            if (line.debitCents() < 0 || line.creditCents() < 0) {
                problems.add(where + ": amounts cannot be negative");
            } else if ((line.debitCents() > 0) == (line.creditCents() > 0)) {
                problems.add(where + ": exactly one of debit and credit must be positive");
            }
            if (line.account() == Account.CUSTOMER_PREPAID && line.customerId() == null) {
                problems.add(where + ": CUSTOMER_PREPAID lines need a customer");
            }
        }
        long debits = draft.totalDebits();
        long credits = draft.totalCredits();
        if (debits != credits) {
            problems.add("debits " + debits + " do not equal credits " + credits);
        }
        return problems;
    }

    /**
     * Writes the entry and its lines. Does not change customer_balances; see {@link #postAndApply}.
     *
     * @return the new journal entry id
     * @throws InvalidJournalEntryException when {@link #validate} finds a problem
     */
    @Transactional
    public long post(JournalDraft draft) {
        List<String> problems = validate(draft);
        if (!problems.isEmpty()) {
            throw new InvalidJournalEntryException(problems);
        }
        LocalDate date = draft.businessDate() != null ? draft.businessDate() : businessDay.current();
        long entryId = jdbc.sql("""
                        insert into journal_entries (business_date, kind, ref_type, ref_id, memo, created_by, approved_by)
                        values (:date, :kind, :refType, :refId, :memo, :createdBy, :approvedBy)
                        returning id
                        """)
                .param("date", date)
                .param("kind", draft.kind())
                .param("refType", draft.refType())
                .param("refId", draft.refId())
                .param("memo", draft.memo())
                .param("createdBy", draft.createdBy())
                .param("approvedBy", draft.approvedBy())
                .query(Long.class)
                .single();
        for (JournalLineDraft line : draft.lines()) {
            jdbc.sql("""
                            insert into journal_lines (entry_id, account_code, customer_id, debit_cents, credit_cents)
                            values (:entryId, :account, :customerId, :debit, :credit)
                            """)
                    .param("entryId", entryId)
                    .param("account", line.account().code())
                    .param("customerId", line.customerId())
                    .param("debit", line.debitCents())
                    .param("credit", line.creditCents())
                    .update();
        }
        return entryId;
    }

    /**
     * Posts the entry and applies its CUSTOMER_PREPAID lines to customer_balances: a net credit
     * raises the balance, a net debit is taken with a conditional update. Use this for every
     * entry that touches CUSTOMER_PREPAID so the subledger cannot drift from the control account.
     *
     * @throws InsufficientBalanceException when a customer's balance cannot cover a net debit;
     *                                      the caller's transaction must roll back
     */
    @Transactional
    public long postAndApply(JournalDraft draft) {
        List<String> problems = validate(draft);
        if (!problems.isEmpty()) {
            throw new InvalidJournalEntryException(problems);
        }
        Map<Long, Long> netCreditByCustomer = new TreeMap<>();
        for (JournalLineDraft line : draft.lines()) {
            if (line.account() == Account.CUSTOMER_PREPAID) {
                netCreditByCustomer.merge(line.customerId(), line.creditCents() - line.debitCents(), Long::sum);
            }
        }
        // Customer id order, so concurrent postings lock balance rows in the same order.
        netCreditByCustomer.forEach((customerId, net) -> {
            if (net < 0) {
                balances.debitOrThrow(customerId, -net);
            } else if (net > 0) {
                balances.credit(customerId, net);
            }
        });
        return post(draft);
    }
}
