package dev.backed.investigate;

import dev.backed.close.Break;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static dev.backed.investigate.Treatment.BALANCE_RETURNED;
import static dev.backed.investigate.Treatment.GOODWILL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProposalWebTest extends InvestigateTestBase {

    @Test
    void makerInvestigatesAndCheckerApproves() throws Exception {
        Break b = scenarios.goodwill(customerA().id());
        investigator.respond(output(GOODWILL, BALANCE_RETURNED, Scenarios.COLD_COFFEE_NOTE));

        mvc.perform(get("/proposals").with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/breaks/" + b.id() + "/investigate")));

        MvcResult created = mvc.perform(post("/breaks/" + b.id() + "/investigate")
                        .with(user("maker").roles("MAKER")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String location = created.getResponse().getRedirectedUrl();
        assertThat(location).startsWith("/proposals/");

        mvc.perform(get(location).with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("You created this proposal")))
                .andExpect(content().string(containsString("<mark>" + Scenarios.COLD_COFFEE_NOTE + "</mark>")))
                .andExpect(content().string(containsString("Not chosen: <span>Balance returned</span>")))
                .andExpect(content().string(not(containsString("Approve and post"))));

        mvc.perform(get(location).with(user("checker").roles("CHECKER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Approve and post")))
                .andExpect(content().string(containsString("Dismiss")))
                .andExpect(content().string(not(containsString("You created this proposal"))));

        mvc.perform(post(location + "/approve").with(user("maker").roles("MAKER")).with(csrf()))
                .andExpect(status().isForbidden());

        mvc.perform(post(location + "/approve").with(user("checker").roles("CHECKER")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("notice", containsString("Approved. Journal entry #")));

        mvc.perform(get("/proposals").with(user("checker").roles("CHECKER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Approved")));
        assertThat(correctionEntries(b.id())).isEqualTo(1);
    }

    @Test
    void onlyAMakerCanRequestAnInvestigation() throws Exception {
        Break b = scenarios.goodwill(customerA().id());
        investigator.respond(output(GOODWILL, BALANCE_RETURNED, Scenarios.COLD_COFFEE_NOTE));

        mvc.perform(post("/breaks/" + b.id() + "/investigate").with(user("checker").roles("CHECKER")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/breaks/" + b.id() + "/investigate").with(user("clerk").roles("CLERK")).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(investigator.calls()).isZero();
    }

    @Test
    void pagesSayWhenTheInvestigatorIsNotConfigured() throws Exception {
        Break b = scenarios.goodwill(customerA().id());
        investigator.setConfigured(false);

        mvc.perform(get("/proposals").with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("AI investigator is not configured")))
                .andExpect(content().string(not(containsString("/breaks/" + b.id() + "/investigate"))));

        mvc.perform(post("/breaks/" + b.id() + "/investigate").with(user("maker").roles("MAKER")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "AI investigator is not configured"));
    }

    @Test
    void rejectedProposalShowsTheValidatorReasons() throws Exception {
        Break b = scenarios.goodwill(customerA().id());
        investigator.respond(output(GOODWILL, BALANCE_RETURNED, "a sentence nobody wrote"));
        long id = service.investigate(b.id(), "maker");

        mvc.perform(get("/proposals/" + id).with(user("checker").roles("CHECKER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Rejected by the validator")))
                .andExpect(content().string(containsString("a sentence nobody wrote")))
                .andExpect(content().string(not(containsString("Approve and post"))));
    }
}
