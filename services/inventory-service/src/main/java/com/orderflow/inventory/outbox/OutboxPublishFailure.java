package com.orderflow.inventory.outbox;

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
            // The bare class is a general client state error, such as a forced producer close, not a property of the record.
            kafkaError |= cause instanceof KafkaException && cause.getClass() != KafkaException.class;
        }
        // Unclassified failures are retried; parking them would silently hold back an aggregate's events.
        return kafkaError;
    }

    public static Throwable cause(Throwable failure) {
        Throwable classified = failure instanceof ExecutionException && failure.getCause() != null
            ? failure.getCause() : failure;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof KafkaException) {
                classified = cause;
            }
        }
        return classified;
    }

    public static String describe(Throwable failure) {
        Throwable described = cause(failure);
        String text = described.getClass().getSimpleName() + ": " + described.getMessage();
        return text.length() <= MAX_DESCRIPTION ? text : text.substring(0, MAX_DESCRIPTION);
    }
}
