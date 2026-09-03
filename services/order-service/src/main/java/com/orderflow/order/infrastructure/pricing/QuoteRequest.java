package com.orderflow.order.infrastructure.pricing;

import java.util.List;

public record QuoteRequest(String customerGroup, List<QuoteLineRequest> lines) {
}
