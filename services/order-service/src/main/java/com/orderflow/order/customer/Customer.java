package com.orderflow.order.customer;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "customer", schema = "orders")
public class Customer {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private CustomerType type;

    @Column(name = "customer_group_code", nullable = false, length = 32)
    private String customerGroupCode;

    protected Customer() {
    }

    private Customer(UUID id, String name, String email, CustomerType type, String customerGroupCode) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.type = type;
        this.customerGroupCode = customerGroupCode;
    }

    public static Customer create(UUID id, String name, String email, CustomerType type, String customerGroupCode) {
        return new Customer(id, name, email, type, customerGroupCode);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public CustomerType getType() {
        return type;
    }

    public String getCustomerGroupCode() {
        return customerGroupCode;
    }
}
