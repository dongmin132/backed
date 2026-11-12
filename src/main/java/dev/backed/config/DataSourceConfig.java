package dev.backed.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Uses the database named by BACKED_DB_URL (with BACKED_DB_USER and BACKED_DB_PASSWORD) when it
 * is set, otherwise starts the embedded PostgreSQL. Flyway's auto-configuration migrates whichever
 * one this bean returns.
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource(
            @Value("${BACKED_DB_URL:}") String url,
            @Value("${BACKED_DB_USER:}") String user,
            @Value("${BACKED_DB_PASSWORD:}") String password,
            @Value("${backed.db.max-pool-size:24}") int maxPoolSize) {
        HikariConfig config = new HikariConfig();
        if (url.isBlank()) {
            config.setJdbcUrl(EmbeddedDatabase.jdbcUrl());
            config.setUsername(EmbeddedDatabase.user());
            log.info("BACKED_DB_URL is not set: using embedded PostgreSQL");
        } else {
            config.setJdbcUrl(url);
            config.setUsername(user);
            config.setPassword(password);
            log.info("Using external PostgreSQL from BACKED_DB_URL");
        }
        config.setPoolName("backed");
        config.setMaximumPoolSize(maxPoolSize);
        // Cached test contexts each own a pool; keep idle connections low so they do not pile up.
        config.setMinimumIdle(2);
        return new HikariDataSource(config);
    }
}
