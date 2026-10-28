package com.example.codreconciliation.dto;

import java.time.Instant;

public record ReconciliationResult(Long batchId, String fileName, Instant uploadedAt,
                                   int totalRows, int successfulMatches, int mismatches,
                                   int notFound, int errors) {
}
