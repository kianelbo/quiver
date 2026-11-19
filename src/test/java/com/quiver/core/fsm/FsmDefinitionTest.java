package com.quiver.core.fsm;

import com.quiver.core.exceptions.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FsmDefinitionTest {

    private FsmDefinition.Builder orderBuilder() {
        return FsmDefinition.builder("order")
                .states("created", "paid", "shipped", "delivered", "cancelled")
                .initial("created")
                .terminal("delivered", "cancelled")
                .alphabet("pay", "ship", "deliver", "cancel")
                .transition("created", "pay", "paid")
                .transition("paid", "ship", "shipped")
                .transition("shipped", "deliver", "delivered")
                .transition("created", "cancel", "cancelled")
                .transition("paid", "cancel", "cancelled");
    }

    @Test
    void buildsAValidDeterministicDefinition() {
        FsmDefinition def = orderBuilder().build();
        assertEquals("order", def.name());
        assertEquals("created", def.initialState());
        assertTrue(def.isTerminal("delivered"));
        assertTrue(def.isTerminal("cancelled"));
        assertFalse(def.isTerminal("paid"));
        assertEquals(Set.of("paid"), def.targets("created", "pay"));
        assertTrue(def.targets("delivered", "pay").isEmpty());
        assertTrue(def.isDeterministic());
    }

    @Test
    void rejectsMissingInitialState() {
        var b = FsmDefinition.builder("bad").states("a", "b");
        assertThrows(ValidationException.class, b::build);
    }

    @Test
    void rejectsTransitionsTargetingUndeclaredStates() {
        var b = FsmDefinition.builder("bad")
                .states("a")
                .initial("a")
                .transition("a", "x", "ghost"); // "ghost" never declared as a state
        assertThrows(ValidationException.class, b::build);
    }

    @Test
    void redeclaringATransitionWithADifferentTargetMakesItNonDeterministicInsteadOfErroring() {
        var def = FsmDefinition.builder("review")
                .states("submitted", "approved", "rejected")
                .initial("submitted")
                .terminal("approved", "rejected")
                .alphabet("decide")
                .transition("submitted", "decide", "approved")
                .transition("submitted", "decide", "rejected")
                .build();

        assertEquals(Set.of("approved", "rejected"), def.targets("submitted", "decide"));
        assertFalse(def.isDeterministic());
    }

    @Test
    void redeclaringTheSameTransitionIdenticallyStaysDeterministic() {
        var def = FsmDefinition.builder("ok")
                .states("a", "b")
                .initial("a")
                .alphabet("x")
                .transition("a", "x", "b")
                .transition("a", "x", "b") // identical redeclaration, not a second branch
                .build();
        assertEquals(Set.of("b"), def.targets("a", "x"));
        assertTrue(def.isDeterministic());
    }

    @Test
    void rejectsBlankName() {
        var b = FsmDefinition.builder(" ").states("a").initial("a");
        assertThrows(ValidationException.class, b::build);
    }
}
