package com.orderflow.order.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.orderflow.order.domain.Customer;
import com.orderflow.order.domain.CustomerNotFoundException;
import com.orderflow.order.domain.CustomerType;
import com.orderflow.order.domain.OrderStatus;
import com.orderflow.order.infrastructure.CustomerRepository;
import com.orderflow.order.infrastructure.pricing.PricingGateway;
import com.orderflow.order.infrastructure.pricing.PricingUnavailableException;
import com.orderflow.order.infrastructure.pricing.QuoteLineResponse;
import com.orderflow.order.infrastructure.pricing.QuoteRequest;
import com.orderflow.order.infrastructure.pricing.QuoteResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaceOrderServiceTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant NOW = Instant.parse("2026-10-01T10:15:31Z");

    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final PricingGateway pricing = mock(PricingGateway.class);
    private final PersistPlacedOrderService persistence = mock(PersistPlacedOrderService.class);
    private final PlaceOrderService service = new PlaceOrderService(customers, pricing, persistence,
        Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void quotesUsingCustomerGroupThenPersistsPricedLines() {
        Customer customer = customer();
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));
        when(pricing.quote(any())).thenReturn(validQuote());
        var expected = new PlaceOrderResult(UUID.randomUUID(), OrderStatus.PENDING, "EUR",
            new BigDecimal("169.80"), new BigDecimal("45.85"), new BigDecimal("215.65"));
        when(persistence.persist(any(), any(), any(), any())).thenReturn(expected);

        PlaceOrderResult actual = service.place(command());

        assertThat(actual).isEqualTo(expected);
        ArgumentCaptor<QuoteRequest> sentQuote = ArgumentCaptor.forClass(QuoteRequest.class);
        verify(pricing).quote(sentQuote.capture());
        assertThat(sentQuote.getValue().customerGroup()).isEqualTo("B2B_STANDARD");
        assertThat(sentQuote.getValue().lines().getFirst().sku()).isEqualTo("CHAIR-001");
        verify(persistence).persist(eq(customer),
            argThat(lines -> lines.size() == 1 && lines.getFirst().sku().equals("CHAIR-001")
                && lines.getFirst().quantity() == 2
                && lines.getFirst().unitPriceNet().compareTo(new BigDecimal("84.90")) == 0),
            eq(new BigDecimal("45.85")), eq(NOW));
    }

    @Test
    void doesNotCallPricingWhenCustomerDoesNotExist() {
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.place(command())).isInstanceOf(CustomerNotFoundException.class);
        verify(pricing, never()).quote(any());
        verify(persistence, never()).persist(any(), any(), any(), any());
    }

    @Test
    void doesNotPersistWhenPricingFails() {
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer()));
        when(pricing.quote(any())).thenThrow(new PricingUnavailableException("Unavailable", new RuntimeException()));

        assertThatThrownBy(() -> service.place(command())).isInstanceOf(PricingUnavailableException.class);
        verify(persistence, never()).persist(any(), any(), any(), any());
    }

    @Test
    void rejectsMismatchedQuoteBeforeWritingOrder() {
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer()));
        QuoteResponse quote = validQuote();
        when(pricing.quote(any())).thenReturn(new QuoteResponse("B2B_STANDARD", "EUR", quote.lines(),
            new BigDecimal("170.00"), quote.totalVat(), quote.totalGross(), quote.quotedAt()));

        assertThatThrownBy(() -> service.place(command())).isInstanceOf(PricingUnavailableException.class);
        verify(persistence, never()).persist(any(), any(), any(), any());
    }

    private static Customer customer() {
        return Customer.create(CUSTOMER_ID, "Sample Business", "business@example.test",
            CustomerType.B2B, "B2B_STANDARD");
    }

    private static PlaceOrderCommand command() {
        return new PlaceOrderCommand(CUSTOMER_ID, List.of(new PlaceOrderCommand.Line("CHAIR-001", 2)));
    }

    private static QuoteResponse validQuote() {
        return new QuoteResponse("B2B_STANDARD", "EUR", List.of(
            new QuoteLineResponse("CHAIR-001", 2, new BigDecimal("84.90"),
                new BigDecimal("169.80"), new BigDecimal("45.85"), null, "BASE")),
            new BigDecimal("169.80"), new BigDecimal("45.85"), new BigDecimal("215.65"), NOW);
    }
}
