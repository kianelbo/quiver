package com.quiver.core.fsm;

import com.quiver.core.exceptions.InvalidTransitionException;
import com.quiver.core.instance.FsmInstance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FsmEngineTest {

    private final FsmDefinition order = FsmDefinition.builder("order")
            .states("created", "paid", "shipped", "delivered", "cancelled")
            .initial("created")
            .terminal("delivered", "cancelled")
            .alphabet("pay", "ship", "deliver", "cancel")
            .transition("created", "pay", "paid")
            .transition("paid", "ship", "shipped")
            .transition("shipped", "deliver", "delivered")
            .transition("created", "cancel", "cancelled")
            .transition("paid", "cancel", "cancelled")
            .build();

    // A non-deterministic fsm: from "submitted" on "decide" the reviewer might land the
    // application in EITHER "approved" or "manual_review", and we genuinely don't know
    // which until something else (a later input) disambiguates it.
    private final FsmDefinition review = FsmDefinition.builder("review")
            .states("submitted", "approved", "manual_review", "rejected")
            .initial("submitted")
            .terminal("approved", "rejected")
            .alphabet("decide", "escalate", "override")
            .transition("submitted", "decide", "approved")
            .transition("submitted", "decide", "manual_review")
            .transition("manual_review", "escalate", "rejected")
            .transition("manual_review", "override", "approved")
            .build();

    @Test
    void appliesASingleValidInputDeterministically() {
        var instance = FsmInstance.created("order", "o1", "created", Map.of(), Instant.now());
        var next = FsmEngine.apply(order, instance, "pay", Instant.now());
        assertEquals(Set.of("paid"), next.currentStates());
        assertEquals("paid", next.soleCurrentState());
        assertEquals(List.of("pay"), next.inputSequence());
        assertEquals(1L, next.version());
    }

    @Test
    void rejectsInvalidInputFromCurrentState() {
        var instance = FsmInstance.created("order", "o1", "created", Map.of(), Instant.now());
        assertThrows(InvalidTransitionException.class, () -> FsmEngine.apply(order, instance, "ship", Instant.now()));
    }

    @Test
    void rejectsAnyInputOnceEveryCurrentStateIsTerminalWithNoOutgoingTransitions() {
        var instance = FsmInstance.created("order", "o1", "delivered", Map.of(), Instant.now());
        assertThrows(InvalidTransitionException.class, () -> FsmEngine.apply(order, instance, "cancel", Instant.now()));
    }

    @Test
    void applyAllAppliesInputsInOrderAndRecordsHistory() {
        var instance = FsmInstance.created("order", "o1", "created", Map.of(), Instant.now());
        var result = FsmEngine.applyAll(order, instance, List.of("pay", "ship", "deliver"), Instant::now);
        assertEquals("delivered", result.soleCurrentState());
        assertEquals(List.of("pay", "ship", "deliver"), result.inputSequence());
        assertTrue(FsmEngine.isCurrentlyTerminal(order, result));
    }

    @Test
    void applyAllIsAllOrNothingOnInvalidInputMidSequence() {
        var instance = FsmInstance.created("order", "o1", "created", Map.of(), Instant.now());
        assertThrows(InvalidTransitionException.class,
                () -> FsmEngine.applyAll(order, instance, List.of("pay", "notaninput"), Instant::now));
    }

    @Test
    void nonDeterministicStepLandsInstanceInMultipleStatesAtOnce() {
        var instance = FsmInstance.created("review", "r1", "submitted", Map.of(), Instant.now());
        var afterDecide = FsmEngine.apply(review, instance, "decide", Instant.now());

        assertEquals(Set.of("approved", "manual_review"), afterDecide.currentStates());
        assertThrows(IllegalStateException.class, afterDecide::soleCurrentState);
        // one of the two branches is already terminal ("approved"), so the instance as a
        // whole reads as terminal even though it might "really" be in manual_review
        assertTrue(FsmEngine.isCurrentlyTerminal(review, afterDecide));
    }

    @Test
    void furtherInputAfterANonDeterministicStepFollowsWhicheverBranchesAcceptIt() {
        var instance = FsmInstance.created("review", "r1", "submitted", Map.of(), Instant.now());
        var afterDecide = FsmEngine.apply(review, instance, "decide", Instant.now());
        // only the "manual_review" branch has an outgoing "escalate" transition;
        // the "approved" branch does not -- but since at least one branch does, this succeeds
        // and the result set collapses to exactly what "escalate" reaches.
        var afterEscalate = FsmEngine.apply(review, afterDecide, "escalate", Instant.now());
        assertEquals(Set.of("rejected"), afterEscalate.currentStates());
        assertEquals(List.of("decide", "escalate"), afterEscalate.inputSequence());
    }
}
