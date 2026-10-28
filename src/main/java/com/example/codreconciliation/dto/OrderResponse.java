package com.example.codreconciliation.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.codreconciliation.enums.OrderStatus;

public record OrderResponse(Long id, String trackingNumber, String customerName,
                            BigDecimal expectedAmount, OrderStatus status,
                            Instant createdAt, Instant updatedAt) {
}
