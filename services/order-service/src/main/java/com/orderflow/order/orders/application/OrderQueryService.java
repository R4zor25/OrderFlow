package com.orderflow.order.orders.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.orderflow.order.orders.domain.InvalidOrderException;
import com.orderflow.order.orders.domain.OrderNotFoundException;
import com.orderflow.order.orders.domain.OrderStatus;
import com.orderflow.order.orders.infrastructure.OrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderQueryService {

    private final OrderRepository orders;

    public OrderQueryService(OrderRepository orders) {
        this.orders = orders;
    }

    @Transactional(readOnly = true)
    public OrderDetails get(UUID id, OrderAccess access) {
        return loadDetails(List.of(id)).stream()
            .filter(details -> access.permits(details.customerId()))
            .findFirst()
            .orElseThrow(() -> new OrderNotFoundException("Order not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<OrderDetails> list(OrderAccess access, UUID customerId, OrderStatus status, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new InvalidOrderException("page must be non-negative and size must be between 1 and 100");
        }
        // The database query takes an int offset; checked in long so the check itself cannot overflow.
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new InvalidOrderException("page × size must not exceed " + Integer.MAX_VALUE);
        }
        Page<UUID> ids = findPageIds(access.listFilter(customerId), status, PageRequest.of(page, size));
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

    // One query per filter combination: an "is null or" predicate would hide every index from a generic plan.
    private Page<UUID> findPageIds(UUID customerId, OrderStatus status, Pageable pageable) {
        if (customerId != null && status != null) {
            return orders.findPageIdsByCustomerAndStatus(customerId, status, pageable);
        }
        if (customerId != null) {
            return orders.findPageIdsByCustomer(customerId, pageable);
        }
        if (status != null) {
            return orders.findPageIdsByStatus(status, pageable);
        }
        return orders.findPageIds(pageable);
    }

    private List<OrderDetails> loadDetails(List<UUID> ids) {
        return orders.findAllWithCustomerAndLines(ids).stream()
            .map(OrderDetails::from)
            .toList();
    }
}
