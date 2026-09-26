package com.orderflow.order.infrastructure.events;

import com.orderflow.order.application.StockResultService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class StockResultListenerTest {

    private static final String KEY = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

    private final StockResultService results = mock(StockResultService.class);
    private final StockResultListener listener = new StockResultListener(
        JsonMapper.builder().findAndAddModules().build(), results);

    @Test
    void skipsUnknownStockEventType() {
        listener.onMessage(record("{\"eventType\":\"StockAudited\",\"eventVersion\":1}"));

        verifyNoInteractions(results);
    }

    @Test
    void skipsNewerStockEventVersion() {
        listener.onMessage(record(reserved(2)));

        verifyNoInteractions(results);
    }

    @Test
    void rejectsMissingStockEventVersion() {
        assertThatThrownBy(() -> listener.onMessage(record(reserved(0))))
            .isInstanceOf(JacksonException.class);
        verifyNoInteractions(results);
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("inventory.stock-events.v1", 0, 0L, KEY, value);
    }

    private static String reserved(int version) {
        return """
            {"eventId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","eventType":"StockReserved","eventVersion":%d,
             "occurredAt":"2026-10-01T12:00:00Z","aggregateId":"%s",
             "payload":{"orderId":"%s","lines":[{"sku":"CHAIR-001","quantity":1}]}}
            """.formatted(version, KEY, KEY);
    }
}
