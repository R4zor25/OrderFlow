package com.orderflow.notification;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.spi.AppenderAttachable;
import com.orderflow.notification.messaging.NotificationEventDecoder;
import com.orderflow.notification.messaging.NotificationEventListener;
import io.opentelemetry.api.trace.Span;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import io.micronaut.context.ApplicationContext;
import io.micronaut.json.JsonMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class NotificationTracingIntegrationTest {
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String PARENT_SPAN_ID = "0123456789abcdef";

    @Test
    void correlatesKafkaDeliveryLogsAndStartsFreshTracesForMissingOrMalformedHeaders() throws Exception {
        var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var console = consoleAppender(root);
        var output = new ByteArrayOutputStream();
        var capture = new OutputStreamAppender<ILoggingEvent>();
        capture.setName("CAPTURE");
        capture.setContext(root.getLoggerContext());
        capture.setEncoder(console.getEncoder());
        capture.setOutputStream(output);
        capture.start();
        @SuppressWarnings("unchecked")
        var wrapped = root.getAppender("OTEL") instanceof AppenderAttachable<?> attached
                ? (AppenderAttachable<ILoggingEvent>) attached : root;
        wrapped.addAppender(capture);
        var recordingSpans = new CopyOnWriteArrayList<Boolean>();
        var probe = new AppenderBase<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (event.getLoggerName().endsWith("LogNotificationSender")) {
                    recordingSpans.add(Span.current().isRecording());
                }
            }
        };
        probe.setContext(root.getLoggerContext());
        probe.start();
        root.addAppender(probe);
        try (var kafka = new KafkaContainer("apache/kafka:4.3.1");
             var postgres = new PostgreSQLContainer("postgres:17.11-bookworm")) {
            kafka.start();
            postgres.start();
            try (var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                    kafka.getBootstrapServers()))) {
                admin.createTopics(List.of(new NewTopic(NotificationEventDecoder.ORDER_TOPIC, 1, (short) 1),
                        new NewTopic(NotificationEventDecoder.STOCK_TOPIC, 1, (short) 1))).all().get();
            }
            try (var context = ApplicationContext.run(Map.of(
                    "kafka.bootstrap.servers", kafka.getBootstrapServers(),
                    "notification.messaging.enabled", true,
                    "otel.traces.sampler", "parentbased_always_on",
                    "datasources.default.url", postgres.getJdbcUrl(),
                    "datasources.default.username", postgres.getUsername(),
                    "datasources.default.password", postgres.getPassword()));
                 var producer = new KafkaProducer<String, String>(Map.of(
                         ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                         ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                         ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
                var mapper = context.getBean(JsonMapper.class);
                var fixture = Files.readString(Path.of("../../contracts/events/order-confirmed.json"));
                var ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
                for (int i = 0; i < ids.size(); i++) {
                    var record = new ProducerRecord<String, String>(NotificationEventDecoder.ORDER_TOPIC, 0,
                            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                            fixture.replace("cccccccc-cccc-4ccc-8ccc-cccccccccccc", ids.get(i).toString()));
                    if (i != 1) {
                        record.headers().add("traceparent", (i == 0
                                ? "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01" : "malformed")
                                .getBytes(StandardCharsets.UTF_8));
                    }
                    producer.send(record).get();
                    int index = i;
                    await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                            assertThat(output.toString(StandardCharsets.UTF_8)).contains(ids.get(index).toString()));
                }
                var lines = output.toString(StandardCharsets.UTF_8).lines()
                        .filter(line -> line.contains("Notification recorded through LOG channel"))
                        .map(line -> parse(mapper, line)).toList();
                assertThat(lines).hasSize(3);
                for (var line : lines) {
                    assertThat(((Map<?, ?>) line.get("context")).get("name")).isEqualTo("notification-service");
                }
                var mdc = lines.stream().map(line -> (Map<?, ?>) line.get("mdc")).toList();
                assertThat(mdc.getFirst().get("trace_id")).isEqualTo(TRACE_ID);
                assertThat(mdc.getFirst().get("span_id")).isNotEqualTo(PARENT_SPAN_ID);
                for (var fields : mdc) {
                    assertThat((String) fields.get("trace_id")).matches("[0-9a-f]{32}");
                    assertThat((String) fields.get("span_id")).matches("[0-9a-f]{16}");
                    assertThat(Integer.parseInt((String) fields.get("trace_flags"), 16) & 1).isEqualTo(1);
                }
                assertThat(mdc.get(1).get("trace_id")).isNotEqualTo(TRACE_ID);
                assertThat(mdc.get(2).get("trace_id")).isNotEqualTo(TRACE_ID).isNotEqualTo(mdc.get(1).get("trace_id"));
                assertThat(recordingSpans).containsExactly(true, true, true);
                assertThat(lines.toString()).doesNotContain("standard@example.test", "customerEmail", "totalGross", "subject=", "body=");
                assertThat(Span.current().getSpanContext().isValid()).isFalse();
                context.getBean(NotificationEventListener.class).onEvent(new ConsumerRecord<>(
                        NotificationEventDecoder.ORDER_TOPIC, 0, 99,
                        "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", fixture.replace("OrderConfirmed", "OrderShipped")));
                assertThat(Span.current().getSpanContext().isValid()).isFalse();
                LoggerFactory.getLogger(getClass()).info("Outside consumer execution");
                var outside = output.toString(StandardCharsets.UTF_8).lines()
                        .filter(line -> line.contains("Outside consumer execution")).findFirst().orElseThrow();
                assertThat(parse(mapper, outside).get("mdc").toString()).doesNotContain("trace_id", "span_id");
            }
        } finally {
            root.detachAppender(probe);
            probe.stop();
            wrapped.detachAppender(capture);
            capture.stop();
        }
    }

    @SuppressWarnings("unchecked")
    private OutputStreamAppender<ILoggingEvent> consoleAppender(Logger root) {
        var appender = root.getAppender("OTEL");
        if (appender instanceof AppenderAttachable<?> attached) {
            return (OutputStreamAppender<ILoggingEvent>) attached.getAppender("STDOUT");
        }
        return (OutputStreamAppender<ILoggingEvent>) root.getAppender("STDOUT");
    }

    private Map<?, ?> parse(JsonMapper mapper, String line) {
        try {
            return mapper.readValue(line, Map.class);
        } catch (Exception failure) {
            throw new AssertionError("Console delivery logs must be parseable JSON", failure);
        }
    }
}
