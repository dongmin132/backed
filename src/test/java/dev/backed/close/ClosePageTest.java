package dev.backed.close;

import dev.backed.support.AbstractDbTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ClosePageTest extends AbstractDbTest {

    @Autowired
    MockMvc mvc;

    CloseFixtures fx;

    @BeforeEach
    void fixtures() {
        fx = new CloseFixtures(jdbc, tx, journal, fakePayPal);
    }

    @Test
    void pageBeforeAnyRun() throws Exception {
        mvc.perform(get("/close").with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Run close")))
                .andExpect(content().string(containsString("No close has run")))
                .andExpect(content().string(not(containsString("Advance to next business day"))));
    }

    @Test
    void brokenRunShowsChecksAndTheBreakWithItsNote() throws Exception {
        CloseFixtures.Topup topup = fx.capturedTopup(customerA().id(), 5_000);
        fakePayPal.refundOutsideLedger(topup.capture().captureId(), 2_000, "Goodwill for the late order");

        mvc.perform(post("/close/run").with(user("maker").roles("MAKER")).with(csrf()))
                .andExpect(redirectedUrl("/close"))
                .andExpect(flash().attribute("error", containsString("1 open break")));

        long breakId = jdbc.sql("select id from breaks").query(Long.class).single();
        mvc.perform(get("/close").with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Broken")))
                .andExpect(content().string(containsString("Ledger cash matches PayPal")))
                .andExpect(content().string(containsString("Unexplained $20.00")))
                .andExpect(content().string(containsString("Goodwill for the late order")))
                .andExpect(content().string(containsString("Demo Customer A")))
                .andExpect(content().string(containsString("/breaks/" + breakId + "/investigate")))
                .andExpect(content().string(not(containsString("Advance to next business day"))));
        assertThat(jdbc.sql("select run_by from day_closes").query(String.class).single()).isEqualTo("maker");
    }

    @Test
    void closedRunOffersAdvance() throws Exception {
        fx.capturedTopup(customerB().id(), 1_000);
        LocalDate today = jdbc.sql("select business_date from business_day").query(LocalDate.class).single();

        mvc.perform(post("/close/advance").with(user("maker").roles("MAKER")).with(csrf()))
                .andExpect(flash().attribute("error", containsString("Run the close")));

        mvc.perform(post("/close/run").with(user("checker").roles("CHECKER")).with(csrf()))
                .andExpect(flash().attribute("notice", containsString("closed")));
        mvc.perform(get("/close").with(user("maker").roles("MAKER")))
                .andExpect(content().string(containsString("Advance to next business day")))
                .andExpect(content().string(containsString("Pass")));

        mvc.perform(post("/close/advance").with(user("maker").roles("MAKER")).with(csrf()))
                .andExpect(redirectedUrl("/close"))
                .andExpect(flash().attribute("notice", containsString(today.plusDays(1).toString())));
        assertThat(jdbc.sql("select business_date from business_day").query(LocalDate.class).single())
                .isEqualTo(today.plusDays(1));
    }

    @Test
    void clerkCannotRunTheClose() throws Exception {
        mvc.perform(post("/close/run").with(user("clerk").roles("CLERK")).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(jdbc.sql("select count(*) from day_closes").query(Integer.class).single()).isZero();
    }
}
