package com.orderflow.notification.infrastructure.persistence;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import io.micronaut.core.annotation.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface NotificationRepository extends CrudRepository<NotificationRow, UUID> {

    @Query(value = """
            INSERT INTO notifications.notification
                (id, event_id, order_id, type, recipient, subject, body, channel, created_at,
                 status, attempts, next_attempt_at)
            VALUES (:id, :eventId, :orderId, :type, :recipient, :subject, :body, :channel, :createdAt,
                    'PENDING', 0, :leaseUntil)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertPending(UUID id, UUID eventId, @Nullable UUID orderId, String type, String recipient,
                      String subject, String body, String channel, Instant createdAt, Instant leaseUntil);

    @Query(value = """
            UPDATE notifications.notification
            SET status = 'SENT', sent_at = :sentAt, next_attempt_at = NULL, last_error = NULL
            WHERE event_id = :eventId AND status = 'PENDING'
            """, nativeQuery = true)
    int markSent(UUID eventId, Instant sentAt);

    @Query(value = """
            SELECT attempts FROM notifications.notification
            WHERE event_id = :eventId AND status = 'PENDING'
            FOR UPDATE
            """, nativeQuery = true)
    Optional<Integer> findPendingAttemptsForUpdate(UUID eventId);

    @Query(value = """
            UPDATE notifications.notification
            SET attempts = :attempts, last_error = :lastError, status = :status, next_attempt_at = :nextAttemptAt
            WHERE event_id = :eventId AND status = 'PENDING'
            """, nativeQuery = true)
    int recordFailure(UUID eventId, int attempts, String lastError, String status, @Nullable Instant nextAttemptAt);

    @Query(value = """
            SELECT * FROM notifications.notification
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<NotificationRow> findNextDueForUpdate(Instant now);

    @Query(value = """
            UPDATE notifications.notification SET next_attempt_at = :leaseUntil WHERE id = :id
            """, nativeQuery = true)
    int extendLease(UUID id, Instant leaseUntil);

    @Query(value = """
            DELETE FROM notifications.notification WHERE id IN (
                SELECT id FROM notifications.notification
                WHERE status IN ('SENT', 'FAILED') AND created_at < :cutoff
                ORDER BY created_at
                LIMIT :limit)
            """, nativeQuery = true)
    int deleteClosedBefore(Instant cutoff, int limit);

    List<NotificationRow> findByOrderIdOrderByCreatedAt(UUID orderId);

    // Literal statuses let PostgreSQL use the partial indexes; a bound parameter would rule them out in a generic plan.
    @Query(value = "SELECT count(*) FROM notifications.notification WHERE status = 'PENDING'", nativeQuery = true)
    long countPending();

    @Query(value = "SELECT count(*) FROM notifications.notification WHERE status = 'FAILED'", nativeQuery = true)
    long countFailed();
}
