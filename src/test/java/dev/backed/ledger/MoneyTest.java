package dev.backed.ledger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void parsesDollarsCentsAndThousandsSeparators() {
        assertThat(Money.parseCents("12")).isEqualTo(1_200);
        assertThat(Money.parseCents("12.5")).isEqualTo(1_250);
        assertThat(Money.parseCents(" $12.50 ")).isEqualTo(1_250);
        assertThat(Money.parseCents("1,234.50")).isEqualTo(123_450);
        assertThat(Money.parseCents("$1,000,000")).isEqualTo(100_000_000);
    }

    @Test
    void aDecimalCommaIsRejectedInsteadOfMultiplyingTheAmountByOneHundred() {
        for (String text : new String[]{"4,50", "1,2", "12,", ",50", "1,23,456", "1,2345", "12.505", "-1", "0", "0.00"}) {
            assertThatThrownBy(() -> Money.parseCents(text)).as(text).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
