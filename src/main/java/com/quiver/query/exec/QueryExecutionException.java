package com.quiver.query.exec;

/**
 * A query was syntactically valid (it parsed) but cannot be executed as written: an
 * incompatible type comparison, a nested {@code BEGIN} block, a {@code DEFINE FSM}
 * inside a transaction, and similar. Kept separate from {@code QuiverException} (core's
 * exception hierarchy, which is about the data model and storage) because these are
 * query-layer concerns -- a different storage engine or a schema change could make some
 * of today's restrictions (e.g. no nested transactions) go away without touching core at all.
 */
public final class QueryExecutionException extends RuntimeException {
    public QueryExecutionException(String message) {
        super(message);
    }
}
