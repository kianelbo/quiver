package com.quiver.query.pattern;

import com.quiver.query.ast.PatternNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Compiles a {@link PatternNode} regex AST into a small NFA program (Thompson
 * construction) and matches it against an instance's input sequence using direct NFA
 * simulation (the "Pike's VM" technique used by RE2, grep -E, etc). Matching is
 * {@code O(sequence length * program size)} with no backtracking, so a pathological
 * pattern like {@code (a?)^30 a^30} that makes backtracking engines hang is cheap here.
 *
 * <p><b>Matching is fully anchored</b> to the whole sequence, start to end -- like SQL's
 * {@code LIKE} (and unlike, say, {@code grep}). A pattern that should match anywhere
 * needs an explicit {@code .*} at each end it isn't anchored at, exactly as SQL's
 * {@code LIKE '%x%'} needs explicit {@code %} on each side. See {@code
 * com.quiver.query.parser.Parser} for the surface-syntax sugar that makes a bare
 * {@code *}/{@code +}/{@code ?} (no preceding atom) shorthand for {@code .*}/{@code
 * .+}/{@code .?}, which is what makes the fully-anchored default feel natural to write.
 *
 * <p>Instances of this class are immutable/stateless after {@link #compile} and safe to
 * reuse (and share across threads) for matching many instances against the same pattern.
 */
public final class HistoryRegex {

    private final List<Inst> program;

    private HistoryRegex(List<Inst> program) {
        this.program = program;
    }

    public static HistoryRegex compile(PatternNode pattern) {
        List<Inst> prog = new ArrayList<>();
        emit(pattern, prog);
        prog.add(new Inst(Op.MATCH));
        return new HistoryRegex(prog);
    }

    /** True iff {@code sequence}, taken as a whole from first element to last, matches this pattern. */
    public boolean matches(List<String> sequence) {
        Set<Integer> visited = new HashSet<>();
        List<Integer> current = new ArrayList<>();
        addThread(current, 0, visited);

        for (String symbol : sequence) {
            List<Integer> next = new ArrayList<>();
            visited = new HashSet<>();
            for (int pc : current) {
                Inst inst = program.get(pc);
                if (inst.op == Op.CHAR && inst.symbol.equals(symbol)) {
                    addThread(next, pc + 1, visited);
                } else if (inst.op == Op.ANY) {
                    addThread(next, pc + 1, visited);
                }
                // CHAR-mismatch and MATCH threads simply do not propagate: a thread that
                // reached MATCH with more input still to come is not a full-sequence match.
            }
            current = next;
            if (current.isEmpty()) {
                return false; // every thread died; no need to keep scanning
            }
        }
        for (int pc : current) {
            if (program.get(pc).op == Op.MATCH) {
                return true;
            }
        }
        return false;
    }

    /** Epsilon-closure: follows JMP/SPLIT until landing on real (consuming or MATCH) instructions. */
    private void addThread(List<Integer> list, int pc, Set<Integer> visited) {
        if (!visited.add(pc)) {
            return; // already reachable via another path; avoids duplicate work and infinite loops
        }
        Inst inst = program.get(pc);
        switch (inst.op) {
            case JMP -> addThread(list, inst.x, visited);
            case SPLIT -> {
                addThread(list, inst.x, visited);
                addThread(list, inst.y, visited);
            }
            case CHAR, ANY, MATCH -> list.add(pc);
        }
    }

    // --- Thompson construction ---
    //
    // Each case below appends its fragment directly onto the shared `prog` list and, where
    // it needs to jump over a not-yet-compiled sub-fragment (alternation, star, plus, opt),
    // emits a JMP/SPLIT with a placeholder target and patches it to the real index once that
    // sub-fragment has been compiled (backpatching). Because every jump target is an absolute
    // index into the one shared list, fragments never need to be copied or offset-adjusted --
    // concatenation is simply "compile the parts one after another".

    private static void emit(PatternNode node, List<Inst> prog) {
        switch (node) {
            case PatternNode.Literal(String symbol) -> prog.add(Inst.charInst(symbol));
            case PatternNode.AnyOne _ -> prog.add(Inst.any());
            case PatternNode.Concat(List<PatternNode> parts) -> {
                for (PatternNode part : parts) emit(part, prog);
            }
            case PatternNode.Union(List<PatternNode> alternatives) -> emitUnion(alternatives, prog);
            case PatternNode.Star(PatternNode inner) -> {
                // L1: split L2, L3      (try the body, or skip it)
                // L2: <inner>
                //     jmp L1
                // L3:
                int l1 = prog.size();
                Inst split = Inst.split(-1, -1);
                prog.add(split);
                split.x = prog.size();
                emit(inner, prog);
                prog.add(Inst.jmp(l1));
                split.y = prog.size();
            }
            case PatternNode.Plus(PatternNode inner) -> {
                // L1: <inner>
                //     split L1, L2      (repeat, or stop -- but the body ran at least once)
                // L2:
                int l1 = prog.size();
                emit(inner, prog);
                prog.add(Inst.split(l1, prog.size() + 1));
            }
            case PatternNode.Opt(PatternNode inner) -> {
                // split L1, L2
                // L1: <inner>
                // L2:
                Inst split = Inst.split(-1, -1);
                prog.add(split);
                split.x = prog.size();
                emit(inner, prog);
                split.y = prog.size();
            }
        }
    }

    private static void emitUnion(List<PatternNode> alternatives, List<Inst> prog) {
        if (alternatives.size() == 1) {
            emit(alternatives.get(0), prog);
            return;
        }
        // split L1, L2
        // L1: <alternatives[0]>
        //     jmp L3
        // L2: <emitUnion(alternatives[1:])>
        // L3:
        Inst split = Inst.split(-1, -1);
        prog.add(split);
        split.x = prog.size();
        emit(alternatives.get(0), prog);
        Inst jmp = Inst.jmp(-1);
        prog.add(jmp);
        split.y = prog.size();
        emitUnion(alternatives.subList(1, alternatives.size()), prog);
        jmp.x = prog.size();
    }

    private enum Op { CHAR, ANY, JMP, SPLIT, MATCH }

    /** One instruction. Mutable only so backpatching can fill in forward jump targets after the fact. */
    private static final class Inst {
        final Op op;
        String symbol; // CHAR only
        int x, y;      // JMP: x = target. SPLIT: x, y = the two targets.

        private Inst(Op op) {
            this.op = op;
        }

        static Inst charInst(String symbol) {
            Inst i = new Inst(Op.CHAR);
            i.symbol = symbol;
            return i;
        }

        static Inst any() {
            return new Inst(Op.ANY);
        }

        static Inst jmp(int target) {
            Inst i = new Inst(Op.JMP);
            i.x = target;
            return i;
        }

        static Inst split(int x, int y) {
            Inst i = new Inst(Op.SPLIT);
            i.x = x;
            i.y = y;
            return i;
        }
    }
}
