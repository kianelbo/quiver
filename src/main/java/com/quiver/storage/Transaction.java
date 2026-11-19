package com.quiver.storage;

import com.quiver.core.instance.FsmInstance;

import java.util.List;
import java.util.Optional;

/**
 * A unit of atomic, isolated work over instance ("row") data.
 *
 * <p><b>Isolation, precisely.</b> Concurrency control is optimistic: no locks are held
 * while a transaction runs. The first time a transaction reads a given {@code fsm:id}
 * key (via {@link #getInstance}), it pins the version it saw for that key; at
 * {@link #commit()}, the engine re-checks every pinned key against the live store, and
 * fails the whole commit with {@code TransactionConflictException} if any of them moved.
 * This gives correct, safe semantics for the pattern every {@code fsm_id:instance_id(input)}
 * call has -- read an instance, decide what to write, write it back -- without ever
 * blocking a reader on a writer or vice versa.
 *
 * <p>What this does <em>not</em> give you, in this increment: a single frozen
 * point-in-time view across an {@link #allInstances} scan (used by MATCH). Two calls to
 * {@code allInstances} within the same transaction can observe different committed data
 * if another transaction commits in between -- i.e. MATCH reads at "read committed", not
 * "repeatable read". Tightening that to a true snapshot is on the roadmap; it does not
 * change this interface, only what happens inside {@code commit()}.
 *
 * <p>A transaction that never calls {@link #createInstance} or {@link #putInstance} is
 * read-only and always commits cleanly -- there is nothing to conflict on.
 */
public interface Transaction extends AutoCloseable {

    /** Reads an instance, pinning its version for conflict detection at commit time. */
    Optional<FsmInstance> getInstance(String fsmName, String instanceId);

    /** Every instance of the given fsm, read at "read committed" (see class docs). */
    List<FsmInstance> allInstances(String fsmName);

    /**
     * Stages the creation of a new instance. The id's absence is itself pinned: if another
     * transaction creates the same {@code fsm:id} first, this transaction's commit fails
     * with {@code DuplicateInstanceException} rather than silently overwriting it.
     */
    void createInstance(FsmInstance instance);

    /**
     * Stages a write of an already-transitioned instance (the caller -- the query executor --
     * is responsible for having computed it via {@code FsmEngine}). At commit, this fails
     * with {@code TransactionConflictException} if the version this write is based on has
     * since been superseded.
     */
    void putInstance(FsmInstance updated);

    /** Commits. Either every staged write becomes durable and visible, or none of it does. */
    void commit();

    /** Discards every staged write and every pinned read. Safe to call even if nothing was staged. */
    void rollback();

    @Override
    default void close() {
        rollback();
    }
}
