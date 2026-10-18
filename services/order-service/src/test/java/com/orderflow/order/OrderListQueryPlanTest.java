package com.orderflow.order;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.customer.Customer;
import com.orderflow.order.customer.CustomerRepository;
import com.orderflow.order.orders.application.OrderAccess;
import com.orderflow.order.orders.application.OrderQueryService;
import com.orderflow.order.orders.domain.Order;
import com.orderflow.order.orders.domain.OrderStatus;
import com.orderflow.order.orders.infrastructure.OrderRepository;
import jakarta.persistence.EntityManager;
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

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private EntityManager entityManager;

    // A full first page makes Spring Data run the count query too, so both statements get planned.
    @BeforeEach
    void storeMoreOrdersThanOnePage() {
        Customer customer = customers.findById(CUSTOMER).orElseThrow();
        for (int index = 0; index < 2; index++) {
            orders.save(Order.place(customer, List.of(new Order.PricedLine("CHAIR-001", 1, new BigDecimal("84.90"))),
                new BigDecimal("22.92"), Instant.parse("2026-10-01T10:00:00Z").plusSeconds(index)));
        }
        entityManager.flush();
        CapturingStatementInspector.clear();
    }

    @Test
    void unfilteredListReadsTheCreatedIndexWithoutSorting() throws SQLException {
        queries.list(OrderAccess.admin(), null, null, 0, 1);

        assertEveryQueryUses("idx_orders_created");
    }

    @Test
    void customerListReadsTheCustomerIndexWithoutSorting() throws SQLException {
        queries.list(OrderAccess.admin(), CUSTOMER, null, 0, 1);

        assertEveryQueryUses("idx_orders_customer_created");
    }

    @Test
    void statusListReadsTheStatusIndexWithoutSorting() throws SQLException {
        queries.list(OrderAccess.admin(), null, OrderStatus.PENDING, 0, 1);

        assertEveryQueryUses("idx_orders_status_created");
    }

    @Test
    void customerAndStatusListReadsTheCombinedIndexWithoutSorting() throws SQLException {
        queries.list(OrderAccess.admin(), CUSTOMER, OrderStatus.PENDING, 0, 1);

        assertEveryQueryUses("idx_orders_customer_status_created");
    }

    private void assertEveryQueryUses(String index) throws SQLException {
        List<String> statements = CapturingStatementInspector.orderQueries().stream()
            .filter(sql -> !sql.contains(" join "))
            .toList();
        assertThat(statements).hasSize(2);
        String page = statements.stream().filter(sql -> sql.contains("order by")).findFirst().orElseThrow();
        String count = statements.stream().filter(sql -> sql.startsWith("select count")).findFirst().orElseThrow();
        var pageNodes = planNodes(page);
        assertThat(pageNodes).as(page).noneMatch(node -> "Sort".equals(node.path("Node Type").asString()));
        assertThat(pageNodes).as(page).noneMatch(node -> "Seq Scan".equals(node.path("Node Type").asString()));
        assertThat(pageNodes).as(page).anyMatch(node -> index.equals(node.path("Index Name").asString()));
        // Any index whose leading columns match the filter can serve the count, so only a table scan is wrong.
        var countNodes = planNodes(count);
        assertThat(countNodes).as(count).noneMatch(node -> "Seq Scan".equals(node.path("Node Type").asString()));
        assertThat(countNodes).as(count).anyMatch(node -> node.has("Index Name"));
    }

    private List<JsonNode> planNodes(String sql) throws SQLException {
        var nodes = new ArrayList<JsonNode>();
        collect(genericPlan(sql), nodes);
        return nodes;
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
