package com.orderflow.catalog_pricing_service.price.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.orderflow.catalog_pricing_service.price.infrastructure.PriceListEntryRepository;
import com.orderflow.catalog_pricing_service.price.domain.PriceCalculator;
import com.orderflow.catalog_pricing_service.price.domain.PriceTier;
import com.orderflow.catalog_pricing_service.price.infrastructure.PriceTierCache;
import com.orderflow.catalog_pricing_service.product.domain.Product;
import com.orderflow.catalog_pricing_service.product.infrastructure.ProductRepository;

@Service
public class QuoteService {

    private static final Logger log = LoggerFactory.getLogger(QuoteService.class);

    private final ProductRepository productRepository;
    private final PriceTierCache priceTierCache;
    private final PriceCalculator priceCalculator;
    private final Clock clock;
    private final PriceListEntryRepository priceListEntries;

    public QuoteService(ProductRepository productRepository, PriceTierCache priceTierCache,
                        PriceCalculator priceCalculator, Clock clock, PriceListEntryRepository priceListEntries) {
        this.productRepository = productRepository;
        this.priceTierCache = priceTierCache;
        this.priceCalculator = priceCalculator;
        this.clock = clock;
        this.priceListEntries = priceListEntries;
    }

    public QuoteResult quote(QuoteInput input) {
        if (!priceListEntries.customerGroupExists(input.customerGroup())) {
            throw new UnknownCustomerGroupException(input.customerGroup());
        }
        Set<String> skus = input.lines().stream()
            .map(QuoteInput.Line::sku)
            .collect(Collectors.toSet());

        Map<String, Product> products = productRepository.findAllBySkuIn(skus).stream()
            .filter(Product::isActive)
            .collect(Collectors.toMap(Product::getSku, Function.identity()));

        List<String> missingSkus = skus.stream()
            .filter(sku -> !products.containsKey(sku))
            .sorted()
            .toList();
        if (!missingSkus.isEmpty()) {
            throw new UnknownSkusException(missingSkus);
        }

        Map<String, List<PriceTier>> tiersBySku = priceTierCache.find(input.customerGroup(), skus);

        List<QuoteResult.Line> lines = new ArrayList<>();
        BigDecimal totalNet = new BigDecimal("0.00");
        BigDecimal totalVat = new BigDecimal("0.00");
        BigDecimal totalGross = new BigDecimal("0.00");

        for (QuoteInput.Line requestedLine : input.lines()) {
            Product product = products.get(requestedLine.sku());
            var calculation = priceCalculator.calculate(
                input.customerGroup(), requestedLine.sku(), product.getListPriceNet(), product.getVatRate(),
                requestedLine.quantity(), tiersBySku.getOrDefault(requestedLine.sku(), List.of())
            );
            lines.add(new QuoteResult.Line(requestedLine.sku(), requestedLine.quantity(), calculation));
            totalNet = totalNet.add(calculation.lineTotalNet());
            totalVat = totalVat.add(calculation.totalVat());
            totalGross = totalGross.add(calculation.totalGross());
        }

        log.info("Quote calculated for customerGroup={}, lineCount={}", input.customerGroup(), lines.size());
        return new QuoteResult(input.customerGroup(), List.copyOf(lines),
            totalNet, totalVat, totalGross, clock.instant());
    }
}
