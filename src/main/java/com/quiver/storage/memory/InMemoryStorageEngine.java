package com.quiver.storage.memory;

import com.quiver.core.exceptions.DuplicateFsmException;
import com.quiver.core.fsm.FsmDefinition;
import com.quiver.core.instance.FsmInstance;
import com.quiver.storage.StorageEngine;
import com.quiver.storage.Transaction;
import com.quiver.storage.wal.WriteAheadLog;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A single-node {@link StorageEngine}: fsm definitions and instances live in
 * {@code ConcurrentHashMap}s, transactions are optimistic (see {@link OccTransaction}),
 * and every committed write is fsync'd to a {@link WriteAheadLog} first.
 *
 * <p>Commit itself -- validating a transaction's pinned read versions against live state
 * and, if they still match, applying its writes -- is serialized behind a single lock
 * ({@link #commitLock}). That is deliberately the simplest thing that is still correct:
 * reads never block (the underlying maps are lock-free for {@code get}), and writers
 * only contend with other writers, for the (short) duration of validate-then-apply. It
 * does mean commit throughput is capped by one thread's worth of work; sharding the lock
 * by fsm, or moving to per-key locking / real MVCC, is exactly the kind of thing a later
 * increment would do without changing the {@link StorageEngine} interface at all.
 */
public final class InMemoryStorageEngine implements StorageEngine {

    private final Map<String, FsmDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, FsmInstance> instances = new ConcurrentHashMap<>();
    private final WriteAheadLog wal; // null in ephemeral (no-durability) mode
    final Object commitLock = new Object();

    private InMemoryStorageEngine(WriteAheadLog wal) {
        this.wal = wal;
    }

    /** A durable engine: replays {@code walPath} if it exists, then appends new commits to it. */
    public static InMemoryStorageEngine open(Path walPath) {
        InMemoryStorageEngine engine = new InMemoryStorageEngine(WriteAheadLog.open(walPath));
        WriteAheadLog.replay(walPath,
                def -> engine.definitions.put(def.name(), def),
                writes -> { for (FsmInstance i : writes) engine.instances.put(i.key(), i); });
        return engine;
    }

    /** No WAL at all: fast, and everything is lost on {@link #close()}. Mainly for tests. */
    public static InMemoryStorageEngine ephemeral() {
        return new InMemoryStorageEngine(null);
    }

    @Override
    public void defineFsm(FsmDefinition definition) {
        synchronized (commitLock) {
            if (definitions.containsKey(definition.name())) {
                throw new DuplicateFsmException(definition.name());
            }
            if (wal != null) {
                wal.appendDefineFsm(definition);
            }
            definitions.put(definition.name(), definition);
        }
    }

    @Override
    public Optional<FsmDefinition> getFsmDefinition(String fsmName) {
        return Optional.ofNullable(definitions.get(fsmName));
    }

    @Override
    public List<String> listFsmNames() {
        return List.copyOf(definitions.keySet());
    }

    @Override
    public Transaction beginTransaction() {
        return new OccTransaction(this);
    }

    @Override
    public void close() {
        if (wal != null) {
            wal.close();
        }
    }

    // --- package-private, used only by OccTransaction ---

    FsmInstance getLive(String key) {
        return instances.get(key);
    }

    List<FsmInstance> liveInstancesForFsm(String fsmName) {
        String prefix = fsmName + ":";
        List<FsmInstance> result = new ArrayList<>();
        for (FsmInstance i : instances.values()) {
            if (i.key().startsWith(prefix)) {
                result.add(i);
            }
        }
        return result;
    }

    WriteAheadLog wal() {
        return wal;
    }

    void applyCommit(Collection<FsmInstance> writes) {
        for (FsmInstance i : writes) {
            instances.put(i.key(), i);
        }
    }
}
