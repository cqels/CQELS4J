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
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.DirectionalMultiPatternJoin
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
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.ChargerRangeFilter

# Advanced CDSP analytics & CEP (org.cqels.examples.cdsp):
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.cdsp.CorrelatedFaultCascade
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.cdsp.DriverAttentionWatchdog
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.cdsp.SuddenSwerveDetector
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.cdsp.WetRoadBraking
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.cdsp.FleetRiskLeaderboard
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.cdsp.LiveEfficiencyTicker

# Reasoning showcase (org.cqels.examples.reasoning):
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.reasoning.TaxonomyEntailment
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.reasoning.BoundedTransitiveClosure
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.reasoning.RetractableInference
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.reasoning.MissionPreservationAsp
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.reasoning.PersistentViolationAsp
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
| [`DirectionalMultiPatternJoin`](src/main/java/org/cqels/examples/DirectionalMultiPatternJoin.java) | `[FUTURE 2s]` + 2-pattern star join (opt-in, alpha.7) | V2G readiness: join each vehicle's SoC and charge-power readings observed inside the same forward window — behind `cqels.directional.multiPatternSelect=true` (set in `main()`); rows surface per closed window. |
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

### Advanced CDSP analytics & CEP (`org.cqels.examples.cdsp`)

Deeper query shapes adapted from the COVESA CDSP vehicle-signal scenario suite — multi-triple
CEP events with cross-event correlation, a negated sequence step, two-reading temporal
self-joins, and heavier windowed analytics. The detector demos push both a matching scenario
and a counter-example that must stay silent, so the discriminating power of each construct is
visible; the ticker instead demonstrates window eviction (the run outlasts the window).

| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`CorrelatedFaultCascade`](src/main/java/org/cqels/examples/cdsp/CorrelatedFaultCascade.java) | CEP multi-triple (reified) events + cross-event `STR()` correlation filters + `FILTER(SEQ(?e1; ?e2; ?e3))` | Fault cascade: three **different** subsystem alerts (inverter over-temp, brake wear, tire pressure) from the **same** vehicle within 15 s → send it to the depot; the same alerts spread across two vehicles stay silent. |
| [`DriverAttentionWatchdog`](src/main/java/org/cqels/examples/cdsp/DriverAttentionWatchdog.java) | CEP **correlated** negated sequence step `FILTER(SEQ(?e1; NOT ?e2; ?e3))` + cross-event `STR()` guards on the fast readings *and* on the negated step (guards on negated steps are honored since CQELS 2.0.0-alpha.13) | Driver-attention watchdog: fast (> 80 km/h), then **no** braking reading (< 40) *by the same vehicle* before the next fast reading (> 60) → attention alert; the vehicle's own hard-brake in the gap keeps it quiet, a *different* vehicle braking in the gap no longer suppresses the alert, and two fast readings from *different* vehicles cannot pair. |
| [`SuddenSwerveDetector`](src/main/java/org/cqels/examples/cdsp/SuddenSwerveDetector.java) | Two-reading temporal self-join in one `[RANGE 3s]` window + `BIND(ABS(…))` delta + compound `FILTER` | Sudden-swerve incident: the steering wheel swings > 90° between two readings while above 50 km/h — a cruise wiggle and a hard swing at parking speed both stay silent. |
| [`WetRoadBraking`](src/main/java/org/cqels/examples/cdsp/WetRoadBraking.java) | Context-gated self-join: the deceleration pair counts only when a rain triple accompanies the first reading | Hard braking specifically on a wet road: a > 20 km/h drop fires only when it was raining as braking began — the identical drop on a dry road stays silent. |
| [`FleetRiskLeaderboard`](src/main/java/org/cqels/examples/cdsp/FleetRiskLeaderboard.java) | Windowed `GROUP BY` + `COUNT(*)`/`AVG` over a multi-pattern reading join, compound `OR` `FILTER`, `HAVING` floor | Rolling per-vehicle risk score: speeding / violent-steering violations per 10 s window — the hard-driven EV tops the board, a two-slip vehicle is gated out by `HAVING`, the clean one never groups. |
| [`LiveEfficiencyTicker`](src/main/java/org/cqels/examples/cdsp/LiveEfficiencyTicker.java) | `[SLIDE 3s STEP 1s]` sliding window + `GROUP BY` over two co-bound metrics on a per-tick reading node | Live per-vehicle efficiency ticker: trailing average battery power vs speed — the hard-driven EV draws ~3× the power of the economical one at only ~2× the speed, the depot-parked EV shows positive (V2G charging) power at 0 km/h, and early ticks demonstrably age out of the window. |

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

> alpha.7 also ships an opt-in *warm parse-cache* ASP solver backend (`WarmParseCacheAspSolverBackend`,
> parses the base program once and reuses it across continuous solves) — it is engine-API opt-in via the
> 5-arg `AspContinuousQuery` constructor and not yet reachable through the `CQELSEngine` facade these
> examples use, so there is no demo of it here.

### Reasoning showcase (`org.cqels.examples.reasoning`)

Deeper reasoning shapes across the RETE (`cqels-reasoning-rete`) and ASP (`cqels-asp`) add-ons —
multi-hop entailment, bounded recursion, truth maintenance, and negation-as-failure defaults.
Each demo pairs the derivation with a case that must NOT derive, so the semantics are visible.

| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`TaxonomyEntailment`](src/main/java/org/cqels/examples/reasoning/TaxonomyEntailment.java) | Multi-hop RDFS entailment — rdfs11 subclass-chain transitivity + rdfs9 type lifting + rdfs7 sub-property propagation (`cqels-reasoning-rete`) | Two-level depot taxonomy + a property axiom: EV-3K8 registers only at the bottom class and reports only the specific property, yet queries against the TOP class and SUPER property still find it; a class with no axiom into the taxonomy stays invisible. |
| [`BoundedTransitiveClosure`](src/main/java/org/cqels/examples/reasoning/BoundedTransitiveClosure.java) | Recursive (transitive-closure) inference with a work bound — `enableRecursiveInference` + `maxRecursionDepth` and an observable truncation counter | A 4-level site containment chain streamed as direct `ex:partOf` edges: one transitive rule derives the long-range containments; a disconnected edge derives nothing. Dedup reaches the fixpoint naturally; the depth cap is a hard ceiling on cascade work. |
| [`RetractableInference`](src/main/java/org/cqels/examples/reasoning/RetractableInference.java) | Opt-in truth maintenance — `enableTruthMaintenance(true)` + `ReteNetwork.retract(...)` (`cqels-reasoning-rete`) | An over-95 °C coolant reading derives an overheat alert; retracting the glitched reading also withdraws the alert — working memory before/after shows assert-then-retract ≡ never-asserted. |
| [`MissionPreservationAsp`](src/main/java/org/cqels/examples/reasoning/MissionPreservationAsp.java) | ASP negation-as-failure default rule (`cqels-asp`): `mission_at_risk(V) :- needs_charge(V), not charging(V)` | Mission preservation: SoC below the vehicle's next-duty reserve derives `needs_charge`, but the mission is only at risk if the EV is **not** plugged in — the charging vehicle needs charge yet never goes at-risk. |
| [`PersistentViolationAsp`](src/main/java/org/cqels/examples/reasoning/PersistentViolationAsp.java) | ASP temporal persistence with two-level stratified negation-as-failure (`cqels-asp`) | Persistent depot-zone speeding: flagged only on the 3rd **consecutive** over-limit reading; an interleaved vehicle whose compliant reading breaks every chain is never flagged. |

### Geospatial (add-on module)
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`GeoSpatialFilter`](src/main/java/org/cqels/examples/GeoSpatialFilter.java) | OGC [GeoSPARQL](https://www.ogc.org/standard/geosparql/) `geof:sfWithin` — `cqels-geo` | Keep only readings whose WKT location falls inside the depot geofence — the vehicles currently in the depot zone. |

### Extension functions (add-on module)
| Class | CQELS feature | Scenario |
|-------|---------------|----------|
| [`ChargerRangeFilter`](src/main/java/org/cqels/examples/ChargerRangeFilter.java) | user-defined function by IRI (SPARQL 1.1 §17.6, alpha.11) — `cqels-functions-ext` | Call `urn:cqels:fn:haversine(...)` from the query to keep only vehicles within 5 km of the depot charge hub. The reference function self-registers via `ServiceLoader` — on the classpath, no Java glue. |

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
