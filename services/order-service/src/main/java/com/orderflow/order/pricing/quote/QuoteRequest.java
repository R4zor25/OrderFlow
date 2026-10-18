package com.orderflow.order.pricing.quote;

import java.util.List;

public record QuoteRequest(String customerGroup, List<QuoteLineRequest> lines) {
}
