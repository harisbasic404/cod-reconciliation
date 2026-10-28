package com.example.codreconciliation.dto;

import java.util.List;
import java.util.UUID;

public record OrderImportPreviewResponse(
        UUID importId,
        int totalRows,
        int validRows,
        int errorRows,
        int duplicateRows,
        int existingRows,
        List<OrderImportError> errors) {
}
