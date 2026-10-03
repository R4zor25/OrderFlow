package com.orderflow.inventory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import com.orderflow.inventory.infrastructure.outbox.InventoryOutboxWriter;
import com.orderflow.inventory.reservation.domain.ReservationLine;
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
class StockEventContractTest {

    @Autowired
    private InventoryOutboxWriter outbox;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Test
    void reservedEventMatchesItsContract() throws IOException {
        UUID orderId = UUID.randomUUID();
        transactions.executeWithoutResult(status ->
            outbox.reserved(orderId, List.of(new ReservationLine("CHAIR-001", 2))));

        assertMatchesSample(produced(orderId, "StockReserved"), "stock-reserved.json");
    }

    @Test
    void reservationFailedEventMatchesItsContract() throws IOException {
        UUID orderId = UUID.randomUUID();
        transactions.executeWithoutResult(status ->
            outbox.failed(orderId, "Insufficient stock", List.of("CHAIR-001")));

        assertMatchesSample(produced(orderId, "StockReservationFailed"), "stock-reservation-failed.json");
    }

    @Test
    void releasedEventMatchesItsContract() throws IOException {
        UUID orderId = UUID.randomUUID();
        transactions.executeWithoutResult(status -> outbox.released(orderId));

        assertMatchesSample(produced(orderId, "StockReleased"), "stock-released.json");
    }

    private JsonNode produced(UUID orderId, String eventType) {
        return json.readTree(jdbc.queryForObject("""
            select payload::text from inventory.outbox_event where aggregate_id = ? and event_type = ?
            """, String.class, orderId.toString(), eventType));
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
