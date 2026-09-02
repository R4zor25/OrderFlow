package com.orderflow.catalog_pricing_service.product.api;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.orderflow.catalog_pricing_service.product.application.ProductCommandService;
import com.orderflow.catalog_pricing_service.product.application.ProductQueryService;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ProductQueryService queryService;
    private final ProductCommandService commandService;

    public ProductController(ProductQueryService queryService, ProductCommandService commandService) {
        this.queryService = queryService;
        this.commandService = commandService;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        var product = commandService.create(
            request.sku(), request.name(), request.category(), request.listPriceNet(), request.vatRate()
        );
        return ResponseEntity.created(URI.create("/api/v1/products/" + product.getSku()))
            .body(ProductResponse.from(product));
    }

    @GetMapping
    public ProductPageResponse list(
        @RequestParam(required = false) String category,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        if (page < 0 || size < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be non-negative and size must be positive");
        }
        var pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), Sort.by("sku").ascending());
        return ProductPageResponse.from(queryService.list(category, pageable));
    }

    @GetMapping("/{sku}")
    public ProductResponse getBySku(@PathVariable String sku) {
        return queryService.findBySku(sku)
            .map(ProductResponse::from)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    }
}
