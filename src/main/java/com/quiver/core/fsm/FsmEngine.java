package com.quiver.core.fsm;

import com.quiver.core.exceptions.InvalidTransitionException;
import com.quiver.core.instance.FsmInstance;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Pure functions that move an {@link FsmInstance} forward according to its
 * {@link FsmDefinition}. Nothing here touches storage: given the same definition,
 * instance, input and clock, the result is always the same.
 *
 * <p>Because a definition may be non-deterministic, a single step is a small subset
 * construction: the next state set is the union, over every state the instance is
 * currently in, of that state's targets on the given input. This is exactly how you'd
 * simulate an NFA -- just performed incrementally, one input at a time, and persisted
 * on the instance instead of thrown away.
 */
public final class FsmEngine {

    private FsmEngine() {
    }

    /**
     * Applies a single input to an instance.
     *
     * @throws InvalidTransitionException if none of the instance's current states have a
     *         transition on this input (this is also what you get feeding input to an
     *         instance every one of whose current states is a terminal state with no
     *         outgoing transitions).
     */
    public static FsmInstance apply(FsmDefinition def, FsmInstance instance, String input, Instant timestamp) {
        Set<String> next = new LinkedHashSet<>();
        for (String state : instance.currentStates()) {
            next.addAll(def.targets(state, input));
        }
        if (next.isEmpty()) {
            throw new InvalidTransitionException(def.name(), instance.instanceId(), instance.currentStates(), input);
        }
        return instance.withTransition(input, next, timestamp);
    }

    /**
     * Applies a sequence of inputs in order, as one logical step. If any input in the
     * sequence is invalid, the whole sequence is rejected and none of it is applied --
     * the caller gets back an exception, not a partially-advanced instance. This is what
     * backs {@code fsm_id:instance_id(a, b, c)} with more than one input in the call.
     */
    public static FsmInstance applyAll(FsmDefinition def, FsmInstance instance, List<String> inputs,
                                        Supplier<Instant> clock) {
        FsmInstance result = instance;
        for (String input : inputs) {
            result = apply(def, result, input, clock.get());
        }
        return result;
    }

    /** True iff any state the instance could currently be in is a terminal state of its fsm. */
    public static boolean isCurrentlyTerminal(FsmDefinition def, FsmInstance instance) {
        return instance.currentStates().stream().anyMatch(def::isTerminal);
    }
}
