package com.orderflow.order;

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
    }
}
