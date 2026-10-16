package com.orderflow.order.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import com.orderflow.order.domain.IdempotencyException;
import com.orderflow.order.domain.InvalidOrderException;
import com.orderflow.order.infrastructure.OrderRepository;
import com.orderflow.order.infrastructure.idempotency.RedisIdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class IdempotentPlaceOrderService {

    private static final Logger log = LoggerFactory.getLogger(IdempotentPlaceOrderService.class);

    private final PlaceOrderService placeOrderService;
    private final RedisIdempotencyStore store;
    private final ObjectMapper json;
    private final OrderRepository orders;

    public IdempotentPlaceOrderService(PlaceOrderService placeOrderService,
                                       RedisIdempotencyStore store, ObjectMapper json, OrderRepository orders) {
        this.placeOrderService = placeOrderService;
        this.store = store;
        this.json = json;
        this.orders = orders;
    }

    public PlaceOrderResult place(String idempotencyKey, PlaceOrderCommand command) {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new InvalidOrderException(
                "Idempotency-Key must contain 1 to 128 letters, digits, dots, underscores or hyphens");
        }
        String requestHash = hash(command);
        var idempotency = new IdempotencyKey(idempotencyKey, requestHash);
        RedisIdempotencyStore.Decision decision;
        try {
            decision = store.claim(command.customerId(), idempotencyKey, requestHash);
        } catch (IdempotencyException exception) {
            if (exception.getReason() != IdempotencyException.Reason.IN_PROGRESS) {
                throw exception;
            }
            // A claim left behind by a crashed request must not hide an order that was committed.
            return existingOrder(command.customerId(), idempotency).orElseThrow(() -> exception);
        }
        if (decision instanceof RedisIdempotencyStore.Replayed replayed) {
            return replayed.result();
        }
        var acquired = (RedisIdempotencyStore.Acquired) decision;
        try {
            var result = findOrPlace(command, idempotency);
            completeQuietly(acquired, result, command.customerId());
            return result;
        } catch (RuntimeException | Error failure) {
            // Every outcome that is not a replay frees the claim, so a correct retry is not blocked by it.
            try {
                store.release(acquired);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private PlaceOrderResult findOrPlace(PlaceOrderCommand command, IdempotencyKey idempotency) {
        var existing = existingOrder(command.customerId(), idempotency);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return placeOrderService.place(command, idempotency);
        } catch (DataIntegrityViolationException duplicate) {
            // Another request with the same key committed first; its order is the answer.
            return existingOrder(command.customerId(), idempotency).orElseThrow(() -> duplicate);
        }
    }

    private Optional<PlaceOrderResult> existingOrder(UUID customerId, IdempotencyKey idempotency) {
        return orders.findByCustomerIdAndIdempotencyKey(customerId, idempotency.key()).map(order -> {
            if (!idempotency.requestHash().equals(order.getRequestHash())) {
                throw new IdempotencyException(IdempotencyException.Reason.KEY_REUSED,
                    "Idempotency-Key was already used with a different order request");
            }
            return PlaceOrderResult.from(order);
        });
    }

    private void completeQuietly(RedisIdempotencyStore.Acquired acquired, PlaceOrderResult result, UUID customerId) {
        try {
            store.complete(acquired, result);
        } catch (RuntimeException failure) {
            // The order is committed with its key, so a retry is answered from Postgres instead.
            log.warn("Idempotency result not stored in Redis customerId={} orderId={} cause={}",
                customerId, result.id(), failure.getClass().getSimpleName());
        }
    }

    private String hash(PlaceOrderCommand command) {
        try {
            byte[] canonical = json.writeValueAsString(command).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
