package com.orderflow.order.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    @Query(value = """
        select o.id from Order o
        order by o.createdAt desc, o.id desc
        """, countQuery = "select count(o) from Order o")
    Page<UUID> findPageIds(Pageable pageable);

    @Query(value = """
        select o.id from Order o
        where o.customer.id = :customerId
        order by o.createdAt desc, o.id desc
        """, countQuery = "select count(o) from Order o where o.customer.id = :customerId")
    Page<UUID> findPageIdsByCustomer(@Param("customerId") UUID customerId, Pageable pageable);

    @Query(value = """
        select o.id from Order o
        where o.status = :status
        order by o.createdAt desc, o.id desc
        """, countQuery = "select count(o) from Order o where o.status = :status")
    Page<UUID> findPageIdsByStatus(@Param("status") OrderStatus status, Pageable pageable);

    @Query(value = """
        select o.id from Order o
        where o.customer.id = :customerId and o.status = :status
        order by o.createdAt desc, o.id desc
        """, countQuery = """
        select count(o) from Order o where o.customer.id = :customerId and o.status = :status
        """)
    Page<UUID> findPageIdsByCustomerAndStatus(@Param("customerId") UUID customerId,
                                              @Param("status") OrderStatus status, Pageable pageable);

    @Query("""
        select distinct o from Order o
        join fetch o.customer
        left join fetch o.lines
        where o.id in :ids
        """)
    List<Order> findAllWithCustomerAndLines(@Param("ids") List<UUID> ids);

    Optional<Order> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);
}
