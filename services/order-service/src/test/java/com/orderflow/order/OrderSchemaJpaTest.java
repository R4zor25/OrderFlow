package com.orderflow.order;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfiguration.class)
class OrderSchemaJpaTest {

    private final JdbcTemplate jdbc;

    @Autowired
    OrderSchemaJpaTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Test
    void createsFiveOrderTablesAndSeedsFourCustomers() {
        assertThat(jdbc.queryForObject("""
            select count(*) from information_schema.tables
            where table_schema = 'orders' and table_name in
                ('customer', 'orders', 'order_line', 'outbox_event', 'processed_event')
            """, Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from orders.customer", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from orders.customer where type = 'B2C'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from orders.customer where type = 'B2B'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            select count(*) from information_schema.columns
            where table_schema = 'orders' and table_name = 'orders'
              and column_name = 'rejection_reason' and is_nullable = 'YES'
            """, Integer.class)).isEqualTo(1);
    }
    @Test
    void rejectedOrderRequiresReasonAtDatabaseBoundary() {
        UUID id = UUID.randomUUID();
        UUID customerId = UUID.fromString("33333333-3333-4333-8333-333333333333");
        jdbc.update("""
            insert into orders.orders
                (id, customer_id, status, currency, total_net, total_vat, total_gross,
                 created_at, updated_at, version)
            values (?, ?, 'PENDING', 'EUR', 0, 0, 0, now(), now(), 0)
            """, id, customerId);

        assertThatThrownBy(() -> jdbc.update(
            "update orders.orders set status = 'REJECTED' where id = ?", id
        )).isInstanceOf(DataIntegrityViolationException.class);
    }
}
