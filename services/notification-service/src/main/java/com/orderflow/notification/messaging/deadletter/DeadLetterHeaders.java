package com.orderflow.notification.messaging.deadletter;

import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

final class DeadLetterHeaders {

    static final String GROUP = "notification-service";
    private static final int MAX_MESSAGE_LENGTH = 1000;

    private DeadLetterHeaders() {
    }

    static String topic(String sourceTopic) {
        return sourceTopic + "." + GROUP + ".DLT";
    }

    // Same names and encodings as Spring's DeadLetterPublishingRecoverer, so one tool reads every DLT.
    static Headers of(String topic, int partition, long offset, Throwable cause) {
        String message = cause.getMessage() == null ? "" : cause.getMessage();
        var headers = new RecordHeaders();
        headers.add("kafka_dlt-original-topic", utf8(topic));
        headers.add("kafka_dlt-original-partition", ByteBuffer.allocate(Integer.BYTES).putInt(partition).array());
        headers.add("kafka_dlt-original-offset", ByteBuffer.allocate(Long.BYTES).putLong(offset).array());
        headers.add("kafka_dlt-original-consumer-group", utf8(GROUP));
        headers.add("kafka_dlt-exception-fqcn", utf8(cause.getClass().getName()));
        headers.add("kafka_dlt-exception-message",
                utf8(message.length() > MAX_MESSAGE_LENGTH ? message.substring(0, MAX_MESSAGE_LENGTH) : message));
        return headers;
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
