package dev.backed.topup;

import dev.backed.paypal.FaultSwitch.Fault;
import dev.backed.support.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class TopupWebTest extends AbstractDbTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    Topups topups;

    @Test
    void customerTopUpCreditsAndSaysSo() throws Exception {
        mvc.perform(post("/c/" + TOKEN_A + "/topup").param("amount", "$25.00").with(csrf()))
                .andExpect(redirectedUrl("/c/" + TOKEN_A))
                .andExpect(flash().attribute("notice", containsString("Added $25.00")));
        assertThat(balanceOf(customerA().id())).isEqualTo(2_500);

        mvc.perform(get("/c/" + TOKEN_A))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("$25.00")));
    }

    @Test
    void lostResponseIsReportedAsProcessingAndASecondTopUpIsRefused() throws Exception {
        faults.arm(Fault.DROP_NEXT_CAPTURE_RESPONSE);
        mvc.perform(post("/c/" + TOKEN_A + "/topup").param("amount", "25").with(csrf()))
                .andExpect(flash().attribute("notice", containsString("Processing: PayPal has not confirmed")));
        assertThat(balanceOf(customerA().id())).isZero();

        mvc.perform(post("/c/" + TOKEN_A + "/topup").param("amount", "10").with(csrf()))
                .andExpect(flash().attribute("error", containsString("still waiting for PayPal")));
        assertThat(fakePayPal.captureCalls()).hasSize(1);
    }

    @Test
    void badAmountIsAnErrorAndNothingIsStored() throws Exception {
        mvc.perform(post("/c/" + TOKEN_A + "/topup").param("amount", "ten dollars").with(csrf()))
                .andExpect(flash().attributeExists("error"));
        mvc.perform(post("/c/" + TOKEN_A + "/topup").param("amount", "0.50").with(csrf()))
                .andExpect(flash().attribute("error", containsString("between $1.00")));
        assertThat(jdbc.sql("select count(*) from topups").query(Long.class).single()).isZero();
        mvc.perform(post("/c/not-a-token/topup").param("amount", "5").with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void staffSeeInDoubtTopUpsAndResolveThem() throws Exception {
        faults.arm(Fault.DROP_NEXT_CAPTURE_RESPONSE);
        mvc.perform(post("/c/" + TOKEN_B + "/topup").param("amount", "12.00").with(csrf()));
        Topup t = topups.recent(1).get(0);
        assertThat(t.state()).isEqualTo(TopupState.IN_DOUBT);

        mvc.perform(get("/topups").with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("In doubt"),
                        containsString("Resolve"),
                        containsString(t.requestId()),
                        containsString("/topups/" + t.id() + "/resolve"))));

        mvc.perform(post("/topups/" + t.id() + "/resolve").with(user("maker").roles("MAKER")).with(csrf()))
                .andExpect(redirectedUrl("/topups"))
                .andExpect(flash().attribute("notice", containsString("credited once")));
        assertThat(balanceOf(customerB().id())).isEqualTo(1_200);

        mvc.perform(get("/topups").with(user("checker").roles("CHECKER")))
                .andExpect(content().string(containsString("Captured")));
        mvc.perform(get("/topups").with(user("clerk").roles("CLERK"))).andExpect(status().isForbidden());
    }

    @Test
    void faultSwitchesCanBeArmedAndDisarmed() throws Exception {
        mvc.perform(get("/admin/faults").with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString(Fault.DROP_NEXT_CAPTURE_RESPONSE.label()),
                        containsString(Fault.FAIL_NEXT_REFUND_WITH_5XX.label()),
                        containsString(Fault.DECLINE_NEXT_REFUND.label()),
                        containsString("outside the ledger"))));

        mvc.perform(post("/admin/faults").param("fault", "FAIL_NEXT_REFUND_WITH_5XX").param("action", "arm")
                        .with(user("maker").roles("MAKER")).with(csrf()))
                .andExpect(redirectedUrl("/admin/faults"))
                .andExpect(flash().attribute("notice", containsString("Armed")));
        assertThat(faults.isArmed(Fault.FAIL_NEXT_REFUND_WITH_5XX)).isTrue();

        mvc.perform(post("/admin/faults").param("fault", "FAIL_NEXT_REFUND_WITH_5XX").param("action", "disarm")
                        .with(user("maker").roles("MAKER")).with(csrf()))
                .andExpect(flash().attribute("notice", containsString("Disarmed")));
        assertThat(faults.isArmed(Fault.FAIL_NEXT_REFUND_WITH_5XX)).isFalse();
    }

    @Test
    void outsideRefundReachesPayPalButNotTheLedger() throws Exception {
        mvc.perform(post("/c/" + TOKEN_A + "/topup").param("amount", "25").with(csrf()));
        Topup t = topups.recent(1).get(0);
        long entries = jdbc.sql("select count(*) from journal_entries").query(Long.class).single();
        faults.arm(Fault.FAIL_NEXT_REFUND_WITH_5XX);

        mvc.perform(post("/admin/faults/outside-refund")
                        .param("topupId", t.id().toString()).param("amount", "5.00").param("note", "Sorry, $5 back")
                        .with(user("maker").roles("MAKER")).with(csrf()))
                .andExpect(redirectedUrl("/admin/faults"))
                .andExpect(flash().attribute("notice", containsString("Nothing was written to the ledger")));

        assertThat(fakePayPal.refundsOf(t.paypalCaptureId())).singleElement().satisfies(r -> {
            assertThat(r.amountCents()).isEqualTo(500);
            assertThat(r.noteToPayer()).isEqualTo("Sorry, $5 back");
        });
        assertThat(jdbc.sql("select count(*) from journal_entries").query(Long.class).single()).isEqualTo(entries);
        assertThat(balanceOf(customerA().id())).isEqualTo(2_500);
        assertThat(faults.isArmed(Fault.FAIL_NEXT_REFUND_WITH_5XX)).as("the outside refund bypasses faults").isTrue();

        mvc.perform(post("/admin/faults/outside-refund")
                        .param("topupId", t.id().toString()).param("amount", "100.00")
                        .with(user("maker").roles("MAKER")).with(csrf()))
                .andExpect(flash().attribute("error", containsString("REFUND_AMOUNT_EXCEEDED")));
        mvc.perform(get("/admin/faults").with(user("clerk").roles("CLERK"))).andExpect(status().isForbidden());
    }
}
