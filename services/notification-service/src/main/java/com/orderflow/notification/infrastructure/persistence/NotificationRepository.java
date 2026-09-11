package com.orderflow.notification.infrastructure.persistence;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import io.micronaut.core.annotation.Nullable;

import java.time.Instant;
import java.util.UUID;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface NotificationRepository extends CrudRepository<NotificationRow, UUID> {

    @Query(value = """
            INSERT INTO notifications.notification
                (id, event_id, order_id, type, recipient, subject, body, channel, created_at)
            VALUES (:id, :eventId, :orderId, :type, :recipient, :subject, :body, :channel, :createdAt)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(UUID id, UUID eventId, @Nullable UUID orderId, String type,
                       String recipient, String subject, String body, String channel, Instant createdAt);
}
