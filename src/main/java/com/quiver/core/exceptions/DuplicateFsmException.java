package com.quiver.core.exceptions;

/** Thrown by DEFINE FSM when the fsm_id is already taken. There is no REDEFINE/ALTER yet. */
public final class DuplicateFsmException extends QuiverException {
    public DuplicateFsmException(String fsmName) {
        super("fsm already defined: '%s'".formatted(fsmName));
    }
}
