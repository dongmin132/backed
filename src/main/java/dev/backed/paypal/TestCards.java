package dev.backed.paypal;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Sandbox test cards by reference (customers.card_ref). Configured as
 * backed.test-cards.&lt;ref&gt;.number / .expiry / .security-code / .name.
 */
@Component
public class TestCards {

    private final Environment env;

    public TestCards(Environment env) {
        this.env = env;
    }

    public TestCard get(String ref) {
        String prefix = "backed.test-cards." + ref + ".";
        String number = env.getProperty(prefix + "number");
        if (number == null || number.isBlank()) {
            throw new IllegalArgumentException("No test card configured for " + ref);
        }
        return new TestCard(
                number,
                env.getProperty(prefix + "expiry", "2030-12"),
                env.getProperty(prefix + "security-code", "123"),
                env.getProperty(prefix + "name", "Sandbox Card"));
    }
}
