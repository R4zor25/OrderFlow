package com.orderflow.catalog_pricing_service.product;

import com.orderflow.catalog_pricing_service.PostgresTestConfiguration;
import com.orderflow.catalog_pricing_service.product.domain.Product;
import com.orderflow.catalog_pricing_service.product.infrastructure.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfiguration.class)
class ProductRepositoryTest {

    private final ProductRepository repository;

    @Autowired
    ProductRepositoryTest(ProductRepository repository) {
        this.repository = repository;
    }

    @Test
    void findsSeededProductBySku() {
        var product = repository.findBySku("CHAIR-001");

        assertThat(product).isPresent();
        assertThat(product.orElseThrow().getName()).isEqualTo("Ergonomic Office Chair");
    }

    @Test
    void pagesProductsWithinCategory() {
        var firstPage = repository.findByCategory(
            "FURNITURE", PageRequest.of(0, 2, Sort.by("sku").ascending())
        );

        assertThat(firstPage.getTotalElements()).isEqualTo(8);
        assertThat(firstPage.getContent()).extracting(Product::getSku)
            .containsExactly("CHAIR-001", "CHAIR-002");
    }
}
