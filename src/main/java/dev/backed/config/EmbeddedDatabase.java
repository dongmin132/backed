package dev.backed.config;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * One embedded PostgreSQL server per JVM. Every Spring context in the JVM (the app, or each
 * cached test context) connects to the same server, so the schema is migrated once and test
 * contexts never fight over ports.
 */
public final class EmbeddedDatabase {

    private static EmbeddedPostgres instance;

    private EmbeddedDatabase() {
    }

    public static synchronized String jdbcUrl() {
        if (instance == null) {
            instance = start();
        }
        return instance.getJdbcUrl("postgres", "postgres");
    }

    public static String user() {
        return "postgres";
    }

    private static EmbeddedPostgres start() {
        try {
            EmbeddedPostgres pg = EmbeddedPostgres.builder()
                    .setServerConfig("max_connections", "300")
                    .setServerConfig("timezone", "UTC")
                    .start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    pg.close();
                } catch (IOException ignored) {
                    // the JVM is exiting; nothing useful to do
                }
            }, "embedded-postgres-shutdown"));
            return pg;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
        }
    }
}
