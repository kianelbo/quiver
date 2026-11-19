package com.quiver.core.exceptions;

/** Thrown by CREATE when an instance_id already exists for that fsm_id. */
public final class DuplicateInstanceException extends QuiverException {
    public DuplicateInstanceException(String fsmName, String instanceId) {
        super("instance already exists: '%s:%s'".formatted(fsmName, instanceId));
    }
}
