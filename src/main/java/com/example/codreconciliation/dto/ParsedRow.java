package com.example.codreconciliation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ParsedRow(int rowNumber, String trackingNumber, BigDecimal collectedAmount,
                        LocalDate deliveryDate, List<String> errors) {
    public boolean hasData() {
        return trackingNumber != null || collectedAmount != null || deliveryDate != null || !errors.isEmpty();
    }

    public boolean isValid() {
        return errors.isEmpty() && trackingNumber != null && collectedAmount != null && deliveryDate != null;
    }
}
