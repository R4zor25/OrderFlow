package com.orderflow.catalog_pricing_service.price.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.orderflow.catalog_pricing_service.price.domain.PriceListEntry;

public interface PriceListEntryRepository extends JpaRepository<PriceListEntry, UUID> {

    List<PriceListEntry> findAllByCustomerGroupCodeAndSkuIn(String customerGroupCode, Collection<String> skus);
}
