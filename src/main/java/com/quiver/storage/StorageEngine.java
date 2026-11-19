package com.quiver.storage;

import com.quiver.core.fsm.FsmDefinition;

import java.util.List;
import java.util.Optional;

/**
 * The boundary between "how state is queried and mutated" (quiver-query) and "where
 * state actually lives" (this module and, later, a distributed implementation of it).
 *
 * <p>Defining an fsm is schema, not row data, so {@link #defineFsm} commits immediately
 * and is not part of any {@link Transaction} -- there is no ALTER or DROP yet (see the
 * roadmap), so definitions never change underneath a running transaction. Everything
 * that touches instance data goes through a {@code Transaction}: there is deliberately
 * no "just write this instance" method here, so it's impossible to accidentally bypass
 * the atomicity/isolation guarantees the engine exists to provide.
 *
 * <p>This increment ships exactly one implementation, {@code memory.InMemoryStorageEngine},
 * which is single-node. The interface itself does not assume that: a future sharded/replicated
 * implementation can hash {@code fsm_id:instance_id} to a shard, run a two-phase or
 * Raft-replicated commit across nodes, and still satisfy this same contract, so the
 * query layer above it would not need to change.
 */
public interface StorageEngine extends AutoCloseable {

    /** Registers a new fsm definition ("table"). Fails immediately if the name is already taken. */
    void defineFsm(FsmDefinition definition);

    Optional<FsmDefinition> getFsmDefinition(String fsmName);

    List<String> listFsmNames();

    /** Starts a new transaction. Every instance read and write after this goes through it. */
    Transaction beginTransaction();

    @Override
    void close();
}
