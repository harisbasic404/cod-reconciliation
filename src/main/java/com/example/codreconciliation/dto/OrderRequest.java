package com.example.codreconciliation.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record OrderRequest(
        @NotBlank(message = "Tracking number is required.")
        String trackingNumber,
        @NotBlank(message = "Customer name is required.")
        String customerName,
        @NotNull(message = "Expected amount is required.")
        @DecimalMin(value = "0.01", message = "Expected amount must be greater than zero.")
        BigDecimal expectedAmount) {
}
