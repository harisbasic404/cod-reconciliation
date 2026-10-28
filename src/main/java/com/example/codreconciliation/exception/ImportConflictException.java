package com.example.codreconciliation.exception;

public class ImportConflictException extends RuntimeException {
    public ImportConflictException(String message) {
        super(message);
    }
}
