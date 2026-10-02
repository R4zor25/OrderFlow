package com.orderflow.order;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

    // One container per test run; every Spring context gets its own database, because cached contexts keep their
    // background jobs (outbox relay, retention, scans) running and must not see another test's rows.
    private static final PostgreSQLContainer CONTAINER = start();

    @Bean
    JdbcConnectionDetails jdbcConnectionDetails() {
        String database = "test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(CONTAINER.getJdbcUrl(), CONTAINER.getUsername(),
                CONTAINER.getPassword()); var statement = connection.createStatement()) {
            statement.execute("create database " + database);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot create test database " + database, exception);
        }
        String url = "jdbc:postgresql://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(5432) + "/" + database;
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return CONTAINER.getUsername();
            }

            @Override
            public String getPassword() {
                return CONTAINER.getPassword();
            }

            @Override
            public String getJdbcUrl() {
                return url;
            }
        };
    }

    private static PostgreSQLContainer start() {
        // Each cached context keeps a full Hikari pool (10) open; the context cache holds up to 32 of them.
        var container = new PostgreSQLContainer("postgres:17.11-bookworm")
            .withCommand("postgres", "-c", "max_connections=500");
        container.start();
        return container;
    }
}
