package dev.backed.cashout;

import dev.backed.ledger.Account;
import dev.backed.ledger.EntryKind;
import dev.backed.ledger.JournalDraft;
import dev.backed.paypal.CaptureResult;
import dev.backed.pos.SpendService;
import dev.backed.support.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static dev.backed.ledger.JournalLineDraft.credit;
import static dev.backed.ledger.JournalLineDraft.debit;
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

@AutoConfigureMockMvc
class CashoutWebTest extends AbstractDbTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    CashoutQueries queries;

    @Test
    void customerCashesOutAndStaffRunsTheRelay() throws Exception {
        long a = customerA().id();
        capturedTopup(a, 2_500);

        mvc.perform(post("/c/" + TOKEN_A + "/cashout").param("amount", "10").with(csrf()))
                .andExpect(redirectedUrl("/c/" + TOKEN_A))
                .andExpect(flash().attribute("notice", containsString("Refund in progress")));
        assertThat(balanceOf(a)).isEqualTo(1_500);
        UUID cashoutId = queries.recent(1).getFirst().id();

        mvc.perform(get("/cashouts").with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(CashoutService.legRequestId(cashoutId, 1))))
                .andExpect(content().string(containsString("Run relay now")))
                .andExpect(content().string(containsString("Pending")));

        mvc.perform(post("/cashouts/relay").with(user("maker").roles("MAKER")).with(csrf()))
                .andExpect(redirectedUrl("/cashouts"))
                .andExpect(flash().attribute("notice", containsString("1 refunded")));
        assertThat(queries.legsOf(cashoutId).getFirst().state()).isEqualTo("DONE");

        mvc.perform(get("/cashouts").with(user("checker").roles("CHECKER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(fakePayPal.refunds().getFirst().refundId())))
                .andExpect(content().string(containsString("Settled")));
    }

    @Test
    void blankAmountCashesOutTheWholeBalance() throws Exception {
        long a = customerA().id();
        capturedTopup(a, 1_234);

        mvc.perform(post("/c/" + TOKEN_A + "/cashout").param("amount", "").with(csrf()))
                .andExpect(flash().attribute("notice", containsString("$12.34")));
        assertThat(balanceOf(a)).isZero();
    }

    @Test
    void refusalAndBadInputAreShownAsErrors() throws Exception {
        capturedTopup(customerA().id(), 500);

        mvc.perform(post("/c/" + TOKEN_A + "/cashout").param("amount", "50").with(csrf()))
                .andExpect(redirectedUrl("/c/" + TOKEN_A))
                .andExpect(flash().attribute("error", containsString("Nothing was taken")));
        mvc.perform(post("/c/" + TOKEN_A + "/cashout").param("amount", "abc").with(csrf()))
                .andExpect(flash().attributeExists("error"));
        mvc.perform(post("/c/not-a-token/cashout").param("amount", "1").with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(balanceOf(customerA().id())).isEqualTo(500);
    }

    @Test
    void cashoutPagesAreForMakersAndCheckers() throws Exception {
        mvc.perform(get("/cashouts").with(user("clerk").roles("CLERK"))).andExpect(status().isForbidden());
        mvc.perform(post("/cashouts/relay").with(user("clerk").roles("CLERK")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/cashouts")).andExpect(status().is3xxRedirection());
    }

    private void capturedTopup(long customerId, long cents) {
        UUID id = UUID.randomUUID();
        CaptureResult capture = fakePayPal.captureCard("topup-" + id, cents, id.toString(), null);
        tx.executeWithoutResult(s -> {
            jdbc.sql("""
                            insert into topups (id, customer_id, amount_cents, state, request_id, paypal_order_id,
                                                paypal_capture_id, fee_cents, net_cents, business_date, created_by)
                            values (:id, :customerId, :amount, 'CAPTURED', :requestId, :orderId, :captureId, :fee, :net,
                                    (select business_date from business_day), 'test')
                            """)
                    .param("id", id)
                    .param("customerId", customerId)
                    .param("amount", cents)
                    .param("requestId", "topup-" + id)
                    .param("orderId", capture.orderId())
                    .param("captureId", capture.captureId())
                    .param("fee", capture.feeCents())
                    .param("net", capture.netCents())
                    .update();
            journal.postAndApply(JournalDraft.of(EntryKind.TOPUP_CAPTURED, "topup", id.toString(), "test top-up",
                    "test",
                    debit(Account.PAYPAL_CASH, capture.netCents()),
                    debit(Account.PAYPAL_FEES, capture.feeCents()),
                    credit(Account.CUSTOMER_PREPAID, customerId, cents)));
        });
    }
}
