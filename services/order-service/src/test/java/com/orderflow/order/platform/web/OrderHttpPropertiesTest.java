package com.orderflow.order.platform.web;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderHttpPropertiesTest {

    @Test
    void acceptsPositiveLimit() {
        assertThat(new OrderHttpProperties(DataSize.ofKilobytes(64)).maxRequestBodySize().toBytes()).isEqualTo(65536);
    }

    @Test
    void rejectsMissingOrNonPositiveLimit() {
        assertThatThrownBy(() -> new OrderHttpProperties(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderHttpProperties(DataSize.ofBytes(0))).isInstanceOf(IllegalArgumentException.class);
    }
}
