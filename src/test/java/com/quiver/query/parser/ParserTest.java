package com.quiver.query.parser;

import com.quiver.query.ast.Condition;
import com.quiver.query.ast.PatternNode;
import com.quiver.query.ast.Statement;
import com.quiver.query.lexer.Lexer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ParserTest {

    @Test
    void parsesADefineFsmStatementWithAllClauses() {
        String src = """
                DEFINE FSM order (
                  STATES (created, paid, shipped, delivered, cancelled),
                  INITIAL created,
                  TERMINAL (delivered, cancelled),
                  ALPHABET (pay, ship, deliver, cancel),
                  TRANSITIONS (
                    created (pay): paid,
                    paid (ship): shipped,
                    shipped (deliver): delivered,
                    created (cancel): cancelled,
                    paid (cancel): cancelled
                  )
                )
                """;
        var stmt = (Statement.DefineFsmStatement) Parser.parseOne(src);
        assertEquals("order", stmt.name());
        assertEquals(List.of("created", "paid", "shipped", "delivered", "cancelled"), stmt.states());
        assertEquals("created", stmt.initial());
        assertEquals(List.of("delivered", "cancelled"), stmt.terminal());
        assertEquals(5, stmt.transitions().size());
        assertEquals(new Statement.DefineFsmStatement.TransitionSpec("created", "pay", "paid"), stmt.transitions().get(0));
    }

    @Test
    void defineFsmClausesCanAppearInAnyOrder() {
        String src = "DEFINE FSM x (ALPHABET (a), INITIAL s, STATES (s, t), TRANSITIONS (s (a): t))";
        var stmt = (Statement.DefineFsmStatement) Parser.parseOne(src);
        assertEquals("s", stmt.initial());
        assertEquals(List.of("a"), stmt.alphabet());
    }

    @Test
    void nonDeterministicTransitionCanListMultipleTargets() {
        String src = "DEFINE FSM review (STATES (s,a,b), INITIAL s, TRANSITIONS (s (decide): a,b))";
        var stmt = (Statement.DefineFsmStatement) Parser.parseOne(src);
        assertEquals(2, stmt.transitions().size());
        assertEquals(List.of(
                new Statement.DefineFsmStatement.TransitionSpec("s", "decide", "a"),
                new Statement.DefineFsmStatement.TransitionSpec("s", "decide", "b")), stmt.transitions());
    }

    @Test
    void infersStatesAndAlphabetWhenClausesAreOmitted() {
        var stmt = (Statement.DefineFsmStatement) Parser.parseOne(
                "DEFINE FSM x (INITIAL s, TRANSITIONS (s (a): t, t (b): u))");
        assertEquals(List.of("s", "t", "u"), stmt.states());
        assertEquals(List.of("a", "b"), stmt.alphabet());
    }

    @Test
    void explicitStatesAndAlphabetMustExactlyMatchTransitions() {
        assertThrows(ParseException.class, () -> Parser.parseOne(
                "DEFINE FSM x (STATES (s,t,unused), INITIAL s, TRANSITIONS (s (a): t))"));
        assertThrows(ParseException.class, () -> Parser.parseOne(
                "DEFINE FSM x (STATES (s), INITIAL s, TRANSITIONS (s (a): t))"));
        assertThrows(ParseException.class, () -> Parser.parseOne(
                "DEFINE FSM x (ALPHABET (a,b), INITIAL s, TRANSITIONS (s (a): t))"));
        assertThrows(ParseException.class, () -> Parser.parseOne(
                "DEFINE FSM x (ALPHABET (), INITIAL s, TRANSITIONS (s (a): t))"));
    }

    @Test
    void rejectsThePreviousArrowTransitionFormat() {
        assertThrows(Lexer.LexException.class, () -> Parser.parseOne(
                "DEFINE FSM x (INITIAL s, TRANSITIONS (s -[a]-> t))"));
    }

    @Test
    void parsesCreateWithoutAttributes() {
        var stmt = (Statement.CreateInstanceStatement) Parser.parseOne("CREATE order:o1001");
        assertEquals("order", stmt.fsmName());
        assertEquals("o1001", stmt.instanceId());
        assertEquals(Map.of(), stmt.attributes());
    }

    @Test
    void parsesCreateWithAttributesUnquotedAndQuotedKeys() {
        var stmt = (Statement.CreateInstanceStatement)
                Parser.parseOne("CREATE order:o1002 WITH { customer: \"alice\", \"rush\": true, total: 42.5 }");
        assertEquals("alice", stmt.attributes().get("customer"));
        assertEquals(Boolean.TRUE, stmt.attributes().get("rush"));
        assertEquals(42.5, stmt.attributes().get("total"));
    }

    @Test
    void parsesTransitionCallWithMultipleInputs() {
        var stmt = (Statement.TransitionStatement) Parser.parseOne("order:o1001(pay, ship)");
        assertEquals("order", stmt.fsmName());
        assertEquals("o1001", stmt.instanceId());
        assertEquals(List.of("pay", "ship"), stmt.inputs());
    }

    @Test
    void parsesGet() {
        var stmt = (Statement.GetStatement) Parser.parseOne("GET order:o1001");
        assertEquals("order", stmt.fsmName());
        assertEquals("o1001", stmt.instanceId());
    }

    @Test
    void parsesMatchWithNoWhereClause() {
        var stmt = (Statement.MatchStatement) Parser.parseOne("MATCH order");
        assertEquals("order", stmt.fsmName());
        assertNull(stmt.where());
    }

    @Test
    void parsesMatchStateIsTerminal() {
        var stmt = (Statement.MatchStatement) Parser.parseOne("MATCH order WHERE STATE IS TERMINAL");
        assertInstanceOf(Condition.StateIsTerminal.class, stmt.where());
    }

    @Test
    void parsesMatchStateEquals() {
        var stmt = (Statement.MatchStatement) Parser.parseOne("MATCH order WHERE STATE = shipped");
        assertEquals(new Condition.StateEquals("shipped"), stmt.where());
    }

    @Test
    void parsesAndOrWithCorrectPrecedenceAndGrouping() {
        // AND binds tighter than OR: "a OR b AND c" === "a OR (b AND c)"
        var stmt = (Statement.MatchStatement) Parser.parseOne(
                "MATCH order WHERE STATE = a OR STATE = b AND STATE = c");
        var where = (Condition.Or) stmt.where();
        assertInstanceOf(Condition.StateEquals.class, where.left());
        assertInstanceOf(Condition.And.class, where.right());
    }

    @Test
    void explicitParensOverridePrecedence() {
        var stmt = (Statement.MatchStatement) Parser.parseOne(
                "MATCH order WHERE (STATE = a OR STATE = b) AND STATE = c");
        var where = (Condition.And) stmt.where();
        assertInstanceOf(Condition.Or.class, where.left());
    }

    @Test
    void parsesAttributeComparison() {
        var stmt = (Statement.MatchStatement) Parser.parseOne("MATCH order WHERE total > 100");
        var cmp = (Condition.AttributeCompare) stmt.where();
        assertEquals("total", cmp.attributeName());
        assertEquals(Condition.CompareOp.GT, cmp.op());
        assertEquals(100L, cmp.literal());
    }

    @Test
    void theMotivatingExampleFromTheProjectBriefParsesToAnchoredDotStarPattern() {
        // HISTORY LIKE *, a, b, *  ==  .*, a, b, .*  -- "a immediately followed by b, anywhere"
        var stmt = (Statement.MatchStatement) Parser.parseOne("MATCH order WHERE HISTORY LIKE *, a, b, *");
        var like = (Condition.HistoryLike) stmt.where();
        PatternNode expected = new PatternNode.Concat(List.of(
                new PatternNode.Star(new PatternNode.AnyOne()),
                new PatternNode.Literal("a"),
                new PatternNode.Literal("b"),
                new PatternNode.Star(new PatternNode.AnyOne())
        ));
        assertEquals(expected, like.pattern());
    }

    @Test
    void parsesHistoryLikeWithGroupingAndAlternationAndQuantifiers() {
        var stmt = (Statement.MatchStatement) Parser.parseOne(
                "MATCH order WHERE HISTORY LIKE pay, ship?, (deliver | cancel)");
        var like = (Condition.HistoryLike) stmt.where();
        // Each alternative is itself a (possibly single-element) pattern sequence, so
        // "deliver" and "cancel" each come back wrapped in their own Concat -- harmless,
        // and HistoryRegex compiles a one-part Concat identically to the bare part.
        PatternNode expected = new PatternNode.Concat(List.of(
                new PatternNode.Literal("pay"),
                new PatternNode.Opt(new PatternNode.Literal("ship")),
                new PatternNode.Union(List.of(
                        new PatternNode.Concat(List.of(new PatternNode.Literal("deliver"))),
                        new PatternNode.Concat(List.of(new PatternNode.Literal("cancel")))))
        ));
        assertEquals(expected, like.pattern());
    }

    @Test
    void parsesTransactionBlockAsASingleStatement() {
        String src = """
                BEGIN
                  order:o1(pay);
                  inventory:i1(reserve)
                COMMIT
                """;
        var block = (Statement.TransactionBlock) Parser.parseOne(src);
        assertEquals(2, block.statements().size());
        assertInstanceOf(Statement.TransitionStatement.class, block.statements().get(0));
    }

    @Test
    void parseScriptHandlesMultipleSemicolonSeparatedStatements() {
        var statements = Parser.parseScript("CREATE order:o1; order:o1(pay); GET order:o1;");
        assertEquals(3, statements.size());
    }

    @Test
    void unexpectedTokenProducesAParseException() {
        assertThrows(ParseException.class, () -> Parser.parseOne("MATCH"));
        assertThrows(ParseException.class, () -> Parser.parseOne("CREATE order"));
        assertThrows(ParseException.class, () -> Parser.parseOne("DEFINE FSM x (STATES (a)"));
    }

    @Test
    void parseOneRejectsMoreThanOneStatement() {
        assertThrows(ParseException.class, () -> Parser.parseOne("GET a:b; GET c:d"));
    }
}
