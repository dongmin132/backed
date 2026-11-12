package dev.backed.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** @Scheduled methods run only when backed.scheduling.enabled is true (the default). Tests turn it off. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "backed.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
