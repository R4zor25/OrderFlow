package com.orderflow.order.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
    private static final IdempotencyKey KEY = new IdempotencyKey("key-1", "a".repeat(64));

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
        when(persistence.persist(any(), any(), any(), any(), any())).thenReturn(expected);

        PlaceOrderResult actual = service.place(command(), KEY);

        assertThat(actual).isEqualTo(expected);
        ArgumentCaptor<QuoteRequest> sentQuote = ArgumentCaptor.forClass(QuoteRequest.class);
        verify(pricing).quote(sentQuote.capture());
        assertThat(sentQuote.getValue().customerGroup()).isEqualTo("B2B_STANDARD");
        assertThat(sentQuote.getValue().lines().getFirst().sku()).isEqualTo("CHAIR-001");
        verify(persistence).persist(eq(customer),
            argThat(lines -> lines.size() == 1 && lines.getFirst().sku().equals("CHAIR-001")
                && lines.getFirst().quantity() == 2
                && lines.getFirst().unitPriceNet().compareTo(new BigDecimal("84.90")) == 0),
            eq(new BigDecimal("45.85")), eq(NOW), eq(KEY));
    }

    @Test
    void doesNotCallPricingWhenCustomerDoesNotExist() {
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.place(command(), KEY)).isInstanceOf(CustomerNotFoundException.class);
        verify(pricing, never()).quote(any());
        verify(persistence, never()).persist(any(), any(), any(), any(), any());
    }

    @Test
    void doesNotPersistWhenPricingFails() {
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer()));
        when(pricing.quote(any())).thenThrow(new PricingUnavailableException("Unavailable", new RuntimeException()));

        assertThatThrownBy(() -> service.place(command(), KEY)).isInstanceOf(PricingUnavailableException.class);
        verify(persistence, never()).persist(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsMismatchedQuoteBeforeWritingOrder() {
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer()));
        QuoteResponse quote = validQuote();
        when(pricing.quote(any())).thenReturn(new QuoteResponse("B2B_STANDARD", "EUR", quote.lines(),
            new BigDecimal("170.00"), quote.totalVat(), quote.totalGross(), quote.quotedAt()));

        assertThatThrownBy(() -> service.place(command(), KEY)).isInstanceOf(PricingUnavailableException.class);
        verify(persistence, never()).persist(any(), any(), any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("quotesThatDoNotMatchTheOrder")
    void rejectsQuoteThatDoesNotMatchTheOrder(String mismatch, PlaceOrderCommand command, QuoteResponse quote) {
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer()));
        when(pricing.quote(any())).thenReturn(quote);

        assertThatThrownBy(() -> service.place(command, KEY)).isInstanceOf(PricingUnavailableException.class);
        verify(persistence, never()).persist(any(), any(), any(), any(), any());
    }

    // Each case breaks exactly one rule of a quote that is otherwise valid for the order.
    static Stream<Arguments> quotesThatDoNotMatchTheOrder() {
        var line = validQuote().lines().getFirst();
        return Stream.of(
            Arguments.of("no quote", command(), null),
            Arguments.of("other customer group", command(), quote("B2C", "EUR", List.of(line), "169.80", "45.85", "215.65")),
            Arguments.of("other currency", command(), quote("B2B_STANDARD", "USD", List.of(line), "169.80", "45.85", "215.65")),
            Arguments.of("no lines", command(), quote("B2B_STANDARD", "EUR", null, "169.80", "45.85", "215.65")),
            Arguments.of("extra line", command(), quote("B2B_STANDARD", "EUR", List.of(line, line), "169.80", "45.85", "215.65")),
            Arguments.of("missing net total", command(), quote("B2B_STANDARD", "EUR", List.of(line), null, "45.85", "215.65")),
            Arguments.of("negative net total", command(), quote("B2B_STANDARD", "EUR", List.of(line), "-169.80", "45.85", "-123.95")),
            Arguments.of("net total with three decimals", command(), quote("B2B_STANDARD", "EUR", List.of(line), "169.800", "45.85", "215.650")),
            Arguments.of("missing VAT total", command(), quote("B2B_STANDARD", "EUR", List.of(line), "169.80", null, "215.65")),
            Arguments.of("gross total with three decimals", command(), quote("B2B_STANDARD", "EUR", List.of(line), "169.80", "45.85", "215.650")),
            Arguments.of("gross is not net plus VAT", command(), quote("B2B_STANDARD", "EUR", List.of(line), "169.80", "45.85", "215.66")),
            Arguments.of("null quoted line", command(), quote("B2B_STANDARD", "EUR", Arrays.asList((QuoteLineResponse) null), "169.80", "45.85", "215.65")),
            Arguments.of("other SKU", command(), quote("B2B_STANDARD", "EUR", List.of(quotedLine("TABLE-001", 2, "84.90", "169.80")), "169.80", "45.85", "215.65")),
            Arguments.of("other quantity", command(), quote("B2B_STANDARD", "EUR", List.of(quotedLine("CHAIR-001", 3, "56.60", "169.80")), "169.80", "45.85", "215.65")),
            Arguments.of("missing unit price", command(), quote("B2B_STANDARD", "EUR", List.of(quotedLine("CHAIR-001", 2, null, "169.80")), "169.80", "45.85", "215.65")),
            Arguments.of("missing line total", command(), quote("B2B_STANDARD", "EUR", List.of(quotedLine("CHAIR-001", 2, "84.90", null)), "169.80", "45.85", "215.65")),
            Arguments.of("line total is not unit times quantity", command(), quote("B2B_STANDARD", "EUR", List.of(quotedLine("CHAIR-001", 2, "84.91", "169.80")), "169.80", "45.85", "215.65")),
            Arguments.of("line totals do not add up to the net total", command(), quote("B2B_STANDARD", "EUR", List.of(line), "170.00", "45.65", "215.65")),
            Arguments.of("blank requested SKU", command(" ", 2), quote("B2B_STANDARD", "EUR", List.of(quotedLine(" ", 2, "84.90", "169.80")), "169.80", "45.85", "215.65")),
            Arguments.of("non-positive requested quantity", command("CHAIR-001", 0), quote("B2B_STANDARD", "EUR", List.of(quotedLine("CHAIR-001", 0, "84.90", "0.00")), "0.00", "0.00", "0.00")));
    }

    private static PlaceOrderCommand command(String sku, int quantity) {
        return new PlaceOrderCommand(CUSTOMER_ID, List.of(new PlaceOrderCommand.Line(sku, quantity)));
    }

    private static QuoteLineResponse quotedLine(String sku, int quantity, String unitPriceNet, String lineTotalNet) {
        return new QuoteLineResponse(sku, quantity, unitPriceNet == null ? null : new BigDecimal(unitPriceNet),
            lineTotalNet == null ? null : new BigDecimal(lineTotalNet), new BigDecimal("45.85"), null, "BASE");
    }

    private static QuoteResponse quote(String group, String currency, List<QuoteLineResponse> lines,
                                       String totalNet, String totalVat, String totalGross) {
        return new QuoteResponse(group, currency, lines, totalNet == null ? null : new BigDecimal(totalNet),
            totalVat == null ? null : new BigDecimal(totalVat), new BigDecimal(totalGross), NOW);
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
