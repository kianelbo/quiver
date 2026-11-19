package com.quiver.query.lexer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LexerTest {

    private List<TokenType> types(String src) {
        return Lexer.tokenize(src).stream().map(Token::type).toList();
    }

    @Test
    void keywordsAreCaseInsensitiveButIdentsKeepCasing() {
        var tokens = Lexer.tokenize("define FSM Order");
        assertEquals(TokenType.DEFINE, tokens.get(0).type());
        assertEquals(TokenType.FSM, tokens.get(1).type());
        assertEquals(TokenType.IDENT, tokens.get(2).type());
        assertEquals("Order", tokens.get(2).text()); // casing preserved -- this is data, not syntax
    }

    @Test
    void transitionDefinitionUsesParenthesesAndColon() {
        var types = types("created (pay): paid, manual");
        assertEquals(List.of(TokenType.IDENT, TokenType.LPAREN, TokenType.IDENT, TokenType.RPAREN,
                TokenType.COLON, TokenType.IDENT, TokenType.COMMA, TokenType.IDENT, TokenType.EOF), types);
    }

    @Test
    void transitionCallSyntax() {
        var types = types("order:o1001(pay, ship)");
        assertEquals(List.of(TokenType.IDENT, TokenType.COLON, TokenType.IDENT, TokenType.LPAREN,
                TokenType.IDENT, TokenType.COMMA, TokenType.IDENT, TokenType.RPAREN, TokenType.EOF), types);
    }

    @Test
    void lineCommentsAreSkipped() {
        var types = types("CREATE -- this is a comment\n order:o1");
        assertEquals(List.of(TokenType.CREATE, TokenType.IDENT, TokenType.COLON, TokenType.IDENT, TokenType.EOF), types);
    }

    @Test
    void stringLiteralWithEscapes() {
        var tokens = Lexer.tokenize("\"alice\\nbob\"");
        assertEquals(TokenType.STRING, tokens.get(0).type());
        assertEquals("alice\nbob", tokens.get(0).text());
    }

    @Test
    void numbersIntegerDecimalAndNegative() {
        var tokens = Lexer.tokenize("42 3.14 -7");
        assertEquals("42", tokens.get(0).text());
        assertEquals("3.14", tokens.get(1).text());
        assertEquals("-7", tokens.get(2).text());
        assertEquals(TokenType.NUMBER, tokens.get(0).type());
        assertEquals(TokenType.NUMBER, tokens.get(1).type());
        assertEquals(TokenType.NUMBER, tokens.get(2).type());
    }

    @Test
    void comparisonOperators() {
        assertEquals(List.of(TokenType.EQUAL, TokenType.NOTEQUAL, TokenType.LT, TokenType.LE,
                TokenType.GT, TokenType.GE, TokenType.EOF), types("= != < <= > >="));
    }

    @Test
    void regexPatternPunctuation() {
        assertEquals(List.of(TokenType.STAR, TokenType.COMMA, TokenType.IDENT, TokenType.COMMA,
                TokenType.IDENT, TokenType.COMMA, TokenType.STAR, TokenType.EOF), types("*, a, b, *"));
        assertEquals(List.of(TokenType.DOT, TokenType.STAR, TokenType.IDENT, TokenType.PLUS,
                TokenType.LPAREN, TokenType.IDENT, TokenType.PIPE, TokenType.IDENT, TokenType.RPAREN,
                TokenType.QUESTION, TokenType.EOF), types(".* a+ (x|y)?"));
    }

    @Test
    void jsonArrayClosingBracketIsTokenized() {
        assertEquals(List.of(TokenType.LBRACKET, TokenType.NUMBER, TokenType.COMMA, TokenType.NUMBER,
                TokenType.RBRACKET, TokenType.EOF), types("[1, 2]"));
    }

    @Test
    void fullDefineStatementTokenizes() {
        String src = """
                DEFINE FSM order (
                  STATES (created, paid),
                  INITIAL created,
                  TERMINAL (paid),
                  ALPHABET (pay),
                  TRANSITIONS (created (pay): paid)
                )
                """;
        // Just assert it doesn't throw and ends in EOF -- the parser test covers structure.
        var tokens = Lexer.tokenize(src);
        assertEquals(TokenType.EOF, tokens.get(tokens.size() - 1).type());
        assertEquals(TokenType.DEFINE, tokens.get(0).type());
    }

    @Test
    void unterminatedStringThrows() {
        assertThrows(Lexer.LexException.class, () -> Lexer.tokenize("\"abc"));
    }

    @Test
    void unexpectedCharacterThrows() {
        assertThrows(Lexer.LexException.class, () -> Lexer.tokenize("@"));
    }
}
