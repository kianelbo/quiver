package com.quiver.query.ast;

/** A boolean predicate in a MATCH statement's WHERE clause. */
public sealed interface Condition {

    /** {@code STATE = x} -- true iff x is one of the instance's current states (see FsmInstance: an instance may be in more than one state at once for a non-deterministic fsm). */
    record StateEquals(String state) implements Condition {
    }

    /** {@code STATE IS TERMINAL} -- true iff any current state is a terminal state of the fsm. */
    record StateIsTerminal() implements Condition {
    }

    /** {@code STATE IS INITIAL} -- true iff any current state is the fsm's initial state. */
    record StateIsInitial() implements Condition {
    }

    /** {@code HISTORY LIKE (pattern)} -- true iff the instance's input sequence matches the regex-style pattern. */
    record HistoryLike(PatternNode pattern) implements Condition {
    }

    /** {@code attrName op literal} -- compares an instance attribute against a JSON literal. */
    record AttributeCompare(String attributeName, CompareOp op, Object literal) implements Condition {
    }

    record And(Condition left, Condition right) implements Condition {
    }

    record Or(Condition left, Condition right) implements Condition {
    }

    enum CompareOp {
        EQ, NEQ, LT, GT, LE, GE
    }
}
