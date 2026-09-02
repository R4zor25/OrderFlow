package com.orderflow.catalog_pricing_service;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfiguration.class)
class CatalogSchemaJpaTest {

	private final JdbcTemplate jdbcTemplate;
	private final DataSource dataSource;

	@Autowired
	CatalogSchemaJpaTest(JdbcTemplate jdbcTemplate, DataSource dataSource) {
		this.jdbcTemplate = jdbcTemplate;
		this.dataSource = dataSource;
	}

	@Test
	void appliesFlywayMigrationsToPostgres() throws Exception {
		try (var connection = dataSource.getConnection()) {
			assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
		}
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM catalog.flyway_schema_history WHERE version IN ('1', '2') AND success", Long.class))
			.isEqualTo(2L);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM catalog.product", Long.class))
			.isEqualTo(20L);
	}

}
