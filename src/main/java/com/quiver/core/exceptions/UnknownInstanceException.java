package com.quiver.core.exceptions;

/** Thrown when a query references an instance_id ("row") that does not exist. */
public final class UnknownInstanceException extends QuiverException {
    public UnknownInstanceException(String fsmName, String instanceId) {
        super("no such instance: '%s:%s'".formatted(fsmName, instanceId));
    }
}
