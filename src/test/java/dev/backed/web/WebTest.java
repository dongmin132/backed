package dev.backed.web;

import dev.backed.support.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@AutoConfigureMockMvc
class WebTest extends AbstractDbTest {

    @Autowired
    MockMvc mvc;

    @Test
    void loginPageListsDemoUsers() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("checker")))
                .andExpect(content().string(containsString("demo")));
    }

    @Test
    void staffPagesNeedLogin() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        mvc.perform(get("/ledger")).andExpect(status().is3xxRedirection());
    }

    @Test
    void formLoginWithDemoPassword() throws Exception {
        mvc.perform(post("/login").param("username", "maker").param("password", "demo").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
        mvc.perform(post("/login").param("username", "maker").param("password", "wrong").with(csrf()))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void dashboardShowsCustomersAndTotals() throws Exception {
        fund(customerA().id(), 1_234);
        mvc.perform(get("/").with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Demo Customer A")))
                .andExpect(content().string(containsString("$12.34")))
                .andExpect(content().string(containsString("/c/" + TOKEN_A)))
                .andExpect(content().string(containsString("Signed in as")));
    }

    @Test
    void ledgerShowsEntries() throws Exception {
        fund(customerB().id(), 500);
        mvc.perform(get("/ledger").with(user("checker").roles("CHECKER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("CUSTOMER_PREPAID")))
                .andExpect(content().string(containsString("Demo Customer B")))
                .andExpect(content().string(containsString("$5.00")));
    }

    @Test
    void customerPageIsPublicAndUnknownTokenIs404() throws Exception {
        fund(customerA().id(), 2_500);
        mvc.perform(get("/c/" + TOKEN_A))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("$25.00")))
                .andExpect(content().string(containsString("/c/" + TOKEN_A + "/topup")))
                .andExpect(content().string(containsString("/c/" + TOKEN_A + "/cashout")))
                .andExpect(content().string(not(containsString("Proposals"))));
        mvc.perform(get("/c/not-a-token")).andExpect(status().isNotFound());
    }

    @Test
    void customerCanMessageTheShop() throws Exception {
        mvc.perform(post("/c/" + TOKEN_A + "/message").param("body", "I was refunded twice?").with(csrf()))
                .andExpect(redirectedUrl("/c/" + TOKEN_A))
                .andExpect(flash().attributeExists("notice"));
        assertThat(jdbc.sql("select body from customer_messages").query(String.class).single())
                .isEqualTo("I was refunded twice?");

        mvc.perform(post("/c/" + TOKEN_A + "/message").param("body", "   ").with(csrf()))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    void formsNeedCsrfButWebhooksDoNot() throws Exception {
        mvc.perform(post("/c/" + TOKEN_A + "/message").param("body", "hi")).andExpect(status().isForbidden());
        // No webhook controller exists yet; a missing CSRF token must not be the reason it fails.
        int webhookStatus = mvc.perform(post("/webhooks/paypal").content("{}").contentType("application/json"))
                .andReturn().getResponse().getStatus();
        assertThat(webhookStatus).isNotEqualTo(403);
    }

    @Test
    void roleRules() throws Exception {
        mvc.perform(get("/pos").with(user("checker").roles("CHECKER"))).andExpect(status().isForbidden());
        mvc.perform(get("/close").with(user("clerk").roles("CLERK"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/faults").with(user("clerk").roles("CLERK"))).andExpect(status().isForbidden());
        mvc.perform(post("/proposals/1/approve").with(user("maker").roles("MAKER")).with(csrf()))
                .andExpect(status().isForbidden());
        // Allowed by security; 404 because those modules are not built yet.
        mvc.perform(get("/pos").with(user("clerk").roles("CLERK"))).andExpect(status().isNotFound());
        mvc.perform(post("/proposals/1/approve").with(user("checker").roles("CHECKER")).with(csrf()))
                .andExpect(status().isNotFound());
    }
}
