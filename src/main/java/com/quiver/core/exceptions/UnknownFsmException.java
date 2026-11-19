package com.quiver.core.exceptions;

/** Thrown when a query references an fsm_id ("table") that has never been DEFINEd. */
public final class UnknownFsmException extends QuiverException {
    public UnknownFsmException(String fsmName) {
        super("no such fsm: '%s'".formatted(fsmName));
    }
}
