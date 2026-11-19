package com.quiver;

import com.quiver.query.exec.QueryExecutor;
import com.quiver.query.exec.QueryResult;
import com.quiver.storage.StorageEngine;
import com.quiver.storage.memory.InMemoryStorageEngine;

import java.nio.file.Path;
import java.util.List;

/**
 * The single thing the application actually talks to: a {@link StorageEngine}
 * and a {@link QueryExecutor} wired together behind one {@code execute(String)}
 * call. This is also the seam a future distributed deployment sits behind:
 * a {@code quiver-cluster} module would provide a {@code StorageEngine} that
 * shards {@code fsm:id} across nodes and replicates each shard, and {@code
 * QuiverDatabase} (and everything above it: the query language, the CLI) would not need
 * to change at all.
 */
public final class QuiverDatabase implements AutoCloseable {

    private final StorageEngine storage;
    private final QueryExecutor executor;

    private QuiverDatabase(StorageEngine storage) {
        this.storage = storage;
        this.executor = new QueryExecutor(storage);
    }

    /** A durable database: persists to (and, if it already exists, replays from) a write-ahead log at {@code path}. */
    public static QuiverDatabase open(Path path) {
        return new QuiverDatabase(InMemoryStorageEngine.open(path));
    }

    /** An in-memory-only database: fast, and everything is lost when it's closed. Mainly for tests and demos. */
    public static QuiverDatabase ephemeral() {
        return new QuiverDatabase(InMemoryStorageEngine.ephemeral());
    }

    /** Parses and runs exactly one QSL statement. */
    public QueryResult execute(String qsl) {
        return executor.execute(qsl);
    }

    /** Parses and runs every {@code ;}-separated statement in {@code qsl}, returning one result per statement. */
    public List<QueryResult> executeScript(String qsl) {
        return executor.executeScript(qsl);
    }

    @Override
    public void close() {
        storage.close();
    }
}
