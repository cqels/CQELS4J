# CQELS-QL Language Specification

**Applies to:** CQELS `2.0.0-alpha.7` · **Based on:** SPARQL 1.1, extended with RSP-QL / C-SPARQL / LARS streaming constructs.

CQELS-QL (*Continuous Query Evaluation over Linked Streams — Query Language*) is **SPARQL 1.1 plus
streaming**. If you know SPARQL, you already know most of it: `SELECT`, `FILTER`, `BIND`, `OPTIONAL`,
`UNION`, aggregates and `GROUP BY` / `HAVING` behave as in SPARQL 1.1. (`ORDER BY` / `LIMIT` carry a
streaming caveat, `MINUS` parses but is not yet executed, and `UNION` / `OPTIONAL` / `FILTER NOT EXISTS`
apply to the static side only — see [§9](#9-standard-sparql-features-supported-as-is).)

This document focuses on **what CQELS-QL adds on top of SPARQL** — the constructs you won't find in a
plain triple store. The standard SPARQL surface is summarized briefly in
[§9](#9-standard-sparql-features-supported-as-is); everything before it is the streaming extension.

> Runnable, verified counterparts to most constructs below live in [`examples/`](examples/).

---

## What CQELS-QL adds over SPARQL — at a glance

| Extension | Construct | Section |
|-----------|-----------|---------|
| Continuous registration | `REGISTER QUERY <name> AS …` | [§1](#1-continuous-query-registration) |
| Stream sources | `FROM STREAM <name> [<window>]` | [§2](#2-stream-sources) |
| Backward windows | `[NOW]`, `[RANGE Ns]`, `[RANGE Ns STEP Ms]`, `[SLIDE Ns STEP Ms]`, `[TRIPLES N]` | [§3](#3-window-specifications) |
| Directional (LARS) windows | `[PAST Ns]`, `[FUTURE Ns]`, `[RANGE Ns FUTURE Ms]`, `… EMIT …`, `… LATENESS …` | [§3.2](#32-directional-lars-windows) |
| Stream graph patterns | `STREAM <name> { … }`, implicit binding | [§4](#4-stream-graph-patterns) |
| Named windows (RSP-QL) | `FROM NAMED WINDOW :w ON <s> […]` + `WINDOW :w { … }` | [§5](#5-named-windows-rsp-ql) |
| Stream–static composition | `FROM [STATIC] <iri> [WITH DEPTH n] [CACHE d]` (lookup join) | [§6](#6-streamstatic-composition) |
| Complex Event Processing | `FILTER(SEQ(?a ; ?b ; …))` with quantifiers & negation | [§7](#7-complex-event-processing-cep) |
| Stream output operators | RStream (default) / opt-in `Istream(...)` | [§8](#8-stream-output-operators-rsp-ql) |

CQELS-QL also has a property-graph dialect, **CypherQL** ([§10](#10-cypherql-property-graph-dialect)).

---

## Query structure

```
[PREFIX declarations]
REGISTER QUERY <name> AS                                -- optional; a bare SELECT gets an auto-generated name
SELECT [Istream(] <projection> [)]                      -- Istream(...) opts into delta emission (see §8)
FROM STREAM <name> [<window>]                       -- stream source (classic mode)
FROM NAMED WINDOW :w ON <stream> [<window>]          -- named window (RSP-QL mode; mutually exclusive; parses but is not yet executed — see §5)
FROM [STATIC] <graph-iri> [WITH DEPTH n] [CACHE d]   -- background knowledge (optional)
WHERE {
  STREAM <name> { <triple patterns> }                -- classic mode
  WINDOW :w   { <triple patterns> }                  -- named-window mode
  <static triple patterns>                           -- matched against the background graph
  [OPTIONAL { … }] [{ … } UNION { … }] [MINUS { … }]   -- static-side only; MINUS parses but is not yet executed (use FILTER NOT EXISTS)
  [FILTER(<expr>)] [FILTER NOT EXISTS { … }] [BIND(<expr> AS ?v)]
  [FILTER(SEQ(?a ; ?b ; …))]                         -- CEP sequence (see §7)
}
[GROUP BY <vars>] [HAVING(<expr>)] [ORDER BY <conds>] [LIMIT <n>]
```

**Result shape.** A registered query emits a continuous result stream. CQELS-QL / CypherQL queries deliver
each solution as a row of variable bindings (variable → value); CEP queries deliver a matched event
sequence (the stream elements that satisfied each step, in order). Results are delivered to a listener as
the engine evaluates the stream (see [`GETTING_STARTED.md`](GETTING_STARTED.md)).

---

## 1. Continuous query registration

Unlike a one-shot SPARQL query, a CQELS-QL query is **registered** and runs continuously until
unregistered:

```sparql
REGISTER QUERY HighTemperature AS
SELECT ?sensor ?temp
FROM STREAM Sensors [NOW]
WHERE { STREAM Sensors { ?sensor ex:temperature ?temp . } FILTER(?temp > 30) }
```

The name identifies the query for lifecycle management (it can be unregistered later). The
`REGISTER QUERY <name> AS` prefix is optional — a bare `SELECT … FROM STREAM … WHERE …` also parses and is
assigned an auto-generated name.

---

## 2. Stream sources

`FROM STREAM` declares a named RDF stream and the window applied to it:

```sparql
FROM STREAM SensorData [NOW]
FROM STREAM Events     [RANGE 10s]
FROM STREAM Events     [RANGE 1h STEP 5m]
FROM STREAM Trades     [SLIDE 30s STEP 10s]
FROM STREAM DataStream [TRIPLES 100]
```

A stream carries timestamped RDF statements. The window decides which statements are *visible* to the
WHERE clause at each evaluation.

---

## 3. Window specifications

A window appears in square brackets after a stream name. **Time units:** `s` (seconds), `m` (minutes),
`h` (hours), `d` (days).

### 3.1 Backward windows

| Window | Syntax | Meaning |
|--------|--------|---------|
| Instantaneous | `[NOW]` | Only the current element; no buffering. Evaluate per arriving statement. |
| Tumbling time | `[RANGE 10s]` | Backward extent of N seconds. Windowed-aggregation queries fire fixed, non-overlapping N-second event-time windows; pattern-match and join queries evaluate on each arriving statement against the trailing N seconds (overlapping evaluations). |
| Sliding time | `[RANGE 10s STEP 5s]` | N-second extent notionally advancing every M seconds. |
| Sliding time (alt) | `[SLIDE 30s STEP 10s]` | Equivalent sliding-window spelling (`[RANGE Ns STEP Ms]` ≡ `[SLIDE Ns STEP Ms]`). |
| Count-based | `[TRIPLES 100]` | The most recent N stream **elements**. |

*Notes.*
- **`STEP` on backward windows** is parsed and validated but does not yet drive an evaluation cadence:
  evaluation runs on every arriving statement over the trailing N-second extent rather than only at step
  boundaries — a correct *superset* of the step-boundary result set. (`STEP` **is** honored as the
  anchor-advance cadence on directional windows, §3.2.)
- **`[TRIPLES N]` counts stream elements.** With the default one-statement-per-push API this equals N
  statements; when a producer pushes a multi-statement observation as a single element (atomic graph push,
  since `2.0.0-alpha.5`), the window keeps the last N observations without splitting one across the
  eviction boundary.
- **`LATENESS d`** (§3.2) is also accepted on a backward `[RANGE Ns]` window for **windowed-aggregation**
  queries (a single `COUNT`/`SUM`/`AVG`/`MIN`/`MAX`, optional `GROUP BY`, in-batch `FILTER`/`BIND`):
  out-of-order statements within the budget still count toward their window. On any other backward-window
  shape — including `SLIDE`, `TRIPLES`, `NOW`, or a plain (non-aggregate) `SELECT` over `[RANGE …]` — a
  `LATENESS` clause is rejected at registration with a clear error rather than silently dropping late data.

### 3.2 Directional (LARS) windows

CQELS-QL implements **LARS-style directional windows** that look *forward* from an evaluation anchor, not
just backward — useful for "did Y happen within N seconds *after* X" reasoning. These are a distinctive
CQELS extension.

| Syntax | Meaning |
|--------|---------|
| `[PAST Ns]` | Backward extent of N seconds (explicit form of `[RANGE Ns]`). |
| `[FUTURE Ns]` | Forward extent of N seconds after the anchor. |
| `[RANGE Ns FUTURE Ms]` | Combined backward N + forward M extent around the anchor. |
| `[PAST Ns FUTURE Ms]` | Same, with explicit backward keyword. |
| `[TRIPLES PAST n FUTURE m]` | Count-based directional extents *(parses, but not yet executable — see the status note below)*. |

Directional windows accept an optional **emission policy** and **lateness budget**:

```sparql
FROM STREAM VehicleSignals [FUTURE 20s STEP 1s EMIT ON_CLOSE LATENESS 2s]
```

| Clause | Values | Meaning |
|--------|--------|---------|
| `STEP Ms` | duration | Advance the anchor every M (overlapping evaluations). |
| `EMIT <policy>` | `ON_UPDATE` \| `ON_CLOSE` \| `EARLY_AND_FINAL` | When results are emitted: on each update, only when the window closes, or both an early estimate and a final result. |
| `LATENESS d` | duration | Watermark out-of-orderness budget — hold window closure back so events up to `d` late still land in their window. |

**Scope of `EMIT` / `LATENESS`.** Where they parse, these clauses execute only where noted. Syntactically,
`EMIT` attaches to `RANGE`/`PAST`/`FUTURE`/`SLIDE`/`TRIPLES` windows and `LATENESS` to
`RANGE`/`PAST`/`FUTURE`/`SLIDE` windows; `[NOW]` accepts neither and `[TRIPLES …]` accepts no `LATENESS`.
At execution, `EMIT` is honored on directional windows only — an explicit `EMIT` on a backward window is
rejected at registration. `LATENESS` is honored on directional windows and, for a backward `[RANGE Ns]`
window, on the windowed-aggregation path (§3.1); every other placement is rejected with an error naming the
supported shape.

**Semantics.** A forward-looking window can only be *complete* once enough time has passed, so directional
windows produce **exact, delayed** results at window close. `LATENESS` trades latency for tolerance of
out-of-order arrivals.

> **Status: count-directional windows.** In the current `2.0.0-alpha` line, count-based directional
> extents (`[TRIPLES PAST n FUTURE m]`, `[TRIPLES FUTURE n]`) *parse* but are *not yet executable* —
> registering such a query raises a clear "not yet executable" error at registration time (no
> deterministic count-ordering runtime exists yet). Use the time-based directional windows above
> (`[FUTURE Ns]`, `[PAST Ns FUTURE Ms]`, `[RANGE Ns FUTURE Ms]`) for executable queries today.

> **Status: executable query shapes.** A directional-window query executes only in these shapes; anything
> else is rejected at registration with a clear error (never silently evaluated backward-only):
> 1. **Windowed aggregation** — a single aggregate (`COUNT`/`SUM`/`AVG`/`MIN`/`MAX`) over one stream triple
>    pattern, optional `GROUP BY`, with in-window `FILTER`/`BIND`; all three `EMIT` policies apply.
> 2. **Plain SELECT projection** — an explicit `SELECT` list over one stream triple pattern; `FILTER`/`BIND`
>    are admitted (since `2.0.0-alpha.6`); `EMIT` must be absent or `ON_CLOSE`, and `SELECT DISTINCT` is
>    rejected. Results surface once, at window close.
> 3. **Complex-event `SEQ` matching** (§7) via the CEP registration path.
>
> A directional `SELECT` with two or more stream triple patterns over one stream is available **opt-in**
> via the system property `-Dcqels.directional.multiPatternSelect=true` (since `2.0.0-alpha.7`;
> per-closed-window join replay, one result set per anchor, `EMIT` absent or `ON_CLOSE`, no
> `DISTINCT`/`Istream(...)`). Every other directional shape — stream–static composition (§6),
> `UNION`/`OPTIONAL`/`FILTER NOT EXISTS`, `ORDER BY`/`LIMIT`, `SELECT DISTINCT`, multi-stream joins, and
> `Istream(...)` — is not yet supported with directional windows and is rejected at registration.

---

## 4. Stream graph patterns

Inside `WHERE`, a `STREAM <name> { … }` block matches triple patterns against statements in that stream's
window. Patterns sharing a subject use SPARQL's `;` notation:

```sparql
WHERE {
  STREAM MessageStream {
    ?message ex:hasCreator ?person ;
             ex:hasTag     ?tag .
  }
}
```

**Implicit stream binding.** When there is exactly one `FROM STREAM`, no static/named graphs, and no
explicit `STREAM`/`WINDOW` blocks, bare triple patterns bind to that stream automatically:

```sparql
FROM STREAM VehicleSignals [RANGE 5s]
WHERE {
  ?r vss:Speed   ?speed .
  ?r vss:Vehicle ?vehicle .
}
```

Use explicit `STREAM { … }` blocks when static graphs are present, for self-joins, or for multi-stream
queries.

---

## 5. Named windows (RSP-QL)

Named windows decouple the **window name** from the **stream name**, so you can open several windows of
different sizes over the *same* stream — something `FROM STREAM` alone cannot express:

```sparql
FROM NAMED WINDOW :short ON SensorData [RANGE 10s]
FROM NAMED WINDOW :long  ON SensorData [RANGE 60s]
WHERE {
  WINDOW :short { ?r ex:value ?recent . }
  WINDOW :long  { ?r ex:value ?baseline . }
}
```

`FROM STREAM` mode and `FROM NAMED WINDOW` mode **cannot be mixed** in one query.

> **Status:** in the current `2.0.0-alpha` line the named-window *syntax* parses, but named-window
> *execution* is not yet implemented — registering such a query raises a clear "not yet implemented"
> error. Use `FROM STREAM` windows (§2–§3) for executable queries today.

---

## 6. Stream–static composition

The defining idea of *Linked* Streams: join a stream against background knowledge. Triple patterns
**outside** any `STREAM`/`WINDOW` block are resolved against a static graph, producing a **lookup join** —
each stream element triggers an enrichment lookup:

```sparql
FROM STREAM Requests [NOW]
FROM <http://example.org/graph> WITH DEPTH 2 CACHE 5m
WHERE {
  STREAM Requests { ?req ex:personIri ?person . }
  ?person ex:firstName ?firstName ;       -- resolved against the static graph
          ex:city      ?city .
}
```

**Matching semantics.** On single-stream queries the static block is an *enrichment*, not a strict join:
if the joined subject has facts in the static store the row is emitted, and each static pattern binds its
variables where it matches — a static pattern that does not match leaves its variables **unbound** rather
than filtering the row. A stream element is dropped when its join subject has no static facts at all (and,
more generally, when the enrichment produces no usable bindings). To make a static pattern mandatory, add a
`FILTER` over one of its variables (a `FILTER` on an unbound variable drops the row). Queries joining **two
streams** with a static graph instead enforce exact SPARQL basic-graph-pattern (inner-join) semantics.

| Clause | Meaning |
|--------|---------|
| `FROM [STATIC] <iri>` | Declares the background graph (the `STATIC` keyword is optional). The IRI is **not dereferenced from the network** — background facts come from the engine's configured static store. On two-stream queries the lookup is scoped to the declared graph; on single-stream queries the whole static store is currently consulted regardless of the IRI. |
| `WITH DEPTH n` | Declares intended lookup depth. **Parsed but not currently enforced by CQELS-QL text queries** — the engine uses a fixed shallow lookup depth regardless of *n* (`WITH DEPTH 1` and `WITH DEPTH 5` behave identically). In practice only shallow reference chains resolve; a pattern chained through more than one intermediate variable typically leaves its variable silently unbound. Test deeper lookups before relying on this clause. |
| `CACHE d` | Cache lookup results for duration *d*. |
| `FROM NAMED <iri>` | Named background graph. |

Because the lookup re-reads the static store per element, updates to background facts are reflected on the
next matching element.

*Notes.*
- A query joining **two streams** with a static graph must declare exactly **one** background graph IRI
  (one `FROM` or one `FROM NAMED`); declaring several is rejected with a clear error.
- Stream–static composition currently requires the legacy window forms (`[NOW]`, `RANGE`, `TRIPLES`,
  `SLIDE`). Directional (`FUTURE`/centered) windows cannot yet be combined with a static graph block —
  such a query is rejected with a clear error.

---

## 7. Complex Event Processing (CEP)

CQELS-QL expresses temporal **event sequences** declaratively, inside the WHERE block, with
`FILTER(SEQ(...))`. The engine compiles the sequence into an automaton over the stream window.

```sparql
PREFIX vss: <https://covesa.global/vss#>
REGISTER QUERY LossOfControl AS
SELECT ?v
FROM STREAM VehicleSignals [RANGE 20s]
WHERE {
  ?e1 vss:hasAlert vss:HighSpeedAlert .
  ?e2 vss:hasAlert vss:AggressiveDrivingAlert .
  ?e3 vss:hasAlert vss:HardBrakingAlert .
  FILTER(SEQ(?e1 ; ?e2 ; ?e3))
  BIND(?e1 AS ?v)
}
```

Reads as: *within a 20-second window, a high-speed alert, then an aggressive-driving alert, then a
hard-braking alert — in that order.*

> **Status: registration.** Sequence (automaton) semantics apply only when the query is registered through
> the engine's dedicated **CEP registration entry point** (which delivers matched-event results). Registering
> a `FILTER(SEQ(...))` query through the ordinary continuous-query registration is **not rejected**, but the
> sequence constraint is then silently unapplied — the query runs as a plain windowed join and emits
> per-evaluation binding rows that do not correspond to matched sequences. Always register `SEQ` queries as
> CEP queries (see [`GETTING_STARTED.md`](GETTING_STARTED.md)).

**`SEQ` syntax.** Arguments are separated by **`;`** (not `,`) and there must be **two or more**. Each
argument has the shape:

```
NOT? <eventVar> <quantifier>?
```

- **`<eventVar>`** (e.g. `?e1`) should appear as the **subject** of at least one triple pattern in the WHERE
  block — that pattern defines what the event matches. *Caution:* this is **not currently enforced** — a
  `SEQ` step whose variable has no subject triple pattern (for example a typo) is accepted and matches **any**
  stream element, silently over-matching. Double-check that each `SEQ` argument names an event variable that
  appears as a triple-pattern subject.
- **`NOT`** turns the step into a negated gap — "this must *not* occur between the previous and next
  events" (a gap constraint, not adjacency).

**Quantifiers:**

| Quantifier | Meaning |
|------------|---------|
| `?e+` | one or more |
| `?e*` | zero or more |
| `?e?` | zero or one |
| `?e{3}` | exactly three |
| `?e{2,5}` | between 2 and 5 (inclusive) |

Repetition quantifiers are **non-greedy**: a burst of repeated events can produce **one match per
qualifying repetition length** (e.g. `A A B` matches `SEQ(?a+ ; ?b)` more than once — for `[A1 A2 B]`,
`[A1 B]` and `[A2 B]`), rather than a single longest match.

**Event matching.** If an event variable is the subject of a *single* triple pattern, it fires when one
statement matches. If it is the subject of *several* patterns, it fires only when a single stream element
satisfies all of them consistently (reified-event shape).

**Cross-event constraints.** Ordinary `FILTER`s referencing one event's variables become per-event
predicates; `FILTER`s referencing variables from *several* events (e.g. `FILTER(STR(?v1) = STR(?v2))` to
require the same vehicle) become guards evaluated once all involved events have matched. A `FILTER`
referencing no event's variables (a typo, or a variable bound only outside the stream pattern) is **not**
enforced on the sequence — the engine logs a warning and ignores it.

**Window scope.** The stream window bounds the sequence. `[RANGE Ns]` gives the match an N-second timeout
measured in event time: a partial sequence whose first event is more than N seconds older than a newly
arriving event is silently discarded and never emits. With `[NOW]` or `[TRIPLES n]` there is no time
bound — only stream termination closes a partial match. **Sequence order is arrival order by default:** on
`[RANGE]`, `[NOW]` and `[TRIPLES]` windows the automaton consumes events as they arrive, so input that
arrives out of event-time order matches in arrival order. An **opt-in event-time matching mode** for
non-directional CEP is available at the registration API — it reorders by timestamp within an explicit
lateness budget (records later than the budget are dropped). Observing timed-out partial sequences (e.g.
for "approve issued, confirm never arrived in time" guardrails) is also available through the programmatic
pattern API; neither is reachable from query text.

Running CEP over a **directional** window (`[FUTURE …]`, `[PAST … FUTURE …]`) switches to exact,
window-close evaluation: each closed window is evaluated once over its final contents sorted by event time
(arrival order breaks ties), and **every** sequence occurrence found is emitted at window close, stamped
with that window's anchor. Overlapping windows emit independently per anchor — there is no cross-anchor
deduplication. An explicit `EMIT` clause is not supported on **any** CEP query (a stream window carrying
`EMIT` is rejected at registration regardless of window type); a `LATENESS` clause is likewise rejected on
non-directional CEP windows but honored on directional windows as the stream's out-of-orderness budget.

**Projecting from a CEP query.** CEP matches are delivered as the matched event sequence itself — the
stream elements that satisfied each step, in order, with the match's start/end timestamps. **The `SELECT`
list and `BIND` clauses are not applied to CEP match output.** Writing `BIND(?e1 AS ?v)` with `SELECT ?v`
is still the recommended style — it keeps the query valid SPARQL (every projected variable is bound) and
meaningful if the same text is reused as an ordinary continuous query — but it does not change what a CEP
match carries:

```sparql
SELECT ?v WHERE { ?e1 … FILTER(SEQ(?e1 ; ?e2)) BIND(?e1 AS ?v) }
```

---

## 8. Stream output operators (RSP-QL)

How window contents become an output stream follows the RSP-QL operators. The default (a bare `SELECT`
projection) is **RStream**-style cumulative emission. **IStream** delta emission is opt-in — wrap the
projection in `Istream(...)`:

```sparql
SELECT Istream(?s ?name ?age)
FROM STREAM <s> [TRIPLES 20]
WHERE { STREAM <s> { ?s ex:name ?name . ?s ex:age ?age . } }
```

| Operator | Emits |
|----------|-------|
| **RStream** *(default)* | For windowed joins/aggregates, the current result on each evaluation (each arrival re-evaluates and re-emits); single-pattern projections emit one row per arriving element. |
| **IStream** *(opt-in `Istream(...)`)* | Only the per-evaluation delta — the multiset difference `eval(t) \ eval(t-1)`, i.e. each new row exactly once. |
| **DStream** | Removed rows — **not selectable in this release** (no syntax). |

`Istream(...)` emits the per-evaluation delta instead of the cumulative result. It currently executes on:

- stream-only windowed **multi-pattern joins** over `[RANGE]`/`[SLIDE]`/`[TRIPLES]` windows;
- **composed stream+static joins** — in both cases including in-batch `FILTER`/`BIND` and
  `UNION`/`OPTIONAL`/`FILTER NOT EXISTS`; and
- **windowed aggregates** over a backward `[RANGE Ns]` window (a single `COUNT`/`SUM`/`AVG`/`MIN`/`MAX`,
  optional `GROUP BY`) — a group's row is emitted only when its aggregate value changed.

Every other shape fails loudly at registration rather than silently emitting cumulative rows:
single-pattern / `[NOW]` queries (already per-element), directional (`FUTURE`/centered) windows,
multi-stream joins, CEP `FILTER(SEQ(...))`, `SELECT DISTINCT`, `ORDER BY`/`LIMIT`, `HAVING`, and `MINUS`.

> The wrapper keyword must be spelled exactly **`Istream`**, **`ISTREAM`**, or **`istream`**. Other casings
> (e.g. `IStream`) are *not* recognized as the wrapper and mis-parse — spell it as shown.

---

## 9. Standard SPARQL features (supported as-is)

These behave per **[SPARQL 1.1](https://www.w3.org/TR/sparql11-query/)**; only the streaming-relevant notes
are called out.

- **`SELECT` / `SELECT DISTINCT`**, projection, and `(expr AS ?v)` expressions. *Note:* `SELECT DISTINCT`
  is rejected with an error when combined with a windowed-aggregate fast path (a single
  `COUNT`/`SUM`/`AVG`/`MIN`/`MAX` over a one-triple-pattern `RANGE` or directional window) or with a bare
  directional-window `SELECT`; it is supported on the standard stream, join, and composed paths.
- **Aggregates:** `COUNT(*)` / `COUNT(?v)`, `SUM`, `AVG`, `MIN`, `MAX`, `GROUP_CONCAT(?v; SEPARATOR=", ")`.
  *Note:* with `GROUP BY`, aggregates are computed per group. Without `GROUP BY`, a **single**
  `COUNT`/`SUM`/`AVG`/`MIN`/`MAX` over a one-triple-pattern `RANGE` or directional window is computed over
  the whole window (one row per window). Other shapes without `GROUP BY` (multi-pattern joins,
  `GROUP_CONCAT`, `TRIPLES`/`SLIDE`/`NOW` windows) emit the raw bindings rather than an aggregate — add an
  explicit `GROUP BY` to force aggregation there.
- **`FILTER(expr)`** — operators `= != < > <= >= && || !` `+ - * /`, plus built-ins (`BOUND`, `IF`,
  `CONCAT`, `STR`, `year(...)`, …); full SPARQL effective-boolean-value semantics. Expressions accept inline
  typed and language-tagged RDF literals — `FILTER(?temp > "90"^^xsd:integer)` compares **numerically**;
  `"cat"@en` matches language-tagged values. The datatype/language prefix resolves against the query's own
  `PREFIX` declarations.
- **`BIND(expr AS ?v)`** — computed variables. `BIND` is evaluated **before** `FILTER` on the single-stream,
  windowed-join and aggregate paths, so a `FILTER` may reference a `BIND`-introduced variable (e.g.
  `BIND(?a + 1 AS ?d) FILTER(?d > 20)`). *Exception:* on the stream+static lookup path the ordering is
  path-dependent — `FILTER` may run before `BIND` so static-side variables are bound first; verify the
  ordering for your query shape if a `FILTER` there needs to see a `BIND`-introduced variable.
- **`OPTIONAL { … }`** — left outer join. *Streaming caveat:* the body is evaluated against the **static
  background graph** only; an `OPTIONAL` whose body contains a `STREAM` block never matches (its variables
  stay unbound). Keep stream patterns in the main WHERE scope.
- **`{ … } UNION { … }`** — alternative patterns merged before `FILTER`. *Streaming caveat:* arms are
  evaluated against the **static** side only; an arm containing a `STREAM { … }` block is **rejected** with a
  clear error. Use `UNION` for static alternatives and keep stream patterns in the main pattern.
- **`FILTER NOT EXISTS { … }`** — anti-join against **static** data; if the engine has no static store
  configured (or the body references only stream patterns) it filters nothing (rows pass through unchanged).
  (`MINUS` is parsed but not yet executed in the current alpha line — use `FILTER NOT EXISTS`.)
- **`GROUP BY ?a, ?b`** · **`HAVING(expr)`** — grouping + post-aggregation filter; `HAVING` references the
  **SELECT aliases** (e.g. `HAVING(?cnt > 1)`, not `HAVING(COUNT(*) > 1)`).
- **`ORDER BY ?v [ASC|DESC]`** (default `ASC`; the SPARQL prefix form `DESC(?v)` is also accepted) ·
  **`LIMIT n`** — *streaming caveat:* `ORDER BY` (alone, or with `LIMIT` as top-K) buffers the entire result
  and emits only when the input stream terminates, so on a continuous (unbounded) stream a query with
  `ORDER BY` produces **no output**. `LIMIT` without `ORDER BY` truncates the live stream after the first
  *n* rows. For continuous queries, omit `ORDER BY` (per-group aggregate rows are emitted as they update)
  and rank/truncate downstream.

**Evaluation order (typical):** stream patterns → static lookup join → `UNION` → `OPTIONAL` →
`FILTER NOT EXISTS` → `BIND` → `FILTER` → `GROUP BY` + aggregates → `HAVING` → `ORDER BY` → `LIMIT`. The
exact interleaving of `BIND`/`FILTER` with the static-side clauses varies by execution path (see the
`BIND` note above).

---

## 10. CypherQL (property-graph dialect)

The same engine accepts continuous **Cypher** queries for property-graph pattern matching over the same
streams (registered separately from CQELS-QL). Clause order is fixed — `FROM` clauses come first, then
`MATCH`, then optional `WHERE` / `GROUP BY` / `HAVING`, then `RETURN`, then optional `ORDER BY` / `LIMIT`
(an optional `REGISTER QUERY <name> AS` prefix names the query):

```cypher
FROM STREAM ProfileRequests [NOW]
MATCH (req:Request)-[:FOR]->(p:Person)-[:LOCATED_IN]->(c:City)
RETURN p.firstName, p.lastName, c.name
```

CypherQL reuses the streaming model (windows, continuous results) with graph-traversal patterns instead of
triple patterns. Its window surface is the **legacy subset only** — `[NOW]`, `[RANGE d [STEP d]]`,
`[TRIPLES n]`, `[SLIDE d STEP d]` (durations also accept `ms`); the directional (`PAST`/`FUTURE`), `EMIT`,
and `LATENESS` extensions of §3.2 apply to CQELS-QL only.

CypherQL also supports an optional `WHERE` clause with full boolean expressions (`AND` / `OR` / `NOT`,
comparisons `=`, `<>`, `<`, `>`, `<=`, `>=`, plus `CONTAINS`, `STARTS WITH`, `ENDS WITH`, `IN`) over
node/relationship properties, aggregates in `RETURN` (`COUNT`, `SUM`, `AVG`, `MIN`, `MAX`, `COLLECT`) with
`AS` aliases, and `DISTINCT`, `GROUP BY`, `HAVING`, `ORDER BY`, `LIMIT` clauses.

---

## 11. Grammar (EBNF summary)

```ebnf
query           ::= prefixDecl* (registerQuery | selectQuery)
registerQuery   ::= 'REGISTER' 'QUERY' Name 'AS' selectQuery
selectQuery     ::= 'SELECT' projection fromClause+ 'WHERE' whereClause
                    groupByClause? havingClause? orderByClause? limitClause?

projection      ::= 'Istream' '(' selectList ')' | selectList   -- 'Istream' | 'ISTREAM' | 'istream'; see §8
selectList      ::= 'DISTINCT'? selectElement (','? selectElement)*
selectElement   ::= Var | '(' expression 'AS' Var ')'

fromClause      ::= fromStream | fromStatic | fromNamedWindow | fromNamed
fromStream      ::= 'FROM' 'STREAM' Name '[' windowSpec ']'
fromStatic      ::= 'FROM' 'STATIC'? IRIREF ('WITH' 'DEPTH' INT)? ('CACHE' duration)?
fromNamedWindow ::= 'FROM' 'NAMED' 'WINDOW' PrefixedName 'ON' Name '[' windowSpec ']'
fromNamed       ::= 'FROM' 'NAMED' IRIREF ('WITH' 'DEPTH' INT)? ('CACHE' duration)?

windowSpec      ::= 'NOW'
                  | 'RANGE'  duration ('FUTURE' duration)? ('STEP' duration)? emit? lateness?
                  | 'PAST'   duration ('FUTURE' duration)? ('STEP' duration)? emit? lateness?
                  | 'FUTURE' duration ('STEP' duration)? emit? lateness?
                  | 'SLIDE'  duration 'STEP' duration emit? lateness?
                  | 'TRIPLES' INT emit?
                  | 'TRIPLES' 'PAST' INT ('FUTURE' INT)? emit?   -- parses; not yet executable, see §3.2
                  | 'TRIPLES' 'FUTURE' INT emit?                 -- parses; not yet executable, see §3.2
emit            ::= 'EMIT' ('ON_UPDATE' | 'ON_CLOSE' | 'EARLY_AND_FINAL')
lateness        ::= 'LATENESS' duration
duration        ::= INT ('s' | 'm' | 'h' | 'd')
-- Execution scope: where the grammar admits them, `emit` executes on directional (FUTURE/centered)
-- windows only; `lateness` on directional windows and, for backward `RANGE`, only on the
-- windowed-aggregate path. Other placements parse but are rejected at registration (see §3.1–§3.2).

whereClause     ::= '{' patternGroup+ '}'
patternGroup    ::= streamPattern | windowPattern | graphPattern | triplePattern+
                  | filterConstraint | bindPattern | optionalPattern | unionPattern | minusPattern
streamPattern   ::= 'STREAM' Name '{' triplePattern+ '}'
windowPattern   ::= 'WINDOW' PrefixedName '{' triplePattern+ '}'
graphPattern    ::= 'GRAPH' IRIREF '{' triplePattern+ '}'   -- resolved as static lookup patterns
filterConstraint::= 'FILTER' '(' expression ')' | 'FILTER' 'NOT' 'EXISTS' '{' patternGroup+ '}'
bindPattern     ::= 'BIND' '(' expression 'AS' Var ')'

seqExpression   ::= 'SEQ' '(' seqArg (';' seqArg)+ ')'        -- inside FILTER(...)
seqArg          ::= 'NOT'? Var seqQuantifier? ('AS' alias)?   -- AS is reserved (parsed, not yet used)
seqQuantifier   ::= '+' | '*' | '?' | '{' INT '}' | '{' INT ',' INT '}'

aggregate       ::= 'COUNT' '(' ('*' | Var) ')' | ('SUM'|'AVG'|'MIN'|'MAX') '(' Var ')'
                  | 'GROUP_CONCAT' '(' Var (';' 'SEPARATOR' '=' STRING)? ')'

literal         ::= STRING (LANGTAG | '^^' (IRIREF | PrefixedName))?
                  | INT | DECIMAL | DOUBLE | 'true' | 'false'
```

---

## 12. Worked examples

**Windowed aggregation (per-group stats):**
```sparql
PREFIX ex: <http://example.org/>
REGISTER QUERY PerSensorStats AS
SELECT ?sensor (AVG(?t) AS ?avg) (MAX(?t) AS ?peak) (COUNT(*) AS ?n)
FROM STREAM Readings [RANGE 3s]
WHERE { STREAM Readings { ?sensor ex:temperature ?t . } }
GROUP BY ?sensor
```

**Stream–static lookup join:**
```sparql
PREFIX ex: <http://example.org/>
REGISTER QUERY EnrichRequests AS
SELECT ?person ?firstName ?city
FROM STREAM Requests [NOW]
FROM <http://example.org/graph> WITH DEPTH 1 CACHE 5m
WHERE {
  STREAM Requests { ?req ex:personIri ?person . }
  ?person ex:firstName ?firstName ; ex:city ?city .
}
```

**Named windows (short vs long baseline on one stream)** — *syntax only; not yet executable, see [§5](#5-named-windows-rsp-ql):*
```sparql
PREFIX vss: <https://covesa.global/vss#>
REGISTER QUERY SpeedVsBaseline AS
SELECT ?vehicle ?recent ?baseline
FROM NAMED WINDOW :short ON VehicleSignals [RANGE 5s]
FROM NAMED WINDOW :long  ON VehicleSignals [RANGE 60s]
WHERE {
  WINDOW :short { ?vehicle vss:Speed ?recent . }
  WINDOW :long  { ?vehicle vss:Speed ?baseline . }
}
```

**CEP sequence with a negated gap:**
```sparql
PREFIX ex: <http://example.org/>
REGISTER QUERY OverheatThenStall AS
SELECT ?e1
FROM STREAM MachineSignals [RANGE 30s]
WHERE {
  ?e1 ex:hasAlert ex:OverheatAlert .
  ?ok ex:hasAlert ex:CoolantRestoredAlert .
  ?e2 ex:hasAlert ex:StallAlert .
  FILTER(SEQ(?e1 ; NOT ?ok ; ?e2))
}
```
Reads as: an overheat alert followed by a stall alert, with **no** coolant-restored event in between (see
[§7](#7-complex-event-processing-cep) for `NOT` semantics). Register `SEQ` queries through the CEP entry
point (§7).

More are in [`examples/`](examples/) — those are all runnable and verified against this release
(the named-window snippet above is the exception: it parses but is not yet executable, see [§5](#5-named-windows-rsp-ql)).

---

## 13. Standards and references

CQELS-QL builds on and aligns with:

- **[SPARQL 1.1](https://www.w3.org/TR/sparql11-query/)** — graph query semantics.
- **[RSP-QL / W3C RSP Community Group](https://www.w3.org/community/rsp/)** — RDF stream processing model
  (windows, R/I/D stream operators, named windows).
- **[RDF-star / SPARQL-star](https://www.w3.org/2021/12/rdf-star.html)** — quoted triples (alignment target).
- **[openCypher](https://opencypher.org/)** — the CypherQL dialect.
- **[W3C SOSA/SSN](https://www.w3.org/TR/vocab-ssn/)** and **[COVESA VSS](https://covesa.global/)** —
  common stream vocabularies used in the examples.

Foundational research:

- Le-Phuoc et al., *A Native and Adaptive Approach for Unified Processing of Linked Streams and Linked
  Data* (CQELS, ISWC 2011).
- Barbieri et al., *C-SPARQL: a Continuous Query Language for RDF Data Streams* (2010).
- Dell'Aglio et al., *RSP-QL Semantics* (2014).
- Beck et al., *LARS: A Logic-based Framework for Analytic Reasoning over Streams* (directional windows).

---

*This specification tracks the `2.0.0-alpha` series and will evolve with the engine. Feedback and
contributions welcome via [issues](https://github.com/cqels/CQELS4J/issues).*
