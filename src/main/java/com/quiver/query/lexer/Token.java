package com.quiver.query.lexer;

/**
 * One lexed token. {@code text} is the meaningful payload: the identifier name for
 * IDENT, the (already-unescaped) contents for STRING, the raw digits for NUMBER, and
 * the canonical spelling for everything else. {@code position} is the character offset
 * the token started at, for error messages.
 */
public record Token(TokenType type, String text, int position) {
    @Override
    public String toString() {
        return "%s(%s)@%d".formatted(type, text, position);
    }
}
