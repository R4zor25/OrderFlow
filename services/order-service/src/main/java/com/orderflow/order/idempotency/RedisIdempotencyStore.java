package com.orderflow.order.idempotency;

import java.util.List;
import java.util.UUID;

import com.orderflow.order.idempotency.IdempotencyException.Reason;
import com.orderflow.order.orders.application.PlaceOrderResult;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class RedisIdempotencyStore {

    private static final RedisScript<Long> COMPLETE = RedisScript.of(
        new ClassPathResource("redis/idempotency-complete.lua"), Long.class);
    private static final RedisScript<Long> RELEASE = RedisScript.of(
        new ClassPathResource("redis/idempotency-release.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final IdempotencyProperties properties;

    public RedisIdempotencyStore(StringRedisTemplate redis, ObjectMapper json,
                                 IdempotencyProperties properties) {
        this.redis = redis;
        this.json = json;
        this.properties = properties;
    }

    public Decision claim(UUID customerId, String clientKey, String requestHash) {
        String redisKey = "idem:" + customerId + ":" + clientKey;
        String pendingJson = json.writeValueAsString(new Entry(State.IN_PROGRESS, requestHash,
            UUID.randomUUID().toString(), null));
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                Boolean created = redis.opsForValue().setIfAbsent(redisKey, pendingJson, properties.inProgressLease());
                if (Boolean.TRUE.equals(created)) {
                    return new Acquired(redisKey, pendingJson, requestHash);
                }
                String existingJson = redis.opsForValue().get(redisKey);
                if (existingJson == null) {
                    continue;
                }
                Entry existing = json.readValue(existingJson, Entry.class);
                if (!requestHash.equals(existing.requestHash())) {
                    throw new IdempotencyException(Reason.KEY_REUSED,
                        "Idempotency-Key was already used with a different order request");
                }
                if (existing.state() == State.IN_PROGRESS) {
                    throw new IdempotencyException(Reason.IN_PROGRESS,
                        "An order request with this Idempotency-Key is still in progress");
                }
                if (existing.state() == State.COMPLETED && existing.result() != null) {
                    return new Replayed(existing.result());
                }
                throw unavailable();
            }
            throw unavailable();
        } catch (DataAccessException exception) {
            throw new IdempotencyException(Reason.STORE_UNAVAILABLE,
                "Idempotency store is temporarily unavailable", exception);
        }
    }

    public void complete(Acquired acquired, PlaceOrderResult result) {
        String completedJson = json.writeValueAsString(new Entry(State.COMPLETED,
            acquired.requestHash(), null, result));
        try {
            Long changed = redis.execute(COMPLETE, List.of(acquired.redisKey()),
                acquired.pendingJson(), completedJson, Long.toString(properties.ttl().toSeconds()));
            if (!Long.valueOf(1).equals(changed)) {
                throw unavailable();
            }
        } catch (DataAccessException exception) {
            throw new IdempotencyException(Reason.STORE_UNAVAILABLE,
                "Idempotency store is temporarily unavailable", exception);
        }
    }

    public void release(Acquired acquired) {
        try {
            redis.execute(RELEASE, List.of(acquired.redisKey()), acquired.pendingJson());
        } catch (DataAccessException exception) {
            throw new IdempotencyException(Reason.STORE_UNAVAILABLE,
                "Idempotency store is temporarily unavailable", exception);
        }
    }

    private static IdempotencyException unavailable() {
        return new IdempotencyException(Reason.STORE_UNAVAILABLE,
            "Idempotency state could not be confirmed");
    }

    public sealed interface Decision permits Acquired, Replayed {
    }

    public record Acquired(String redisKey, String pendingJson, String requestHash) implements Decision {
    }

    public record Replayed(PlaceOrderResult result) implements Decision {
    }

    private enum State {
        IN_PROGRESS, COMPLETED
    }

    private record Entry(State state, String requestHash, String ownerToken, PlaceOrderResult result) {
    }
}
