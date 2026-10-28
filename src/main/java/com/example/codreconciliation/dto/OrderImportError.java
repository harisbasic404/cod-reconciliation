package com.example.codreconciliation.dto;

public record OrderImportError(int rowNumber, String trackingNumber, String reason) {
}
