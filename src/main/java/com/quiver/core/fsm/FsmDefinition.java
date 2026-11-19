package com.quiver.core.fsm;

import com.quiver.core.exceptions.ValidationException;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The schema of a "table" in the FSM-as-schema analogy: a named finite state machine
 * with a fixed set of states, one initial state, zero or more terminal (accepting)
 * states, a declared input alphabet, and a transition relation.
 *
 * <p><b>Non-determinism is a first-class feature, not an edge case.</b> A given
 * (state, input) pair may map to more than one target state -- exactly like a
 * classical NFA. An {@code FsmInstance} therefore tracks the <em>set</em> of states
 * it could currently be in, consistent with every input it has received so far
 * (i.e. the standard NFA subset-construction view, computed lazily per instance
 * instead of precomputed into a DFA). A deterministic fsm is simply the special
 * case where every transition set happens to have size 1; nothing about the model
 * treats determinism specially.
 *
 * <p>Instances of this class are immutable and safe to share across threads and
 * transactions once built.
 */
public final class FsmDefinition {

    private final String name;
    private final Set<String> states;
    private final String initialState;
    private final Set<String> terminalStates;
    private final Set<String> alphabet;
    private final Map<TransitionKey, Set<String>> transitions;

    private FsmDefinition(Builder b) {
        this.name = b.name;
        this.states = Set.copyOf(b.states);
        this.initialState = b.initialState;
        this.terminalStates = Set.copyOf(b.terminalStates);
        this.alphabet = Set.copyOf(b.alphabet);
        Map<TransitionKey, Set<String>> frozen = new LinkedHashMap<>();
        b.transitions.forEach((k, v) -> frozen.put(k, Set.copyOf(v)));
        this.transitions = Map.copyOf(frozen);
        validate();
    }

    private void validate() {
        if (name == null || name.isBlank()) {
            throw new ValidationException("fsm name must not be blank");
        }
        if (states.isEmpty()) {
            throw new ValidationException("fsm '%s' must declare at least one state".formatted(name));
        }
        if (initialState == null) {
            throw new ValidationException("fsm '%s' must declare an INITIAL state".formatted(name));
        }
        if (!states.contains(initialState)) {
            throw new ValidationException(
                    "fsm '%s': initial state '%s' is not one of the declared states %s"
                            .formatted(name, initialState, states));
        }
        for (String t : terminalStates) {
            if (!states.contains(t)) {
                throw new ValidationException(
                        "fsm '%s': terminal state '%s' is not one of the declared states %s"
                                .formatted(name, t, states));
            }
        }
        for (var entry : transitions.entrySet()) {
            TransitionKey k = entry.getKey();
            if (!states.contains(k.state())) {
                throw new ValidationException(
                        "fsm '%s': transition references undeclared state '%s'".formatted(name, k.state()));
            }
            if (!alphabet.contains(k.input())) {
                throw new ValidationException(
                        "fsm '%s': transition references undeclared input '%s'".formatted(name, k.input()));
            }
            for (String target : entry.getValue()) {
                if (!states.contains(target)) {
                    throw new ValidationException(
                            "fsm '%s': transition targets undeclared state '%s'".formatted(name, target));
                }
            }
        }
    }

    public String name() {
        return name;
    }

    public Set<String> states() {
        return states;
    }

    public String initialState() {
        return initialState;
    }

    public Set<String> terminalStates() {
        return terminalStates;
    }

    public Set<String> alphabet() {
        return alphabet;
    }

    public boolean isTerminal(String state) {
        return terminalStates.contains(state);
    }

    public boolean isInitial(String state) {
        return initialState.equals(state);
    }

    /**
     * Returns every state reachable from {@code state} on {@code input}. Empty if there is
     * no such transition; size 1 for a deterministic step; size &gt;1 for a non-deterministic one.
     */
    public Set<String> targets(String state, String input) {
        return transitions.getOrDefault(new TransitionKey(state, input), Set.of());
    }

    public Map<TransitionKey, Set<String>> transitions() {
        return transitions;
    }

    /** True iff every declared transition has exactly one target -- i.e. this fsm behaves like a DFA. */
    public boolean isDeterministic() {
        return transitions.values().stream().allMatch(s -> s.size() <= 1);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    @Override
    public String toString() {
        return "FsmDefinition[%s]".formatted(name);
    }

    /** Validating builder. Call order does not matter; validation happens once, in {@link #build()}. */
    public static final class Builder {
        private final String name;
        private final Set<String> states = new LinkedHashSet<>();
        private String initialState;
        private final Set<String> terminalStates = new LinkedHashSet<>();
        private final Set<String> alphabet = new LinkedHashSet<>();
        private final Map<TransitionKey, Set<String>> transitions = new LinkedHashMap<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder states(String... states) {
            for (String s : states) this.states.add(s);
            return this;
        }

        public Builder initial(String state) {
            this.initialState = state;
            this.states.add(state);
            return this;
        }

        public Builder terminal(String... states) {
            for (String s : states) {
                this.terminalStates.add(s);
                this.states.add(s);
            }
            return this;
        }

        public Builder alphabet(String... inputs) {
            for (String i : inputs) this.alphabet.add(i);
            return this;
        }

        /**
         * Declares a transition from {@code from} to {@code to} on {@code input}. Calling this
         * more than once for the same (from, input) with a <em>different</em> {@code to} does not
         * error -- it simply makes the fsm non-deterministic on that (state, input) pair, exactly
         * as declaring two out-edges with the same label does in a hand-drawn NFA diagram.
         */
        /**
         * Note: unlike {@link #initial} and {@link #terminal}, this does <em>not</em>
         * implicitly declare {@code from}/{@code to} as states. A transition naming a
         * state that was never declared via {@link #states} is a validation error at
         * {@link #build()} -- deliberately, so a typo'd state name fails loudly instead
         * of silently creating a new "ghost" state that nothing else ever reaches.
         */
        public Builder transition(String from, String input, String to) {
            alphabet.add(input);
            transitions.computeIfAbsent(new TransitionKey(from, input), k -> new LinkedHashSet<>()).add(to);
            return this;
        }

        public FsmDefinition build() {
            return new FsmDefinition(this);
        }
    }
}
