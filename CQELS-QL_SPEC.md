# CQELS-QL Language Specification

**Applies to:** CQELS `2.0.0-alpha.22` · **Based on:** SPARQL 1.1, extended with RSP-QL / C-SPARQL / LARS streaming constructs.

CQELS-QL (*Continuous Query Evaluation over Linked Streams — Query Language*) is **SPARQL 1.1 plus
streaming**. If you know SPARQL, you already know most of it: `SELECT`, `FILTER`, `BIND`, `OPTIONAL`,
`UNION`, aggregates and `GROUP BY` / `HAVING` behave as in SPARQL 1.1. (`ORDER BY` / `LIMIT` are
supported with a streaming caveat, `MINUS` is not yet executed — registration rejects it with a
hint to use `FILTER NOT EXISTS` — property paths run on one route only, and a few SPARQL 1.1
built-ins are not yet evaluated; see [§9](#9-standard-sparql-features-supported-as-is) for the full
list.)

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
| Stream output operators | RStream / IStream / DStream | [§8](#8-stream-output-operators-rsp-ql) |
| Extension functions (§17.6) | `PREFIX cqfn: <urn:cqels:fn:> … FILTER(cqfn:haversine(…) <= 5.0)` | [§9](#9-standard-sparql-features-supported-as-is) |

CQELS-QL also has a property-graph dialect, **CypherQL** ([§10](#10-cypherql-property-graph-dialect)).

---

## Query structure

```
[PREFIX declarations]
REGISTER QUERY <name> AS
SELECT <projection>
FROM STREAM <name> [<window>]                       -- stream source (classic mode)
FROM NAMED WINDOW :w ON <stream> [<window>]          -- named window (RSP-QL mode; mutually exclusive)
FROM [STATIC] <graph-iri> [WITH DEPTH n] [CACHE d]   -- background knowledge (optional)
WHERE {
  STREAM <name> { <triple patterns> }                -- classic mode
  WINDOW :w   { <triple patterns> }                  -- named-window mode
  <static triple patterns>                           -- matched against the background graph
  [OPTIONAL { … }] [{ … } UNION { … }] [MINUS { … }]   -- MINUS parses but is rejected at registration (use FILTER NOT EXISTS)
  [FILTER(<expr>)] [FILTER NOT EXISTS { … }] [BIND(<expr> AS ?v)]
  [FILTER(SEQ(?a ; ?b ; …))]                         -- CEP sequence (see §7)
}
[GROUP BY <vars>] [HAVING(<expr>)] [ORDER BY <conds>] [LIMIT <n>]
```

**Result shape.** A registered query emits a continuous result stream. CQELS-QL / CypherQL queries deliver
each solution as a row of variable bindings (variable → value); CEP queries deliver a matched event
sequence. Results are delivered to a listener as the engine evaluates the stream (see
[`GETTING_STARTED.md`](GETTING_STARTED.md)).

---

## 1. Continuous query registration

Unlike a one-shot SPARQL query, a CQELS-QL query is **registered** and runs continuously until
unregistered:

```sparql
PREFIX ex: <http://example.org/>
REGISTER QUERY HighTemperature AS
SELECT ?sensor ?temp
FROM STREAM Sensors [NOW]
WHERE { STREAM Sensors { ?sensor ex:temperature ?temp . } FILTER(?temp > 30) }
```

The name identifies the query for lifecycle management (it can be unregistered later).

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

A window appears in square brackets after a stream name. **Time units:** `ms` (milliseconds, since
`2.0.0-alpha.22`), `s` (seconds), `m` (minutes), `h` (hours), `d` (days).

### 3.1 Backward windows

| Window | Syntax | Meaning |
|--------|--------|---------|
| Instantaneous | `[NOW]` | Only the current element; no buffering. Evaluated once per arriving element (one `push`, which may carry several statements). Since `2.0.0-alpha.21` it emits every solution the element yields; earlier alphas emitted only the first. |
| Time window | `[RANGE 10s]` | The last N seconds of elements, by event time. An aggregate over it reports either epoch-aligned **tumbling** buckets or a **rolling** window on every arrival, depending on the query's shape — see the measured cases under §9 *Aggregates*. |
| Sliding time | `[RANGE 10s STEP 5s]` | N-second window advancing every M seconds (overlapping). |
| Sliding time (alt) | `[SLIDE 30s STEP 10s]` | Equivalent sliding-window spelling. |
| Count-based | `[TRIPLES 100]` | The most recent N elements (pushes), regardless of time — despite the name, an element of several statements counts once. |

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
| `[TRIPLES PAST n FUTURE m]` | Count-based directional extents. **Parses but is not yet executable** — registration fails loud; use the time-based directional forms. |

Directional windows accept an optional **emission policy** and **lateness budget** (the lateness
budget is not directional-only — see below):

```sparql
FROM STREAM VehicleSignals [FUTURE 20s STEP 1s EMIT ON_CLOSE LATENESS 2s]
```

| Clause | Values | Meaning |
|--------|--------|---------|
| `STEP Ms` | duration | Advance the anchor every M (overlapping evaluations). |
| `EMIT <policy>` | `ON_UPDATE` \| `ON_CLOSE` \| `EARLY_AND_FINAL` | When results are emitted: on each update, only when the window closes, or both an early estimate and a final result. |
| `LATENESS d` | duration | Watermark out-of-orderness budget — hold window closure back so events up to `d` late still land in their window. |

**Semantics.** A forward-looking window can only be *complete* once enough time has passed, so directional
windows produce **exact, delayed** results at window close (one result per anchor). `LATENESS` trades
latency for tolerance of out-of-order arrivals. The `EMIT` policy governs emission for **windowed
aggregation** queries; complex-event (`SEQ`) matching over a directional window instead emits one final
match per anchor (see [§7](#7-complex-event-processing-cep)).

> **`LATENESS` on backward windows.** `LATENESS d` also parses on the backward forms. It is
> honoured on:
>
> - a single-stream `[RANGE Ns]` aggregate (`COUNT`/`SUM`/`AVG`/`MIN`/`MAX`, optional
>   `GROUP BY` — e.g. `FROM STREAM Sensors [RANGE 10s LATENESS 2s]`);
> - since `2.0.0-alpha.22`, a single-stream `[SLIDE … STEP … EMIT ON_CLOSE]` aggregate;
> - since `2.0.0-alpha.22`, a multi-stream join over `[RANGE]` or `[SLIDE]` windows.
>
> On every other backward shape it **fails loud** at registration rather than being silently
> ignored. Measured on `2.0.0-alpha.22`, that includes a `[SLIDE … STEP …]` or `[RANGE … STEP …]`
> aggregate without `EMIT ON_CLOSE`, a non-aggregate windowed `SELECT`, and `FILTER(SEQ(...))` on a
> non-directional window.
>
> **`EMIT` on backward windows.** Since `2.0.0-alpha.22`, `EMIT ON_CLOSE` or `EMIT ON_UPDATE` on a
> `[RANGE]`, `[SLIDE]` or `[TRIPLES]` window can state the emission model of an aggregate over one
> stream. Measured on `2.0.0-alpha.22` with one `COUNT` over one stream pattern:
> `[RANGE 3s EMIT ON_UPDATE]` reports on every arrival, and `[RANGE 3s EMIT ON_CLOSE]` once per
> closed bucket (see §9 *Aggregates*). `EMIT ON_CLOSE` also reports once per closed window on a
> two-pattern `STREAM` block and beside a static graph pattern, measured under `[RANGE 3s]`,
> `[TRIPLES 3]` and `[SLIDE 3s STEP 1s]`; with a static pattern under `[RANGE 3s]` it also
> accepted `HAVING` and a second aggregate. Over **one** stream triple pattern it runs on the incremental aggregate route,
> which takes one `COUNT` / `SUM` / `AVG` / `MIN` / `MAX` with optional `GROUP BY`, `FILTER` and
> `BIND`: there, adding `HAVING` or a second aggregate is refused at registration, measured under
> `[RANGE 3s]` and `[TRIPLES 3]`. An
> aggregate on a backward window that states no `EMIT` logs one warning naming the model its
> window keyword chose. `EMIT` on a non-aggregate or multi-stream query is refused at
> registration, as are `EMIT EARLY_AND_FINAL` on a backward window and `EMIT` under `[NOW]`.
>
> **Default.** Without `LATENESS`, allowed lateness is 0, and what happens to an element older than
> one already seen depends on the route. Measured on `2.0.0-alpha.22` with `COUNT(*)` over
> elements at 2800, 2900 and then 2850 ms: the on-close aggregate (`[RANGE 3s]`, or
> `[RANGE 3s EMIT ON_CLOSE]`) drops the late element **silently** and reports 2. The per-arrival
> forms (`[RANGE 3s EMIT ON_UPDATE]`, `[SLIDE 3s STEP 1s]`) count it and report 3. `[NOW]` and
> `[TRIPLES n]` use no watermark, so lateness does not apply. If late data matters, say so in the
> window spec.

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

| Clause | Meaning |
|--------|---------|
| `FROM [STATIC] <iri>` | Background graph (the `STATIC` keyword is optional). |
| `WITH DEPTH n` | Follow references up to *n* hops for multi-hop lookups. |
| `CACHE d` | Cache lookup results for duration *d*. |
| `FROM NAMED <iri>` | Named background graph. |

**`FROM <iri>` scopes the lookup.** Since `2.0.0-alpha.22`, a query that declares `FROM` reads
only its `FROM` graphs, on every route: with `FROM <http://example.org/graph>`, a static pattern
does not match a triple stored only in another named graph, or in the default graph. Before
`2.0.0-alpha.22` a query over one stream ignored the IRI and read the whole store. A query with no
`FROM` still reads every graph in the store. One declaring only `FROM NAMED <g>` matches nothing
with a plain static pattern: write the pattern inside `GRAPH <g> { … }`, or declare `FROM <g>`.

Because the lookup re-reads the static store per element, updates to background facts are reflected on the
next matching element.

**Elimination semantics.** A static pattern is a *required* join, exactly as in SPARQL 1.1: if the
lookup finds no match, the row is eliminated. This is what lets a static pattern act as a **guard** —
"only vehicles in this fleet", "only signals the model declares" — and not merely as enrichment.
**Since `2.0.0-alpha.20` the engine enforces this**: a static pattern that fails to match eliminates
the row rather than leaving it unbound (`OPTIONAL`-like). A fully-constant static pattern was, and
still is, evaluated correctly either way.

> **Reverse-edge static guards: unsound before `2.0.0-alpha.21`; the shapes below are sound since**
> ([#67](https://github.com/cqels/CQELS4J/issues/67)). Before `2.0.0-alpha.21`, a static guard that
> reached the join key by a *reverse* edge — `ex:coll ex:member ?f`, with `?f` bound by the stream
> as the *object* — failed in two opposite ways depending on the query's shape. It could
> **fabricate** rows: pushing a triple that matched no stream pattern at all still emitted a binding
> pulled from the store. And it could **eliminate** rows that fully matched. Under `[NOW]`, any
> satisfied static pattern — forward or reverse — also let a row through while a required stream
> pattern had no match. The earlier workaround was to guard on an outgoing edge instead.
>
> Since `2.0.0-alpha.21` none of that reproduces. A sweep of 162 cases returned the SPARQL 1.1
> answer in every one — six windows (`[NOW]`, `[TRIPLES 1]`, `[TRIPLES 5]`, `[RANGE 3s]`, `[SLIDE 3s
> STEP 1s]`, `[PAST 3s]`); one and two stream patterns; reverse, forward and no guard; matching,
> non-member, unrelated and partial pushes; six static-pattern shapes under `[NOW]`; and a
> variable-predicate query in four projections. Run against the previous release, the same sweep
> returns the wrong answer in 24 of them, which is what makes it evidence rather than a tautology.
> Shapes outside it have not been tested.
>
> Two static-pattern shapes outside that sweep stayed wrong on alpha.21 and are fixed since
> `2.0.0-alpha.22`. On the composed windowed route — a multi-pattern `STREAM` block under
> `[TRIPLES n]` or `[RANGE d]` — a stream solution whose join key binds a **literal** dropped its
> row. And a `FILTER` over a `BIND` target ran before the `BIND`
> ([#83](https://github.com/cqels/CQELS4J/issues/83)).
>
> One point from that history is independent of the bug and still holds. On real s2dm output the two
> guards are **not** interchangeable: the exporter makes every enum value a `skos:member` of
> `FieldConcepts` while typing it `s2dm:EnumValue`, so `?field a s2dm:Field` selects a strict
> **subset** of the collection. That is why
> [`S2dmConceptCatalog`](examples/src/main/java/org/cqels/examples/cdsp/S2dmConceptCatalog.java)
> still guards on the type — it asks for things the model classifies as fields, not for everything
> in the collection.

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

**`SEQ` syntax.** Arguments are separated by **`;`** (not `,`) and there must be **two or more**. Each
argument has one of two shapes:

```
<eventVar> <quantifier>?
NOT <eventVar>
```

- **`<eventVar>`** (e.g. `?e1`) must appear as the **subject** of at least one triple pattern in the WHERE
  block — that pattern defines what the event matches. Since `2.0.0-alpha.22` a variable that is
  never a subject — mentioned nowhere else, or only as an object — is refused at registration
  ([#74](https://github.com/cqels/CQELS4J/issues/74)). Earlier alphas registered it, and it then
  matched **any** element on the stream.
- **`NOT`** turns the step into a negated gap — "this must *not* occur between the previous and next
  events" (a gap constraint, not adjacency). A `NOT` step takes no quantifier: since
  `2.0.0-alpha.21` a quantified negation such as `NOT ?e+` is refused at registration. Nor may it
  directly follow an argument that can match nothing: `SEQ(?a ; ?b? ; NOT ?c ; ?d)` is refused too,
  because the gap's start would depend on whether `?b` matched.
- A **trailing** `NOT` — `SEQ(?a ; NOT ?b)`, or one followed only by optional arguments such as
  `SEQ(?a ; NOT ?b ; ?c?)` — needs a time window to close it. Under `[RANGE …]` it is accepted;
  since `2.0.0-alpha.21` it is refused at registration under `[NOW]` or `[TRIPLES n]`. Under
  `[RANGE d]`, `SEQ(?a ; NOT ?b)` emits `[a]` once an event or watermark past `a` + *d* arrives
  with no `?b` before it; earlier alphas emitted it only if the stream ended first.
- Each event variable may appear **once** in a `SEQ`; since `2.0.0-alpha.21` a repeated one is
  refused at registration.

**Quantifiers:**

| Quantifier | Meaning |
|------------|---------|
| `?e+` | one or more |
| `?e*` | zero or more |
| `?e?` | zero or one |
| `?e{3}` | exactly three |
| `?e{2,5}` | between 2 and 5 (inclusive) |

Since `2.0.0-alpha.21` a range whose minimum exceeds its maximum, such as `?e{3,1}`, is refused at
registration.

**Matches.** Since `2.0.0-alpha.21` each match is a complex event emitted **exactly once**, and a
quantified argument consumes every relevant event: `SEQ(?a ; ?b+ ; ?c)` over `a b b c` emits
`[a b b c]` once. Earlier alphas also emitted shorter matches and repeated longer ones — for that
input, `[a b b c]` twice and `[a b c]` once.

An **optional** argument is a true alternative since `2.0.0-alpha.21`. `SEQ(?a ; ?b?)` emits `[a]`
as soon as `a` arrives; earlier alphas emitted nothing. `SEQ(?a ; ?b? ; ?c)` over `a c c` emits one
match, `[a c]` with the first `c` — as `SEQ(?a ; ?c)` does — where earlier alphas emitted one per
`c`; over `a b c` it emits both `[a b c]` and `[a c]`, where earlier alphas emitted only the first.

**Event matching.** If an event variable is the subject of a *single* triple pattern, it fires when one
statement matches. If it is the subject of *several* patterns, it fires only when a single stream element
satisfies all of them consistently (reified-event shape).

**Cross-event constraints.** Ordinary `FILTER`s referencing one event's variables become per-event
predicates; `FILTER`s referencing variables from *several* events (e.g. `FILTER(STR(?v1) = STR(?v2))` to
require the same vehicle) become guards evaluated once all involved events have matched. Since
`2.0.0-alpha.21` a guard that reads a quantified argument's variables sees that argument's
**first** event; earlier alphas could let a run's later events spill into the next argument's
variables.

**Window scope.** The stream window bounds the sequence: `[RANGE Ns]` gives the match an N-second timeout.
With `[NOW]` or `[TRIPLES n]` there is no time bound — only stream termination closes a partial match.
Since `2.0.0-alpha.21` a match is bounded in size instead: a partial match that would hold more than
10 000 events is dropped, and a pattern is limited to 256 arguments with names of at most 256
characters (system properties `cqels.cep.maxEventsPerMatch`, `cqels.cep.maxPatternArguments` and
`cqels.cep.maxArgumentNameLength`; `0` disables each).
Running CEP over a **directional** window (`[FUTURE …]`, `[PAST … FUTURE …]`) switches to exact,
window-close evaluation: one final match per anchor at window close (an explicit `EMIT` policy is not
currently supported on directional CEP).

**Projecting from a CEP query.** The match output does not carry the SELECT list directly — bind an event
variable to your projection variable:

```sparql
SELECT ?v WHERE { ?e1 … FILTER(SEQ(?e1 ; ?e2)) BIND(?e1 AS ?v) }
```

---

## 8. Stream output operators (RSP-QL)

How window contents are turned back into an output stream follows the RSP-QL operators:

| Operator | Emits |
|----------|-------|
| **RStream** | the complete window contents on each update (stateful) |
| **IStream** | only newly added elements per update |
| **DStream** | only removed elements per update |

---

## 9. Standard SPARQL features (supported as-is)

These behave per **[SPARQL 1.1](https://www.w3.org/TR/sparql11-query/)**; only the streaming-relevant notes
are called out.

- **`SELECT` / `SELECT DISTINCT`**, projection, and `(expr AS ?v)` expressions. Since
  `2.0.0-alpha.21` an expression that **wraps an aggregate**, such as `(COUNT(*) * 10 AS ?c)`, is
  refused at registration; earlier alphas silently returned the bare aggregate (`?c = 2`, not `20`).
  Since `2.0.0-alpha.22` the arithmetic can go over the aggregate's alias instead: measured,
  `(COUNT(*) AS ?n) (?n * 10 AS ?scaled)` under `[TRIPLES 3]` reports 10, 20, 30. On a single-pattern stream query, `DISTINCT` is scoped to the
  window. Under `[NOW]` that means one arrival: since `2.0.0-alpha.21` the same projection from two
  arrivals is reported twice. With `ORDER BY … LIMIT`, alpha.21 could also repeat a projection
  within one arrival, once for each row that improved the sort (upstream #904). Measured on
  `2.0.0-alpha.22`, `SELECT DISTINCT ?o … [NOW] … ORDER BY DESC(?x) LIMIT 1` over one element
  carrying `(o1 ex:v 1) (o1 ex:v 2) (o1 ex:v 3)` emits `o1` once, as it did on alpha.20.
- **Aggregates:** `COUNT(*)` / `COUNT(?v)`, `SUM`, `AVG`, `MIN`, `MAX`, `GROUP_CONCAT(?v; SEPARATOR=", ")`,
  and since `2.0.0-alpha.22` per-aggregate `DISTINCT` (`COUNT(DISTINCT ?v)`).
  *Note:* `GROUP BY` selects **per-group** results rather than global ones; it is **not** required
  for an aggregate to apply. Measured on `2.0.0-alpha.22`, a global (no `GROUP BY`) `COUNT(*)`
  accumulates correctly over every window that accumulates — `[RANGE]`, `[TRIPLES]`, `[SLIDE]`.
  Three exceptions, all upstream known issues of `2.0.0-alpha.22`, measured under `[RANGE 3s]`
  and `[TRIPLES 3]`:
  - **An aggregate whose argument no solution binds reports no row at all** where SPARQL 1.1
    gives `COUNT` 0. With `?missing` bound by no pattern, `SELECT (COUNT(?missing) AS ?n)` emits
    nothing, and so does `COUNT(*)` written beside it. An argument left unbound by an `OPTIONAL`
    is counted correctly (`{n=0}`).
  - **`AVG` of a group in which no row binds the argument is `null`**, where SPARQL 1.1 gives 0.
  - **A `GROUP BY` key compares a literal by its label, ignoring its language tag or datatype**,
    so `"cat"@en` and `"cat"@fr` fall into one group, reported under the first key seen
    (`{l="cat"@en, n=2}`). Upstream reports the same for
    `"PT1S"^^xsd:duration` and `"PT1S"^^xsd:dayTimeDuration`. When the tag matters, add
    `BIND(LANG(?l) AS ?lang)` and `GROUP BY ?l ?lang`: measured, that keeps the two apart.
    (`GROUP BY LANG(?l)` is a syntax error.)
  One shape is rejected: `[NOW]` with a global aggregate **fails at registration**, with a message
  naming the windows to use instead (`[NOW]` is zero-length, so there is nothing to accumulate).
  `[NOW]` with `GROUP BY` registers, and since `2.0.0-alpha.21` folds once per arrival: each arrival
  reports its own groups, so a `COUNT` over single-statement elements reads `1` every time. Earlier
  alphas reported nothing on a live stream.

  Beyond that, **which elements an aggregate covers — and when it reports — depend on the query's
  shape**, and the difference is not only one of cadence. Two populations exist: an epoch-aligned
  **tumbling bucket**, reported once the bucket closes, and a **rolling window** relative to the
  arriving element, reported on every arrival.

  The table below is a list of **measured cases**, not a rule, and shapes outside it have not been
  tested. All measured on `2.0.0-alpha.22` with one stream pattern and `[RANGE 3s]`, using elements
  timestamped 2800, 2900 and 3100 ms, with no `EMIT` clause:

  | Query shape | Result |
  |---|---|
  | one simple aggregate (`COUNT`, `SUM`, `AVG`, `MIN`, `MAX`), or `COUNT(DISTINCT ?v)` | **tumbling** — the closed bucket `[0, 3000)`; for `COUNT` that is `{n=2}` |
  | …plus `FILTER` or `BIND` | tumbling |
  | …plus `GROUP BY` | tumbling — one row per group in the closed bucket |
  | …plus `HAVING`, a static join, or `OPTIONAL` | **rolling** — running `{n=1}`, `{n=2}`, `{n=3}` |
  | two aggregates, or `GROUP_CONCAT` | rolling |
  | two stream patterns | rolling |

  Since `2.0.0-alpha.22` a query can state the model instead of inheriting it from its shape:
  `[RANGE 3s EMIT ON_UPDATE]` turns the first row of the table into running `{n=1}`, `{n=2}`,
  `{n=3}`, and `[RANGE 3s EMIT ON_CLOSE]` keeps it at `{n=2}` (see
  [§3.2](#32-directional-lars-windows) for where `EMIT` is accepted). `SELECT DISTINCT` over the
  simple-aggregate form is still **rejected at registration**. Other window forms —
  `[TRIPLES n]`, `[SLIDE W STEP S]` and `[RANGE W STEP S]` — reported per arrival in every shape
  tested.

  A tumbling bucket is closed by an element whose **event time** falls past the boundary — it need
  not match the query's own pattern — or by `DataStream.complete()`. Elapsed wall-clock time alone
  closes nothing, which is the trap: a test that pushes a handful of elements and then sleeps inside
  the window sees nothing at all from the tumbling form, and that silence is not a defect.


- **`FILTER(expr)`** — operators `= != < > <= >= && || !` `+ - * /`, plus built-ins; full SPARQL
  effective-boolean-value semantics. Verified working on `2.0.0-alpha.22`: `BOUND`, `IF`, `STR`,
  `CONCAT`, `ABS`, `SUBSTR`, `IRI`, `year(...)`, `sameTerm`, and a prefixed name used as a constant
  (resolved against the query's own `PREFIX` declarations, the same as in a triple pattern). Since
  `2.0.0-alpha.22` also `REPLACE`, `COALESCE`, `REGEX` and `STRDT`, and a string literal's escapes
  (`"a\nb"` has `STRLEN` 3).
  **Not every SPARQL 1.1 built-in is evaluated**, and an unevaluated one fails the same silent way an
  unregistered function IRI does — no registration error, `FILTER` simply never matches and `BIND`
  leaves the variable unbound. Known gaps on `2.0.0-alpha.22`:

  | Construct | SPARQL 1.1 | Status | Use instead |
  |-----------|-----------|--------|-------------|
  | `xsd:dateTime` subtraction | XPath-derived, outside the SPARQL 1.1 operator table | **not evaluated** — the variable is left unbound | express the interval as a **window** ([§3](#3-window-specifications)) |
  | `xsd:duration` in `ORDER BY`, `MIN` or `MAX` | XPath-derived, outside the SPARQL 1.1 operator table | **compared as text** — `MAX` over `PT9S` and `PT10S` returns `PT9S` | compare in a `FILTER`, which compares durations by value, or order in the result listener |

  Neither row is a conformance gap — SPARQL 1.1's operator table defines neither `-` over two
  `xsd:dateTime` values nor ordering over `xsd:duration` — but they are worth stating, because
  porting rule engines that do (RDFox, for one) will reach for them. Since `2.0.0-alpha.22` a
  `FILTER` compares durations by value (`PT10S > PT9S` and `PT60S = PT1M` both hold;
  [#82](https://github.com/cqels/CQELS4J/issues/82)), so the second row is the dangerous one: the
  same pair of durations orders one way in a `FILTER` and the other way in `MAX`.
- **`BIND(expr AS ?v)`** — computed variables. Since `2.0.0-alpha.22` the group's `FILTER` sees a
  `BIND` target on every route, as SPARQL 1.1 requires
  ([#83](https://github.com/cqels/CQELS4J/issues/83)). Measured on `2.0.0-alpha.22` beside a
  static graph pattern, `BIND(?x * 2 AS ?y) FILTER(?y > 5)` answers, and `FILTER(!BOUND(?y))`
  correctly drops the row. The same holds beside `FILTER NOT EXISTS`, and with `OPTIONAL` and
  `GROUP BY` under `[TRIPLES 2]`. Before `2.0.0-alpha.22`, on a query with a static graph pattern,
  the `FILTER` ran before the `BIND` in most shapes and saw the target unbound.

  Also since `2.0.0-alpha.22`, a top-level `BIND` whose target is already bound where the `BIND`
  is written, such as `BIND(1 AS ?x)` after a pattern binding `?x`, is refused at registration
  (SPARQL 1.1 §18.2.2.6). Earlier alphas kept one of the two values, depending on the route.
- **Extension functions (SPARQL 1.1 §17.6)** — since `2.0.0-alpha.11`, a `FILTER` / `BIND` /
  `SELECT`-expression call to an **explicit function IRI** — `<urn:cqels:fn:haversine>(…)` or a
  declared-prefix form such as `cqfn:haversine(…)` with `PREFIX cqfn: <urn:cqels:fn:>` — resolves
  against RDF4J's `ServiceLoader`-fed `FunctionRegistry`. Any jar on the classpath shipping a
  `META-INF/services/org.eclipse.rdf4j.query.algebra.evaluation.function.Function` entry adds
  functions with **zero registration code** — e.g. the `org.cqels:cqels-functions-ext` pack
  (`urn:cqels:fn:levenshtein`, `urn:cqels:fn:haversine`; see the runnable
  [`ChargerRangeFilter`](examples/) example). Bare keyword names (`year(…)`, `ABS(…)`, …) never
  consult the registry — the built-in namespace is frozen — and built-ins plus `geof:` still
  resolve first. A call to an *unregistered* IRI (or with an unbound argument) remains a **silent
  SPARQL type error**: `FILTER` drops the row, `BIND` leaves the variable unbound.
- **`OPTIONAL { … }`** — left outer join (unmatched optional variables are unbound). Since
  `2.0.0-alpha.21` a `STREAM` block inside `OPTIONAL` is refused at registration; earlier alphas
  registered it and null-filled every row. To correlate two streams, use a top-level multi-stream
  join.

  Since `2.0.0-alpha.22` the group's `FILTER` runs after its `OPTIONAL` on every route
  ([#85](https://github.com/cqels/CQELS4J/issues/85)). Measured on `2.0.0-alpha.22`, the negation
  idiom `OPTIONAL { ?o ex:label ?l } FILTER(!BOUND(?l))` admits only the rows with no label, and
  `FILTER(BOUND(?l))` only the rows with one. That holds on a stream-only query, and on a
  `GROUP BY` query with a static graph pattern under `[TRIPLES 2]`. On alpha.20 and alpha.21 both
  shapes ran the `FILTER` first, so it saw the variable unbound.
- **`{ … } UNION { … }`** — alternative patterns merged before `FILTER`. **On a non-aggregate
  query, a static `UNION` drops every row when the variable joining it to the stream is not
  projected** — an upstream known issue of `2.0.0-alpha.22`. Measured under `[NOW]`, `[TRIPLES 3]`
  and `[RANGE 3s]`:
  `SELECT ?r ?city … { STREAM S { ?r ex:person ?p } { ?p ex:city ?city } UNION { ?p ex:town ?city } }`
  answers nothing, and the same query projecting `?p` answers. Under `[TRIPLES 3]` or
  `[RANGE 3s]`, adding `ORDER BY` or `LIMIT` also avoids it, and so does an aggregate such as
  `COUNT(*)`; under `[NOW]`, project the join variable.
- **Property paths (`/`, `^`, `+`, `*`, `?`, `|`)** — **executed on one route since
  `2.0.0-alpha.22`**: the triple patterns of the one `STREAM` block of a single-stream query over a
  `[RANGE]`, `[SLIDE]` or `[TRIPLES]` window, with no static graph pattern. Measured on
  `2.0.0-alpha.22` under each of those windows, `STREAM S { ?x ex:broader+ ex:root }` over
  `leaf → mid → root` answers `mid` and `leaf`, and `ex:broader/ex:broader` answers `leaf`. Every
  other placement is **refused at registration**, with a message naming the path and the route:
  under `[NOW]`, beside a static graph pattern, and in a static pattern, `GRAPH`, `OPTIONAL`,
  `UNION` or `FILTER (NOT) EXISTS` block. A path's evaluation runs under a work budget; past it
  the query fails rather than answering partially. So the common case of traversing a
  hierarchy held in the **static** graph is still not expressible as a path: write the hops
  explicitly (`?a skos:broader ?mid . ?mid skos:broader ?b .`) for a fixed depth, or use a
  recursive rule (see the [`BoundedTransitiveClosure`](examples/) example) for unbounded closure.
  (Before `2.0.0-alpha.20` a path registered despite a parse error and was silently mis-evaluated;
  alpha.20 and alpha.21 refused every path.)
- **`FILTER NOT EXISTS { … }`** and, since `2.0.0-alpha.22`, **`FILTER EXISTS { … }`** —
  anti-join and semi-join over the static graph. Since `2.0.0-alpha.22` the body may also hold a
  `FILTER`, including one that reads outer variables
  ([#92](https://github.com/cqels/CQELS4J/issues/92)): measured, `FILTER NOT EXISTS { ?o ex:lim ?m
  FILTER(?x > ?m) }` keeps the row with `?x` 3 and drops the one with `?x` 9 against a limit of 5.
  On alpha.21 an inner `FILTER` was refused at registration. On a query with **no**
  static graph pattern, a block that correlates on a variable the row does not carry — bound
  outside the block but not projected — is refused too, since `2.0.0-alpha.21`: name the variable
  in `SELECT`, or add a static pattern. (`MINUS` is not yet
  executed; since `2.0.0-alpha.13` registering a query containing `MINUS` **fails loud** with a hint
  to rewrite it as `FILTER NOT EXISTS` — earlier alphas parsed it but silently skipped it.)
- **`GROUP BY ?a, ?b`** · **`HAVING(expr)`** — per-group aggregation (**not** required for an
  aggregate to apply — see the aggregates note above) + post-aggregation filter; `HAVING` may use
  the SELECT alias (`HAVING(?cnt > 1)`) or the aggregate call (`HAVING(COUNT(*) > 1)`); since
  `2.0.0-alpha.21` the two answer the same. Before it, the call form silently matched nothing.
- **`ORDER BY ?v [ASC|DESC]`** (default `ASC`) · **`LIMIT n`** — on a continuous query, semantics
  are decided by the **execution route**, not by whether the query text declares a window: a
  declared window alone does not make a query "windowed" in the sense that matters here.
  - A route with a real evaluation boundary — a window close, or each element's batch on a
    windowed hash join — applies `ORDER BY` / `LIMIT` **inside each evaluation batch**, in
    solution-modifier order (aggregation → `HAVING` → `ORDER BY` → `LIMIT`).
  - A **single fixed-predicate stream pattern**, non-aggregate, with `ORDER BY` or `LIMIT` under
    `[RANGE]`, `[TRIPLES]` or `[SLIDE]`, registers since `2.0.0-alpha.22` and ranks **per
    evaluation**; earlier alphas refused it. It runs on the windowed hash join, which changes its
    emission model as well as its ranking: every arrival re-evaluates the whole window, so a bare
    `ORDER BY` re-emits each row for as long as it stays in the window. Measured with three
    arrivals under `[RANGE 5s]` or `[TRIPLES 3]`, a bare `ORDER BY DESC(?x)` emits 1 + 2 + 3 = 6
    rows, where the same query without the modifier emits 3.
  - Under `[NOW]`, `ORDER BY … LIMIT k` is a continuous top-`k` over everything seen.
  - A **windowed stream–static, non-aggregate** query whose stream side needs the windowed join
    ranks **per evaluation** since `2.0.0-alpha.21`, like the other batch routes: each arrival
    emits the current window's ranked rows, and a row that leaves the window stops competing. A bare
    `ORDER BY` or a bare `LIMIT` registers on this route too. Before `2.0.0-alpha.21` it ran a
    continuous top-`k` accumulated across the whole stream, and refused a bare modifier.
  - A bare `ORDER BY` or a bare `LIMIT` (not paired with the other) over a route with no
    evaluation boundary is rejected at registration — sorting or truncating an unbounded stream
    is not well-defined without one.
  - `[NOW]` combined with a **global** (no `GROUP BY`) aggregate is **rejected at registration**
    since `2.0.0-alpha.20`; earlier alphas silently leaked the raw argument binding instead.

**Evaluation order:** stream patterns → static lookup join → `OPTIONAL`, `UNION` and `BIND`, **in
the order written** → `FILTER` and `FILTER NOT EXISTS` → `GROUP BY` + aggregates → `HAVING` →
`ORDER BY` → `LIMIT`. Written order matters: measured, with `ex:label "a"` and `ex:fallback "b"`
stored, `OPTIONAL { ?o ex:label ?l }` before `{ ?o ex:label ?l } UNION { ?o ex:fallback ?l }`
answers `a` only, and the reverse order answers `a` and `b`.
Since `2.0.0-alpha.22` that order holds on every route; on earlier alphas a `FILTER`
could run before a `BIND` ([#83](https://github.com/cqels/CQELS4J/issues/83)) or an `OPTIONAL`
([#85](https://github.com/cqels/CQELS4J/issues/85)) on some routes.

---

## 10. CypherQL (property-graph dialect)

The same engine accepts continuous **Cypher** queries for property-graph pattern matching over the same
streams (registered separately from CQELS-QL):

```cypher
FROM STREAM ProfileRequests [NOW]
MATCH (req:Request)-[:FOR]->(p:Person)-[:LOCATED_IN]->(c:City)
RETURN p.firstName, p.lastName, c.name
```

CypherQL reuses the streaming model with graph-traversal patterns instead of triple patterns. The
clauses come in the order shown: `FROM STREAM` first, then `MATCH`, then `RETURN`; a query that
starts with `MATCH` is a syntax error.

CypherQL accepts `[NOW]`, `[TRIPLES n]`, `[RANGE d]` and `[SLIDE d STEP s]`; the directional
`[PAST …]` / `[FUTURE …]` forms are a syntax error. Since `2.0.0-alpha.22`, `[RANGE]` and `[SLIDE]`
answer on a live stream once an element's event time passes the window's end. On alpha.20 and
alpha.21 they reported nothing until the source completed. Measured on `2.0.0-alpha.22` with
`MATCH (o:Observation) RETURN o` over elements at event times 500, 1000 and 2500 ms: under
`[RANGE 2s]` and `[SLIDE 2s STEP 1s]` nothing is reported until the element at 2500 arrives, and
then `o1` and `o2`. `[TRIPLES 2]` reports `o1` and `o2` as the second element arrives. As with
CQELS-QL, elapsed wall-clock time alone closes nothing. A variable-length relationship such as
`(a)-[:broader*1..3]->(b)` is also evaluated since `2.0.0-alpha.22`, once per window batch:
over `leaf → mid → root` it answers the three pairs. `RETURN DISTINCT` needs a projected
variable or property; under `[NOW]`, the same `o.type` from two arrivals is reported twice.

---

## 11. Grammar (EBNF summary)

```ebnf
query           ::= prefixDecl* registerQuery
registerQuery   ::= 'REGISTER' 'QUERY' Name 'AS' selectQuery
selectQuery     ::= 'SELECT' selectList fromClause+ 'WHERE' whereClause
                    groupByClause? havingClause? orderByClause? limitClause?

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
                  | 'TRIPLES' 'PAST' INT ('FUTURE' INT)? emit?
                  | 'TRIPLES' 'FUTURE' INT emit?
emit            ::= 'EMIT' ('ON_UPDATE' | 'ON_CLOSE' | 'EARLY_AND_FINAL')
lateness        ::= 'LATENESS' duration
duration        ::= INT ('ms' | 's' | 'm' | 'h' | 'd')

whereClause     ::= '{' patternGroup+ '}'
patternGroup    ::= streamPattern | windowPattern | triplePattern+
                  | filterConstraint | bindPattern | optionalPattern | unionPattern | minusPattern
streamPattern   ::= 'STREAM' Name '{' triplePattern+ '}'
windowPattern   ::= 'WINDOW' PrefixedName '{' triplePattern+ '}'
filterConstraint::= 'FILTER' '(' expression ')'
                  | 'FILTER' 'NOT'? 'EXISTS' '{' (triplePattern | filterConstraint)+ '}'   -- §9
bindPattern     ::= 'BIND' '(' expression 'AS' Var ')'

seqExpression   ::= 'SEQ' '(' seqArg (';' seqArg)+ ')'        -- inside FILTER(...)
seqArg          ::= 'NOT'? Var seqQuantifier? ('AS' alias)?   -- NOT takes no quantifier (§7); AS renames the argument
seqQuantifier   ::= '+' | '*' | '?' | '{' INT '}' | '{' INT ',' INT '}'

aggregate       ::= 'COUNT' '(' ('*' | 'DISTINCT'? Var) ')' | ('SUM'|'AVG'|'MIN'|'MAX') '(' 'DISTINCT'? Var ')'
                  | 'GROUP_CONCAT' '(' 'DISTINCT'? Var (';' 'SEPARATOR' '=' STRING)? ')'
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
  ?e2 ex:hasAlert ex:StallAlert .
  FILTER(SEQ(?e1 ; ?e2))
}
```

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
