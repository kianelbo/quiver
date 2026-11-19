# Roadmap

Currently a complete single-node engine: the data model (including
non-deterministic fsms), ACID transactions (OCC + WAL), and the full query language
(including the `HISTORY LIKE` regex engine). What to do later :

## Distribution (the big one)

- **Sharding.** Hash `fsm_id:instance_id` to a shard via consistent hashing. See
  `docs/ARCHITECTURE.md`'s "Where sharding/replication would go" for the planned shape
  of a `quiver-cluster` module implementing this behind the existing `StorageEngine`
  interface.
- **Replication.** Raft-replicate each shard's write-ahead log to a quorum. The WAL
  already exists and is already the unit of durability, so this is "ship the same log
  entries to followers" rather than a new mechanism.
- **Cross-shard transactions.** A coordinator collecting each shard's OCC validation
  result before any shard applies its writes -- two-phase commit layered on top of each
  shard's existing single-node OCC check, not a replacement for it.
- **Scatter-gather MATCH.** Once instances of one fsm can live on multiple shards, a
  `MATCH` needs to fan out to every shard holding that fsm and merge results.

## Storage

- **Stronger isolation for MATCH.** Today, two `allInstances` calls in the same
  transaction can observe different committed data if something else commits in between
  ("read committed"). True snapshot isolation (pin a single commit-sequence-number at
  transaction start, read as of that snapshot) would close this without changing the
  `Transaction` interface, only what `allInstances` does internally.
- **WAL compaction.** The log is one flat, never-rotated file today. A periodic
  snapshot-and-truncate (or segment-and-garbage-collect) scheme is needed before this
  could run unattended for a long time under real write volume.
- **Finer-grained commit locking.** Commit is serialized behind one global lock today
  (see `InMemoryStorageEngine`). Sharding that lock by fsm, or moving to per-key locking
  / real MVCC, would raise write throughput without changing correctness.
- **Indexes.** `MATCH` is a full scan of every instance of an fsm today. An index on
  current state (trivial -- there are only as many buckets as declared states) or on a
  commonly-queried attribute would make large-fsm `MATCH ... WHERE` queries much
  cheaper.

## Data model

- **Schema evolution.** There's no `ALTER FSM` or `DROP FSM` yet -- `defineFsm` only
  ever adds a brand-new definition. Changing a live fsm's states/transitions safely
  (what happens to instances already in a state that no longer exists?) needs real
  design, not just an API addition.
- **Epsilon transitions.** The NFA model doesn't support epsilon (input-free) moves.
  Useful for things like "automatically enter a sub-state on arrival" -- a closure step
  at definition-build time (precompute the epsilon-closure of every state) would add
  this without touching the simulation algorithm.
- **Guards / transition conditions.** A transition that only fires if some predicate
  over the instance's attributes holds (e.g. `created (pay): paid WHERE amount > 0`).
  Right now non-determinism is the only way to express "it depends" and it doesn't let
  you depend on attribute data, only on which branch a later input happens to confirm.
- **Richer numeric semantics.** Equality coerces Long/Double (`5 = 5.0`) but there's no
  decimal/BigDecimal type, no integer-overflow story, and ordering comparisons reject
  non-numeric operands outright rather than defining any cross-type ordering.

## Query language

- **`ROLLBACK`.** The token is reserved but unused -- there's no way to explicitly abort
  mid-block today because a `BEGIN` block is parsed and executed as one atomic unit with
  no interactive back-and-forth. An "open a transaction, run statements against it one
  at a time over multiple separate calls, then COMMIT or ROLLBACK whenever the caller
  decides" mode would need the executor to expose a live `Transaction` handle across
  calls instead of opening and closing one per `execute()`.
- **Nested `BEGIN` blocks** are explicitly rejected today rather than silently
  flattened or given savepoint semantics; savepoints (partial rollback within a
  transaction) would be the natural next step if nesting is actually needed.
  `DEFINE FSM` inside a transaction is rejected for the same underlying reason schema
  changes aren't transactional at all yet (see "Schema evolution" above).
- **`EXPLAIN`.** No way to see a `HISTORY LIKE` pattern's compiled NFA program or a
  `MATCH`'s query plan today; would help once indexes exist and there's an actual plan
  worth explaining.
- **A client protocol.** The standalone process now keeps the database alive, but does
  not yet accept client connections. A wire protocol and client are needed before
  applications can communicate with it remotely.

## Tooling

- **CLI niceties.** No `.load <file>`, no command history/line editing, no syntax
  highlighting. The REPL is deliberately minimal for this increment.
- **Packaging.** The `application` plugin's `distZip`/`installDist` tasks bundle runtime
  dependencies and generate launch scripts. A self-contained executable jar would
  require additional packaging configuration if one is needed.
