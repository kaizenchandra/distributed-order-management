package dev.oms.testsupport;

import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Test-only storage: an isolated container by default, or a new disposable local database.
 */
public final class TestPostgres {
    private final String adminUrl = System.getenv("OMS_TEST_POSTGRES_ADMIN_URL");
    private final String database = "oms_test_" + UUID.randomUUID().toString().replace("-", "");
    private PostgreSQLContainer<?> container;
    private boolean created;

    public void start() {
        if (adminUrl == null) {
            container =
                    new PostgreSQLContainer<>("postgres:17.6")
                            .withTmpFs(Map.of("/var/lib/postgresql/data", "rw"))
                            .withStartupTimeout(Duration.ofMinutes(3));
            container.start();
            return;
        }
        // Never accept a service database or remote financial system as a test clean target.
        if (!adminUrl.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/postgres")
                || !"1".equals(System.getenv("OMS_TEST_ALLOW_DATABASE_CREATION")))
            throw new IllegalArgumentException(
                    "External tests require explicit local database creation opt-in");
        try (var connection = admin();
             var statement = connection.createStatement()) {
            statement.execute("create database " + database);
            created = true;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create isolated test database", e);
        }
    }

    private Connection admin() throws SQLException {
        var properties = new Properties();
        properties.setProperty(
                "user", System.getenv().getOrDefault("OMS_TEST_POSTGRES_ADMIN_USER", "postgres"));
        properties.setProperty("password", System.getenv("OMS_TEST_POSTGRES_ADMIN_PASSWORD"));
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "120");
        return DriverManager.getConnection(adminUrl, properties);
    }

    public String getJdbcUrl() {
        if (container != null) return container.getJdbcUrl();
        if (!created) throw new IllegalStateException("Test database was not created");
        return adminUrl.substring(0, adminUrl.length() - "postgres".length()) + database;
    }

    public String getUsername() {
        return container != null
                ? container.getUsername()
                : System.getenv().getOrDefault("OMS_TEST_POSTGRES_ADMIN_USER", "postgres");
    }

    public String getPassword() {
        return container != null
                ? container.getPassword()
                : System.getenv("OMS_TEST_POSTGRES_ADMIN_PASSWORD");
    }

    public void stop() {
        if (container != null) {
            container.stop();
            return;
        }
        if (!created) return;
        try (var connection = admin();
             var statement = connection.createStatement()) {
            statement.execute("drop database " + database + " with (force)");
            created = false;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot drop isolated test database " + database, e);
        }
    }
}
