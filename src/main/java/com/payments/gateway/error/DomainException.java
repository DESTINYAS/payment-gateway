package com.payments.gateway.error;

/** Base class for business-rule failures. The handler maps each subclass to an HTTP status. */
public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
