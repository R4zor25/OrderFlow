package com.orderflow.catalog_pricing_service.config;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogHttpPropertiesTest {

    @Test
    void acceptsPositiveLimit() {
        assertThat(new CatalogHttpProperties(DataSize.ofKilobytes(64)).maxRequestBodySize().toBytes()).isEqualTo(65536);
    }

    @Test
    void rejectsMissingOrNonPositiveLimit() {
        assertThatThrownBy(() -> new CatalogHttpProperties(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatalogHttpProperties(DataSize.ofBytes(0))).isInstanceOf(IllegalArgumentException.class);
    }
}
