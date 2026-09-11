package com.orderflow.notification;

import com.orderflow.notification.application.NotificationDraft;
import com.orderflow.notification.application.NotificationComposer;
import com.orderflow.notification.config.NotificationSettings;
import com.orderflow.notification.messaging.NotificationEvent;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import com.orderflow.notification.application.NotificationStore;
import com.orderflow.notification.application.NotificationProcessor;
import com.orderflow.notification.config.NotificationSettings.Channel;
import com.orderflow.notification.messaging.NotificationEventDecoder;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@MicronautTest(transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationStoreIntegrationTest implements TestPropertyProvider {

    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-bookworm");

    static {
        postgres.start();
    }

    @Inject
    NotificationStore store;

    @Inject
    NotificationProcessor processor;

    @Inject
    NotificationEventDecoder decoder;

    @Override
    public Map<String, String> getProperties() {
        return Map.of(
                "datasources.default.url", postgres.getJdbcUrl(),
                "datasources.default.username", postgres.getUsername(),
                "datasources.default.password", postgres.getPassword(),
                "datasources.default.driver-class-name", "org.postgresql.Driver",
                "notification.channel", "LOG"
        );
    }

    @Test
    void insertsOnceForSameEventId() throws Exception {
        var eventId = UUID.randomUUID();
        var draft = new NotificationDraft(eventId, UUID.randomUUID(), "OrderConfirmed",
                "buyer@example.test", "Confirmed", "Order confirmed", Channel.LOG);

        assertThat(store.insertIfAbsent(draft)).isTrue();
        assertThat(store.insertIfAbsent(draft)).isFalse();

        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     select count(*) from notifications.notification where event_id = ?
                     """)) {
            statement.setObject(1, eventId);
            try (var rows = statement.executeQuery()) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void concurrentDuplicatesHaveOneWinner() throws Exception {
        var draft = new NotificationDraft(UUID.randomUUID(), null, "LowStock", "ops@orderflow.test",
                "Low stock", "Low stock", Channel.LOG);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = IntStream.range(0, 8)
                    .<java.util.concurrent.Callable<Boolean>>mapToObj(i -> () -> store.insertIfAbsent(draft)).toList();
            int winners = 0;
            for (var result : executor.invokeAll(tasks)) {
                if (result.get()) winners++;
            }
            assertThat(winners).isEqualTo(1);
        }
    }

    @Test
    void commitsBeforeDeliveryAndDoesNotRetryFailedDelivery() throws Exception {
        var eventId = UUID.randomUUID();
        var orderId = UUID.randomUUID();
        var event = new NotificationEvent(eventId, "OrderConfirmed", Instant.now(), orderId.toString(),
                new NotificationEvent.OrderConfirmed(orderId, UUID.randomUUID(), "buyer@orderflow.test",
                        new BigDecimal("12.00"), "EUR"));
        var attempts = new AtomicInteger();
        var processing = new NotificationProcessor(
                new NotificationComposer(new NotificationSettings(Channel.LOG, "ops@orderflow.test")),
                store, draft -> {
                    attempts.incrementAndGet();
                    try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                            postgres.getUsername(), postgres.getPassword());
                         var query = connection.prepareStatement(
                                 "select count(*) from notifications.notification where event_id = ?")) {
                        query.setObject(1, eventId);
                        try (var rows = query.executeQuery()) {
                            rows.next();
                            assertThat(rows.getLong(1)).isEqualTo(1);
                        }
                    } catch (java.sql.SQLException failure) {
                        throw new IllegalStateException(failure);
                    }
                    throw new IllegalStateException("Simulated delivery failure");
                });

        processing.process(event);
        processing.process(event);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void processesConfirmedOrderThroughApplicationService() throws Exception {
        var event = decoder.decode(NotificationEventDecoder.ORDER_TOPIC,
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                Files.readString(Path.of("../../contracts/events/order-confirmed.json"))).orElseThrow();

        processor.process(event);
        assertThat(store.insertIfAbsent(new NotificationDraft(event.eventId(),
                UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"), "OrderConfirmed",
                "buyer@example.test", "Confirmed", "Confirmed", Channel.LOG))).isFalse();
    }
}
