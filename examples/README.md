# CQELS Engine — Getting-Started Examples

Self-contained programs demonstrating the capabilities of the CQELS 2.0 continuous query
engine — from a one-line filter through windows, joins, the SPARQL algebra, CEP, and the
Cypher dialect, to standard-vocabulary (W3C SOSA/SSN, COVESA VSS) scenarios. Each is a
`main()` you can run directly.

All demos except `VehicleSignalsCdsp` share **one coherent world** — a *smart brewery*
modelled in W3C SOSA/SSN: fermentation tanks (`sosa:FeatureOfInterest`) monitored by InkBird
IBS-TH2 sensors (`sosa:Sensor`) emitting `sosa:Observation`s. The shared vocabulary, fixed
entities (Tank1–3 and their sensors), and push helpers live in
[`Brewery.java`](src/main/java/org/cqels/examples/Brewery.java), so the scenarios connect into
one story. `VehicleSignalsCdsp` keeps its real COVESA VSS schema as a contrasting domain.

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
| [`HelloCqels`](src/main/java/org/cqels/examples/HelloCqels.java) | `[NOW]` window + `FILTER` | Alert when a fermentation tank's temperature crosses 28 °C — the minimal continuous query. |

### Windowing & aggregation
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`WindowedAggregation`](src/main/java/org/cqels/examples/WindowedAggregation.java) | `[RANGE 3s]` tumbling + `GROUP BY` | Per-sensor average / count / peak temperature, one row per 3-second window. |
| [`SlidingWindowTrends`](src/main/java/org/cqels/examples/SlidingWindowTrends.java) | `[SLIDE 4s STEP 2s]` + `GROUP BY` | Overlapping trailing-window stats (moving average / min / max) re-emitted every 2s. |
| [`CountWindow`](src/main/java/org/cqels/examples/CountWindow.java) | `[TRIPLES N]` count-based window | Observations per sensor over the most recent N stream triples (not seconds). |
| [`DirectionalWindow`](src/main/java/org/cqels/examples/DirectionalWindow.java) | `[FUTURE 2s … EMIT …]` (LARS) | Forward-looking window with an emission policy (running + final rows). |
| [`GroupConcatSummary`](src/main/java/org/cqels/examples/GroupConcatSummary.java) | `GROUP_CONCAT(…; SEPARATOR=…)` | Per-tank reading count + concatenated observed quantity-kinds per window. |

### Advanced query patterns
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`StreamStaticJoin`](src/main/java/org/cqels/examples/StreamStaticJoin.java) | stream–static lookup join | Enrich each reading with its sensor's tank and that tank's room from a seeded background graph (static patterns outside `STREAM {}`). |
| [`AdvancedQueryOperators`](src/main/java/org/cqels/examples/AdvancedQueryOperators.java) | `OPTIONAL` / `UNION` / `FILTER NOT EXISTS` / `BIND` | Reading enrichment exercising the SPARQL algebra over the observation stream + static brewery graph. |

### Complex event processing
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`ComplexEventPattern`](src/main/java/org/cqels/examples/ComplexEventPattern.java) | declarative CEP `FILTER(SEQ(...))` | Detect a fermentation incident — an overheat alert **then** a foaming alert — within a window. |
| [`CepQuantifier`](src/main/java/org/cqels/examples/CepQuantifier.java) | CEP quantifier `?e+` | Escalation: an overheat, then one-or-more pressure rises, then foaming. |

### Query dialects
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`CypherGraphQuery`](src/main/java/org/cqels/examples/CypherGraphQuery.java) | CypherQL `MATCH … RETURN` | Continuous property-graph matching (`MATCH (o:Observation)`) over the brewery stream. |

### Reasoning & validation (add-on modules)
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`RdfsReasoning`](src/main/java/org/cqels/examples/RdfsReasoning.java) | RDFS/OWL inference — `cqels-reasoning-rete` | `sensor:IBS-TH2-Plus-T rdfs:subClassOf sosa:Sensor` lets a query for `sosa:Sensor` match the IBS-TH2 instance via inference. |
| [`ShaclValidation`](src/main/java/org/cqels/examples/ShaclValidation.java) | continuous [SHACL](https://www.w3.org/TR/shacl/) — `cqels-shacl` | Validate a stream against shapes; `conforms` flips from `false` to `true` as the required edge arrives. |
| [`AspReasoning`](src/main/java/org/cqels/examples/AspReasoning.java) | Answer-Set Programming — `cqels-asp` | A logic rule derives `monitors(Sensor,Tank)` from each observation's `madeBySensor` + `hasFeatureOfInterest`. |

### Geospatial (add-on module)
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`GeoSpatialFilter`](src/main/java/org/cqels/examples/GeoSpatialFilter.java) | OGC [GeoSPARQL](https://www.ogc.org/standard/geosparql/) `geof:sfWithin` — `cqels-geo` | Keep only tanks whose WKT location falls inside the cellar-zone polygon. |

### Standard vocabularies & domains
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`SosaObservations`](src/main/java/org/cqels/examples/SosaObservations.java) | W3C [SOSA/SSN](https://www.w3.org/TR/vocab-ssn/) + multi-pattern stream join | Average temperature per fermentation tank, joining `hasFeatureOfInterest` + `observedProperty` + `hasSimpleResult` on each `sosa:Observation`. |
| [`VehicleSignalsCdsp`](src/main/java/org/cqels/examples/VehicleSignalsCdsp.java) | COVESA [VSS](https://covesa.global/) (CDSP) + `GROUP BY` + `HAVING` | Per-vehicle speed over a window; emit only vehicles that were speeding. |

## Adapting them

- Change the CQELS-QL string to try other windows (`[TRIPLES N]`), aggregates
  (`SUM`, `MIN`), or clauses (`HAVING`, `ORDER BY`, `LIMIT`).
- `engine.createStream(name)` returns a `DataStream` with several `push(...)`
  overloads (RDF `Statement`, `(subject, predicate, value)` with `String`/`double`/
  `long`/`IRI` objects, and explicit-timestamp variants).
- Bump `<cqels.version>` in [`pom.xml`](pom.xml) to track new releases.
