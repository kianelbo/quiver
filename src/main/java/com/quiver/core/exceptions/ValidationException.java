package com.quiver.core.exceptions;

/** Thrown while building an {@code FsmDefinition} that is internally inconsistent. */
public final class ValidationException extends QuiverException {
    public ValidationException(String message) {
        super(message);
    }
}
