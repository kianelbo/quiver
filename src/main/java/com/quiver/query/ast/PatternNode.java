package com.quiver.query.ast;

import java.util.List;

/**
 * A regular expression over fsm input symbols -- the pattern language behind
 * {@code HISTORY LIKE (...)}. "Characters" in this regex are whole input names, not
 * single characters, e.g. the pattern {@code (pay, ship?, (deliver|cancel))} means:
 * literally "pay", then optionally "ship", then either "deliver" or "cancel". Compiled
 * to an NFA and matched against an instance's input sequence by
 * {@code com.quiver.query.pattern.HistoryRegex} -- see that class for the compile/match
 * algorithm (Thompson construction + direct NFA simulation).
 *
 * <p>Surface syntax notes (see the parser and {@code docs/QUERY_LANGUAGE.md} for the full
 * story): {@code .} is {@link AnyOne} (matches exactly one arbitrary input); a bare
 * {@code *}, {@code +}, or {@code ?} with no preceding atom is sugar for {@code .*},
 * {@code .+}, {@code .?} respectively, so the intuitive {@code (*, pay, ship, *)} -- "pay
 * then ship, anywhere in the history" -- parses to {@code Concat[Star[AnyOne], pay, ship, Star[AnyOne]]}.
 * A bare comma between atoms is insignificant whitespace, purely for readability.
 */
public sealed interface PatternNode {

    /** Matches exactly this one input symbol, literally. */
    record Literal(String symbol) implements PatternNode {
    }

    /** {@code .} -- matches exactly one arbitrary input, whatever it is. */
    record AnyOne() implements PatternNode {
    }

    /** Sequential composition: each part must match, in order, immediately after the previous. */
    record Concat(List<PatternNode> parts) implements PatternNode {
    }

    /** Alternation: any one of the alternatives may match. */
    record Union(List<PatternNode> alternatives) implements PatternNode {
    }

    /** {@code x*} -- zero or more repetitions of x. */
    record Star(PatternNode inner) implements PatternNode {
    }

    /** {@code x+} -- one or more repetitions of x. */
    record Plus(PatternNode inner) implements PatternNode {
    }

    /** {@code x?} -- zero or one occurrence of x. */
    record Opt(PatternNode inner) implements PatternNode {
    }
}
