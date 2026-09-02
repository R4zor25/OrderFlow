package com.orderflow.catalog_pricing_service.price.api;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orderflow.catalog_pricing_service.price.application.QuoteService;
import com.orderflow.catalog_pricing_service.price.application.QuoteInput;

@RestController
@RequestMapping("/api/v1/quotes")
public class QuoteController {

    private final QuoteService quoteService;

    public QuoteController(QuoteService quoteService) {
        this.quoteService = quoteService;
    }

    @PostMapping
    public QuoteResponse quote(@Valid @RequestBody QuoteRequest request) {
        var lines = request.lines().stream()
            .map(line -> new QuoteInput.Line(line.sku(), line.quantity()))
            .toList();
        return QuoteResponse.from(quoteService.quote(new QuoteInput(request.customerGroup(), lines)));
    }
}
