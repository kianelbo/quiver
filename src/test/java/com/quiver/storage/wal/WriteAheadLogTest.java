package com.quiver.storage.wal;

import com.quiver.core.fsm.FsmDefinition;
import com.quiver.core.instance.FsmInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WriteAheadLogTest {

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
    void replayOfANonexistentFileDoesNothing(@TempDir Path dir) {
        List<FsmDefinition> defs = new ArrayList<>();
        WriteAheadLog.replay(dir.resolve("missing.wal"), defs::add, w -> fail("should not be called"));
        assertTrue(defs.isEmpty());
    }

    @Test
    void roundTripsADefinitionAndAnInstanceWithHistory(@TempDir Path dir) {
        Path walPath = dir.resolve("test.wal");
        FsmDefinition def = order();
        FsmInstance instance = FsmInstance.created("order", "o1", "created", Map.of("customer", "alice"), Instant.now());
        instance = instance.withTransition("pay", Set.of("paid"), Instant.now());
        instance = instance.withTransition("ship", Set.of("shipped"), Instant.now());

        try (WriteAheadLog wal = WriteAheadLog.open(walPath)) {
            wal.appendDefineFsm(def);
            wal.appendCommit(List.of(instance));
        }

        List<FsmDefinition> defs = new ArrayList<>();
        List<FsmInstance> instances = new ArrayList<>();
        WriteAheadLog.replay(walPath, defs::add, writes -> instances.addAll(writes));

        assertEquals(1, defs.size());
        assertEquals("order", defs.get(0).name());
        assertEquals(Set.of("paid"), defs.get(0).targets("created", "pay"));

        assertEquals(1, instances.size());
        FsmInstance replayed = instances.get(0);
        assertEquals(Set.of("shipped"), replayed.currentStates());
        assertEquals(List.of("pay", "ship"), replayed.inputSequence());
        assertEquals("alice", replayed.attributes().get("customer"));
        assertEquals(2L, replayed.version());
    }

    @Test
    void aTornLastLineIsDroppedSilentlyOnReplay(@TempDir Path dir) throws IOException {
        Path walPath = dir.resolve("torn.wal");
        FsmInstance instance = FsmInstance.created("order", "o1", "created", Map.of(), Instant.now());

        try (WriteAheadLog wal = WriteAheadLog.open(walPath)) {
            wal.appendCommit(List.of(instance));
        }
        // simulate a crash mid-write: append a truncated, unparseable fragment as the new last line
        Files.write(walPath, "{\"type\":\"COMMIT\",\"writes\":[{\"fsm\":\"order\"".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);

        List<FsmInstance> instances = new ArrayList<>();
        assertDoesNotThrow(() -> WriteAheadLog.replay(walPath, d -> fail("no defs written"), instances::addAll));
        assertEquals(1, instances.size()); // the complete first record survives; the torn one is dropped
    }

    @Test
    void corruptionInAnEarlierLineIsNotSilentlyIgnored(@TempDir Path dir) throws IOException {
        Path walPath = dir.resolve("corrupt.wal");
        Files.writeString(walPath, "not even json\n{\"type\":\"COMMIT\",\"writes\":[]}\n");
        assertThrows(IllegalStateException.class,
                () -> WriteAheadLog.replay(walPath, d -> {}, w -> {}));
    }

    @Test
    void appendingAcrossTwoOpensProducesOneConsistentLog(@TempDir Path dir) {
        Path walPath = dir.resolve("append.wal");
        try (WriteAheadLog wal = WriteAheadLog.open(walPath)) {
            wal.appendDefineFsm(order());
        }
        // reopen and append more, simulating a process restart that keeps the same file
        try (WriteAheadLog wal = WriteAheadLog.open(walPath)) {
            wal.appendCommit(List.of(FsmInstance.created("order", "o1", "created", Map.of(), Instant.now())));
        }
        List<FsmDefinition> defs = new ArrayList<>();
        List<FsmInstance> instances = new ArrayList<>();
        WriteAheadLog.replay(walPath, defs::add, instances::addAll);
        assertEquals(1, defs.size());
        assertEquals(1, instances.size());
    }
}
