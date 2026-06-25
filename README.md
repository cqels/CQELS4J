# CQELS 2.0 — Standalone CQELS Engine in Java

**CQELS** (Continuous Query Evaluation over Linked Streams) is a native engine for
continuous querying and reasoning over high-throughput RDF / graph streams. This is
the next generation of the [CQELS engine prototyped in 2013](https://github.com/cqels/CQELS-1.x),
rebuilt for edge-to-cloud deployments — code name **COSMO**.

> **Latest release:** `2.0.0-alpha.3` · **License:** MIT · **Requires:** JDK 17+
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
- **Aggregation & joins** — incremental (F-IVM) and parallel windowed aggregation,
  multi-way stream joins, and stream + static-graph composition.
- **Durable & embeddable** — pluggable storage backends (LMDB, RocksDB, IoTDB) with
  event-journal recovery; runs on ARM hardware (Raspberry Pi, Jetson) as well as servers.
- **Standards-based & interoperable** — plain RDF with [SPARQL 1.1](https://www.w3.org/TR/sparql11-query/)
  semantics; works out of the box with standard vocabularies such as W3C
  [SOSA/SSN](https://www.w3.org/TR/vocab-ssn/) (sensor observations) and
  [COVESA VSS](https://covesa.global/) (connected-vehicle signals).

See **[Query language & standards](#query-language--standards)** for the full list of
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
try (CQELSEngine engine = CQELSEngine.builder().withMemoryStore().build()) {
    DataStream sensors = engine.createStream("Sensors");

    engine.registerCqelsQuery("""
        PREFIX ex: <http://example.org/>
        REGISTER QUERY HighTemperature AS
        SELECT ?sensor ?temp
        FROM STREAM Sensors [NOW]
        WHERE { STREAM Sensors { ?sensor ex:temperature ?temp . } FILTER(?temp > 30) }
        """, row -> System.out.println("ALERT: " + row));

    engine.start();
    sensors.push("http://example.org/sensor/1", "http://example.org/temperature", 35.6);
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
    <version>2.0.0-alpha.3</version>
  </dependency>
</dependencies>
```

GitHub Packages requires a token with `read:packages` — see
[GETTING_STARTED.md §2](GETTING_STARTED.md#2-authenticate-to-github-packages-one-time).

---

## Demonstration scenarios

Runnable, verified demos in [`examples/`](examples/), grouped by use-case category —
new scenarios can be added under the matching heading.

**Basics**
| Demo | Feature |
|------|---------|
| [`HelloCqels`](examples/src/main/java/org/cqels/examples/HelloCqels.java) | `[NOW]` window + `FILTER` — the minimal continuous query |

**Windowing & aggregation**
| Demo | Feature |
|------|---------|
| [`WindowedAggregation`](examples/src/main/java/org/cqels/examples/WindowedAggregation.java) | tumbling `[RANGE]` + `GROUP BY` aggregates |
| [`SlidingWindowTrends`](examples/src/main/java/org/cqels/examples/SlidingWindowTrends.java) | sliding `[SLIDE … STEP …]` windows |

**Complex event processing**
| Demo | Feature |
|------|---------|
| [`ComplexEventPattern`](examples/src/main/java/org/cqels/examples/ComplexEventPattern.java) | declarative CEP `FILTER(SEQ(…))` sequence detection |

**Standard vocabularies & domains**
| Demo | Feature |
|------|---------|
| [`SosaObservations`](examples/src/main/java/org/cqels/examples/SosaObservations.java) | W3C [SOSA/SSN](https://www.w3.org/TR/vocab-ssn/) observations + multi-pattern stream join |
| [`VehicleSignalsCdsp`](examples/src/main/java/org/cqels/examples/VehicleSignalsCdsp.java) | COVESA [VSS](https://covesa.global/) (CDSP) vehicle signals + `GROUP BY` / `HAVING` |

---

## Query language & standards

**CQELS-QL** is a continuous-query language: [SPARQL 1.1](https://www.w3.org/TR/sparql11-query/)
graph patterns extended with stream windows
(`FROM STREAM … [NOW | RANGE Ns | SLIDE Ns STEP Ms | TRIPLES N]`), aggregation
(`GROUP BY` / `HAVING`), and declarative complex-event patterns (`FILTER(SEQ(…))`).
The working syntax reference is the set of [examples](examples/) plus the operator summary
in [GETTING_STARTED.md](GETTING_STARTED.md#6-where-to-go-next). Continuous property-graph
queries use Cypher via `registerCypherQuery(...)`.

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
