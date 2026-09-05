package com.orderflow.order.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.orderflow.order.domain.InvalidOrderException;
import com.orderflow.order.domain.OrderNotFoundException;
import com.orderflow.order.domain.OrderStatus;
import com.orderflow.order.infrastructure.OrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderQueryService {

    private final OrderRepository orders;

    public OrderQueryService(OrderRepository orders) {
        this.orders = orders;
    }

    @Transactional(readOnly = true)
    public OrderDetails get(UUID id) {
        List<OrderDetails> matches = loadDetails(List.of(id));
        if (matches.isEmpty()) {
            throw new OrderNotFoundException("Order not found: " + id);
        }
        return matches.getFirst();
    }

    @Transactional(readOnly = true)
    public Page<OrderDetails> list(UUID customerId, OrderStatus status, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new InvalidOrderException("page must be non-negative and size must be between 1 and 100");
        }
        Page<UUID> ids = orders.findPageIds(customerId, status, PageRequest.of(page, size));
        if (ids.isEmpty()) {
            return new PageImpl<>(List.of(), ids.getPageable(), ids.getTotalElements());
        }
        Map<UUID, OrderDetails> byId = loadDetails(ids.getContent()).stream()
            .collect(Collectors.toMap(OrderDetails::id, Function.identity()));
        return ids.map(id -> {
            OrderDetails details = byId.get(id);
            if (details == null) {
                throw new IllegalStateException("Order disappeared during page loading: " + id);
            }
            return details;
        });
    }

    private List<OrderDetails> loadDetails(List<UUID> ids) {
        return orders.findAllWithCustomerAndLines(ids).stream()
            .map(OrderDetails::from)
            .toList();
    }
}
