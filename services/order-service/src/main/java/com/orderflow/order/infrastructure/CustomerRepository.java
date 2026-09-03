package com.orderflow.order.infrastructure;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.orderflow.order.domain.Customer;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
}
