package com.quiver.storage.memory;

import com.quiver.core.exceptions.DuplicateFsmException;
import com.quiver.core.exceptions.DuplicateInstanceException;
import com.quiver.core.exceptions.TransactionConflictException;
import com.quiver.core.fsm.FsmDefinition;
import com.quiver.core.instance.FsmInstance;
import com.quiver.storage.Transaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryStorageEngineTest {

    private FsmDefinition order() {
        return FsmDefinition.builder("order")
                .states("created", "paid", "shipped")
                .initial("created")
                .terminal("shipped")
                .alphabet("pay", "ship")
                .transition("created", "pay", "paid")
                .transition("paid", "ship", "shipped")
                .build();
    }

    @Test
    void definingTheSameFsmTwiceFails() {
        var engine = InMemoryStorageEngine.ephemeral();
        engine.defineFsm(order());
        assertThrows(DuplicateFsmException.class, () -> engine.defineFsm(order()));
    }

    @Test
    void createThenReadInSeparateTransactionsSeesTheCommittedInstance() {
        var engine = InMemoryStorageEngine.ephemeral();
        engine.defineFsm(order());

        try (Transaction tx = engine.beginTransaction()) {
            tx.createInstance(FsmInstance.created("order", "o1", "created", Map.of(), Instant.now()));
            tx.commit();
        }

        try (Transaction tx = engine.beginTransaction()) {
            FsmInstance read = tx.getInstance("order", "o1").orElseThrow();
            assertEquals(Set.of("created"), read.currentStates());
        }
    }

    @Test
    void creatingTheSameInstanceIdTwiceAcrossTransactionsFailsOnTheSecondCommit() {
        var engine = InMemoryStorageEngine.ephemeral();
        engine.defineFsm(order());

        try (Transaction tx1 = engine.beginTransaction()) {
            tx1.createInstance(FsmInstance.created("order", "o1", "created", Map.of(), Instant.now()));
            tx1.commit();
        }
        try (Transaction tx2 = engine.beginTransaction()) {
            tx2.createInstance(FsmInstance.created("order", "o1", "created", Map.of(), Instant.now()));
            assertThrows(DuplicateInstanceException.class, tx2::commit);
        }
    }

    @Test
    void creatingTheSameInstanceIdTwiceWithinOneTransactionFailsImmediately() {
        var engine = InMemoryStorageEngine.ephemeral();
        engine.defineFsm(order());
        try (Transaction tx = engine.beginTransaction()) {
            tx.createInstance(FsmInstance.created("order", "o1", "created", Map.of(), Instant.now()));
            assertThrows(DuplicateInstanceException.class,
                    () -> tx.createInstance(FsmInstance.created("order", "o1", "created", Map.of(), Instant.now())));
        }
    }

    @Test
    void readYourOwnWritesWithinATransaction() {
        var engine = InMemoryStorageEngine.ephemeral();
        engine.defineFsm(order());
        try (Transaction tx = engine.beginTransaction()) {
            FsmInstance created = FsmInstance.created("order", "o1", "created", Map.of(), Instant.now());
            tx.createInstance(created);
            FsmInstance seen = tx.getInstance("order", "o1").orElseThrow();
            assertEquals(Set.of("created"), seen.currentStates());
        }
    }

    @Test
    void twoTransactionsBothTransitioningTheSameInstanceOnlyOneWins() {
        var engine = InMemoryStorageEngine.ephemeral();
        engine.defineFsm(order());
        try (Transaction setup = engine.beginTransaction()) {
            setup.createInstance(FsmInstance.created("order", "o1", "created", Map.of(), Instant.now()));
            setup.commit();
        }

        Transaction txA = engine.beginTransaction();
        Transaction txB = engine.beginTransaction();

        // Both read the same version (0) before either writes.
        FsmInstance seenByA = txA.getInstance("order", "o1").orElseThrow();
        FsmInstance seenByB = txB.getInstance("order", "o1").orElseThrow();
        assertEquals(seenByA.version(), seenByB.version());

        FsmInstance updatedByA = seenByA.withTransition("pay", Set.of("paid"), Instant.now());
        FsmInstance updatedByB = seenByB.withTransition("pay", Set.of("paid"), Instant.now());
        txA.putInstance(updatedByA);
        txB.putInstance(updatedByB);

        txA.commit(); // first to commit wins
        assertThrows(TransactionConflictException.class, txB::commit); // second sees a stale read version

        try (Transaction check = engine.beginTransaction()) {
            FsmInstance finalState = check.getInstance("order", "o1").orElseThrow();
            assertEquals(1L, finalState.version()); // only A's write applied
        }
    }

    @Test
    void aReadOnlyTransactionAlwaysCommitsCleanlyEvenIfOthersConcurrentlyWrite() {
        var engine = InMemoryStorageEngine.ephemeral();
        engine.defineFsm(order());
        try (Transaction setup = engine.beginTransaction()) {
            setup.createInstance(FsmInstance.created("order", "o1", "created", Map.of(), Instant.now()));
            setup.commit();
        }

        try (Transaction reader = engine.beginTransaction()) {
            reader.getInstance("order", "o1"); // pins a read, but never writes

            try (Transaction writer = engine.beginTransaction()) {
                FsmInstance current = writer.getInstance("order", "o1").orElseThrow();
                writer.putInstance(current.withTransition("pay", Set.of("paid"), Instant.now()));
                writer.commit();
            }

            assertDoesNotThrow(reader::commit); // read-only, so nothing to conflict on
        }
    }

    @Test
    void rollbackDiscardsStagedWrites() {
        var engine = InMemoryStorageEngine.ephemeral();
        engine.defineFsm(order());
        try (Transaction tx = engine.beginTransaction()) {
            tx.createInstance(FsmInstance.created("order", "o1", "created", Map.of(), Instant.now()));
            tx.rollback();
        }
        try (Transaction tx = engine.beginTransaction()) {
            assertTrue(tx.getInstance("order", "o1").isEmpty());
        }
    }

    @Test
    void operationsAfterCommitOrRollbackAreRejected() {
        var engine = InMemoryStorageEngine.ephemeral();
        engine.defineFsm(order());
        Transaction tx = engine.beginTransaction();
        tx.commit();
        assertThrows(IllegalStateException.class, () -> tx.getInstance("order", "o1"));
    }

    @Test
    void allInstancesReflectsCommittedDataForTheGivenFsmOnly() {
        var engine = InMemoryStorageEngine.ephemeral();
        engine.defineFsm(order());
        engine.defineFsm(FsmDefinition.builder("ticket")
                .states("open", "closed").initial("open").terminal("closed")
                .alphabet("close").transition("open", "close", "closed").build());

        try (Transaction tx = engine.beginTransaction()) {
            tx.createInstance(FsmInstance.created("order", "o1", "created", Map.of(), Instant.now()));
            tx.createInstance(FsmInstance.created("order", "o2", "created", Map.of(), Instant.now()));
            tx.createInstance(FsmInstance.created("ticket", "t1", "open", Map.of(), Instant.now()));
            tx.commit();
        }

        try (Transaction tx = engine.beginTransaction()) {
            List<FsmInstance> orders = tx.allInstances("order");
            assertEquals(2, orders.size());
            assertTrue(orders.stream().allMatch(i -> i.fsmName().equals("order")));
        }
    }

    @Test
    void survivesAReopenByReplayingTheWal(@TempDir Path dir) {
        Path walPath = dir.resolve("db.wal");

        try (var engine = InMemoryStorageEngine.open(walPath)) {
            engine.defineFsm(order());
            try (Transaction tx = engine.beginTransaction()) {
                tx.createInstance(FsmInstance.created("order", "o1", "created", Map.of(), Instant.now()));
                tx.commit();
            }
            try (Transaction tx = engine.beginTransaction()) {
                FsmInstance current = tx.getInstance("order", "o1").orElseThrow();
                tx.putInstance(current.withTransition("pay", Set.of("paid"), Instant.now()));
                tx.commit();
            }
        } // engine "crashes" / shuts down here

        try (var reopened = InMemoryStorageEngine.open(walPath)) {
            assertTrue(reopened.getFsmDefinition("order").isPresent());
            try (Transaction tx = reopened.beginTransaction()) {
                FsmInstance recovered = tx.getInstance("order", "o1").orElseThrow();
                assertEquals(Set.of("paid"), recovered.currentStates());
                assertEquals(List.of("pay"), recovered.inputSequence());
            }
        }
    }
}
