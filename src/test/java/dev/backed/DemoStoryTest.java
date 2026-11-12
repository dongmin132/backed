package dev.backed;

import dev.backed.close.Break;
import dev.backed.close.BreakKind;
import dev.backed.close.BreakRepository;
import dev.backed.close.BreakStatus;
import dev.backed.close.CloseRuns;
import dev.backed.close.CloseStatus;
import dev.backed.customer.CustomerMessages;
import dev.backed.investigate.InvestigateTestConfig.StubInvestigator;
import dev.backed.investigate.InvestigatorOutput;
import dev.backed.investigate.Proposal;
import dev.backed.investigate.ProposalRepository;
import dev.backed.investigate.ProposalStatus;
import dev.backed.investigate.Treatment;
import dev.backed.ledger.Account;
import dev.backed.ledger.LedgerQueries;
import dev.backed.paypal.CaptureSnapshot;
import dev.backed.paypal.RefundSnapshot;
import dev.backed.support.AbstractDbTest;
import dev.backed.topup.Topup;
import dev.backed.topup.TopupState;
import dev.backed.topup.Topups;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The demo, end to end, through the same HTTP routes the video uses. Only PayPal (the in-memory
 * fake) and the model (a scripted investigator) are stand-ins; the close, the validator, the
 * approval and the cash-out relay are the real beans.
 */
@AutoConfigureMockMvc
@Import(DemoStoryTest.ScriptedInvestigatorConfig.class)
class DemoStoryTest extends AbstractDbTest {

    static final String ASK = "Could you send $20 of my class pack back to my card? I am away for a month.";
    static final String REFUND_NOTE = "Returning $20.00 of your class pack balance, as you asked";

    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedInvestigatorConfig {
        @Bean
        @Primary
        StubInvestigator scriptedInvestigator() {
            return new StubInvestigator();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    StubInvestigator investigator;
    @Autowired
    Topups topups;
    @Autowired
    BreakRepository breaks;
    @Autowired
    ProposalRepository proposals;
    @Autowired
    CloseRuns closeRuns;
    @Autowired
    LedgerQueries ledger;
    @Autowired
    CustomerMessages messages;

    @Test
    void theDemoStory() throws Exception {
        investigator.reset();
        long a = customerA().id();
        String page = "/c/" + TOKEN_A;

        // 1. The customer asks the shop for part of her balance back.
        mvc.perform(post(page + "/message").param("body", ASK).with(csrf()))
                .andExpect(redirectedUrl(page))
                .andExpect(flash().attribute("notice", containsString("Message sent")));

        // 2. A normal top-up.
        mvc.perform(post(page + "/topup").param("amount", "50.00").with(csrf()))
                .andExpect(redirectedUrl(page))
                .andExpect(flash().attribute("notice", containsString("Added $50.00")));
        Topup first = topups.recent(1).getFirst();
        assertThat(first.state()).isEqualTo(TopupState.CAPTURED);
        assertThat(balanceOf(a)).isEqualTo(5_000);

        // 3. PayPal accepts the next capture but the response is lost.
        mvc.perform(post("/admin/faults").param("fault", "DROP_NEXT_CAPTURE_RESPONSE").param("action", "arm")
                        .with(user("maker").roles("MAKER")).with(csrf()))
                .andExpect(redirectedUrl("/admin/faults"));
        mvc.perform(post(page + "/topup").param("amount", "30.00").with(csrf()))
                .andExpect(flash().attribute("pending", containsString("Processing: PayPal has not confirmed")));
        Topup lost = topups.recent(1).getFirst();
        assertThat(lost.state()).isEqualTo(TopupState.IN_DOUBT);
        assertThat(balanceOf(a)).isEqualTo(5_000);

        // The impatient customer tries again: refused, so she cannot be charged twice.
        mvc.perform(post(page + "/topup").param("amount", "30.00").with(csrf()))
                .andExpect(flash().attribute("error", containsString("still waiting for PayPal")));
        assertThat(fakePayPal.captureCount()).isEqualTo(2);

        // 4. The maker resolves it: PayPal is asked with the same request id, the customer is credited once.
        mvc.perform(post("/topups/" + lost.id() + "/resolve").with(user("maker").roles("MAKER")).with(csrf()))
                .andExpect(redirectedUrl("/topups"))
                .andExpect(flash().attribute("notice", containsString("credited once")));
        assertThat(topups.findById(lost.id()).orElseThrow().state()).isEqualTo(TopupState.CAPTURED);
        assertThat(fakePayPal.captureCount()).isEqualTo(2);
        assertThat(fakePayPal.captureCalls()).filteredOn(c -> c.requestId().equals(lost.requestId())).hasSize(2);
        assertThat(balanceOf(a)).isEqualTo(8_000);

        // 5. She spends at the counter.
        mvc.perform(post("/pos").param("customerId", String.valueOf(a)).param("amount", "12.50")
                        .param("memo", "Day pass").with(user("clerk").roles("CLERK")).with(csrf()))
                .andExpect(redirectedUrl("/pos?customerId=" + a))
                .andExpect(flash().attributeExists("notice"));
        assertThat(balanceOf(a)).isEqualTo(6_750);

        // 6. The owner refunds $20 by hand in the PayPal dashboard. The ledger does not know.
        long journalBefore = journalEntryCount();
        mvc.perform(post("/admin/faults/outside-refund").param("topupId", first.id().toString())
                        .param("amount", "20.00").param("note", REFUND_NOTE)
                        .with(user("maker").roles("MAKER")).with(csrf()))
                .andExpect(redirectedUrl("/admin/faults"))
                .andExpect(flash().attribute("notice", containsString("PayPal refund")));
        assertThat(journalEntryCount()).isEqualTo(journalBefore);
        assertThat(fakePayPal.refunds()).hasSize(1);
        RefundSnapshot outside = fakePayPal.refunds().getFirst();

        // 7. The close finds it: BROKEN, one unbooked refund, $20 unexplained.
        mvc.perform(post("/close/run").with(user("maker").roles("MAKER")).with(csrf()))
                .andExpect(redirectedUrl("/close"))
                .andExpect(flash().attribute("error", containsString("The day is broken")));
        CloseRuns.CloseRun broken = closeRuns.latest().orElseThrow();
        assertThat(broken.status()).isEqualTo(CloseStatus.BROKEN);
        assertThat(broken.unexplainedCents()).isEqualTo(outside.netCents() == null ? 2_000 : outside.netCents());
        Break found = breaks.findByRef(BreakKind.UNBOOKED_REFUND, outside.refundId()).orElseThrow();
        assertThat(found.customerId()).isEqualTo(a);
        assertThat(found.amountCents()).isEqualTo(2_000);
        assertThat(breaks.findOpen()).extracting(Break::id).containsExactly(found.id());
        mvc.perform(get("/close").with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(REFUND_NOTE.substring(0, 30))))
                .andExpect(content().string(containsString("/breaks/" + found.id() + "/investigate")));

        // 8. The maker asks the investigator. It picks a treatment and quotes evidence; code drafts the entry.
        investigator.respond(new InvestigatorOutput(Treatment.BALANCE_RETURNED,
                List.of(REFUND_NOTE, "send $20 of my class pack back to my card"),
                "The refund note and the customer's own request both say part of her balance was paid back.",
                Treatment.GOODWILL, "Nothing suggests a complaint or compensation.", "HIGH",
                "We have taken the refund you received off your class pack balance.", "scripted"));
        MvcResult investigated = mvc.perform(post("/breaks/" + found.id() + "/investigate")
                        .with(user("maker").roles("MAKER")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String proposalUrl = investigated.getResponse().getRedirectedUrl();
        assertThat(proposalUrl).startsWith("/proposals/");
        long proposalId = Long.parseLong(proposalUrl.substring("/proposals/".length()));
        Proposal proposal = proposals.findById(proposalId).orElseThrow();
        assertThat(proposal.status()).isEqualTo(ProposalStatus.PENDING_APPROVAL);
        assertThat(proposal.validationOk()).isTrue();
        assertThat(proposal.evidenceBundle()).contains(REFUND_NOTE).contains("class pack back to my card");
        assertThat(investigator.calls()).isEqualTo(1);
        mvc.perform(get(proposalUrl).with(user("checker").roles("CHECKER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Approve")));

        // 9. The maker cannot approve their own proposal; the checker can. Approval re-runs the close.
        mvc.perform(post(proposalUrl + "/approve").with(user("maker").roles("MAKER")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post(proposalUrl + "/approve").with(user("checker").roles("CHECKER")).with(csrf()))
                .andExpect(redirectedUrl(proposalUrl))
                .andExpect(flash().attribute("notice", containsString("Approved")));
        assertThat(proposals.findById(proposalId).orElseThrow().status()).isEqualTo(ProposalStatus.APPROVED);
        assertThat(breaks.findById(found.id()).orElseThrow().status()).isEqualTo(BreakStatus.RESOLVED);
        assertThat(balanceOf(a)).isEqualTo(4_750);
        CloseRuns.CloseRun afterApproval = closeRuns.latest().orElseThrow();
        assertThat(afterApproval.id()).isGreaterThan(broken.id());
        assertThat(afterApproval.status()).isEqualTo(CloseStatus.CLOSED);
        assertThat(afterApproval.unexplainedCents()).isZero();
        assertBooksAgreeWithPayPal();

        // 10. She cashes out the rest. Refunds are split across her captures, newest first.
        mvc.perform(post(page + "/cashout").param("amount", "all").with(csrf()))
                .andExpect(redirectedUrl(page))
                .andExpect(flash().attribute("notice", containsString("Refund in progress: 2 refunds")));
        assertThat(balanceOf(a)).isZero();
        assertThat(fakePayPal.refunds()).hasSize(1);
        mvc.perform(post("/cashouts/relay").with(user("maker").roles("MAKER")).with(csrf()))
                .andExpect(redirectedUrl("/cashouts"));
        assertThat(fakePayPal.refunds()).hasSize(3);
        assertThat(jdbc.sql("select state from cashouts").query(String.class).single()).isEqualTo("SETTLED");
        assertThat(jdbc.sql("select amount_cents from cashout_legs order by leg_no").query(Long.class).list())
                .containsExactly(3_000L, 1_750L);
        assertThat(fakePayPal.refundableCents(lost.paypalCaptureId())).isZero();
        assertThat(fakePayPal.refundableCents(first.paypalCaptureId())).isEqualTo(1_250);

        // 11. The final close is clean, and the day can move on.
        mvc.perform(post("/close/run").with(user("maker").roles("MAKER")).with(csrf()))
                .andExpect(flash().attribute("notice", containsString("Nothing is unexplained")));
        assertThat(closeRuns.latest().orElseThrow().status()).isEqualTo(CloseStatus.CLOSED);
        assertBooksAgreeWithPayPal();
        mvc.perform(post("/close/advance").with(user("maker").roles("MAKER")).with(csrf()))
                .andExpect(flash().attribute("notice", containsString("The business date is now")));

        for (String staffPage : List.of("/", "/pos", "/topups", "/cashouts", "/close", "/proposals", "/ledger",
                "/admin/faults")) {
            mvc.perform(get(staffPage).with(user("maker").roles("MAKER"))).andExpect(status().isOk());
        }
        mvc.perform(get(page)).andExpect(status().isOk()).andExpect(content().string(containsString("$0.00")));
    }

    private long journalEntryCount() {
        return jdbc.sql("select count(*) from journal_entries").query(Long.class).single();
    }

    private void assertBooksAgreeWithPayPal() {
        assertThat(ledger.unbalancedEntryIds()).isEmpty();
        long owed = jdbc.sql("select coalesce(sum(balance_cents), 0) from customer_balances")
                .query(Long.class).single();
        assertThat(ledger.balanceOf(Account.CUSTOMER_PREPAID)).isEqualTo(owed);
        long captured = fakePayPal.captures().stream().mapToLong(CaptureSnapshot::netCents).sum();
        long refunded = fakePayPal.refunds().stream()
                .mapToLong(r -> r.netCents() != null ? r.netCents() : r.amountCents()).sum();
        assertThat(ledger.balanceOf(Account.PAYPAL_CASH)).isEqualTo(captured - refunded);
        assertThat(ledger.balanceOf(Account.IN_DOUBT_RECEIVABLE)).isZero();
        assertThat(ledger.balanceOf(Account.CASHOUT_CLEARING)).isZero();
    }
}
