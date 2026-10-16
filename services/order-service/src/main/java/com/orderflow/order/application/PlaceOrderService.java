package com.orderflow.order.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.orderflow.order.domain.Customer;
import com.orderflow.order.domain.CustomerNotFoundException;
import com.orderflow.order.domain.InvalidOrderException;
import com.orderflow.order.domain.Order;
import com.orderflow.order.infrastructure.CustomerRepository;
import com.orderflow.order.infrastructure.pricing.PricingGateway;
import com.orderflow.order.infrastructure.pricing.PricingUnavailableException;
import com.orderflow.order.infrastructure.pricing.QuoteLineRequest;
import com.orderflow.order.infrastructure.pricing.QuoteLineResponse;
import com.orderflow.order.infrastructure.pricing.QuoteRequest;
import com.orderflow.order.infrastructure.pricing.QuoteResponse;
import org.springframework.stereotype.Service;

@Service
public class PlaceOrderService {

    private final CustomerRepository customers;
    private final PricingGateway pricing;
    private final PersistPlacedOrderService persistence;
    private final Clock clock;

    public PlaceOrderService(CustomerRepository customers, PricingGateway pricing,
                             PersistPlacedOrderService persistence, Clock clock) {
        this.customers = customers;
        this.pricing = pricing;
        this.persistence = persistence;
        this.clock = clock;
    }

    public PlaceOrderResult place(PlaceOrderCommand command, IdempotencyKey idempotency) {
        if (command == null || command.customerId() == null || command.lines() == null
            || command.lines().isEmpty()) {
            throw new InvalidOrderException("Customer and at least one order line are required");
        }
        Customer customer = customers.findById(command.customerId())
            .orElseThrow(() -> new CustomerNotFoundException(command.customerId()));
        List<QuoteLineRequest> quoteLines = command.lines().stream()
            .map(line -> new QuoteLineRequest(line.sku(), line.quantity()))
            .toList();
        QuoteResponse quote = pricing.quote(new QuoteRequest(customer.getCustomerGroupCode(), quoteLines));
        List<Order.PricedLine> pricedLines = verifiedLines(command, customer, quote);
        return persistence.persist(customer, pricedLines, quote.totalVat(), Instant.now(clock), idempotency);
    }

    private List<Order.PricedLine> verifiedLines(PlaceOrderCommand command, Customer customer, QuoteResponse quote) {
        if (!quoteMatches(quote, customer, command.lines().size())) {
            throw invalidQuote();
        }
        List<Order.PricedLine> pricedLines = new ArrayList<>();
        BigDecimal summedNet = BigDecimal.ZERO;
        for (int index = 0; index < command.lines().size(); index++) {
            QuoteLineResponse priced = quote.lines().get(index);
            if (!lineMatches(command.lines().get(index), priced)) {
                throw invalidQuote();
            }
            summedNet = summedNet.add(priced.lineTotalNet());
            pricedLines.add(new Order.PricedLine(priced.sku(), priced.quantity(), priced.unitPriceNet()));
        }
        if (summedNet.compareTo(quote.totalNet()) != 0) {
            throw invalidQuote();
        }
        return List.copyOf(pricedLines);
    }

    private static boolean quoteMatches(QuoteResponse quote, Customer customer, int lineCount) {
        return quote != null && customer.getCustomerGroupCode().equals(quote.customerGroup())
            && "EUR".equals(quote.currency()) && quote.lines() != null && quote.lines().size() == lineCount
            && validMoney(quote.totalNet()) && validMoney(quote.totalVat()) && validMoney(quote.totalGross())
            && quote.totalGross().compareTo(quote.totalNet().add(quote.totalVat())) == 0;
    }

    private static boolean lineMatches(PlaceOrderCommand.Line requested, QuoteLineResponse priced) {
        return requested != null && priced != null && requested.sku() != null && !requested.sku().isBlank()
            && requested.quantity() > 0 && requested.sku().equals(priced.sku())
            && requested.quantity() == priced.quantity()
            && validMoney(priced.unitPriceNet()) && validMoney(priced.lineTotalNet())
            && priced.lineTotalNet()
                .compareTo(priced.unitPriceNet().multiply(BigDecimal.valueOf(priced.quantity()))) == 0;
    }

    private static boolean validMoney(BigDecimal amount) {
        return amount != null && amount.signum() >= 0 && amount.scale() <= 2;
    }

    private static PricingUnavailableException invalidQuote() {
        return new PricingUnavailableException("Pricing service returned an invalid quote",
            new IllegalStateException("Quote does not match the requested order"));
    }
}
