package com.example.codreconciliation.exception;

public class DuplicateTrackingNumberException extends RuntimeException {
    public DuplicateTrackingNumberException(String trackingNumber) {
        super("An order with tracking number '" + trackingNumber + "' already exists.");
    }
}
