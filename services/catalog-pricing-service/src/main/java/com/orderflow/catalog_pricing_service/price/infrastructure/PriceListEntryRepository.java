package com.orderflow.catalog_pricing_service.price.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.orderflow.catalog_pricing_service.price.domain.PriceListEntry;

public interface PriceListEntryRepository extends JpaRepository<PriceListEntry, UUID> {

    List<PriceListEntry> findAllByCustomerGroupCodeAndSkuIn(String customerGroupCode, Collection<String> skus);

    @Modifying
    @Query("delete from PriceListEntry entry where entry.customerGroupCode = :group and entry.sku = :sku")
    void deleteForGroupAndSku(@Param("group") String group, @Param("sku") String sku);

    @Query(value = "select sku as sku, price_version as priceVersion from catalog.product where sku in (:skus)",
        nativeQuery = true)
    List<PriceVersion> findPriceVersions(@Param("skus") Collection<String> skus);

    @Query(value = "select exists (select 1 from catalog.customer_group where code = :code)", nativeQuery = true)
    boolean customerGroupExists(@Param("code") String code);

    interface PriceVersion {

        String getSku();

        long getPriceVersion();
    }
}
