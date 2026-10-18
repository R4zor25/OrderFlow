package com.orderflow.order.orders.api;

import java.util.List;

import com.orderflow.order.orders.application.OrderDetails;
import org.springframework.data.domain.Page;

public record OrderPageResponse(
    List<OrderResponse> content,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
    public static OrderPageResponse from(Page<OrderDetails> result) {
        return new OrderPageResponse(result.getContent().stream().map(OrderResponse::from).toList(),
            result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }
}
