package com.example.codreconciliation.dto;

import java.util.List;

public record OrderDetailResponse(OrderResponse order, List<ReconciliationItemResponse> reconciliationHistory) {
}
