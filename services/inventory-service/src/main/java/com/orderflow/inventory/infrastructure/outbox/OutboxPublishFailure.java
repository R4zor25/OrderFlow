package com.orderflow.inventory.infrastructure.outbox;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.RetriableException;

public final class OutboxPublishFailure {

    private static final int MAX_DESCRIPTION = 1000;

    private OutboxPublishFailure() {
    }

    public static boolean isPermanent(Throwable failure) {
        boolean kafkaError = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RetriableException || cause instanceof TimeoutException
                || cause instanceof InterruptedException) {
                return false;
            }
            kafkaError |= cause instanceof KafkaException;
        }
        // Unclassified failures are retried; parking them would silently hold back an aggregate's events.
        return kafkaError;
    }

    public static String describe(Throwable failure) {
        Throwable described = failure instanceof ExecutionException && failure.getCause() != null
            ? failure.getCause() : failure;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof KafkaException) {
                described = cause;
            }
        }
        String text = described.getClass().getSimpleName() + ": " + described.getMessage();
        return text.length() <= MAX_DESCRIPTION ? text : text.substring(0, MAX_DESCRIPTION);
    }
}
