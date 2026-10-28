package com.example.codreconciliation.entity;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.codreconciliation.enums.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_orders_customer_name", columnList = "customer_name"),
        @Index(name = "idx_orders_status", columnList = "status")
})
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tracking_number", nullable = false, unique = true, length = 64)
    private String trackingNumber;

    @Column(name = "customer_name", nullable = false, length = 160)
    private String customerName;

    @Column(name = "expected_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal expectedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Order() {
    }

    public Order(String trackingNumber, String customerName, BigDecimal expectedAmount, OrderStatus status) {
        this.trackingNumber = trackingNumber;
        this.customerName = customerName;
        this.expectedAmount = expectedAmount;
        this.status = status;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTrackingNumber() { return trackingNumber; }
    public String getCustomerName() { return customerName; }
    public BigDecimal getExpectedAmount() { return expectedAmount; }
    public OrderStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void updateDetails(String trackingNumber, String customerName, BigDecimal expectedAmount) {
        this.trackingNumber = trackingNumber;
        this.customerName = customerName;
        this.expectedAmount = expectedAmount;
    }
    public void setStatus(OrderStatus status) { this.status = status; }
}
