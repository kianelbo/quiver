package com.quiver.query.lexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns raw QSL text into a flat list of {@link Token}s, ending in a single {@code EOF}
 * token. Keywords are matched case-insensitively (so {@code define}, {@code DEFINE} and
 * {@code Define} are all the {@code DEFINE} keyword) but the original casing of every
 * identifier -- fsm names, instance ids, input symbols, attribute names -- is preserved
 * verbatim, because those are data, not syntax, and {@code order} and {@code Order} are
 * different fsm names.
 *
 * <p>{@code --} starts a line comment that runs to the next newline, same as SQL.
 *
 * <p>Comparison operators {@code >=}/{@code <=}/{@code !=} need one character of
 * lookahead to disambiguate from their single-character forms.
 */
public final class Lexer {

    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("DEFINE", TokenType.DEFINE),
            Map.entry("FSM", TokenType.FSM),
            Map.entry("STATES", TokenType.STATES),
            Map.entry("INITIAL", TokenType.INITIAL),
            Map.entry("TERMINAL", TokenType.TERMINAL),
            Map.entry("ALPHABET", TokenType.ALPHABET),
            Map.entry("TRANSITIONS", TokenType.TRANSITIONS),
            Map.entry("CREATE", TokenType.CREATE),
            Map.entry("WITH", TokenType.WITH),
            Map.entry("GET", TokenType.GET),
            Map.entry("MATCH", TokenType.MATCH),
            Map.entry("WHERE", TokenType.WHERE),
            Map.entry("STATE", TokenType.STATE),
            Map.entry("IS", TokenType.IS),
            Map.entry("HISTORY", TokenType.HISTORY),
            Map.entry("LIKE", TokenType.LIKE),
            Map.entry("AND", TokenType.AND),
            Map.entry("OR", TokenType.OR),
            Map.entry("BEGIN", TokenType.BEGIN),
            Map.entry("COMMIT", TokenType.COMMIT),
            Map.entry("ROLLBACK", TokenType.ROLLBACK),
            Map.entry("TRUE", TokenType.TRUE),
            Map.entry("FALSE", TokenType.FALSE),
            Map.entry("NULL", TokenType.NULL)
    );

    private final String src;
    private int pos = 0;
    private final List<Token> tokens = new ArrayList<>();

    private Lexer(String src) {
        this.src = src;
    }

    public static List<Token> tokenize(String src) {
        Lexer l = new Lexer(src);
        l.run();
        return l.tokens;
    }

    private void run() {
        while (true) {
            skipWhitespaceAndComments();
            if (atEnd()) {
                tokens.add(new Token(TokenType.EOF, "", pos));
                return;
            }
            int start = pos;
            char c = peek();

            if (isIdentStart(c)) {
                readIdentOrKeyword(start);
            } else if (Character.isDigit(c)) {
                readNumber(start);
            } else if (c == '"') {
                readString(start);
            } else {
                readPunctuation(start);
            }
        }
    }

    private void skipWhitespaceAndComments() {
        while (!atEnd()) {
            char c = peek();
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == '-' && pos + 1 < src.length() && src.charAt(pos + 1) == '-') {
                while (!atEnd() && peek() != '\n') pos++;
            } else {
                return;
            }
        }
    }

    private void readIdentOrKeyword(int start) {
        while (!atEnd() && isIdentPart(peek())) pos++;
        String text = src.substring(start, pos);
        TokenType kw = KEYWORDS.get(text.toUpperCase(java.util.Locale.ROOT));
        tokens.add(new Token(kw != null ? kw : TokenType.IDENT, text, start));
    }

    private void readNumber(int start) {
        readIntegerDigits();
        if (!atEnd() && peek() == '.' && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1))) {
            pos++; // consume '.'
            readIntegerDigits();
        }
        if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
            int mark = pos;
            pos++;
            if (!atEnd() && (peek() == '+' || peek() == '-')) pos++;
            if (!atEnd() && Character.isDigit(peek())) {
                readIntegerDigits();
            } else {
                pos = mark; // not actually an exponent; back out
            }
        }
        tokens.add(new Token(TokenType.NUMBER, src.substring(start, pos), start));
    }

    private void readIntegerDigits() {
        while (!atEnd() && Character.isDigit(peek())) pos++;
    }

    private void readString(int start) {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (atEnd()) {
                throw new LexException("unterminated string literal starting at offset " + start);
            }
            char c = src.charAt(pos++);
            if (c == '"') break;
            if (c == '\\') {
                if (atEnd()) throw new LexException("unterminated escape at offset " + (pos - 1));
                char esc = src.charAt(pos++);
                sb.append(switch (esc) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    default -> throw new LexException("invalid escape '\\" + esc + "' at offset " + (pos - 2));
                });
            } else {
                sb.append(c);
            }
        }
        tokens.add(new Token(TokenType.STRING, sb.toString(), start));
    }

    private void readPunctuation(int start) {
        char c = src.charAt(pos++);
        switch (c) {
            case ':' -> tokens.add(new Token(TokenType.COLON, ":", start));
            case '(' -> tokens.add(new Token(TokenType.LPAREN, "(", start));
            case ')' -> tokens.add(new Token(TokenType.RPAREN, ")", start));
            case '{' -> tokens.add(new Token(TokenType.LBRACE, "{", start));
            case '}' -> tokens.add(new Token(TokenType.RBRACE, "}", start));
            case '[' -> tokens.add(new Token(TokenType.LBRACKET, "[", start));
            case ',' -> tokens.add(new Token(TokenType.COMMA, ",", start));
            case ';' -> tokens.add(new Token(TokenType.SEMICOLON, ";", start));
            case '.' -> tokens.add(new Token(TokenType.DOT, ".", start));
            case '*' -> tokens.add(new Token(TokenType.STAR, "*", start));
            case '+' -> tokens.add(new Token(TokenType.PLUS, "+", start));
            case '?' -> tokens.add(new Token(TokenType.QUESTION, "?", start));
            case '|' -> tokens.add(new Token(TokenType.PIPE, "|", start));
            case '=' -> tokens.add(new Token(TokenType.EQUAL, "=", start));
            case ']' -> tokens.add(new Token(TokenType.RBRACKET, "]", start));
            case '-' -> {
                if (!atEnd() && Character.isDigit(peek())) {
                    readNumber(start); // re-scan from '-', consuming the sign into the number
                } else {
                    throw new LexException("unexpected '-' at offset " + start
                            + " (expected a negative number)");
                }
            }
            case '!' -> {
                if (!atEnd() && peek() == '=') {
                    pos++;
                    tokens.add(new Token(TokenType.NOTEQUAL, "!=", start));
                } else {
                    throw new LexException("unexpected '!' at offset " + start + " (expected '!=')");
                }
            }
            case '<' -> {
                if (!atEnd() && peek() == '=') {
                    pos++;
                    tokens.add(new Token(TokenType.LE, "<=", start));
                } else {
                    tokens.add(new Token(TokenType.LT, "<", start));
                }
            }
            case '>' -> {
                if (!atEnd() && peek() == '=') {
                    pos++;
                    tokens.add(new Token(TokenType.GE, ">=", start));
                } else {
                    tokens.add(new Token(TokenType.GT, ">", start));
                }
            }
            default -> throw new LexException("unexpected character '%s' at offset %d".formatted(c, start));
        }
    }

    private boolean atEnd() {
        return pos >= src.length();
    }

    private char peek() {
        return src.charAt(pos);
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    public static final class LexException extends RuntimeException {
        public LexException(String message) {
            super(message);
        }
    }
}
