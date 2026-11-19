# QSL -- the Quiver State Language

QSL statements end in `;`. Keywords are case-insensitive (`DEFINE`, `define`, `Define`
are the same keyword); identifiers (fsm names, instance ids, input names, attribute
names) are case-sensitive, since those are data. `--` starts a line comment.

## Statements

### `DEFINE FSM` -- declare a table schema

```
DEFINE FSM order (
  STATES (created, paid, shipped, delivered, cancelled),
  INITIAL created,
  TERMINAL (delivered, cancelled),
  ALPHABET (pay, ship, deliver, cancel),
  TRANSITIONS (
    created (pay): paid,
    paid (ship): shipped,
    shipped (deliver): delivered,
    created (cancel): cancelled,
    paid (cancel): cancelled
  )
);
```

Clauses can appear in any order, separated by commas. `STATES`/`TERMINAL`/`ALPHABET` are
parenthesized lists; `INITIAL` is a single state name; `TRANSITIONS` is a parenthesized
list of `source (input): destination` rules. `STATES` and `ALPHABET` are optional: if
omitted, states and inputs are inferred from the transition rules. If supplied, each list
must exactly match the states or inputs appearing in those rules; extra or missing
entries are errors. Multiple destinations for one rule are comma-separated; rules are
also comma-separated.

**Non-deterministic transitions are not an error.** List multiple destinations in one
rule to express that the step's outcome isn't determined by the input alone:

```
DEFINE FSM review (
  STATES (submitted, approved, manual_review, rejected),
  INITIAL submitted,
  TERMINAL (approved, rejected),
  ALPHABET (decide, escalate, override),
  TRANSITIONS (
    submitted (decide): approved, manual_review,
    manual_review (escalate): rejected,
    manual_review (override): approved
  )
);
```

After `review:r1(decide)`, the instance's current state is the **set**
`{approved, manual_review}` -- both are consistent with what's happened so far. See
`docs/ARCHITECTURE.md` for the full semantics (it's standard NFA subset construction).

### `CREATE` -- insert a row

```
CREATE order:o1001;
CREATE order:o1002 WITH { customer: "alice", rush: true, total: 42.5 };
```

The instance starts in its fsm's `INITIAL` state with empty history. `WITH { ... }` is a
JSON-like object literal (object keys may be bare identifiers or quoted strings; values
are objects, arrays, strings, numbers, `true`/`false`/`null`) stored as free-form
attributes, queryable later via `MATCH ... WHERE attr = value`.

### `fsm_id:instance_id(input, ...)` -- advance a row

```
order:o1001(pay);
order:o1001(pay, ship, deliver);   -- three inputs applied in order, as one atomic step
```

Multiple inputs in one call are all-or-nothing: if any input partway through is invalid
for the state reached so far, the whole call fails and the instance is left completely
untouched (no partial progress is ever visible).

### `GET` -- fetch one row

```
GET order:o1001;
```

### `MATCH` -- query rows

```
MATCH order;                                     -- every instance
MATCH order WHERE STATE = paid;                  -- paid is one of the current states
MATCH order WHERE STATE IS TERMINAL;             -- any current state is terminal
MATCH order WHERE STATE IS INITIAL;              -- any current state is the initial state
MATCH order WHERE HISTORY LIKE *, pay, ship, *;  -- see the pattern language below
MATCH order WHERE customer = "alice";            -- attribute comparison
MATCH order WHERE total > 100 AND customer != "alice";
MATCH order WHERE (STATE = paid OR STATE = shipped) AND rush = true;
```

`AND` binds tighter than `OR` (standard precedence); use parentheses to override it.
Attribute comparison operators are `= != < <= > >=`. Numbers compare by value across
integer/decimal (`5 = 5.0` is true); ordering operators (`< <= > >=`) require both sides
to be numbers and raise an error otherwise. An attribute that doesn't exist on an
instance reads as absent (compares equal to `null`, not equal to anything else).

### `BEGIN ... COMMIT` -- a transaction

```
BEGIN
  order:o1001(pay);
  inventory:i55(reserve)
COMMIT;
```

Every statement inside runs against the **same** transaction, which commits exactly once
at the end: either every statement's effect becomes visible, or (if any statement fails,
for any reason, anywhere in the block) none of them do. `DEFINE FSM` and nested
`BEGIN` blocks are not allowed inside a transaction block -- see `docs/ROADMAP.md` for why
and what a future interactive-transaction mode might look like.

## The `HISTORY LIKE` pattern language

A pattern is a regular expression whose "characters" are whole input names, matched
**fully anchored** against an instance's input sequence from the first input to the
last -- like SQL's `LIKE`, unlike `grep`. A pattern that should match anywhere in the
history needs explicit leading/trailing wildcards, exactly like `LIKE '%x%'` needs
explicit `%` on both sides.

| Syntax | Meaning |
|---|---|
| `pay` | literally the input `pay` |
| `.` | exactly one arbitrary input, whatever it is |
| `a, b` | `a` immediately followed by `b` (comma is concatenation) |
| `x*` | zero or more `x`, back to back |
| `x+` | one or more `x` |
| `x?` | zero or one `x` (optional) |
| <code>a &#124; b</code> | `a` or `b` |
| `(...)` | grouping, for applying a quantifier to a sequence or scoping an alternation |
| `*`, `+`, `?` (bare, nothing to their left) | sugar for `.*`, `.+`, `.?` |

The bare-quantifier sugar is what makes the intuitive `*, pay, ship, *` work: it
desugars to `.*, pay, ship, .*`, i.e. "an arbitrary prefix, then `pay`, then `ship`,
then an arbitrary suffix" -- "`pay` immediately followed by `ship`, anywhere in the
history."

```
-- anywhere in the history, pay is immediately followed by ship
MATCH order WHERE HISTORY LIKE *, pay, ship, *;

-- exactly this history, start to finish, no more and no less
MATCH order WHERE HISTORY LIKE pay, ship, deliver;

-- pay, then any number of "retry" attempts, then ship
MATCH order WHERE HISTORY LIKE pay, retry*, ship;

-- pay, optionally followed by a single reminder, then either deliver or cancel
MATCH order WHERE HISTORY LIKE pay, reminder?, (deliver | cancel);

-- the history has at least two inputs and the first was "pay" (second input, whatever it is)
MATCH order WHERE HISTORY LIKE pay, ., *;
```

Matching is compiled to a small NFA and simulated directly (Thompson construction + Pike's
VM -- see `docs/ARCHITECTURE.md`), so it's `O(history length)` with no backtracking:
there's no pattern you can write here that makes a query hang.

## Worked example end to end

```
DEFINE FSM order (
  STATES (created, paid, shipped, delivered, cancelled),
  INITIAL created,
  TERMINAL (delivered, cancelled),
  ALPHABET (pay, ship, deliver, cancel),
  TRANSITIONS (
    created (pay): paid,
    paid (ship): shipped,
    shipped (deliver): delivered,
    created (cancel): cancelled,
    paid (cancel): cancelled
  )
);

CREATE order:o1001 WITH { customer: "alice", rush: true };
CREATE order:o1002 WITH { customer: "bob" };
CREATE order:o1003;

order:o1001(pay, ship, deliver);
order:o1002(pay);
order:o1003(cancel);

GET order:o1001;
MATCH order;
MATCH order WHERE STATE IS TERMINAL;
MATCH order WHERE HISTORY LIKE *, pay, ship, *;
MATCH order WHERE customer = "alice";

BEGIN
  order:o1002(ship);
  order:o1002(deliver)
COMMIT;

GET order:o1002;
```
