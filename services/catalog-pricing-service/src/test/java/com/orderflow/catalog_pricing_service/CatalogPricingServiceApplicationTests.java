package com.orderflow.catalog_pricing_service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class CatalogPricingServiceApplicationTests {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-bookworm");

	private final DataSource dataSource;

	@Autowired
	CatalogPricingServiceApplicationTests(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Test
	void connectsToPostgres() throws Exception {
		try (Connection connection = dataSource.getConnection();
			 Statement statement = connection.createStatement();
			 ResultSet result = statement.executeQuery("SELECT 1")) {
			assertThat(result.next()).isTrue();
			assertThat(result.getInt(1)).isEqualTo(1);
		}
	}

	@Test
	void appliesCatalogMigrationsAndSeedData() throws Exception {
		try (Connection connection = dataSource.getConnection();
			 Statement statement = connection.createStatement()) {
			assertThat(count(statement, "SELECT count(*) FROM catalog.flyway_schema_history WHERE version IN ('1', '2') AND success"))
				.isEqualTo(2);
			assertThat(count(statement, "SELECT count(*) FROM catalog.product"))
				.isEqualTo(20);
			assertThat(count(statement, "SELECT count(*) FROM catalog.customer_group"))
				.isEqualTo(3);
			assertThat(count(statement, "SELECT count(*) FROM catalog.price_list_entry"))
				.isEqualTo(12);
		}
	}

	@Test
	void rejectsDuplicateSkuAtDatabaseBoundary() throws Exception {
		try (Connection connection = dataSource.getConnection();
			 Statement statement = connection.createStatement()) {
			connection.setAutoCommit(false);
			assertThatThrownBy(() -> statement.executeUpdate("""
				INSERT INTO catalog.product
				    (id, sku, name, category, list_price_net, vat_rate, active, created_at, updated_at)
				SELECT '00000000-0000-4000-8000-000000009999', sku, name, category,
				       list_price_net, vat_rate, active, created_at, updated_at
				FROM catalog.product WHERE sku = 'CHAIR-001'
				"""))
				.isInstanceOf(SQLException.class)
				.satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23505"));
			connection.rollback();
		}
	}

	private long count(Statement statement, String query) throws SQLException {
		try (ResultSet result = statement.executeQuery(query)) {
			assertThat(result.next()).isTrue();
			return result.getLong(1);
		}
	}

}
