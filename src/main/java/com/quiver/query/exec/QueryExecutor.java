package com.quiver.query.exec;

import com.quiver.core.exceptions.UnknownFsmException;
import com.quiver.core.exceptions.UnknownInstanceException;
import com.quiver.core.fsm.FsmDefinition;
import com.quiver.core.fsm.FsmEngine;
import com.quiver.core.instance.FsmInstance;
import com.quiver.query.ast.Condition;
import com.quiver.query.ast.PatternNode;
import com.quiver.query.ast.Statement;
import com.quiver.query.parser.Parser;
import com.quiver.query.pattern.HistoryRegex;
import com.quiver.storage.StorageEngine;
import com.quiver.storage.Transaction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Runs parsed {@link Statement}s against a {@link StorageEngine}. This is where the
 * query language and the data model actually meet: parsing has no notion of fsm
 * definitions or instances, and the storage engine has no notion of statements or
 * conditions -- this class is the translation between them.
 *
 * Every top-level statement runs inside a {@link Transaction}: for a single statement
 * that transaction is opened, used once, and committed immediately
 * (so e.g. a single {@code fsm:id(input)} call is always atomic on its own);
 * for a {@link Statement.TransactionBlock}, one transaction is opened for the whole block
 * and every contained statement runs inside <em>that same</em> transaction, committing
 * only once at the very end -- which is what makes {@code BEGIN ... COMMIT} genuinely
 * atomic across multiple fsm instances rather than just a sequence of independent commits.
 * {@code DEFINE FSM} is the one exception: schema changes commit immediately via {@link
 * StorageEngine#defineFsm} and are not allowed inside a transaction block at all (see
 * {@link StorageEngine} for why schema isn't transactional in this increment).
 */
public final class QueryExecutor {

    private final StorageEngine storage;
    private final Supplier<Instant> clock;

    public QueryExecutor(StorageEngine storage) {
        this(storage, Instant::now);
    }

    /** Package-visible-ish constructor for tests that need a deterministic clock. */
    public QueryExecutor(StorageEngine storage, Supplier<Instant> clock) {
        this.storage = storage;
        this.clock = clock;
    }

    public List<QueryResult> executeScript(String src) {
        List<QueryResult> results = new ArrayList<>();
        for (Statement statement : Parser.parseScript(src)) {
            results.add(execute(statement));
        }
        return results;
    }

    public QueryResult execute(String src) {
        return execute(Parser.parseOne(src));
    }

    public QueryResult execute(Statement statement) {
        if (statement instanceof Statement.DefineFsmStatement define) {
            return executeDefine(define);
        }
        if (statement instanceof Statement.TransactionBlock block) {
            return executeTransactionBlock(block);
        }
        try (Transaction tx = storage.beginTransaction()) {
            QueryResult result = executeWithin(statement, tx);
            tx.commit();
            return result;
        }
    }

    // ------------------------------------------------------------------

    private QueryResult executeTransactionBlock(Statement.TransactionBlock block) {
        try (Transaction tx = storage.beginTransaction()) {
            List<QueryResult> results = new ArrayList<>();
            for (Statement inner : block.statements()) {
                if (inner instanceof Statement.DefineFsmStatement) {
                    throw new QueryExecutionException(
                            "DEFINE FSM is not allowed inside a BEGIN/COMMIT block (schema changes commit immediately and are not transactional)");
                }
                if (inner instanceof Statement.TransactionBlock) {
                    throw new QueryExecutionException("nested BEGIN blocks are not supported");
                }
                results.add(executeWithin(inner, tx));
            }
            tx.commit();
            return new QueryResult.TransactionResult(results);
        }
    }

    /** Dispatches every statement kind except DEFINE and nested TransactionBlocks, which {@link #execute} handles directly. */
    private QueryResult executeWithin(Statement statement, Transaction tx) {
        return switch (statement) {
            case Statement.CreateInstanceStatement c -> executeCreate(c, tx);
            case Statement.TransitionStatement t -> executeTransition(t, tx);
            case Statement.GetStatement g -> executeGet(g, tx);
            case Statement.MatchStatement m -> executeMatch(m, tx);
            case Statement.DefineFsmStatement _ ->
                    throw new QueryExecutionException("DEFINE FSM must be a top-level statement, not nested inside a transaction");
            case Statement.TransactionBlock _ ->
                    throw new QueryExecutionException("nested BEGIN blocks are not supported");
        };
    }

    private QueryResult executeDefine(Statement.DefineFsmStatement d) {
        FsmDefinition.Builder builder = FsmDefinition.builder(d.name())
                .states(d.states().toArray(new String[0]))
                .initial(d.initial())
                .terminal(d.terminal().toArray(new String[0]))
                .alphabet(d.alphabet().toArray(new String[0]));
        for (var t : d.transitions()) {
            builder.transition(t.from(), t.input(), t.to());
        }
        FsmDefinition def = builder.build();
        storage.defineFsm(def);
        return new QueryResult.Ack("defined fsm '%s' (%s)".formatted(
                def.name(), def.isDeterministic() ? "deterministic" : "non-deterministic"));
    }

    private QueryResult executeCreate(Statement.CreateInstanceStatement c, Transaction tx) {
        FsmDefinition def = requireFsm(c.fsmName());
        FsmInstance instance = FsmInstance.created(def.name(), c.instanceId(), def.initialState(), c.attributes(), clock.get());
        tx.createInstance(instance);
        return new QueryResult.InstanceResult(instance);
    }

    private QueryResult executeTransition(Statement.TransitionStatement t, Transaction tx) {
        FsmDefinition def = requireFsm(t.fsmName());
        FsmInstance instance = tx.getInstance(t.fsmName(), t.instanceId())
                .orElseThrow(() -> new UnknownInstanceException(t.fsmName(), t.instanceId()));
        FsmInstance result = FsmEngine.applyAll(def, instance, t.inputs(), clock);
        tx.putInstance(result);
        return new QueryResult.InstanceResult(result);
    }

    private QueryResult executeGet(Statement.GetStatement g, Transaction tx) {
        requireFsm(g.fsmName());
        FsmInstance instance = tx.getInstance(g.fsmName(), g.instanceId())
                .orElseThrow(() -> new UnknownInstanceException(g.fsmName(), g.instanceId()));
        return new QueryResult.InstanceResult(instance);
    }

    private QueryResult executeMatch(Statement.MatchStatement m, Transaction tx) {
        FsmDefinition def = requireFsm(m.fsmName());
        List<FsmInstance> all = tx.allInstances(m.fsmName());
        if (m.where() == null) {
            return new QueryResult.InstanceListResult(all);
        }
        Map<PatternNode, HistoryRegex> patternCache = new HashMap<>();
        List<FsmInstance> filtered = all.stream()
                .filter(i -> evaluate(def, i, m.where(), patternCache))
                .toList();
        return new QueryResult.InstanceListResult(filtered);
    }

    // ------------------------------------------------------------------

    private FsmDefinition requireFsm(String name) {
        return storage.getFsmDefinition(name).orElseThrow(() -> new UnknownFsmException(name));
    }

    private boolean evaluate(FsmDefinition def, FsmInstance instance, Condition cond,
                              Map<PatternNode, HistoryRegex> patternCache) {
        return switch (cond) {
            case Condition.StateEquals(String state) -> instance.currentStates().contains(state);
            case Condition.StateIsTerminal _ -> FsmEngine.isCurrentlyTerminal(def, instance);
            case Condition.StateIsInitial _ -> instance.currentStates().contains(def.initialState());
            case Condition.HistoryLike(PatternNode pattern) -> patternCache
                    .computeIfAbsent(pattern, HistoryRegex::compile)
                    .matches(instance.inputSequence());
            case Condition.AttributeCompare(String attr, Condition.CompareOp op, Object literal) ->
                    compare(instance.attributes().get(attr), op, literal);
            case Condition.And(Condition l, Condition r) ->
                    evaluate(def, instance, l, patternCache) && evaluate(def, instance, r, patternCache);
            case Condition.Or(Condition l, Condition r) ->
                    evaluate(def, instance, l, patternCache) || evaluate(def, instance, r, patternCache);
        };
    }

    private boolean compare(Object actual, Condition.CompareOp op, Object literal) {
        if (op == Condition.CompareOp.EQ) return numericAwareEquals(actual, literal);
        if (op == Condition.CompareOp.NEQ) return !numericAwareEquals(actual, literal);
        if (!(actual instanceof Number an) || !(literal instanceof Number ln)) {
            throw new QueryExecutionException(
                    "cannot order-compare %s and %s with operator %s (ordering comparisons require both sides to be numbers)"
                            .formatted(actual, literal, op));
        }
        double a = an.doubleValue(), b = ln.doubleValue();
        return switch (op) {
            case LT -> a < b;
            case GT -> a > b;
            case LE -> a <= b;
            case GE -> a >= b;
            case EQ, NEQ -> throw new AssertionError("handled above");
        };
    }

    /** Numbers compare by value across Long/Double (5 == 5.0); everything else falls back to equals. */
    private boolean numericAwareEquals(Object a, Object b) {
        if (a instanceof Number an && b instanceof Number bn) {
            return an.doubleValue() == bn.doubleValue();
        }
        return Objects.equals(a, b);
    }
}
