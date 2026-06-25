# CQELS Engine — Getting-Started Examples

Self-contained programs demonstrating the capabilities of the CQELS 2.0 continuous query
engine — from a one-line filter through windows, joins, the SPARQL algebra, CEP, and the
Cypher dialect, to standard-vocabulary (W3C SOSA/SSN, COVESA VSS) scenarios. Each is a
`main()` you can run directly.

## Prerequisites

- JDK 17+ and Maven 3.8+
- A GitHub Packages token configured in `~/.m2/settings.xml` (one-time setup) —
  see [`../GETTING_STARTED.md`](../GETTING_STARTED.md#2-authenticate-to-github-packages-one-time).

## Build & run

```bash
mvn -q compile

# Default (HelloCqels):
mvn -q exec:java

# Any scenario, e.g.:
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.WindowedAggregation
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.CountWindow
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.DirectionalWindow
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.GroupConcatSummary
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.StreamStaticJoin
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.AdvancedQueryOperators
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.ComplexEventPattern
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.CepQuantifierNegation
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.CypherGraphQuery
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.SosaObservations
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.VehicleSignalsCdsp
```

Each program prints what it pushes and what the engine emits, then exits on its own.

## The scenarios

Grouped by use-case category — add new demos under the matching heading (and list them
in the table + the `Build & run` block above).

### Basics
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`HelloCqels`](src/main/java/org/cqels/examples/HelloCqels.java) | `[NOW]` window + `FILTER` | Alert on every sensor reading above a threshold — the minimal continuous query. |

### Windowing & aggregation
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`WindowedAggregation`](src/main/java/org/cqels/examples/WindowedAggregation.java) | `[RANGE 3s]` tumbling + `GROUP BY` | Per-sensor average / count / peak temperature, one row per 3-second window. |
| [`SlidingWindowTrends`](src/main/java/org/cqels/examples/SlidingWindowTrends.java) | `[SLIDE 4s STEP 2s]` + `GROUP BY` | Overlapping trailing-window stats (moving average / min / max) re-emitted every 2s. |
| [`CountWindow`](src/main/java/org/cqels/examples/CountWindow.java) | `[TRIPLES N]` count-based window | Page views per user over the most recent N elements (not seconds). |
| [`DirectionalWindow`](src/main/java/org/cqels/examples/DirectionalWindow.java) | `[FUTURE 2s … EMIT …]` (LARS) | Forward-looking window with an emission policy (running + final rows). |
| [`GroupConcatSummary`](src/main/java/org/cqels/examples/GroupConcatSummary.java) | `GROUP_CONCAT(…; SEPARATOR=…)` | Per-product sales count + concatenated regions per window. |

### Advanced query patterns
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`StreamStaticJoin`](src/main/java/org/cqels/examples/StreamStaticJoin.java) | `FROM STATIC` lookup join | Enrich each reading with its sensor's room/floor from a seeded background graph. |
| [`AdvancedQueryOperators`](src/main/java/org/cqels/examples/AdvancedQueryOperators.java) | `OPTIONAL` / `UNION` / `FILTER NOT EXISTS` / `BIND` | Order enrichment exercising the SPARQL algebra over a stream + static catalogue. |

### Complex event processing
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`ComplexEventPattern`](src/main/java/org/cqels/examples/ComplexEventPattern.java) | declarative CEP `FILTER(SEQ(...))` | Detect a temporal sequence — overheat **then** stall — within a window. |
| [`CepQuantifierNegation`](src/main/java/org/cqels/examples/CepQuantifierNegation.java) | CEP quantifier `?e+` | Escalation: a critical alert, then one-or-more retries, then a failure. |

### Query dialects
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`CypherGraphQuery`](src/main/java/org/cqels/examples/CypherGraphQuery.java) | CypherQL `MATCH … RETURN` | Continuous property-graph matching (`MATCH (p:Person)`) over the stream. |

### Standard vocabularies & domains
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`SosaObservations`](src/main/java/org/cqels/examples/SosaObservations.java) | W3C [SOSA/SSN](https://www.w3.org/TR/vocab-ssn/) + multi-pattern stream join | Average / peak result per sensor over standard `sosa:Observation` streams. |
| [`VehicleSignalsCdsp`](src/main/java/org/cqels/examples/VehicleSignalsCdsp.java) | COVESA [VSS](https://covesa.global/) (CDSP) + `GROUP BY` + `HAVING` | Per-vehicle speed over a window; emit only vehicles that were speeding. |

## Adapting them

- Change the CQELS-QL string to try other windows (`[TRIPLES N]`), aggregates
  (`SUM`, `MIN`), or clauses (`HAVING`, `ORDER BY`, `LIMIT`).
- `engine.createStream(name)` returns a `DataStream` with several `push(...)`
  overloads (RDF `Statement`, `(subject, predicate, value)` with `String`/`double`/
  `long`/`IRI` objects, and explicit-timestamp variants).
- Bump `<cqels.version>` in [`pom.xml`](pom.xml) to track new releases.
