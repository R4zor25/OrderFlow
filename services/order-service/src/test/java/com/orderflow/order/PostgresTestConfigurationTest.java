package com.orderflow.order;

import java.sql.DriverManager;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresTestConfigurationTest {

    @Test
    void givesEveryContextItsOwnDatabase() throws Exception {
        var configuration = new PostgresTestConfiguration();
        var first = configuration.jdbcConnectionDetails();
        var second = configuration.jdbcConnectionDetails();

        assertThat(first.getJdbcUrl()).isNotEqualTo(second.getJdbcUrl());
        assertThat(hostAndPort(first.getJdbcUrl())).isEqualTo(hostAndPort(second.getJdbcUrl()));
        for (var details : List.of(first, second)) {
            try (var connection = DriverManager.getConnection(details.getJdbcUrl(), details.getUsername(),
                    details.getPassword())) {
                assertThat(connection.isValid(2)).isTrue();
            }
        }
    }

    private static String hostAndPort(String jdbcUrl) {
        return jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/'));
    }
}
