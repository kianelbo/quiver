package com.quiver.core.fsm;

/** A (state, input) pair -- the lookup key of the transition table. */
public record TransitionKey(String state, String input) {
}
