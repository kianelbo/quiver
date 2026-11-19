package com.quiver.core.exceptions;

import java.util.Set;

/**
 * Thrown when an input is applied to an instance but none of the fsm states the
 * instance is currently in have a transition on that input (this includes the common
 * case of trying to feed input to an instance every one of whose current states is a
 * terminal state with no outgoing transitions).
 */
public final class InvalidTransitionException extends QuiverException {
    public InvalidTransitionException(String fsmName, String instanceId, Set<String> fromStates, String input) {
        super("'%s:%s' has no transition from state(s) %s on input '%s'"
                .formatted(fsmName, instanceId, fromStates, input));
    }
}
