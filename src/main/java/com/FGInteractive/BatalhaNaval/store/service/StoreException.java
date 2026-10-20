package com.FGInteractive.BatalhaNaval.store.service;

import org.springframework.http.HttpStatus;

public class StoreException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public StoreException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
