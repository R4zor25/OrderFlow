package com.orderflow.inventory;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class OutboxLagMetricIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private Clock clock;

    @Test
    void reportsAgeOfOldestUnpublishedEventAndZeroWhenQueueIsEmpty() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        jdbc.update("delete from inventory.outbox_event");
        expectLag(0.0);

        insert(NOW.minusSeconds(90), null);
        insert(NOW.minusSeconds(30), null);
        insert(NOW.minusSeconds(300), NOW.minusSeconds(1));
        expectLag(90.0);

        jdbc.update("delete from inventory.outbox_event where created_at = ?", Timestamp.from(NOW.minusSeconds(90)));
        expectLag(30.0);
    }

    private void expectLag(double seconds) throws Exception {
        mvc.perform(get("/actuator/metrics/outbox.lag.seconds"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.measurements[0].value").value(seconds));
    }

    private void insert(Instant createdAt, Instant publishedAt) {
        jdbc.update("""
            insert into inventory.outbox_event
                (id, aggregate_type, aggregate_id, event_type, payload, created_at, published_at)
            values (?, 'Stock', ?, 'StockReserved', '{}'::jsonb, ?, ?)
            """, UUID.randomUUID(), UUID.randomUUID().toString(), Timestamp.from(createdAt),
            publishedAt == null ? null : Timestamp.from(publishedAt));
    }
}
