package com.example.codreconciliation.exception;

public class ExcelParsingException extends RuntimeException {
    public ExcelParsingException(String message) {
        super(message);
    }

    public ExcelParsingException(String message, Throwable cause) {
        super(message, cause);
    }
}
