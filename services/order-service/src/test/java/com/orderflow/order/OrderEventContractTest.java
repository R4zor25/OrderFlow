package com.orderflow.order;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import com.orderflow.order.domain.Customer;
import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderStatus;
import com.orderflow.order.infrastructure.CustomerRepository;
import com.orderflow.order.infrastructure.OrderRepository;
import com.orderflow.order.infrastructure.outbox.OrderOutboxWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class OrderEventContractTest {

    private static final Instant CREATED = Instant.parse("2026-10-01T10:15:31Z");
    private static final Instant CHANGED = Instant.parse("2026-10-01T10:16:00Z");

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private OrderOutboxWriter outbox;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Test
    void placedEventMatchesItsContract() throws IOException {
        UUID id = createOrder();
        transactions.executeWithoutResult(status -> outbox.placed(load(id), CREATED));

        assertMatchesSample(produced(id, "OrderPlaced"), "order-placed.json");
    }

    @Test
    void confirmedEventMatchesItsContract() throws IOException {
        UUID id = createOrder();
        transactions.executeWithoutResult(status -> {
            Order order = load(id);
            order.confirm(CHANGED);
            orders.flush();
            outbox.confirmed(order, CHANGED);
        });

        assertMatchesSample(produced(id, "OrderConfirmed"), "order-confirmed.json");
    }

    @Test
    void rejectedEventMatchesItsContract() throws IOException {
        UUID id = createOrder();
        transactions.executeWithoutResult(status -> {
            Order order = load(id);
            order.reject("Insufficient stock", CHANGED);
            orders.flush();
            outbox.rejected(order, CHANGED);
        });

        assertMatchesSample(produced(id, "OrderRejected"), "order-rejected.json");
    }

    @Test
    void cancelledEventMatchesItsContract() throws IOException {
        UUID id = createOrder();
        transactions.executeWithoutResult(status -> {
            Order order = load(id);
            OrderStatus previous = order.getStatus();
            order.cancel(CHANGED);
            orders.flush();
            outbox.cancelled(order, previous, CHANGED);
        });

        assertMatchesSample(produced(id, "OrderCancelled"), "order-cancelled.json");
    }

    private UUID createOrder() {
        return transactions.execute(status -> {
            Customer customer = customers.findById(UUID.fromString("33333333-3333-4333-8333-333333333333"))
                .orElseThrow();
            Order order = Order.place(customer, List.of(
                new Order.PricedLine("CHAIR-001", 2, new BigDecimal("84.90"))),
                new BigDecimal("22.92"), CREATED);
            return orders.saveAndFlush(order).getId();
        });
    }

    private Order load(UUID id) {
        return orders.findAllWithCustomerAndLines(List.of(id)).getFirst();
    }

    private JsonNode produced(UUID id, String eventType) {
        return json.readTree(jdbc.queryForObject("""
            select payload::text from orders.outbox_event where aggregate_id = ? and event_type = ?
            """, String.class, id, eventType));
    }

    // Field names and JSON node types must match the sample exactly, so neither the code nor the sample can drift.
    private static Object shape(JsonNode node) {
        if (node.isObject()) {
            var fields = new TreeMap<String, Object>();
            for (var field : node.properties()) {
                fields.put(field.getKey(), shape(field.getValue()));
            }
            return fields;
        }
        if (node.isArray()) {
            return node.isEmpty() ? List.of() : List.of(shape(node.get(0)));
        }
        return node.getNodeType();
    }

    private JsonNode sample(String file) throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isDirectory(directory.resolve("contracts/events"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("Cannot find contracts/events from test working directory");
        }
        return json.readTree(Files.readString(directory.resolve("contracts/events").resolve(file)));
    }

    private void assertMatchesSample(JsonNode produced, String file) throws IOException {
        JsonNode sample = sample(file);
        assertThat(shape(produced)).isEqualTo(shape(sample));
        assertThat(produced.get("eventType").asString()).isEqualTo(sample.get("eventType").asString());
        assertThat(produced.get("eventVersion").asInt()).isEqualTo(sample.get("eventVersion").asInt());
    }
}
