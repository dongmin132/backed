package dev.backed;

import dev.backed.investigate.Investigator;
import dev.backed.support.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class BackedApplicationTests extends AbstractDbTest {

    @Autowired
    Investigator investigator;

    @Test
    void contextLoadsAndSeedIsPresent() {
        assertThat(jdbc.sql("select count(*) from accounts").query(Integer.class).single()).isEqualTo(8);
        assertThat(customers.all()).hasSize(3);
        assertThat(balanceOf(customerA().id())).isZero();
    }

    @Test
    void noAiKeyReachesTheTestsSoNothingCallsAModel() {
        // Holds even with OPENAI_API_KEY or ANTHROPIC_API_KEY exported or written in .env.
        assertThat(investigator.configured()).isFalse();
    }
}
