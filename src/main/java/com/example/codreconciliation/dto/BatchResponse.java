package com.example.codreconciliation.dto;

import java.time.Instant;
import java.util.List;

public record BatchResponse(Long batchId, String fileName, Instant uploadedAt, int totalRows,
                            int successfulMatches, int mismatches, int notFound, int errors,
                            List<ReconciliationItemResponse> items) {
}
