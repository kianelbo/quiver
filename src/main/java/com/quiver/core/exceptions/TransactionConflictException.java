package com.quiver.core.exceptions;

/**
 * Thrown when a transaction cannot commit because something it read was concurrently
 * modified by another transaction (write-write / read-write conflict under optimistic
 * concurrency control). The caller may safely retry the whole transaction.
 */
public final class TransactionConflictException extends QuiverException {
    public TransactionConflictException(String message) {
        super(message);
    }
}
