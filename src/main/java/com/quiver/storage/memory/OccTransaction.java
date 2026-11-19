package com.quiver.storage.memory;

import com.quiver.core.exceptions.DuplicateInstanceException;
import com.quiver.core.exceptions.TransactionConflictException;
import com.quiver.core.instance.FsmInstance;
import com.quiver.storage.Transaction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * See {@link Transaction} for the isolation contract this implements. In short: the
 * first read of each key pins that key's version; commit re-checks every pinned key
 * against live state and aborts the whole transaction if anything moved.
 */
final class OccTransaction implements Transaction {

    private final InMemoryStorageEngine engine;
    private final Map<String, Long> readVersions = new HashMap<>(); // key -> version pinned, or -1 for "confirmed absent"
    private final Set<String> pendingCreates = new HashSet<>(); // subset of pendingWrites.keySet() staged via createInstance
    private final Map<String, FsmInstance> pendingWrites = new LinkedHashMap<>();
    private boolean finished = false;

    OccTransaction(InMemoryStorageEngine engine) {
        this.engine = engine;
    }

    @Override
    public Optional<FsmInstance> getInstance(String fsmName, String instanceId) {
        ensureActive();
        String key = fsmName + ":" + instanceId;
        FsmInstance staged = pendingWrites.get(key);
        if (staged != null) {
            return Optional.of(staged); // read-your-own-writes
        }
        FsmInstance live = engine.getLive(key);
        pinIfUnseen(key, live);
        return Optional.ofNullable(live);
    }

    @Override
    public List<FsmInstance> allInstances(String fsmName) {
        ensureActive();
        // "Read committed": overlay this transaction's own not-yet-committed writes on top of
        // whatever is currently live. No version is pinned for the scan as a whole -- see the
        // isolation note on Transaction.allInstances.
        Map<String, FsmInstance> merged = new LinkedHashMap<>();
        for (FsmInstance i : engine.liveInstancesForFsm(fsmName)) {
            merged.put(i.key(), i);
        }
        String prefix = fsmName + ":";
        for (var e : pendingWrites.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                merged.put(e.getKey(), e.getValue());
            }
        }
        return List.copyOf(merged.values());
    }

    @Override
    public void createInstance(FsmInstance instance) {
        ensureActive();
        String key = instance.key();
        if (pendingWrites.containsKey(key)) {
            throw new DuplicateInstanceException(instance.fsmName(), instance.instanceId());
        }
        // A create's precondition is "this key does not exist yet" -- pin -1 unconditionally
        // (not via pinIfUnseen/putIfAbsent) even if an earlier read in this same transaction
        // already pinned this key's *current* version to something else. Whatever was pinned
        // before, what we're now asserting is absence, and that's what commit must re-check.
        readVersions.put(key, -1L);
        pendingWrites.put(key, instance);
        pendingCreates.add(key);
    }

    @Override
    public void putInstance(FsmInstance updated) {
        ensureActive();
        String key = updated.key();
        pinIfUnseen(key, engine.getLive(key));
        pendingWrites.put(key, updated);
    }

    private void pinIfUnseen(String key, FsmInstance live) {
        readVersions.putIfAbsent(key, live == null ? -1L : live.version());
    }

    @Override
    public void commit() {
        ensureActive();
        finished = true;
        if (pendingWrites.isEmpty()) {
            return; // read-only transaction: nothing staged, nothing to validate, always succeeds
        }
        synchronized (engine.commitLock) {
            for (var entry : readVersions.entrySet()) {
                String key = entry.getKey();
                long expected = entry.getValue();
                FsmInstance live = engine.getLive(key);
                long actual = (live == null) ? -1L : live.version();
                if (actual != expected) {
                    if (pendingCreates.contains(key)) {
                        FsmInstance staged = pendingWrites.get(key);
                        throw new DuplicateInstanceException(staged.fsmName(), staged.instanceId());
                    }
                    throw new TransactionConflictException(
                            "conflict on '%s': read at version %d, but it is now at version %d (concurrently modified by another transaction -- retry)"
                                    .formatted(key, expected, actual));
                }
            }
            if (engine.wal() != null) {
                engine.wal().appendCommit(new ArrayList<>(pendingWrites.values()));
            }
            engine.applyCommit(pendingWrites.values());
        }
    }

    @Override
    public void rollback() {
        finished = true;
        readVersions.clear();
        pendingWrites.clear();
        pendingCreates.clear();
    }

    private void ensureActive() {
        if (finished) {
            throw new IllegalStateException("transaction has already committed or rolled back");
        }
    }
}
