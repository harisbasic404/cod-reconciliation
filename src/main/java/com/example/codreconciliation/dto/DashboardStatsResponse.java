package com.example.codreconciliation.dto;

public record DashboardStatsResponse(long totalOrders, long pendingOrders, long reconciledOrders,
                                     long amountMismatchOrders, long totalBatches,
                                     long totalRowsProcessed, long successfulMatches,
                                     long historicalMismatches, long historicalNotFound,
                                     long historicalErrors) {
}
