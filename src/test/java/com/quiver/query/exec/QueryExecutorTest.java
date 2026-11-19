package com.quiver.query.exec;

import com.quiver.core.exceptions.DuplicateInstanceException;
import com.quiver.core.exceptions.InvalidTransitionException;
import com.quiver.core.exceptions.UnknownFsmException;
import com.quiver.core.exceptions.UnknownInstanceException;
import com.quiver.core.instance.FsmInstance;
import com.quiver.storage.memory.InMemoryStorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class QueryExecutorTest {

    private InMemoryStorageEngine storage;
    private QueryExecutor exec;

    private static final String ORDER_FSM = """
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

    @BeforeEach
    void setUp() {
        storage = InMemoryStorageEngine.ephemeral();
        exec = new QueryExecutor(storage);
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    @Test
    void definesAnFsmAndReportsWhetherItIsDeterministic() {
        var ack = (QueryResult.Ack) exec.execute(ORDER_FSM);
        assertTrue(ack.message().contains("deterministic"));
        assertTrue(storage.getFsmDefinition("order").isPresent());
    }

    @Test
    void infersStatesAndAlphabetFromTransitions() {
        exec.execute("DEFINE FSM inferred (INITIAL s, TRANSITIONS (s (a): t, t (b): u))");
        var def = storage.getFsmDefinition("inferred").orElseThrow();
        assertEquals(Set.of("s", "t", "u"), def.states());
        assertEquals(Set.of("a", "b"), def.alphabet());
    }

    @Test
    void createStartsAnInstanceInTheInitialStateWithEmptyHistory() {
        exec.execute(ORDER_FSM);
        var result = (QueryResult.InstanceResult) exec.execute("CREATE order:o1001");
        assertEquals(Set.of("created"), result.instance().currentStates());
        assertTrue(result.instance().history().isEmpty());
    }

    @Test
    void createWithAttributesStoresThem() {
        exec.execute(ORDER_FSM);
        var result = (QueryResult.InstanceResult) exec.execute(
                "CREATE order:o1002 WITH { customer: \"alice\", rush: true }");
        assertEquals("alice", result.instance().attributes().get("customer"));
        assertEquals(Boolean.TRUE, result.instance().attributes().get("rush"));
    }

    @Test
    void duplicateCreateFails() {
        exec.execute(ORDER_FSM);
        exec.execute("CREATE order:o1001");
        assertThrows(DuplicateInstanceException.class, () -> exec.execute("CREATE order:o1001"));
    }

    @Test
    void transitionAdvancesStateAndRecordsHistory() {
        exec.execute(ORDER_FSM);
        exec.execute("CREATE order:o1001");
        var result = (QueryResult.InstanceResult) exec.execute("order:o1001(pay)");
        assertEquals(Set.of("paid"), result.instance().currentStates());
        assertEquals(List.of("pay"), result.instance().inputSequence());
    }

    @Test
    void transitionWithMultipleInputsAppliesThemAllAsOneAtomicStep() {
        exec.execute(ORDER_FSM);
        exec.execute("CREATE order:o1001");
        var result = (QueryResult.InstanceResult) exec.execute("order:o1001(pay, ship, deliver)");
        assertEquals(Set.of("delivered"), result.instance().currentStates());
        assertEquals(List.of("pay", "ship", "deliver"), result.instance().inputSequence());
    }

    @Test
    void invalidTransitionLeavesTheInstanceUntouched() {
        exec.execute(ORDER_FSM);
        exec.execute("CREATE order:o1001");
        assertThrows(InvalidTransitionException.class, () -> exec.execute("order:o1001(ship)")); // can't ship before paying
        var still = (QueryResult.InstanceResult) exec.execute("GET order:o1001");
        assertEquals(Set.of("created"), still.instance().currentStates());
        assertTrue(still.instance().history().isEmpty());
    }

    @Test
    void getOnUnknownInstanceThrows() {
        exec.execute(ORDER_FSM);
        assertThrows(UnknownInstanceException.class, () -> exec.execute("GET order:nope"));
    }

    @Test
    void anyStatementOnAnUndefinedFsmThrows() {
        assertThrows(UnknownFsmException.class, () -> exec.execute("CREATE order:o1001"));
    }

    @Test
    void matchWithNoWhereReturnsEveryInstance() {
        exec.execute(ORDER_FSM);
        exec.execute("CREATE order:a");
        exec.execute("CREATE order:b");
        var all = (QueryResult.InstanceListResult) exec.execute("MATCH order");
        assertEquals(2, all.instances().size());
    }

    @Test
    void matchFiltersByStateEquals() {
        exec.execute(ORDER_FSM);
        exec.execute("CREATE order:a");
        exec.execute("CREATE order:b");
        exec.execute("order:a(pay)");
        var paid = (QueryResult.InstanceListResult) exec.execute("MATCH order WHERE STATE = paid");
        assertEquals(1, paid.instances().size());
        assertEquals("a", paid.instances().get(0).instanceId());
    }

    @Test
    void matchFiltersByStateIsTerminal() {
        exec.execute(ORDER_FSM);
        exec.execute("CREATE order:delivered_one");
        exec.execute("order:delivered_one(pay, ship, deliver)");
        exec.execute("CREATE order:still_open");
        exec.execute("order:still_open(pay)");

        var terminal = (QueryResult.InstanceListResult) exec.execute("MATCH order WHERE STATE IS TERMINAL");
        assertEquals(1, terminal.instances().size());
        assertEquals("delivered_one", terminal.instances().get(0).instanceId());
    }

    @Test
    void matchFiltersByHistoryLikeMotivatingExamplePattern() {
        // "instances that have received input like *, a, b, *" from the project brief,
        // translated into this fsm's alphabet: anywhere, "pay" immediately followed by "ship".
        exec.execute(ORDER_FSM);
        exec.execute("CREATE order:hit");
        exec.execute("order:hit(pay, ship)"); // pay then ship: matches
        exec.execute("CREATE order:also_hit");
        exec.execute("order:also_hit(pay, ship, deliver)"); // pay,ship with a suffix: still matches
        exec.execute("CREATE order:miss");
        exec.execute("order:miss(pay)"); // pay with no ship yet: does not match
        exec.execute("CREATE order:cancelled_one");
        exec.execute("order:cancelled_one(cancel)"); // no pay/ship at all: does not match

        var result = (QueryResult.InstanceListResult) exec.execute("MATCH order WHERE HISTORY LIKE *, pay, ship, *");
        var ids = result.instances().stream().map(FsmInstance::instanceId).sorted().toList();
        assertEquals(List.of("also_hit", "hit"), ids);
    }

    @Test
    void matchCombinesConditionsWithAndOr() {
        exec.execute(ORDER_FSM);
        exec.execute("CREATE order:a WITH { priority: \"high\" }");
        exec.execute("order:a(pay)");
        exec.execute("CREATE order:b WITH { priority: \"low\" }");
        exec.execute("order:b(pay)");

        var result = (QueryResult.InstanceListResult) exec.execute(
                "MATCH order WHERE STATE = paid AND priority = \"high\"");
        assertEquals(1, result.instances().size());
        assertEquals("a", result.instances().get(0).instanceId());
    }

    @Test
    void nonDeterministicFsmInstanceCanBeInMultipleStatesAndMatchesExistentially() {
        exec.execute("""
                DEFINE FSM review (
                  STATES (submitted, approved, manual_review, rejected),
                  INITIAL submitted,
                  TERMINAL (approved, rejected),
                  ALPHABET (decide),
                  TRANSITIONS (
                  submitted (decide): approved, manual_review,
                  manual_review (decide): rejected
                  )
                )
                """);
        exec.execute("CREATE review:r1");
        var result = (QueryResult.InstanceResult) exec.execute("review:r1(decide)");
        assertEquals(Set.of("approved", "manual_review"), result.instance().currentStates());

        // existential: it IS terminal because "approved" is one of the possible current states
        var terminal = (QueryResult.InstanceListResult) exec.execute("MATCH review WHERE STATE IS TERMINAL");
        assertEquals(1, terminal.instances().size());
    }

    @Test
    void transactionBlockCommitsAllStatementsAtomically() {
        exec.execute(ORDER_FSM);
        exec.execute("CREATE order:a");
        exec.execute("CREATE order:b");

        exec.execute("""
                BEGIN
                  order:a(pay);
                  order:b(pay)
                COMMIT
                """);

        var a = (QueryResult.InstanceResult) exec.execute("GET order:a");
        var b = (QueryResult.InstanceResult) exec.execute("GET order:b");
        assertEquals(Set.of("paid"), a.instance().currentStates());
        assertEquals(Set.of("paid"), b.instance().currentStates());
    }

    @Test
    void transactionBlockIsAllOrNothingWhenAStatementPartwayThroughFails() {
        exec.execute(ORDER_FSM);
        exec.execute("CREATE order:a");
        exec.execute("CREATE order:b");

        assertThrows(InvalidTransitionException.class, () -> exec.execute("""
                BEGIN
                  order:a(pay);
                  order:b(ship)
                COMMIT
                """)); // b can't "ship" from "created" -- the whole block must fail

        // "a" must NOT have been paid either, even though its statement came first and
        // "succeeded" on its own -- that's exactly what BEGIN/COMMIT atomicity means.
        var a = (QueryResult.InstanceResult) exec.execute("GET order:a");
        assertEquals(Set.of("created"), a.instance().currentStates());
    }

    @Test
    void defineInsideATransactionBlockIsRejected() {
        assertThrows(QueryExecutionException.class, () -> exec.execute("""
                BEGIN
                  DEFINE FSM x (STATES (s), INITIAL s, TRANSITIONS (s (a): s))
                COMMIT
                """));
    }
}
