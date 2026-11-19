package com.quiver.query.pattern;

import com.quiver.query.ast.PatternNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.quiver.query.ast.PatternNode.*;
import static org.junit.jupiter.api.Assertions.*;

class HistoryRegexTest {

    private static PatternNode lit(String s) {
        return new Literal(s);
    }

    private boolean matches(PatternNode pattern, String... sequence) {
        return HistoryRegex.compile(pattern).matches(List.of(sequence));
    }

    @Test
    void literalConcatMatchesExactSequenceOnly() {
        PatternNode p = new Concat(List.of(lit("pay"), lit("ship"), lit("deliver")));
        assertTrue(matches(p, "pay", "ship", "deliver"));
        assertFalse(matches(p, "pay", "ship"));                 // too short
        assertFalse(matches(p, "pay", "ship", "deliver", "x")); // too long
        assertFalse(matches(p, "pay", "deliver", "ship"));      // wrong order
    }

    @Test
    void emptyConcatMatchesOnlyTheEmptySequence() {
        PatternNode p = new Concat(List.of());
        assertTrue(HistoryRegex.compile(p).matches(List.of()));
        assertFalse(matches(p, "anything"));
    }

    @Test
    void dotMatchesExactlyOneArbitraryInput() {
        PatternNode p = new Concat(List.of(lit("a"), new AnyOne(), lit("b")));
        assertTrue(matches(p, "a", "whatever", "b"));
        assertFalse(matches(p, "a", "b"));              // dot requires exactly one element present
        assertFalse(matches(p, "a", "x", "y", "b"));    // dot is exactly one, not more
    }

    @Test
    void starIsZeroOrMore() {
        PatternNode p = new Concat(List.of(lit("a"), new Star(lit("x")), lit("b")));
        assertTrue(matches(p, "a", "b"));
        assertTrue(matches(p, "a", "x", "b"));
        assertTrue(matches(p, "a", "x", "x", "x", "x", "x", "b"));
        assertFalse(matches(p, "a", "y", "b"));
    }

    @Test
    void plusIsOneOrMore() {
        PatternNode p = new Concat(List.of(lit("a"), new Plus(lit("x")), lit("b")));
        assertFalse(matches(p, "a", "b")); // zero x's is not enough for +
        assertTrue(matches(p, "a", "x", "b"));
        assertTrue(matches(p, "a", "x", "x", "x", "b"));
    }

    @Test
    void optIsZeroOrOne() {
        PatternNode p = new Concat(List.of(lit("a"), new Opt(lit("x")), lit("b")));
        assertTrue(matches(p, "a", "b"));
        assertTrue(matches(p, "a", "x", "b"));
        assertFalse(matches(p, "a", "x", "x", "b"));
    }

    @Test
    void unionTriesEachAlternative() {
        PatternNode p = new Concat(List.of(lit("a"), new Union(List.of(lit("x"), lit("y"), lit("z"))), lit("b")));
        assertTrue(matches(p, "a", "x", "b"));
        assertTrue(matches(p, "a", "y", "b"));
        assertTrue(matches(p, "a", "z", "b"));
        assertFalse(matches(p, "a", "w", "b"));
    }

    @Test
    void starOfAGroupRepeatsTheWholeGroup() {
        // (retry, fail)* , succeed  --  any number of (retry,fail) pairs, then succeed
        PatternNode pair = new Concat(List.of(lit("retry"), lit("fail")));
        PatternNode p = new Concat(List.of(new Star(pair), lit("succeed")));
        assertTrue(matches(p, "succeed"));
        assertTrue(matches(p, "retry", "fail", "succeed"));
        assertTrue(matches(p, "retry", "fail", "retry", "fail", "retry", "fail", "succeed"));
        assertFalse(matches(p, "retry", "succeed"));          // incomplete pair
        assertFalse(matches(p, "retry", "fail", "retry", "succeed")); // trailing incomplete pair
    }

    @Test
    void theMotivatingExampleAnyPrefixThenAThenBThenAnySuffix() {
        // the "*, a, b, *" example from the spec, written out as its desugared form .*, a, b, .*
        PatternNode p = new Concat(List.of(new Star(new AnyOne()), lit("a"), lit("b"), new Star(new AnyOne())));
        assertTrue(matches(p, "a", "b"));
        assertTrue(matches(p, "x", "y", "a", "b"));
        assertTrue(matches(p, "a", "b", "z", "w"));
        assertTrue(matches(p, "p", "q", "a", "b", "r", "s"));
        assertFalse(matches(p, "a", "x", "b")); // a and b must be adjacent
        assertFalse(matches(p, "b", "a"));
    }

    @Test
    void noCatastrophicBacktrackingOnClassicPathologicalPattern() {
        // (x?){20} x{20} against 20 x's: a textbook backtracking-engine killer. Thompson
        // simulation is O(n*m) regardless, so this must return promptly either way.
        PatternNode optX = new Opt(lit("x"));
        java.util.List<PatternNode> parts = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) parts.add(optX);
        for (int i = 0; i < 20; i++) parts.add(lit("x"));
        PatternNode p = new Concat(parts);

        String[] seq = new String[20];
        java.util.Arrays.fill(seq, "x");

        long start = System.nanoTime();
        boolean result = matches(p, seq);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result);
        assertTrue(elapsedMs < 1000, "took " + elapsedMs + "ms -- should be near-instant");
    }

    @Test
    void compiledPatternIsReusableAcrossManyMatchCalls() {
        HistoryRegex regex = HistoryRegex.compile(new Concat(List.of(lit("a"), lit("b"))));
        assertTrue(regex.matches(List.of("a", "b")));
        assertFalse(regex.matches(List.of("a", "c")));
        assertTrue(regex.matches(List.of("a", "b"))); // same instance, called again
    }
}
