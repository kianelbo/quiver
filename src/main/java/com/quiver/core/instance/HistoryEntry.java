package com.quiver.core.instance;

import java.time.Instant;
import java.util.Set;

/**
 * One entry in an instance's append-only input history: the input that was applied,
 * when it was applied, and the set of states that input left the instance able to be
 * in. That set has more than one member exactly when the step was non-deterministic;
 * for a deterministic fsm it is always a singleton.
 *
 * <p>This is what makes queries like "instances whose history matches the pattern
 * (pay, ship, deliver)" possible: the full ordered sequence of inputs is always
 * retained, not just the current state(s).
 */
public record HistoryEntry(String input, Instant timestamp, Set<String> resultingStates) {
    public HistoryEntry {
        resultingStates = Set.copyOf(resultingStates);
    }
}
