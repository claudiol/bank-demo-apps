package com.northwater;

/** A transfer or sign-in was rejected before any money moved. */
public final class FormException extends RuntimeException {
    public FormException(String message) {
        super(message);
    }
}
