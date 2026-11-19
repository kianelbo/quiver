package com.quiver.query.exec;

import com.quiver.core.instance.FsmInstance;

import java.util.List;

/**
 * The outcome of executing one {@code Statement}. There is deliberately no "error"
 * variant here: a statement that cannot be executed throws (a {@code QuiverException}
 * from the data model, or a {@link QueryExecutionException} from this layer) rather than
 * returning one, so that a failure partway through a {@code TransactionResult} correctly
 * unwinds the whole transaction instead of being swallowed into a result the caller has
 * to remember to check.
 */
public sealed interface QueryResult {

    /** A statement that doesn't produce instance data: DEFINE FSM. */
    record Ack(String message) implements QueryResult {
    }

    /** CREATE, a transition call, or GET: exactly one instance. */
    record InstanceResult(FsmInstance instance) implements QueryResult {
    }

    /** MATCH: every instance satisfying the (optional) WHERE clause. */
    record InstanceListResult(List<FsmInstance> instances) implements QueryResult {
    }

    /** A BEGIN ... COMMIT block: the result of each contained statement, in order. */
    record TransactionResult(List<QueryResult> results) implements QueryResult {
    }
}
