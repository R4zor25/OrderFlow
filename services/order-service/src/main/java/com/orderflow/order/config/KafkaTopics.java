package com.orderflow.order.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.kafka.config.TopicBuilder;

final class KafkaTopics {

    // Kafka deletes whole segments, so a record stays readable for up to retention + segment.ms (8 days);
    // processed_event and cancelled_order keep 14 days, so a replay never outlives deduplication.
    private static final String RETENTION_MS = "604800000";
    private static final String SEGMENT_MS = "86400000";

    // Matches spring.kafka.consumer.group-id; a dead-letter topic belongs to exactly one consumer group.
    static final String GROUP = "order-service";

    private KafkaTopics() {
    }

    static String deadLetter(String topic) {
        return topic + "." + GROUP + ".DLT";
    }

    static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1)
            .config(TopicConfig.RETENTION_MS_CONFIG, RETENTION_MS)
            .config(TopicConfig.SEGMENT_MS_CONFIG, SEGMENT_MS)
            .build();
    }
}
