package com.orderflow.order.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.orderflow.order.domain.InvalidOrderException;
import com.orderflow.order.infrastructure.idempotency.RedisIdempotencyStore;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class IdempotentPlaceOrderService {

    private final PlaceOrderService placeOrderService;
    private final RedisIdempotencyStore store;
    private final ObjectMapper json;

    public IdempotentPlaceOrderService(PlaceOrderService placeOrderService,
                                       RedisIdempotencyStore store, ObjectMapper json) {
        this.placeOrderService = placeOrderService;
        this.store = store;
        this.json = json;
    }

    public PlaceOrderResult place(String idempotencyKey, PlaceOrderCommand command) {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new InvalidOrderException(
                "Idempotency-Key must contain 1 to 128 letters, digits, dots, underscores or hyphens");
        }
        String requestHash = hash(command);
        var decision = store.claim(command.customerId(), idempotencyKey, requestHash);
        if (decision instanceof RedisIdempotencyStore.Replayed replayed) {
            return replayed.result();
        }
        var acquired = (RedisIdempotencyStore.Acquired) decision;
        PlaceOrderResult result;
        try {
            result = placeOrderService.place(command);
        } catch (RuntimeException | Error failure) {
            try {
                store.release(acquired);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        store.complete(acquired, result);
        return result;
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
