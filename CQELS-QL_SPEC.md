# CQELS-QL Language Specification

**Applies to:** CQELS `2.0.0-alpha.3` · **Based on:** SPARQL 1.1, extended with RSP-QL / C-SPARQL / LARS streaming constructs.

CQELS-QL (*Continuous Query Evaluation over Linked Streams — Query Language*) is **SPARQL 1.1 plus
streaming**. If you know SPARQL, you already know most of it: `SELECT`, `FILTER`, `BIND`, `OPTIONAL`,
`UNION`, `MINUS`, aggregates, `GROUP BY` / `HAVING` / `ORDER BY` / `LIMIT` behave as in SPARQL 1.1.

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
  [OPTIONAL { … }] [{ … } UNION { … }] [MINUS { … }]
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

A window appears in square brackets after a stream name. **Time units:** `s` (seconds), `m` (minutes),
`h` (hours), `d` (days).

### 3.1 Backward windows

| Window | Syntax | Meaning |
|--------|--------|---------|
| Instantaneous | `[NOW]` | Only the current element; no buffering. Evaluate per arriving statement. |
| Tumbling time | `[RANGE 10s]` | Fixed, non-overlapping N-second windows. |
| Sliding time | `[RANGE 10s STEP 5s]` | N-second window advancing every M seconds (overlapping). |
| Sliding time (alt) | `[SLIDE 30s STEP 10s]` | Equivalent sliding-window spelling. |
| Count-based | `[TRIPLES 100]` | The most recent N statements, regardless of time. |

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
| `[TRIPLES PAST n FUTURE m]` | Count-based directional extents. |

Directional windows accept an optional **emission policy** and **lateness budget**:

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

Because the lookup re-reads the static store per element, updates to background facts are reflected on the
next matching element.

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
argument has the shape:

```
NOT? <eventVar> <quantifier>?
```

- **`<eventVar>`** (e.g. `?e1`) must appear as the **subject** of at least one triple pattern in the WHERE
  block — that pattern defines what the event matches. A variable that is never a subject is a compile
  error.
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

**Event matching.** If an event variable is the subject of a *single* triple pattern, it fires when one
statement matches. If it is the subject of *several* patterns, it fires only when a single stream element
satisfies all of them consistently (reified-event shape).

**Cross-event constraints.** Ordinary `FILTER`s referencing one event's variables become per-event
predicates; `FILTER`s referencing variables from *several* events (e.g. `FILTER(STR(?v1) = STR(?v2))` to
require the same vehicle) become guards evaluated once all involved events have matched.

**Window scope.** The stream window bounds the sequence: `[RANGE Ns]` gives the match an N-second timeout.
With `[NOW]` or `[TRIPLES n]` there is no time bound — only stream termination closes a partial match.
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

- **`SELECT` / `SELECT DISTINCT`**, projection, and `(expr AS ?v)` expressions.
- **Aggregates:** `COUNT(*)` / `COUNT(?v)`, `SUM`, `AVG`, `MIN`, `MAX`, `GROUP_CONCAT(?v; SEPARATOR=", ")`.
  *Note:* aggregates are computed **per group — an explicit `GROUP BY` is required**; without it the engine
  emits the raw bindings rather than an aggregate.
- **`FILTER(expr)`** — operators `= != < > <= >= && || !` `+ - * /`, plus built-ins (`BOUND`, `IF`,
  `CONCAT`, `STR`, `year(...)`, …); full SPARQL effective-boolean-value semantics.
- **`BIND(expr AS ?v)`** — computed variables (evaluated after `FILTER`).
- **`OPTIONAL { … }`** — left outer join (unmatched optional variables are unbound).
- **`{ … } UNION { … }`** — alternative patterns merged before `FILTER`.
- **`MINUS { … }` / `FILTER NOT EXISTS { … }`** — set difference / anti-join.
- **`GROUP BY ?a, ?b`** · **`HAVING(expr)`** — post-aggregation filter; the expression references the
  **SELECT aliases** (e.g. `HAVING(?cnt > 1)`, not `HAVING(COUNT(*) > 1)`).
- **`ORDER BY ?v [ASC|DESC]`** (default `ASC`) · **`LIMIT n`**.

**Evaluation order:** stream patterns → static lookup join → `UNION` → `OPTIONAL` → `MINUS` /
`FILTER NOT EXISTS` → `FILTER` → `BIND` → `GROUP BY` + aggregates → `HAVING` → `ORDER BY` → `LIMIT`.

---

## 10. CypherQL (property-graph dialect)

The same engine accepts continuous **Cypher** queries for property-graph pattern matching over the same
streams (registered separately from CQELS-QL):

```cypher
MATCH (req:Request)-[:FOR]->(p:Person)-[:LOCATED_IN]->(c:City)
FROM STREAM ProfileRequests [NOW]
RETURN p.firstName, p.lastName, c.name
```

CypherQL reuses the streaming model (windows, continuous results) with graph-traversal patterns instead of
triple patterns.

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
duration        ::= INT ('s' | 'm' | 'h' | 'd')

whereClause     ::= '{' patternGroup+ '}'
patternGroup    ::= streamPattern | windowPattern | triplePattern+
                  | filterConstraint | bindPattern | optionalPattern | unionPattern | minusPattern
streamPattern   ::= 'STREAM' Name '{' triplePattern+ '}'
windowPattern   ::= 'WINDOW' PrefixedName '{' triplePattern+ '}'
filterConstraint::= 'FILTER' '(' expression ')' | 'FILTER' 'NOT' 'EXISTS' '{' patternGroup+ '}'
bindPattern     ::= 'BIND' '(' expression 'AS' Var ')'

seqExpression   ::= 'SEQ' '(' seqArg (';' seqArg)+ ')'        -- inside FILTER(...)
seqArg          ::= 'NOT'? Var seqQuantifier? ('AS' alias)?   -- AS is reserved (parsed, not yet used)
seqQuantifier   ::= '+' | '*' | '?' | '{' INT '}' | '{' INT ',' INT '}'

aggregate       ::= 'COUNT' '(' ('*' | Var) ')' | ('SUM'|'AVG'|'MIN'|'MAX') '(' Var ')'
                  | 'GROUP_CONCAT' '(' Var (';' 'SEPARATOR' '=' STRING)? ')'
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

**Named windows (short vs long baseline on one stream):**
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

More, all runnable and verified against this release, are in [`examples/`](examples/).

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
