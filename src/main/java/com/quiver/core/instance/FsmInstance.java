package com.quiver.core.instance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A running instance of an fsm definition: the "row" in the FSM-as-table analogy.
 * Immutable -- every transition produces a new {@code FsmInstance}; nothing is mutated
 * in place.
 *
 * <p>{@code currentStates} is a <em>set</em>, not a single state, because the fsm it
 * belongs to may be non-deterministic: the instance is understood to be in any one of
 * these states, consistent with every input applied so far (standard NFA semantics).
 * For a deterministic fsm this set always has exactly one element, so day-to-day code
 * working only with deterministic machines can just call {@link #soleCurrentState()}.
 *
 * <p>{@code version} is a monotonically increasing counter bumped on every transition.
 * The storage layer uses it to detect write-write conflicts under optimistic concurrency
 * control.
 */
public final class FsmInstance {

    private final String fsmName;
    private final String instanceId;
    private final Set<String> currentStates;
    private final List<HistoryEntry> history;
    private final Map<String, Object> attributes;
    private final long version;
    private final Instant createdAt;

    private FsmInstance(String fsmName, String instanceId, Set<String> currentStates,
                         List<HistoryEntry> history, Map<String, Object> attributes,
                         long version, Instant createdAt) {
        if (currentStates.isEmpty()) {
            throw new IllegalArgumentException("an instance must always be in at least one state");
        }
        this.fsmName = fsmName;
        this.instanceId = instanceId;
        this.currentStates = Set.copyOf(currentStates);
        this.history = List.copyOf(history);
        this.attributes = Map.copyOf(attributes);
        this.version = version;
        this.createdAt = createdAt;
    }

    /** Creates a brand-new instance sitting in its fsm's initial state, with no history yet. */
    public static FsmInstance created(String fsmName, String instanceId, String initialState,
                                       Map<String, Object> attributes, Instant now) {
        return new FsmInstance(fsmName, instanceId, Set.of(initialState), List.of(), attributes, 0L, now);
    }

    /** Returns a new instance reflecting one more applied input. Does not mutate {@code this}. */
    public FsmInstance withTransition(String input, Set<String> newStates, Instant timestamp) {
        var newHistory = new ArrayList<>(history);
        newHistory.add(new HistoryEntry(input, timestamp, newStates));
        return new FsmInstance(fsmName, instanceId, newStates, newHistory, attributes, version + 1, createdAt);
    }

    public String fsmName() {
        return fsmName;
    }

    public String instanceId() {
        return instanceId;
    }

    /** Every state this instance could currently be in. Never empty. */
    public Set<String> currentStates() {
        return currentStates;
    }

    /**
     * The single current state, for callers working with a definition they know to be
     * deterministic.
     *
     * @throws IllegalStateException if the instance is currently in more than one state
     */
    public String soleCurrentState() {
        if (currentStates.size() != 1) {
            throw new IllegalStateException(
                    "'%s:%s' is in %d states at once (%s) -- this fsm step was non-deterministic, so there is no single current state"
                            .formatted(fsmName, instanceId, currentStates.size(), currentStates));
        }
        return currentStates.iterator().next();
    }

    public List<HistoryEntry> history() {
        return history;
    }

    /** Just the input symbols, in order -- the sequence that HISTORY LIKE patterns match against. */
    public List<String> inputSequence() {
        return history.stream().map(HistoryEntry::input).toList();
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String key() {
        return fsmName + ":" + instanceId;
    }

    @Override
    public String toString() {
        return "%s:%s[states=%s, version=%d, history=%s]".formatted(fsmName, instanceId, currentStates, version, inputSequence());
    }
}
