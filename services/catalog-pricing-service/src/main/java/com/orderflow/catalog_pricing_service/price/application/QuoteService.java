package com.orderflow.catalog_pricing_service.price.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orderflow.catalog_pricing_service.price.domain.PriceListEntry;
import com.orderflow.catalog_pricing_service.price.domain.PriceCalculator;
import com.orderflow.catalog_pricing_service.price.domain.PriceTier;
import com.orderflow.catalog_pricing_service.price.infrastructure.PriceListEntryRepository;
import com.orderflow.catalog_pricing_service.product.domain.Product;
import com.orderflow.catalog_pricing_service.product.infrastructure.ProductRepository;

@Service
public class QuoteService {

    private final ProductRepository productRepository;
    private final PriceListEntryRepository priceListEntryRepository;
    private final PriceCalculator priceCalculator;
    private final Clock clock;

    public QuoteService(ProductRepository productRepository, PriceListEntryRepository priceListEntryRepository,
                        PriceCalculator priceCalculator, Clock clock) {
        this.productRepository = productRepository;
        this.priceListEntryRepository = priceListEntryRepository;
        this.priceCalculator = priceCalculator;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public QuoteResult quote(QuoteInput input) {
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

        Map<String, List<PriceTier>> tiersBySku = new HashMap<>();
        for (PriceListEntry entry : priceListEntryRepository.findAllByCustomerGroupCodeAndSkuIn(input.customerGroup(), skus)) {
            tiersBySku.computeIfAbsent(entry.getSku(), ignored -> new ArrayList<>())
                .add(new PriceTier(entry.getCustomerGroupCode(), entry.getSku(), entry.getMinQty(),
                    entry.getUnitPriceNet(), entry.getValidFrom(), entry.getValidTo()));
        }

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

        return new QuoteResult(input.customerGroup(), List.copyOf(lines),
            totalNet, totalVat, totalGross, clock.instant());
    }
}
