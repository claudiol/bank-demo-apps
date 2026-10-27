package com.northwater;

/** A transfer or sign-in was rejected before any money moved. */
public final class FormException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public FormException(String message) {
        super(message);
    }

    public FormException(String message, Throwable cause) {
        super(message, cause);
    }
}
