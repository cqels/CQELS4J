# CQELS Engine — Getting-Started Examples

Self-contained programs demonstrating the capabilities of the CQELS 2.0 continuous query
engine — from a one-line filter through windows, joins, the SPARQL algebra, CEP, and the
Cypher dialect, to standard-vocabulary (W3C SOSA/SSN, COVESA VSS) scenarios. Each is a
`main()` you can run directly.

## Prerequisites

- JDK 17+ and Maven 3.8+
- A GitHub Packages token (classic PAT with `read:packages`) in `~/.m2/settings.xml` —
  **required**: GitHub Packages has no anonymous access even for public packages, so the
  engine JARs can't be fetched without it. One-time setup in
  [`../GETTING_STARTED.md`](../GETTING_STARTED.md#2-authenticate-to-github-packages-one-time).
  (A token-free Maven Central release is planned — see the README roadmap.)

## Build & run

```bash
mvn -q compile

# Default (HelloCqels):
mvn -q exec:java

# Any scenario, e.g.:
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.WindowedAggregation
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.SlidingWindowTrends
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.CountWindow
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.DirectionalWindow
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.GroupConcatSummary
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.StreamStaticJoin
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.AdvancedQueryOperators
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.ComplexEventPattern
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.CepQuantifier
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.CypherGraphQuery
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.RdfsReasoning
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.ShaclValidation
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.AspReasoning
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.GeoSpatialFilter
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
| [`StreamStaticJoin`](src/main/java/org/cqels/examples/StreamStaticJoin.java) | stream–static lookup join | Enrich each reading with its sensor's room/floor from a seeded background graph (static patterns outside `STREAM {}`). |
| [`AdvancedQueryOperators`](src/main/java/org/cqels/examples/AdvancedQueryOperators.java) | `OPTIONAL` / `UNION` / `FILTER NOT EXISTS` / `BIND` | Order enrichment exercising the SPARQL algebra over a stream + static catalogue. |

### Complex event processing
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`ComplexEventPattern`](src/main/java/org/cqels/examples/ComplexEventPattern.java) | declarative CEP `FILTER(SEQ(...))` | Detect a temporal sequence — overheat **then** stall — within a window. |
| [`CepQuantifier`](src/main/java/org/cqels/examples/CepQuantifier.java) | CEP quantifier `?e+` | Escalation: a critical alert, then one-or-more retries, then a failure. |

### Query dialects
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`CypherGraphQuery`](src/main/java/org/cqels/examples/CypherGraphQuery.java) | CypherQL `MATCH … RETURN` | Continuous property-graph matching (`MATCH (p:Person)`) over the stream. |

### Reasoning & validation (add-on modules)
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`RdfsReasoning`](src/main/java/org/cqels/examples/RdfsReasoning.java) | RDFS/OWL inference — `cqels-reasoning-rete` | A `rdfs:subClassOf` schema lets a query for `ex:Device` match an `ex:Sensor` instance via inference. |
| [`ShaclValidation`](src/main/java/org/cqels/examples/ShaclValidation.java) | continuous [SHACL](https://www.w3.org/TR/shacl/) — `cqels-shacl` | Validate a stream against shapes; `conforms` flips from `false` to `true` as the required edge arrives. |
| [`AspReasoning`](src/main/java/org/cqels/examples/AspReasoning.java) | Answer-Set Programming — `cqels-asp` | A logic rule derives `colleague(X,Y)` for people sharing an employer (join + inequality). |

### Geospatial (add-on module)
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`GeoSpatialFilter`](src/main/java/org/cqels/examples/GeoSpatialFilter.java) | OGC [GeoSPARQL](https://www.ogc.org/standard/geosparql/) `geof:sfWithin` — `cqels-geo` | Keep only sensor readings whose WKT location falls inside a zone polygon. |

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
