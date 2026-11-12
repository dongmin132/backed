package dev.backed.support;

import dev.backed.paypal.FakePayPalGateway;
import dev.backed.paypal.FaultInjectingPayPalGateway;
import dev.backed.paypal.FaultSwitch;
import dev.backed.paypal.PayPalGateway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test wiring for PayPal: the injected PayPalGateway is the fault-injecting wrapper around the
 * in-memory fake, the same shape the app uses around the SDK adapter.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakePayPalConfig {

    @Bean
    public FakePayPalGateway fakePayPalGateway() {
        return new FakePayPalGateway();
    }

    @Bean
    @Primary
    public PayPalGateway payPalGateway(FakePayPalGateway fake, FaultSwitch faults) {
        return new FaultInjectingPayPalGateway(fake, faults);
    }
}
