# CQELS Engine — Getting-Started Examples

Self-contained programs demonstrating the capabilities of the CQELS 2.0 continuous query
engine — from a one-line filter through windows, joins, the SPARQL algebra, CEP, and the
Cypher dialect, to standard-vocabulary (W3C SOSA/SSN, VSSo, COVESA VSS) scenarios. Each is a
`main()` you can run directly.

## One coherent world: a smart EV fleet / V2G depot

Every demo tells one story: a depot operates a small fleet of electric vehicles streaming
telemetry — speed, battery state-of-charge, location, steering, charge power. Each reading is
wrapped as a `sosa:Observation` whose `observedProperty` is a COVESA VSS signal (e.g.
`vss:Speed`, `vss:Powertrain.TractionBattery.StateOfCharge.Current`), whose
`hasFeatureOfInterest` is the vehicle (a `vsso:Vehicle`), and which carries a numeric
`hasSimpleResult` value. The model layers **W3C SOSA/SSN → VSSo → COVESA VSS** for richer
semantics, and vehicle ids are pseudonymous asset ids (EV-7Q2 / EV-3K8 / EV-9TZ), never plates.

Shared vocabulary, the canonical observation push, and the seeded static context (depot,
charging stations, geofenced zones, drivers, GTFS-style service assignments) live in
[`Fleet.java`](src/main/java/org/cqels/examples/Fleet.java) so the demos form a single
connected world rather than ad-hoc per-demo data. The domain models a smart electric-vehicle fleet /
vehicle-to-grid (V2G) scenario.

> **Reading the output:** CQELS is a *continuous* engine — it re-evaluates a query's window as each
> element arrives and re-emits the current results (RStream-style). Because each observation is pushed
> as several triples, you'll see a result row or aggregate **printed multiple times** as a reading
> streams in, and counts climb while the window fills. That repetition is the engine working, not a
> bug; the **latest / distinct values** are the answer. The demos print every emission so the dynamics
> are visible.

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
| [`HelloCqels`](src/main/java/org/cqels/examples/HelloCqels.java) | `[NOW]` window + `FILTER` | Low-battery alert: raise an alert whenever a vehicle's state-of-charge drops below 20 % — the minimal continuous query. |

### Windowing & aggregation
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`WindowedAggregation`](src/main/java/org/cqels/examples/WindowedAggregation.java) | `[RANGE 3s]` tumbling + `GROUP BY` | Per-vehicle average / peak speed and sample count, one row per 3-second window. |
| [`SlidingWindowTrends`](src/main/java/org/cqels/examples/SlidingWindowTrends.java) | `[SLIDE 4s STEP 2s]` + `GROUP BY` | Per-vehicle moving state-of-charge (battery-drain) trend — average / min / max re-emitted every 2s. |
| [`CountWindow`](src/main/java/org/cqels/examples/CountWindow.java) | `[TRIPLES 30]` count-based window | Observations per vehicle over the most recent stream triples (the last N readings, not seconds). |
| [`DirectionalWindow`](src/main/java/org/cqels/examples/DirectionalWindow.java) | `[FUTURE 2s EMIT EARLY_AND_FINAL]` (LARS) | Forward-looking charge-reading counts per vehicle during a V2G charging burst (running + final rows). |
| [`GroupConcatSummary`](src/main/java/org/cqels/examples/GroupConcatSummary.java) | `GROUP_CONCAT(…; SEPARATOR=…)` | Per-vehicle observation count + the list of observed VSS signals per 3-second window. |

### Advanced query patterns
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`StreamStaticJoin`](src/main/java/org/cqels/examples/StreamStaticJoin.java) | stream–static lookup join | Enrich each speed reading with the vehicle's assigned driver and GTFS-style service route from the seeded fleet graph (static patterns outside `STREAM {}`). |
| [`AdvancedQueryOperators`](src/main/java/org/cqels/examples/AdvancedQueryOperators.java) | `OPTIONAL` / `UNION` / `FILTER NOT EXISTS` / `BIND` | Enrich each speed reading against the static fleet graph: OPTIONAL battery state-of-health, a UNION supervisor-or-backup contact, FILTER NOT EXISTS to drop vehicles in maintenance, and BIND for speed headroom. |

### Complex event processing
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`ComplexEventPattern`](src/main/java/org/cqels/examples/ComplexEventPattern.java) | declarative CEP `FILTER(SEQ(...))` | Road-rage detection: a sharp speed **drop** then a sharp speed **spike** within a window (order matters). |
| [`CepQuantifier`](src/main/java/org/cqels/examples/CepQuantifier.java) | CEP quantifier `?e+` | Impaired driving: a speed drop, then one-or-more lane-weaves, then a speed spike — a variable-length sequence. |

### Query dialects
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`CypherGraphQuery`](src/main/java/org/cqels/examples/CypherGraphQuery.java) | CypherQL `MATCH … RETURN` | Continuous property-graph matching (`MATCH (o:Observation) RETURN o`) over the telemetry stream. |

### Reasoning & validation (add-on modules)
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`RdfsReasoning`](src/main/java/org/cqels/examples/RdfsReasoning.java) | RDFS/OWL inference — `cqels-reasoning-rete` | An `ex:DepotVehicle rdfs:subClassOf vsso:Vehicle` schema lets a query for `vsso:Vehicle` match the EV via inference. |
| [`ShaclValidation`](src/main/java/org/cqels/examples/ShaclValidation.java) | continuous [SHACL](https://www.w3.org/TR/shacl/) — `cqels-shacl` | Require every `sosa:Observation` to carry a result; `conforms` flips from `false` to `true` as the result arrives for the same observation. |
| [`AspReasoning`](src/main/java/org/cqels/examples/AspReasoning.java) | Answer-Set Programming — `cqels-asp` | A logic rule derives `convoy(V1,V2)` for two distinct vehicles reporting telemetry together (join + inequality). |

### Geospatial (add-on module)
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`GeoSpatialFilter`](src/main/java/org/cqels/examples/GeoSpatialFilter.java) | OGC [GeoSPARQL](https://www.ogc.org/standard/geosparql/) `geof:sfWithin` — `cqels-geo` | Keep only readings whose WKT location falls inside the depot geofence — the vehicles currently in the depot zone. |

### Standard vocabularies & domains
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`SosaObservations`](src/main/java/org/cqels/examples/SosaObservations.java) | W3C [SOSA/SSN](https://www.w3.org/TR/vocab-ssn/) + multi-pattern stream join | Average / count per (vehicle × observed VSS signal) — the `observedProperty` keeps speed and battery readings apart. |
| [`VehicleSignalsCdsp`](src/main/java/org/cqels/examples/VehicleSignalsCdsp.java) | COVESA [VSS](https://covesa.global/) (CDSP) + `GROUP BY` + `HAVING` | The flagship query: per-vehicle top/average speed over a window, emitting only vehicles that were speeding. |

## Adapting them

- Change the CQELS-QL string to try other windows (`[TRIPLES N]`), aggregates
  (`SUM`, `MIN`), or clauses (`HAVING`, `ORDER BY`, `LIMIT`).
- `engine.createStream(name)` returns a `DataStream` with several `push(...)`
  overloads (RDF `Statement`, `(subject, predicate, value)` with `String`/`double`/
  `long`/`IRI` objects, and explicit-timestamp variants).
- Bump `<cqels.version>` in [`pom.xml`](pom.xml) to track new releases.
