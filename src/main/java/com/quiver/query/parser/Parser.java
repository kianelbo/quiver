package com.quiver.query.parser;

import com.quiver.query.ast.Condition;
import com.quiver.query.ast.PatternNode;
import com.quiver.query.ast.Statement;
import com.quiver.query.lexer.Lexer;
import com.quiver.query.lexer.Token;
import com.quiver.query.lexer.TokenType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.quiver.query.lexer.TokenType.*;

/**
 * Hand-written recursive-descent parser for QSL. See {@code docs/QUERY_LANGUAGE.md} for
 * the full grammar in EBNF with worked examples; this class is the executable version of
 * that grammar. {@link #parseScript} is the entry point most callers want: it splits
 * {@code ;}-separated input into a list of {@link Statement}s, one per top-level
 * statement (a {@code BEGIN ... COMMIT} block counts as a single statement).
 *
 * <p>One piece of surface sugar lives here rather than in the grammar doc's formal
 * productions, because it's really a parsing-time rewrite rather than a grammar rule: a
 * bare {@code *}, {@code +}, or {@code ?} appearing where a pattern atom is expected (i.e.
 * with nothing to its left to quantify) is treated as shorthand for {@code .*}, {@code
 * .+}, {@code .?}. That's what turns the intuitive {@code *, pay, ship, *} into the
 * properly-anchored regex {@code .*, pay, ship, .*} ("pay then ship, anywhere in the
 * history") without the person having to write the dot themselves.
 */
public final class Parser {

    private final List<Token> tokens;
    private int pos = 0;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    /** Parses every top-level statement in {@code src}, in order. */
    public static List<Statement> parseScript(String src) {
        Parser p = new Parser(Lexer.tokenize(src));
        List<Statement> statements = new ArrayList<>();
        while (!p.check(EOF)) {
            statements.add(p.parseStatement());
            if (p.check(SEMICOLON)) {
                p.advance();
            }
        }
        return statements;
    }

    /** Parses exactly one statement; useful for callers (like the CLI) that read one at a time. */
    public static Statement parseOne(String src) {
        List<Statement> statements = parseScript(src);
        if (statements.size() != 1) {
            throw new ParseException("expected exactly one statement, found " + statements.size());
        }
        return statements.get(0);
    }

    // ------------------------------------------------------------------
    // Statements
    // ------------------------------------------------------------------

    private Statement parseStatement() {
        if (check(DEFINE)) return parseDefineFsm();
        if (check(CREATE)) return parseCreateInstance();
        if (check(GET)) return parseGet();
        if (check(MATCH)) return parseMatch();
        if (check(BEGIN)) return parseTransactionBlock();
        if (check(IDENT)) return parseTransitionStatement();
        throw error("expected a statement (DEFINE, CREATE, GET, MATCH, BEGIN, or fsm:id(...))");
    }

    private Statement parseDefineFsm() {
        expect(DEFINE);
        expect(FSM);
        String name = expect(IDENT).text();
        expect(LPAREN);

        List<String> states = new ArrayList<>();
        String initial = null;
        List<String> terminal = new ArrayList<>();
        List<String> alphabet = new ArrayList<>();
        List<Statement.DefineFsmStatement.TransitionSpec> transitions = new ArrayList<>();
        boolean statesDeclared = false;
        boolean alphabetDeclared = false;

        while (!check(RPAREN)) {
            if (check(STATES)) {
                advance();
                statesDeclared = true;
                states.addAll(parseParenIdentList());
            } else if (check(INITIAL)) {
                advance();
                initial = expect(IDENT).text();
            } else if (check(TERMINAL)) {
                advance();
                terminal.addAll(parseParenIdentList());
            } else if (check(ALPHABET)) {
                advance();
                alphabetDeclared = true;
                alphabet.addAll(parseParenIdentList());
            } else if (check(TRANSITIONS)) {
                advance();
                expect(LPAREN);
                if (!check(RPAREN)) {
                    transitions.addAll(parseTransitionDef());
                    while (check(COMMA)) {
                        advance();
                        transitions.addAll(parseTransitionDef());
                    }
                }
                expect(RPAREN);
            } else {
                throw error("expected STATES, INITIAL, TERMINAL, ALPHABET, or TRANSITIONS");
            }
            if (check(COMMA)) {
                advance();
            }
        }
        expect(RPAREN);

        if (initial == null) {
            throw error("fsm '%s' definition is missing an INITIAL clause".formatted(name));
        }

        Set<String> transitionStates = new LinkedHashSet<>();
        Set<String> transitionAlphabet = new LinkedHashSet<>();
        for (var transition : transitions) {
            transitionStates.add(transition.from());
            transitionStates.add(transition.to());
            transitionAlphabet.add(transition.input());
        }
        if (statesDeclared && !new LinkedHashSet<>(states).equals(transitionStates)) {
            throw error("fsm '%s': STATES %s must exactly match states in TRANSITIONS %s"
                    .formatted(name, states, transitionStates));
        }
        if (alphabetDeclared && !new LinkedHashSet<>(alphabet).equals(transitionAlphabet)) {
            throw error("fsm '%s': ALPHABET %s must exactly match inputs in TRANSITIONS %s"
                    .formatted(name, alphabet, transitionAlphabet));
        }
        if (!statesDeclared) {
            states.addAll(transitionStates);
        }
        if (!alphabetDeclared) {
            alphabet.addAll(transitionAlphabet);
        }

        return new Statement.DefineFsmStatement(name, states, initial, terminal, alphabet, transitions);
    }

    private List<Statement.DefineFsmStatement.TransitionSpec> parseTransitionDef() {
        String from = expect(IDENT).text();
        expect(LPAREN);
        String input = expect(IDENT).text();
        expect(RPAREN);
        expect(COLON);
        List<String> targets = new ArrayList<>();
        targets.add(expect(IDENT).text());
        while (check(COMMA) && !isTransitionStartAt(pos + 1)) {
            advance();
            targets.add(expect(IDENT).text());
        }
        return targets.stream()
                .map(to -> new Statement.DefineFsmStatement.TransitionSpec(from, input, to))
                .toList();
    }

    private boolean isTransitionStartAt(int index) {
        return index + 1 < tokens.size()
                && tokens.get(index).type() == IDENT
                && tokens.get(index + 1).type() == LPAREN;
    }

    private Statement parseCreateInstance() {
        expect(CREATE);
        String fsm = expect(IDENT).text();
        expect(COLON);
        String id = expect(IDENT).text();
        Map<String, Object> attributes = Map.of();
        if (check(WITH)) {
            advance();
            attributes = parseJsonObject();
        }
        return new Statement.CreateInstanceStatement(fsm, id, attributes);
    }

    private Statement parseTransitionStatement() {
        String fsm = expect(IDENT).text();
        expect(COLON);
        String id = expect(IDENT).text();
        expect(LPAREN);
        List<String> inputs = new ArrayList<>();
        if (!check(RPAREN)) {
            inputs.add(expect(IDENT).text());
            while (check(COMMA)) {
                advance();
                inputs.add(expect(IDENT).text());
            }
        }
        expect(RPAREN);
        return new Statement.TransitionStatement(fsm, id, inputs);
    }

    private Statement parseGet() {
        expect(GET);
        String fsm = expect(IDENT).text();
        expect(COLON);
        String id = expect(IDENT).text();
        return new Statement.GetStatement(fsm, id);
    }

    private Statement parseMatch() {
        expect(MATCH);
        String fsm = expect(IDENT).text();
        Condition where = null;
        if (check(WHERE)) {
            advance();
            where = parseCondition();
        }
        return new Statement.MatchStatement(fsm, where);
    }

    private Statement parseTransactionBlock() {
        expect(BEGIN);
        List<Statement> statements = new ArrayList<>();
        while (!check(COMMIT)) {
            if (check(EOF)) {
                throw error("unterminated BEGIN block (missing COMMIT)");
            }
            statements.add(parseStatement());
            if (check(SEMICOLON)) {
                advance();
            }
        }
        expect(COMMIT);
        return new Statement.TransactionBlock(statements);
    }

    // ------------------------------------------------------------------
    // Conditions (MATCH ... WHERE ...)
    // ------------------------------------------------------------------

    private Condition parseCondition() {
        return parseOr();
    }

    private Condition parseOr() {
        Condition left = parseAnd();
        while (check(OR)) {
            advance();
            left = new Condition.Or(left, parseAnd());
        }
        return left;
    }

    private Condition parseAnd() {
        Condition left = parsePredicate();
        while (check(AND)) {
            advance();
            left = new Condition.And(left, parsePredicate());
        }
        return left;
    }

    private Condition parsePredicate() {
        if (check(LPAREN)) {
            advance();
            Condition inner = parseCondition();
            expect(RPAREN);
            return inner;
        }
        if (check(STATE)) {
            advance();
            if (check(EQUAL)) {
                advance();
                return new Condition.StateEquals(expect(IDENT).text());
            }
            if (check(IS)) {
                advance();
                if (check(TERMINAL)) {
                    advance();
                    return new Condition.StateIsTerminal();
                }
                if (check(INITIAL)) {
                    advance();
                    return new Condition.StateIsInitial();
                }
                throw error("expected TERMINAL or INITIAL after 'STATE IS'");
            }
            throw error("expected '=' or 'IS' after STATE");
        }
        if (check(HISTORY)) {
            advance();
            expect(LIKE);
            return new Condition.HistoryLike(parsePatternAlternation());
        }
        if (check(IDENT)) {
            String attribute = advance().text();
            Condition.CompareOp op = parseCompareOp();
            return new Condition.AttributeCompare(attribute, op, parseJsonValue());
        }
        throw error("expected a predicate (STATE ..., HISTORY LIKE ..., an attribute comparison, or '(')");
    }

    private Condition.CompareOp parseCompareOp() {
        if (check(EQUAL)) { advance(); return Condition.CompareOp.EQ; }
        if (check(NOTEQUAL)) { advance(); return Condition.CompareOp.NEQ; }
        if (check(LE)) { advance(); return Condition.CompareOp.LE; }
        if (check(GE)) { advance(); return Condition.CompareOp.GE; }
        if (check(LT)) { advance(); return Condition.CompareOp.LT; }
        if (check(GT)) { advance(); return Condition.CompareOp.GT; }
        throw error("expected a comparison operator (=, !=, <, <=, >, >=)");
    }

    // ------------------------------------------------------------------
    // HISTORY LIKE patterns -- see PatternNode for the AST and the class doc above for
    // the bare-quantifier-is-sugar-for-dot-quantifier rule implemented in parsePatternTerm.
    // ------------------------------------------------------------------

    private PatternNode parsePatternAlternation() {
        List<PatternNode> alternatives = new ArrayList<>();
        alternatives.add(parsePatternSequence());
        while (check(PIPE)) {
            advance();
            alternatives.add(parsePatternSequence());
        }
        return alternatives.size() == 1 ? alternatives.get(0) : new PatternNode.Union(alternatives);
    }

    private PatternNode parsePatternSequence() {
        List<PatternNode> parts = new ArrayList<>();
        if (isPatternTermStart()) {
            parts.add(parsePatternTerm());
            while (check(COMMA)) {
                advance();
                parts.add(parsePatternTerm());
            }
        }
        return new PatternNode.Concat(parts);
    }

    private boolean isPatternTermStart() {
        return switch (peek().type()) {
            case IDENT, DOT, LPAREN, STAR, PLUS, QUESTION -> true;
            default -> false;
        };
    }

    private PatternNode parsePatternTerm() {
        // Bare quantifier sugar: "*" / "+" / "?" with nothing to its left to quantify
        // means "apply that quantifier to an implicit '.'". Consuming just the one token
        // here (rather than falling into parsePatternAtom + a second quantifier check) is
        // what stops "**" from being misread as "(.*)*".
        if (check(STAR)) { advance(); return new PatternNode.Star(new PatternNode.AnyOne()); }
        if (check(PLUS)) { advance(); return new PatternNode.Plus(new PatternNode.AnyOne()); }
        if (check(QUESTION)) { advance(); return new PatternNode.Opt(new PatternNode.AnyOne()); }

        PatternNode atom = parsePatternAtom();
        if (check(STAR)) { advance(); return new PatternNode.Star(atom); }
        if (check(PLUS)) { advance(); return new PatternNode.Plus(atom); }
        if (check(QUESTION)) { advance(); return new PatternNode.Opt(atom); }
        return atom;
    }

    private PatternNode parsePatternAtom() {
        if (check(DOT)) {
            advance();
            return new PatternNode.AnyOne();
        }
        if (check(LPAREN)) {
            advance();
            PatternNode inner = parsePatternAlternation();
            expect(RPAREN);
            return inner;
        }
        if (check(IDENT)) {
            return new PatternNode.Literal(advance().text());
        }
        throw error("expected a pattern atom (an input name, '.', or '(')");
    }

    // ------------------------------------------------------------------
    // JSON values (WITH { ... } attribute literals, and comparison literals)
    // ------------------------------------------------------------------

    private Map<String, Object> parseJsonObject() {
        expect(LBRACE);
        Map<String, Object> result = new LinkedHashMap<>();
        if (check(RBRACE)) {
            advance();
            return result;
        }
        while (true) {
            String key = check(STRING) ? advance().text() : expect(IDENT).text();
            expect(COLON);
            result.put(key, parseJsonValue());
            if (check(COMMA)) {
                advance();
                continue;
            }
            expect(RBRACE);
            break;
        }
        return result;
    }

    private List<Object> parseJsonArray() {
        expect(LBRACKET);
        List<Object> result = new ArrayList<>();
        if (check(RBRACKET)) {
            advance();
            return result;
        }
        while (true) {
            result.add(parseJsonValue());
            if (check(COMMA)) {
                advance();
                continue;
            }
            expect(RBRACKET);
            break;
        }
        return result;
    }

    private Object parseJsonValue() {
        if (check(LBRACE)) return parseJsonObject();
        if (check(LBRACKET)) return parseJsonArray();
        if (check(STRING)) return advance().text();
        if (check(NUMBER)) return parseNumberLiteral(advance().text());
        if (check(TRUE)) { advance(); return Boolean.TRUE; }
        if (check(FALSE)) { advance(); return Boolean.FALSE; }
        if (check(NULL)) { advance(); return null; }
        throw error("expected a value (object, array, string, number, true, false, or null)");
    }

    private Object parseNumberLiteral(String text) {
        if (text.indexOf('.') >= 0 || text.indexOf('e') >= 0 || text.indexOf('E') >= 0) {
            return Double.parseDouble(text);
        }
        return Long.parseLong(text);
    }

    // ------------------------------------------------------------------
    // Low-level token helpers
    // ------------------------------------------------------------------

    private List<String> parseParenIdentList() {
        expect(LPAREN);
        List<String> result = new ArrayList<>();
        if (!check(RPAREN)) {
            result.add(expect(IDENT).text());
            while (check(COMMA)) {
                advance();
                result.add(expect(IDENT).text());
            }
        }
        expect(RPAREN);
        return result;
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private boolean check(TokenType type) {
        return peek().type() == type;
    }

    private Token advance() {
        Token t = tokens.get(pos);
        if (t.type() != EOF) pos++;
        return t;
    }

    private Token expect(TokenType type) {
        if (!check(type)) {
            throw error("expected " + type + " but found " + peek());
        }
        return advance();
    }

    private ParseException error(String message) {
        return new ParseException(message + " at offset " + peek().position() + " (found " + peek() + ")");
    }
}
