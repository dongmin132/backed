package dev.backed;

import dev.backed.support.AbstractDbTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BackedApplicationTests extends AbstractDbTest {

    @Test
    void contextLoadsAndSeedIsPresent() {
        assertThat(jdbc.sql("select count(*) from accounts").query(Integer.class).single()).isEqualTo(8);
        assertThat(customers.all()).hasSize(3);
        assertThat(balanceOf(customerA().id())).isZero();
    }
}
