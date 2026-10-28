package com.example.codreconciliation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.example.codreconciliation.enums.ReconciliationOutcome;

public record ReconciliationItemResponse(Long id, int rowNumber, String trackingNumber,
                                         BigDecimal expectedAmount, BigDecimal collectedAmount,
                                         LocalDate deliveryDate, ReconciliationOutcome outcome,
                                         String message) {
}
