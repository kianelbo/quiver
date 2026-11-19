# Quiver

Quiver is a NoSQL database where every "table" is a **finite state machine definition**
and every "row" is a **running instance** of that machine. An fsm definition can be
non-deterministic (an instance may be in several possible states at once, exactly like a
classical NFA), every instance remembers the full, ordered, timestamped sequence of
inputs it has ever received, and the query language (**QSL**) lets you advance instances,
fetch them, and search for them by current state or by a regular expression over their
input history.

```
DEFINE FSM order (
  INITIAL created,
  TERMINAL (delivered, cancelled),
  TRANSITIONS (
    created (pay): paid,
    paid (ship): shipped,
    shipped (deliver): delivered,
    created (cancel): cancelled,
    paid (cancel): cancelled
  )
);

CREATE order:o1001 WITH { customer: "alice", rush: true };
order:o1001(pay, ship, deliver);

MATCH order WHERE STATE IS TERMINAL;
MATCH order WHERE HISTORY LIKE *, pay, ship, *;   -- "pay immediately followed by ship, anywhere"

BEGIN
  order:o1001(pay);
  inventory:i55(reserve)
COMMIT;   -- both succeed or neither does
```

This is an **incremental build**. What's here now is a complete single-node engine:
ACID transactions (optimistic concurrency control + a write-ahead log for durability),
and the full query language described above. Sharding and replication are designed for
(see [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)) but not yet implemented -- see
[`docs/ROADMAP.md`](docs/ROADMAP.md) for exactly what's deferred and why.

## Quick start

Requires JDK 25 and Gradle.

```bash
gradle test     # builds and runs all tests
gradle installDist  # creates a runnable distribution with its runtime dependencies
```

Start the server with no arguments for an ephemeral database, or pass a WAL path for
durable storage:

```bash
build/install/quiver-server/bin/quiver-server mydata.wal --host 127.0.0.1 --port 3672
```

The server listens on `127.0.0.1:3672` by default. To accept connections from other
machines, pass an externally reachable address with `--host`. The server requires
`QUIVER_PASSWORD` to be set in its environment before startup.

The distribution also includes a CLI. Run it interactively, or send a QSL script:

```bash
build/install/quiver-server/bin/quiver-cli --url quiver://127.0.0.1:3672
build/install/quiver-server/bin/quiver-cli --url quiver://127.0.0.1:3672 -e 'DEFINE FSM order (INITIAL created, TRANSITIONS (created (pay): paid))'
```

The CLI reads `QUIVER_PASSWORD` from its environment, or prompts for it without echo
when run in a terminal. It never accepts the password as a command-line argument. The
client proves knowledge of the password using a fresh, one-time HMAC-SHA256 challenge
on each TCP connection, so the password itself is not sent over the wire. This protocol
does not provide transport encryption; use a trusted network or TLS tunnel for remote
connections. The server stays running until stopped with Ctrl-C and closes its WAL on
shutdown.

### Build requirements

The build requires JDK 25 and a recent Gradle installation. Jackson is a runtime
dependency, and JUnit dependencies are used only by the test suite; all are resolved
from Maven Central. Gradle's native JUnit Platform support runs the tests with
`gradle test`.
