package com.quiver.query.parser;

/** Thrown for any QSL syntax error: an unexpected token, or an unterminated construct. */
public final class ParseException extends RuntimeException {
    public ParseException(String message) {
        super(message);
    }
}
