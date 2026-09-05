package com.orderflow.order.infrastructure;

import java.util.List;
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
        where (:customerId is null or o.customer.id = :customerId)
          and (:status is null or o.status = :status)
        order by o.createdAt desc, o.id desc
        """, countQuery = """
        select count(o) from Order o
        where (:customerId is null or o.customer.id = :customerId)
          and (:status is null or o.status = :status)
        """)
    Page<UUID> findPageIds(@Param("customerId") UUID customerId,
                           @Param("status") OrderStatus status, Pageable pageable);

    @Query("""
        select distinct o from Order o
        join fetch o.customer
        left join fetch o.lines
        where o.id in :ids
        """)
    List<Order> findAllWithCustomerAndLines(@Param("ids") List<UUID> ids);
}
