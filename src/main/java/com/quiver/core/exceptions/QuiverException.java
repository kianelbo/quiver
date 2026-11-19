package com.quiver.core.exceptions;

/**
 * Root of the Quiver exception hierarchy. Unchecked: callers that care about a
 * specific failure mode catch the specific subtype; everyone else lets it propagate
 * to the query executor, which turns it into a user-facing error result.
 */
public sealed class QuiverException extends RuntimeException
        permits UnknownFsmException, UnknownInstanceException, DuplicateInstanceException,
                DuplicateFsmException, InvalidTransitionException, ValidationException,
                TransactionConflictException {

    public QuiverException(String message) {
        super(message);
    }

    public QuiverException(String message, Throwable cause) {
        super(message, cause);
    }
}
