package com.quiver.query.ast;

import java.util.List;
import java.util.Map;

/**
 * A parsed QSL statement. See {@code docs/QUERY_LANGUAGE.md} in the project root for the
 * full grammar and worked examples; this file is the authoritative list of what the
 * parser can produce and the executor must handle.
 */
public sealed interface Statement {

    /** {@code DEFINE FSM name (STATES(...), INITIAL x, TERMINAL(...), ALPHABET(...), TRANSITIONS(...))} */
    record DefineFsmStatement(
            String name,
            List<String> states,
            String initial,
            List<String> terminal,
            List<String> alphabet,
            List<TransitionSpec> transitions
    ) implements Statement {
        public record TransitionSpec(String from, String input, String to) {
        }
    }

    /** {@code CREATE fsm:id [WITH { ... }]} */
    record CreateInstanceStatement(String fsmName, String instanceId, Map<String, Object> attributes) implements Statement {
    }

    /** {@code fsm:id(input1, input2, ...)} -- applies one or more inputs as a single atomic step. */
    record TransitionStatement(String fsmName, String instanceId, List<String> inputs) implements Statement {
    }

    /** {@code GET fsm:id} */
    record GetStatement(String fsmName, String instanceId) implements Statement {
    }

    /** {@code MATCH fsm [WHERE condition]} */
    record MatchStatement(String fsmName, Condition where) implements Statement {
    }

    /** {@code BEGIN stmt; stmt; ...; COMMIT} -- everything inside runs as one atomic transaction. */
    record TransactionBlock(List<Statement> statements) implements Statement {
    }
}
