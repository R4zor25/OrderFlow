package com.orderflow.order;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.application.OrderAccess;
import com.orderflow.order.application.OrderQueryService;
import com.orderflow.order.domain.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector="
    + "com.orderflow.order.CapturingStatementInspector", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfiguration.class, OrderQueryService.class})
class OrderListQueryPlanTest {

    private static final UUID CUSTOMER = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private OrderQueryService queries;

    @Autowired
    private JdbcConnectionDetails database;

    @BeforeEach
    void captureFreshStatements() {
        CapturingStatementInspector.clear();
    }

    @Test
    void unfilteredListReadsTheCreatedIndexWithoutSorting() throws SQLException {
        queries.list(OrderAccess.admin(), null, null, 0, 10);

        assertEveryQueryUses("idx_orders_created");
    }

    @Test
    void customerListReadsTheCustomerIndexWithoutSorting() throws SQLException {
        queries.list(OrderAccess.admin(), CUSTOMER, null, 0, 10);

        assertEveryQueryUses("idx_orders_customer_created");
    }

    @Test
    void statusListReadsTheStatusIndexWithoutSorting() throws SQLException {
        queries.list(OrderAccess.admin(), null, OrderStatus.PENDING, 0, 10);

        assertEveryQueryUses("idx_orders_status_created");
    }

    @Test
    void customerAndStatusListReadsTheCombinedIndexWithoutSorting() throws SQLException {
        queries.list(OrderAccess.admin(), CUSTOMER, OrderStatus.PENDING, 0, 10);

        assertEveryQueryUses("idx_orders_customer_status_created");
    }

    private void assertEveryQueryUses(String index) throws SQLException {
        List<String> statements = CapturingStatementInspector.orderQueries();
        assertThat(statements).isNotEmpty();
        for (String sql : statements) {
            var nodes = new ArrayList<JsonNode>();
            collect(genericPlan(sql), nodes);
            assertThat(nodes).as(sql).noneMatch(node -> "Sort".equals(node.path("Node Type").asString()));
            assertThat(nodes).as(sql).noneMatch(node -> "Seq Scan".equals(node.path("Node Type").asString()));
            assertThat(nodes).as(sql).anyMatch(node -> index.equals(node.path("Index Name").asString()));
        }
    }

    // A generic plan is what a prepared statement may reuse for every parameter value. The simple query protocol
    // sends $n as text, which EXPLAIN (GENERIC_PLAN) needs; the extended protocol would expect bound values.
    private JsonNode genericPlan(String sql) throws SQLException {
        String numbered = sql;
        for (int index = 1; numbered.contains("?"); index++) {
            numbered = numbered.replaceFirst("\\?", "\\$" + index);
        }
        try (var connection = DriverManager.getConnection(database.getJdbcUrl() + "?preferQueryMode=simple",
                database.getUsername(), database.getPassword());
             var statement = connection.createStatement()) {
            // The test table is tiny, so the planner would rightly pick a sequential scan; disabling it asks only
            // whether a usable index exists.
            statement.execute("set enable_seqscan = off");
            try (var result = statement.executeQuery("explain (generic_plan, format json) " + numbered)) {
                result.next();
                return json.readTree(result.getString(1));
            }
        }
    }

    private static void collect(JsonNode node, List<JsonNode> nodes) {
        if (node.isObject()) {
            nodes.add(node);
        }
        for (JsonNode child : node) {
            collect(child, nodes);
        }
    }
}
