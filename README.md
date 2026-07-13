# CQELS 2.0 — Standalone CQELS Engine in Java

**CQELS** (Continuous Query Evaluation over Linked Streams) is a native engine for
continuous querying and reasoning over high-throughput RDF / graph streams. This is
the next generation of the [CQELS engine prototyped in 2013](https://github.com/cqels/CQELS-1.x),
rebuilt for edge-to-cloud deployments — code name **COSMO**.

> **Latest release:** `2.0.0-alpha.11` · **License:** MIT · **Requires:** JDK 17+
>
> **New here? → [GETTING_STARTED.md](GETTING_STARTED.md)** &nbsp;·&nbsp; **Runnable demos → [examples/](examples/)**

---

## What it does

- **CQELS-QL** — SPARQL-style continuous queries over streams, with time- and
  count-based windows (`[NOW]`, `[RANGE]`, `[SLIDE … STEP …]`, `[TRIPLES]`) and
  directional (LARS-style) windows.
- **Cypher** — continuous property-graph pattern matching over the same streams.
- **Complex Event Processing** — declarative sequences via `FILTER(SEQ(…))` with
  quantifiers and negation, plus a programmatic pattern API.
- **Reasoning & validation** — RDFS/OWL inference (RETE), SHACL validation, and
  Answer-Set Programming over windowed streams.
- **Geospatial** — GeoSPARQL spatial relations with R-tree (JTS) indexing.
- **Extensible functions** — call user-defined functions by IRI in queries (SPARQL 1.1 §17.6);
  `cqels-functions-ext` ships reference functions (`cqfn:haversine`, `cqfn:levenshtein`) that
  self-register via `ServiceLoader` — on the classpath, no Java glue.
- **Aggregation & joins** — incremental (F-IVM) and parallel windowed aggregation,
  multi-way stream joins, and stream + static-graph composition.
- **Durable & embeddable** — pluggable storage backends (LMDB, RocksDB, IoTDB) with
  event-journal recovery; runs on ARM hardware (Raspberry Pi, Jetson) as well as servers.
- **Standards-based & interoperable** — plain RDF with [SPARQL 1.1](https://www.w3.org/TR/sparql11-query/)
  semantics; works out of the box with standard vocabularies such as W3C
  [SOSA/SSN](https://www.w3.org/TR/vocab-ssn/) (sensor observations) and
  [COVESA VSS](https://covesa.global/) (connected-vehicle signals).

See **[Query language and standards](#query-language-and-standards)** for the full list of
specifications CQELS builds on and aligns with.

---

## Quick start

Run a continuous query in under a minute (full setup, including the one-time GitHub
Packages token, is in **[GETTING_STARTED.md](GETTING_STARTED.md)**):

```bash
git clone https://github.com/cqels/CQELS4J.git
cd CQELS4J/examples
mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.HelloCqels
```

```java
// Smart EV fleet: alert when a vehicle's battery state-of-charge drops below 20 %.
try (CQELSEngine engine = CQELSEngine.builder().withMemoryStore().build()) {
    DataStream telemetry = engine.createStream("Telemetry");

    engine.registerCqelsQuery("""
        PREFIX sosa: <http://www.w3.org/ns/sosa/>
        REGISTER QUERY LowBattery AS
        SELECT ?obs ?soc
        FROM STREAM Telemetry [NOW]
        WHERE { STREAM Telemetry { ?obs sosa:hasSimpleResult ?soc . } FILTER(?soc < 20) }
        """, row -> System.out.println("LOW BATTERY: " + row));

    engine.start();
    telemetry.push("https://example.org/fleet/obs/1",
                   "http://www.w3.org/ns/sosa/hasSimpleResult", 18.0);
    Thread.sleep(500);
}
```

### Use it as a dependency

```xml
<repositories>
  <repository>
    <id>github-cqels</id>
    <url>https://maven.pkg.github.com/cqels/CQELS4J</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>org.cqels</groupId>
    <artifactId>cqels-engine</artifactId>
    <version>2.0.0-alpha.11</version>
  </dependency>
</dependencies>
```

GitHub Packages requires a token with `read:packages` — see
[GETTING_STARTED.md §2](GETTING_STARTED.md#2-authenticate-to-github-packages-one-time).

---

## Demonstration scenarios

Runnable, verified demos in [`examples/`](examples/), grouped by use-case category —
new scenarios can be added under the matching heading.

All demos share **one coherent world**: a *smart electric-vehicle fleet / V2G depot*, layered
W3C [SOSA/SSN](https://www.w3.org/TR/vocab-ssn/) → [VSSo](https://www.w3.org/TR/vsso/) →
[COVESA VSS](https://covesa.global/project/vehicle-signal-specification/). Vehicles stream
telemetry — speed, battery state-of-charge, location, charge power — as `sosa:Observation`s whose
`observedProperty` is a VSS signal, joined against a static fleet graph (depot, charging stations,
geofenced zones, drivers, GTFS-style service duties). The shared vocabulary, fixed entities, and
push helpers live in [`Fleet.java`](examples/src/main/java/org/cqels/examples/Fleet.java), so the
examples connect into a single story rather than ad-hoc per-demo data. (The domain models a smart
electric-vehicle fleet / vehicle-to-grid (V2G) scenario.)

**Basics**
| Demo | Feature |
|------|---------|
| [`HelloCqels`](examples/src/main/java/org/cqels/examples/HelloCqels.java) | `[NOW]` window + `FILTER` — the minimal continuous query |

**Windowing & aggregation**
| Demo | Feature |
|------|---------|
| [`WindowedAggregation`](examples/src/main/java/org/cqels/examples/WindowedAggregation.java) | tumbling `[RANGE]` + `GROUP BY` aggregates |
| [`SlidingWindowTrends`](examples/src/main/java/org/cqels/examples/SlidingWindowTrends.java) | sliding `[SLIDE … STEP …]` windows |
| [`CountWindow`](examples/src/main/java/org/cqels/examples/CountWindow.java) | count-based `[TRIPLES N]` window |
| [`DirectionalWindow`](examples/src/main/java/org/cqels/examples/DirectionalWindow.java) | directional/LARS `[FUTURE … EMIT …]` window |
| [`DirectionalMultiPatternJoin`](examples/src/main/java/org/cqels/examples/DirectionalMultiPatternJoin.java) | `[FUTURE …]` + 2-pattern star join (opt-in, alpha.7) |
| [`GroupConcatSummary`](examples/src/main/java/org/cqels/examples/GroupConcatSummary.java) | `GROUP_CONCAT` string aggregation |

**Advanced query patterns**
| Demo | Feature |
|------|---------|
| [`StreamStaticJoin`](examples/src/main/java/org/cqels/examples/StreamStaticJoin.java) | stream–static lookup join (static patterns outside `STREAM {}`) |
| [`AdvancedQueryOperators`](examples/src/main/java/org/cqels/examples/AdvancedQueryOperators.java) | `OPTIONAL` / `UNION` / `FILTER NOT EXISTS` / `BIND` |

**Complex event processing**
| Demo | Feature |
|------|---------|
| [`ComplexEventPattern`](examples/src/main/java/org/cqels/examples/ComplexEventPattern.java) | declarative CEP `FILTER(SEQ(…))` sequence detection |
| [`CepQuantifier`](examples/src/main/java/org/cqels/examples/CepQuantifier.java) | CEP quantifier `?e+` (one-or-more) |

**Advanced CDSP analytics & CEP** (`org.cqels.examples.cdsp`)
| Demo | Feature | Scenario |
|------|---------|----------|
| [`CorrelatedFaultCascade`](examples/src/main/java/org/cqels/examples/cdsp/CorrelatedFaultCascade.java) | CEP multi-triple (reified) events + cross-event `STR()` correlation filters + `FILTER(SEQ(?e1; ?e2; ?e3))` | Fault cascade: three different subsystem alerts from the same vehicle within 15 s → send it to the depot; the same alerts spread across two vehicles stay silent. |
| [`DriverAttentionWatchdog`](examples/src/main/java/org/cqels/examples/cdsp/DriverAttentionWatchdog.java) | CEP negated sequence step `FILTER(SEQ(?e1; NOT ?e2; ?e3))` + same-vehicle pairing guard | Driver-attention watchdog: fast driving, then no braking reading before the next fast reading → attention alert. |
| [`SuddenSwerveDetector`](examples/src/main/java/org/cqels/examples/cdsp/SuddenSwerveDetector.java) | Two-reading temporal self-join in one `[RANGE 3s]` window + `BIND(ABS(…))` delta | Sudden-swerve incident: the steering wheel swings > 90° between two readings while above 50 km/h. |
| [`WetRoadBraking`](examples/src/main/java/org/cqels/examples/cdsp/WetRoadBraking.java) | Context-gated self-join (rain triple gates the reading pair) | Hard braking specifically on a wet road: a > 20 km/h drop fires only when it was raining as braking began. |
| [`FleetRiskLeaderboard`](examples/src/main/java/org/cqels/examples/cdsp/FleetRiskLeaderboard.java) | Windowed `GROUP BY` + `COUNT(*)`/`AVG` over a multi-pattern join + `HAVING` floor | Rolling per-vehicle risk score: speeding / violent-steering violations per 10 s window. |
| [`LiveEfficiencyTicker`](examples/src/main/java/org/cqels/examples/cdsp/LiveEfficiencyTicker.java) | `[SLIDE 3s STEP 1s]` sliding window + `GROUP BY` over two co-bound metrics | Live per-vehicle efficiency ticker: trailing average battery power vs speed. |

**Query dialects**
| Demo | Feature |
|------|---------|
| [`CypherGraphQuery`](examples/src/main/java/org/cqels/examples/CypherGraphQuery.java) | CypherQL `MATCH … RETURN` over a stream |

**Reasoning & validation** (add-on modules)
| Demo | Feature |
|------|---------|
| [`RdfsReasoning`](examples/src/main/java/org/cqels/examples/RdfsReasoning.java) | RDFS/OWL inference over a stream (`cqels-reasoning-rete`) |
| [`ShaclValidation`](examples/src/main/java/org/cqels/examples/ShaclValidation.java) | continuous [SHACL](https://www.w3.org/TR/shacl/) validation (`cqels-shacl`) |
| [`AspReasoning`](examples/src/main/java/org/cqels/examples/AspReasoning.java) | Answer-Set Programming rules (`cqels-asp`) |

**Reasoning showcase** (`org.cqels.examples.reasoning`)
| Demo | Feature | Scenario |
|------|---------|----------|
| [`TaxonomyEntailment`](examples/src/main/java/org/cqels/examples/reasoning/TaxonomyEntailment.java) | Multi-hop RDFS entailment — subclass-chain + type lifting + sub-property propagation (`cqels-reasoning-rete`) | A two-level depot taxonomy: queries against the top class and super-property still find a vehicle registered only at the bottom. |
| [`BoundedTransitiveClosure`](examples/src/main/java/org/cqels/examples/reasoning/BoundedTransitiveClosure.java) | Recursive (transitive-closure) inference with a work bound — `enableRecursiveInference` + `maxRecursionDepth` | A 4-level site containment chain: one transitive rule derives the long-range containments under a depth cap. |
| [`RetractableInference`](examples/src/main/java/org/cqels/examples/reasoning/RetractableInference.java) | Opt-in truth maintenance — `enableTruthMaintenance(true)` + `ReteNetwork.retract(...)` (`cqels-reasoning-rete`) | An overheat alert derived from a coolant reading is withdrawn when the reading is retracted. |
| [`MissionPreservationAsp`](examples/src/main/java/org/cqels/examples/reasoning/MissionPreservationAsp.java) | ASP negation-as-failure default rule (`cqels-asp`) | Mission preservation: a low-SoC vehicle is only "at risk" if it is not currently charging. |
| [`PersistentViolationAsp`](examples/src/main/java/org/cqels/examples/reasoning/PersistentViolationAsp.java) | ASP temporal persistence with stratified negation-as-failure (`cqels-asp`) | Persistent depot-zone speeding: flagged only on the 3rd consecutive over-limit reading. |

**Geospatial** (add-on module)
| Demo | Feature |
|------|---------|
| [`GeoSpatialFilter`](examples/src/main/java/org/cqels/examples/GeoSpatialFilter.java) | OGC [GeoSPARQL](https://www.ogc.org/standard/geosparql/) `geof:sfWithin` (`cqels-geo`) |

**Extension functions** (add-on module)
| Demo | Feature |
|------|---------|
| [`ChargerRangeFilter`](examples/src/main/java/org/cqels/examples/ChargerRangeFilter.java) | user-defined function by IRI (SPARQL 1.1 §17.6, alpha.11) — `cqels-functions-ext`, e.g. `cqfn:haversine(...)` |

**Standard vocabularies & domains**
| Demo | Feature |
|------|---------|
| [`SosaObservations`](examples/src/main/java/org/cqels/examples/SosaObservations.java) | W3C [SOSA/SSN](https://www.w3.org/TR/vocab-ssn/) + [VSSo](https://www.w3.org/TR/vsso/) + VSS: per (vehicle × observed signal) averages |
| [`VehicleSignalsCdsp`](examples/src/main/java/org/cqels/examples/VehicleSignalsCdsp.java) | flagship COVESA [VSS](https://covesa.global/) speeding detection + `GROUP BY` / `HAVING` |

---

## Use CQELS as an MCP server

[`mcp-server/`](mcp-server/) is a self-contained [Model Context Protocol](https://modelcontextprotocol.io/)
server that exposes a CQELS engine as **AI-accessible tools** over stdio — static memory
(`store_fact`, `query`, `recall`), agent-memory patterns (episodic `record_event`/
`recall_episodes`, procedural `save_procedure`/`run_procedure`, working-memory
`assemble_context`), the **streaming** engine (`push_event`,
`register_stream_query`, `poll_results`, `unregister_stream_query`), and intent-shaped
capability tools (`detect_sequence` for CEP event-pattern matching, `define_subclass` for
RDFS reasoning). So an MCP client such as Claude Desktop can run the same continuous
windows/aggregates/CEP/reasoning the [`examples/`](examples/) demonstrate. Its core dependencies
are the published `cqels-engine` (+ `cqels-reasoning-rete` for the reasoning tool) and the MCP Java
SDK (plus an `slf4j-simple` binding and a `jackson-annotations` pin — see the pom).

```bash
cd mcp-server
mvn -q package          # -> target/cqels-mcp-server.jar
```

Then point your MCP client at `java -jar …/cqels-mcp-server.jar` — see
[`mcp-server/README.md`](mcp-server/README.md) for the Claude Desktop configuration and a
smoke test.

---

## Query language and standards

**CQELS-QL** is a continuous-query language: [SPARQL 1.1](https://www.w3.org/TR/sparql11-query/)
graph patterns extended with stream windows
(`FROM STREAM … [NOW | RANGE Ns | SLIDE Ns STEP Ms | TRIPLES N]`), directional (LARS) windows,
aggregation (`GROUP BY` / `HAVING`), and declarative complex-event patterns (`FILTER(SEQ(…))`).
Continuous property-graph queries use Cypher (CypherQL).

📘 **Full language reference: [`CQELS-QL_SPEC.md`](CQELS-QL_SPEC.md)** — focuses on the streaming
extensions over SPARQL (windows, stream–static joins, CEP; named windows are parsed but not yet
executed — see the spec), with grammar and examples.
The [examples/](examples/) are runnable counterparts.

CQELS builds on and interoperates with these standards:

| Area | Standard / vocabulary |
|------|-----------------------|
| Graph query | [SPARQL 1.1](https://www.w3.org/TR/sparql11-query/) |
| RDF stream processing | [RSP-QL — W3C RSP Community Group](https://www.w3.org/community/rsp/) † |
| Quoted triples | [RDF-star / SPARQL-star](https://www.w3.org/2021/12/rdf-star.html) † |
| Property graphs | [openCypher](https://opencypher.org/) |
| Sensor observations | [W3C SOSA/SSN](https://www.w3.org/TR/vocab-ssn/) |
| Connected-vehicle signals | [COVESA VSS](https://covesa.global/) |
| Geospatial | [OGC GeoSPARQL](https://www.ogc.org/standard/geosparql/) |

† RSP-QL surface syntax and RDF-star/SPARQL-star are active alignment targets — see **Roadmap**.

---

## Roadmap

CQELS 2.0 (COSMO) is under active development toward a public release, aligned with the
[SmartEdge](https://smart-edge.eu/) project's open-source timeline; the current line is
the `2.0.0-alpha` series ([latest release](https://github.com/cqels/CQELS4J/releases)).
Headline goals:

1. Run on ARM hardware (Raspberry Pi, Jetson Nano, …) as well as servers.
2. Support the recommended syntaxes of the [RSP Community Group](https://www.w3.org/community/rsp/)
   and RDF\*/SPARQL\*.
3. Support Cypher and CEP operators alongside CQELS-QL.

---

## License

CQELS is released as an open-science artifact under the **[MIT License](LICENSE)**.

## Funding & acknowledgments

CQELS 2.0 (COSMO) is funded by DFG, BMBF, Horizon Europe and Chips-JU across the
[COSMO](https://gepris.dfg.de/gepris/projekt/453130567?language=en) (DFG, project
453130567), [BIFOLD](https://bifold.berlin/), [SmartEdge](https://smart-edge.eu/)
(EU Horizon Europe, grant agreement 101092908), [AIoTwin](https://aiotwin.eu/) and
[SMARTY](https://www.smarty-project.eu/) projects. Before that, the team was funded by
EU and Irish funding agencies across more than ten projects over the past twelve years.
