package dev.backed.pos;

import dev.backed.support.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

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
class PosWebTest extends AbstractDbTest {

    @Autowired
    MockMvc mvc;

    @Test
    void clerkChargesABalanceAtTheCounter() throws Exception {
        long a = customerA().id();
        fund(a, 1_000);

        mvc.perform(get("/pos").with(user("clerk").roles("CLERK")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Demo Customer A ($10.00)")))
                .andExpect(content().string(containsString("Charge")));

        mvc.perform(post("/pos").with(user("clerk").roles("CLERK")).with(csrf())
                        .param("customerId", String.valueOf(a)).param("amount", "4.50").param("memo", "Latte")
                        .param("requestId", "spend-web-1"))
                .andExpect(redirectedUrl("/pos?customerId=" + a))
                .andExpect(flash().attribute("notice", containsString("Charged $4.50")));
        assertThat(balanceOf(a)).isEqualTo(550);

        mvc.perform(post("/pos").with(user("clerk").roles("CLERK")).with(csrf())
                        .param("customerId", String.valueOf(a)).param("amount", "4.50").param("memo", "Latte")
                        .param("requestId", "spend-web-1"))
                .andExpect(flash().attribute("notice", containsString("already recorded")));
        assertThat(balanceOf(a)).isEqualTo(550);

        mvc.perform(get("/pos").with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Latte")))
                .andExpect(content().string(containsString("$4.50")));
    }

    @Test
    void overdraftAndBadInputAreRefused() throws Exception {
        long a = customerA().id();
        fund(a, 300);

        mvc.perform(post("/pos").with(user("clerk").roles("CLERK")).with(csrf())
                        .param("customerId", String.valueOf(a)).param("amount", "3.01"))
                .andExpect(flash().attribute("error", containsString("Refused")));
        mvc.perform(post("/pos").with(user("clerk").roles("CLERK")).with(csrf())
                        .param("customerId", String.valueOf(a)).param("amount", "lots"))
                .andExpect(flash().attributeExists("error"));
        mvc.perform(post("/pos").with(user("clerk").roles("CLERK")).with(csrf())
                        .param("customerId", "999").param("amount", "1"))
                .andExpect(flash().attribute("error", containsString("Pick a customer")));
        assertThat(balanceOf(a)).isEqualTo(300);
    }
}
