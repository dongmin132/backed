package dev.backed.paypal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * The real PayPal wiring, active when backed.paypal.mode is sandbox (the default). The only
 * PayPalGateway bean is the fault-injecting wrapper around the SDK adapter. Tests use mode=fake.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "backed.paypal.mode", havingValue = "sandbox", matchIfMissing = true)
public class PayPalSandboxConfig {

    @Bean
    @Primary
    public PayPalGateway payPalGateway(
            FaultSwitch faults,
            @Value("${backed.paypal.client-id:}") String clientId,
            @Value("${backed.paypal.client-secret:}") String clientSecret,
            @Value("${backed.paypal.connect-timeout:5s}") Duration connectTimeout,
            @Value("${backed.paypal.read-timeout:25s}") Duration readTimeout,
            @Value("${backed.paypal.write-timeout:10s}") Duration writeTimeout,
            @Value("${backed.paypal.call-timeout:45s}") Duration callTimeout,
            @Value("${backed.paypal.billing.address-line-1:2211 N First Street}") String line1,
            @Value("${backed.paypal.billing.city:San Jose}") String city,
            @Value("${backed.paypal.billing.state:CA}") String state,
            @Value("${backed.paypal.billing.postal-code:95131}") String postalCode,
            @Value("${backed.paypal.billing.country-code:US}") String countryCode) {
        SdkPayPalGateway sdk = new SdkPayPalGateway(clientId, clientSecret,
                new SdkPayPalGateway.BillingAddress(line1, city, state, postalCode, countryCode),
                new SdkPayPalGateway.Timeouts(connectTimeout, readTimeout, writeTimeout, callTimeout));
        return new FaultInjectingPayPalGateway(sdk, faults);
    }
}
