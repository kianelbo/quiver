# Architecture

## Data model

**An fsm definition is a table schema. An fsm instance is a row.**

```
FsmDefinition "order"                    FsmInstance "order:o1001"
  states: {created, paid, shipped, ...}    currentStates: {paid}
  initialState: created                    history: [(pay, t0, {paid})]
  terminalStates: {delivered, cancelled}   attributes: {customer: "alice"}
  alphabet: {pay, ship, deliver, cancel}   version: 1
  transitions: (state,input) -> {states}
```

### Non-determinism is a first-class feature

A `FsmDefinition`'s transition relation is `Map<(state, input), Set<state>>`, not
`Map<(state, input), state>`. Declaring two transitions out of the same `(state, input)`
pair doesn't error -- it just makes that step non-deterministic, exactly like drawing two
out-edges with the same label on an NFA diagram.

Consequently, `FsmInstance.currentStates()` returns a **set** of states, not one state.
This is the standard subset-construction view of an NFA, computed incrementally: applying
an input unions, over every state the instance is currently in, that state's targets on
that input (`FsmEngine.apply`). A deterministic fsm is simply the case where every one of
those sets always has size 1 -- nothing in the model treats determinism specially, and
`FsmDefinition.isDeterministic()` is purely an informational query over an otherwise
uniform representation.

Two consequences worth knowing:

- **"Terminal" is existential.** `STATE IS TERMINAL` is true if *any* of an instance's
  current states is terminal -- the standard NFA acceptance condition. An instance that's
  non-deterministically "maybe approved, maybe still in manual review" already reads as
  terminal if `approved` is terminal, even though it might "really" still be in review.
- **A step can fail even when some current states have an edge.** `FsmEngine.apply`
  rejects an input only when *none* of the instance's current states has a transition for
  it (the unioned next-state set is empty). If even one branch survives, the instance
  moves forward along just that branch -- dead branches silently drop out of the set,
  again exactly as in NFA simulation.

### History is the append-only source of truth

Every `FsmInstance` carries its full `List<HistoryEntry>` (`input`, `timestamp`,
`resultingStates` at that step) -- not just the current state. This is what makes
`HISTORY LIKE` queries possible at all, and it's also why instances are immutable:
`withTransition` returns a *new* `FsmInstance` with one more history entry and a bumped
`version`, rather than mutating anything in place. That immutability is what lets the
storage layer hand instances to concurrent readers and use them directly as the "pinned"
snapshot for optimistic concurrency control, with no copying.

## Storage: optimistic concurrency + a write-ahead log

`StorageEngine` is the boundary between "how state is queried and mutated" (`com.quiver.query`)
and "where state actually lives." `defineFsm` commits immediately (schema isn't
transactional yet -- see the roadmap); every read and write of instance data goes through
a `Transaction`, and there is deliberately no "just write this instance" method on
`StorageEngine` itself, so the atomicity/isolation guarantees can't accidentally be
bypassed.

**Isolation** is optimistic, not lock-based: a `Transaction` holds no locks while it runs.
The first read of each `fsm:id` key pins the version it saw; `commit()` re-checks every
pinned key against live state, and aborts the whole transaction (`TransactionConflictException`,
safe to retry) if anything moved. `createInstance` pins "absence" the same way, so two
concurrent `CREATE`s of the same id correctly produce one winner and one
`DuplicateInstanceException` rather than a silent overwrite. This gives every
`fsm_id:instance_id(input)` call -- read, decide, write -- correct, non-blocking
semantics. What it does *not* give (yet): a frozen point-in-time view across a `MATCH`
scan -- two reads of `allInstances` in the same transaction can see different committed
data if something else commits in between ("read committed", not "repeatable read"). See
the roadmap for tightening this to real snapshot isolation.

**Durability** is one flat, fsync'd, append-only write-ahead log: one JSON line per
`DEFINE_FSM`, and one JSON line per successful commit (carrying whole-instance snapshots
of everything it wrote, so replay is a simple last-write-wins overwrite rather than
reconstructing deltas). A crash can only tear the very last line; `WriteAheadLog.replay`
treats a parse failure on the last line as an expected torn write and drops it, and a
parse failure anywhere earlier as real corruption. There is no rotation or compaction yet
-- a long-lived busy database would grow this file without bound (also on the roadmap).

Commit itself is serialized behind one lock (`InMemoryStorageEngine.commitLock`): reads
never block (the underlying maps are lock-free for `get`), writers only contend with each
other, and only for the short validate-then-apply window. Sharding that lock, or moving
to per-key locking / real MVCC, is exactly the kind of change a later increment can make
without touching the `StorageEngine` interface at all.

## Query pipeline

```
   QSL text
      |
      v
   Lexer            -- com.quiver.query.lexer
      |  List<Token>
      v
   Parser           -- com.quiver.query.parser
      |  List<Statement>  (sealed AST: DefineFsmStatement, CreateInstanceStatement,
      |                     TransitionStatement, GetStatement, MatchStatement,
      |                     TransactionBlock)
      v
   QueryExecutor     -- com.quiver.query.exec
      |  translates a Statement into FsmEngine calls + StorageEngine/Transaction calls
      v
   QueryResult        (Ack | InstanceResult | InstanceListResult | TransactionResult)
```

The lexer and parser know nothing about fsm definitions or storage; the executor is
where the query language and the data model actually meet. `MATCH ... WHERE` conditions
compile to a small evaluator over `Condition` (see `QueryExecutor.evaluate`); a
`HISTORY LIKE` pattern is compiled once per `MATCH` call (not once per instance) into a
`HistoryRegex` and then just run against each instance's input sequence.

### HISTORY LIKE: a regex engine over whole input names, via Thompson construction

A pattern's "characters" are whole fsm input names (`pay`, `ship`, ...), not single
characters, but otherwise `HISTORY LIKE` is a real, if small, regular expression language:
literals, `.` (any one input), `*`/`+`/`?` quantifiers, `|` alternation, `(...)` grouping.
`com.quiver.query.pattern.HistoryRegex` compiles the parsed `PatternNode` AST to a small
NFA bytecode program (Thompson construction: each AST node emits a self-contained
fragment of `CHAR`/`ANY`/`JMP`/`SPLIT`/`MATCH` instructions, with forward jumps
backpatched once the sub-fragment they jump over has actually been compiled) and matches
it against an instance's input sequence via direct NFA simulation ("Pike's VM" --
maintain the set of states reachable without consuming input, advance the whole set one
input at a time). This is `O(sequence length x program size)` with **no backtracking**,
so even a textbook backtracking-killer pattern like `(x?){20}x{20}` matches instantly
(there's a test for exactly this). It's also a deliberate thematic fit: pattern matching
in an FSM-based database is, itself, powered by a tiny finite automaton.

Matching is **fully anchored** to the whole history, like SQL's `LIKE` (and unlike
`grep`): a pattern that should match anywhere needs explicit `.*` at the ends that aren't
anchored, just like `LIKE '%x%'` needs explicit `%`. One piece of sugar makes that
natural to write: a bare `*`, `+`, or `?` with nothing to its left to quantify is treated
as shorthand for `.*`, `.+`, `.?`. That's what turns `*, pay, ship, *` into the properly
anchored `.*, pay, ship, .*` ("pay then ship, anywhere in the history") without making
the person spell out the dot. See `docs/QUERY_LANGUAGE.md` for the full pattern grammar.

## Where sharding/replication would go

`StorageEngine` and `Transaction` are the seam. Nothing above them (`com.quiver.query`,
`com.quiver.engine`, a client) knows or cares that today's implementation is a single
in-process map. A future clustering layer would provide a `StorageEngine` that:

- hashes `fsm_id:instance_id` to a shard (consistent hashing, so adding/removing nodes
  reshuffles a bounded fraction of keys) -- a natural fit, since every read and write in
  this model is already keyed by exactly that string;
- replicates each shard's write-ahead log to a small quorum (Raft is the obvious choice
  given the WAL already exists and is the unit of durability);
- for a transaction touching keys on more than one shard, runs a coordinator that
  collects each shard's OCC validation result before any shard applies its writes (i.e.
  two-phase commit layered on top of each shard's existing single-node OCC check, rather
  than replacing it).

The `MATCH` scan (`Transaction.allInstances`) would become a scatter-gather across every
shard holding that fsm's instances. None of this requires changing `Statement`,
`Condition`, `PatternNode`, or `QueryExecutor` -- which is the whole point of the
interface boundary being where it is.
