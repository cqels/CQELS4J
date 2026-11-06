# Getting Started with CQELS 2.0

This guide takes you from zero to a running continuous query in a few minutes:
install the prerequisites, pull the engine anonymously, run the bundled
examples, then wire CQELS into your own project.

> **Current release:** `2.0.0-alpha.22` — coordinates `org.cqels:cqels-*`, entry point `cqels-engine`.

---

## 1. Prerequisites

| Tool | Minimum |
|------|---------|
| JDK  | 17 (also tested on 21 and 24) |
| Maven | 3.8+ |
| Git  | 2.30+ |

```bash
java -version   # 17+
mvn -version    # 3.8+
```

No databases or external services are required — the quick-start uses an in-memory store.

---

## 2. Add the repository (no account needed)

CQELS artifacts are served anonymously. There is nothing to sign up for, no token to create,
and no `~/.m2/settings.xml` to edit — add the repository to your POM and build.

```xml
<repositories>
  <repository>
    <id>cqels</id>
    <name>CQELS Releases</name>
    <url>https://raw.githubusercontent.com/cqels/maven/main/releases</url>
  </repository>
</repositories>
```

Pin the version, as the snippet below does. Since the `2.0.0-alpha.21` release the repository also
serves `maven-metadata.xml`, so version ranges resolve. Alphas change behaviour from one release to
the next, so a pin is still the safer choice.

> Every release is published from a cosign-signed manifest — see
> [SUPPLY_CHAIN.md](SUPPLY_CHAIN.md) to verify what you downloaded. The one artifact not served
> here is the runnable shaded `cqels-mcp` jar, which is attached to the
> [GitHub release](https://github.com/cqels/CQELS4J/releases/tag/v2.0.0-alpha.22) instead.

---

## 3. Run the bundled examples

The [`examples/`](examples/) folder is a self-contained Maven project. Clone this repo
and run any demo:

```bash
git clone https://github.com/cqels/CQELS4J.git
cd CQELS4J/examples
mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.HelloCqels
```

You should see the alert fire for the two readings below the 20 % threshold, and nothing for
the others:

```
Engine started. Alerting on battery state-of-charge below 20 %.
...
  LOW BATTERY -> {obs=https://example.org/fleet/obs/2, soc=18.5}
...
  LOW BATTERY -> {obs=https://example.org/fleet/obs/4, soc=12.0}
...
Done.
```

(abridged — the run also echoes each reading as it is pushed)

That is the whole setup — no credentials, no local configuration. Every other demo runs the
same way, swapping `-Dexec.mainClass`. A few representative ones — the full categorized list is in
[`examples/README.md`](examples/README.md):

| Class | What it shows |
|-------|---------------|
| `org.cqels.examples.HelloCqels` | `[NOW]` window + `FILTER` — low-battery alert (SoC < 20 %), the minimal continuous query |
| `org.cqels.examples.WindowedAggregation` | `[RANGE 3s]` rolling window + `GROUP BY` — per-vehicle avg/peak speed, re-emitted for every vehicle on each arrival |
| `org.cqels.examples.SlidingWindowTrends` | `[SLIDE 4s STEP 2s]` overlapping windows — per-vehicle moving state-of-charge trend |
| `org.cqels.examples.AdvancedQueryOperators` | `OPTIONAL` / `UNION` / `FILTER NOT EXISTS` / `BIND` enriching a speed reading against the static fleet graph |
| `org.cqels.examples.ComplexEventPattern` | declarative CEP — `FILTER(SEQ(?e1; ?e2))` road-rage detection (speed drop then spike) |
| `org.cqels.examples.CypherGraphQuery` | CypherQL — `MATCH (o:Observation) RETURN o` over the telemetry stream |
| `org.cqels.examples.RdfsReasoning` | RDFS inference — `ex:DepotVehicle rdfs:subClassOf vsso:Vehicle` (`cqels-reasoning-rete`) |
| `org.cqels.examples.GeoSpatialFilter` | GeoSPARQL `geof:sfWithin` — vehicles inside the depot geofence (`cqels-geo`) |
| `org.cqels.examples.SosaObservations` | W3C SOSA/SSN observations + multi-pattern stream join — per vehicle × VSS signal |
| `org.cqels.examples.VehicleSignalsCdsp` | COVESA VSS (CDSP) — per-vehicle speeding via `GROUP BY` + `HAVING` |

See [`examples/README.md`](examples/README.md) for a description of each (grouped by
category: Basics, Windowing, Advanced query, CEP, Advanced CDSP analytics & CEP, Query
dialects, Reasoning & validation, Reasoning showcase, Geospatial, Extension functions,
Standard vocabularies).

---

## 4. Add CQELS to your own project

Add the repository and the engine dependency to your `pom.xml`:

```xml
<repositories>
  <repository>
    <id>cqels</id>
    <url>https://raw.githubusercontent.com/cqels/maven/main/releases</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>org.cqels</groupId>
    <artifactId>cqels-engine</artifactId>
    <version>2.0.0-alpha.22</version>
  </dependency>
</dependencies>
```

Optional add-on modules (same group/version): `cqels-reasoning-rete` (RDFS/OWL
inference), `cqels-shacl` (validation), `cqels-geo` (GeoSPARQL), `cqels-asp`
(Answer-Set Programming), `cqels-storage-lmdb` and `cqels-storage-iotdb-tsfile` (durable
backends, over `cqels-storage-spi`), `cqels-functions-ext`
(user-defined SPARQL functions by IRI — e.g. `urn:cqels:fn:haversine`,
`urn:cqels:fn:levenshtein` — self-registering via ServiceLoader). The RocksDB and IoTDB-session
backends are not published at this version, so they cannot be added as dependencies.

---

## 5. Your first query, explained

```java
import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

public class FirstQuery {
    public static void main(String[] args) throws InterruptedException {
        // The engine is AutoCloseable; try-with-resources stops it cleanly.
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("first-query")
                .withMemoryStore()        // in-memory RDF store
                .build()) {

            // 1. A named stream to push fleet telemetry into.
            DataStream telemetry = engine.createStream("Telemetry");

            // 2. A continuous CQELS-QL query over that stream — a low-battery alert.
            String query = """
                    PREFIX sosa: <http://www.w3.org/ns/sosa/>
                    REGISTER QUERY LowBattery AS
                    SELECT ?obs ?soc
                    FROM STREAM Telemetry [NOW]
                    WHERE {
                      STREAM Telemetry { ?obs sosa:hasSimpleResult ?soc . }
                      FILTER(?soc < 20)
                    }
                    """;

            // 3. Results arrive as Map<variable, value> rows.
            engine.registerCqelsQuery(query, row -> System.out.println("ALERT: " + row));

            // 4. Activate the query graph.
            engine.start();

            // 5. Feed data; matching rows are pushed to the listener.
            telemetry.push("https://example.org/fleet/obs/1", "http://www.w3.org/ns/sosa/hasSimpleResult", 18.0); // SoC 18 % -> alert
            telemetry.push("https://example.org/fleet/obs/2", "http://www.w3.org/ns/sosa/hasSimpleResult", 64.0); // ignored

            Thread.sleep(500); // let results flush before close()
        }
    }
}
```

**Anatomy of a CQELS-QL query:**
- `REGISTER QUERY <name> AS` — names the continuous query.
- `FROM STREAM <name> [<window>]` — the stream and its window.
- `WHERE { STREAM <name> { <triple patterns> } FILTER(...) BIND(...) }` — graph
  pattern matched against each window.
- Optional `GROUP BY` / `HAVING` / `ORDER BY` / `LIMIT` as in SPARQL.

**Window types:** `[NOW]` (per element), `[RANGE Ns]` (time; tumbling or rolling depending on the query's shape — `CQELS-QL_SPEC.md` §9), `[SLIDE Ws STEP Ss]`
(sliding), `[TRIPLES N]` (count-based).

---

## 6. Known limitations on this release

CQELS 2.0 is an alpha, and a handful of constructs do not yet behave as SPARQL 1.1 specifies.
They are grouped here because they share one signature that makes them expensive to discover
alone: **nothing here is rejected at registration.** The query goes live and simply returns wrong
rows, or none. If a query is inexplicably quiet, check this list before assuming your data is at
fault.

| What | Symptom | Do this instead |
|------|---------|-----------------|
| Rules matching the background graph ([#58](https://github.com/cqels/CQELS4J/issues/58)) | The reasoner reads stream elements only, so an ontology in the store is invisible to rules | Push the ontology onto the stream at start-up |
| `xsd:duration` in `ORDER BY`, `MIN` or `MAX` | Compared as text: `MAX` over `PT9S` and `PT10S` returns `PT9S` | Compare in a `FILTER`, which compares durations by value, or order in the result listener — see `CQELS-QL_SPEC.md` §9 |
| `AVG` of a group in which no row binds the argument | `null`, where SPARQL 1.1 gives `0` | Treat `null` as `0` in the result listener |
| An aggregate whose argument no solution binds, such as `COUNT(?v)` with `?v` in no pattern | No row at all, where SPARQL 1.1 gives `COUNT` 0 — and a `COUNT(*)` beside it disappears too | Aggregate only arguments every solution binds, or bind them through an `OPTIONAL` (counted correctly) — see `CQELS-QL_SPEC.md` §9 |
| `GROUP BY` on a language-tagged or typed literal | Keys are compared by label only: `"cat"@en` and `"cat"@fr` form one group, reported as `"cat"@en` | Add `BIND(LANG(?l) AS ?lang)` (or `DATATYPE`) and `GROUP BY ?l ?lang` |
| A static `UNION` joined to the stream in a non-aggregate query, when the join variable is not projected | Every row is dropped — under `[NOW]`, and under a window unless the query has `ORDER BY` or `LIMIT` | Project the join variable — see `CQELS-QL_SPEC.md` §9 |

`2.0.0-alpha.22` fixed six rows that this table carried for alpha.21: `REPLACE()`, a `FILTER`
over a `BIND` target ([#83](https://github.com/cqels/CQELS4J/issues/83)) or an `OPTIONAL`
variable ([#85](https://github.com/cqels/CQELS4J/issues/85)), a literal join key, `FILTER`
comparison of `xsd:duration` values ([#82](https://github.com/cqels/CQELS4J/issues/82)), and
`FROM <graph>` scoping. A seventh, a CEP `SEQ` variable that is never a subject
([#74](https://github.com/cqels/CQELS4J/issues/74)), is now refused at registration. If you wrote
a workaround for one of them, it still works, but it is no longer needed.

Property paths (`+`, `*`, `/`, `^`, `?`, `|`) are not in the table either way. Since
`2.0.0-alpha.22` they run inside the one `STREAM` block of a single-stream `[RANGE]`, `[SLIDE]`
or `[TRIPLES]` query with no static graph pattern. Anywhere else, registration refuses them with a
message naming the path. For a hierarchy in the static graph, write the hops explicitly, or use a
recursive rule — see `CQELS-QL_SPEC.md` §9.

The demos in [`examples/`](examples/) are all verified to run against this release, working around
the rows above where they must. This table is kept honest by `scripts/ci/capability-probe.sh` (see
`RELEASING.md`), which asserts each row automatically — including the property-path routes and
the reasoner rows — and fails the moment one of them stops being true.

---

## 7. Reaching the engine over HTTP

Everything above embeds the engine in your own JVM. To reach it from another process or
language, there are two routes today. Which one you need depends on whether the query should
**answer once** or **keep running**.

**One-shot SPARQL over the background graph:** [`sparql-endpoint/`](sparql-endpoint/). A SPARQL
1.1 Protocol endpoint over the graph the engine joins its streams against. curl, YASGUI, rdflib,
Jena and RDF4J's `SPARQLRepository` work as they do against any other endpoint:

```bash
cd sparql-endpoint && mvn -q package
CQELS_SPARQL_SEED_FLEET=true java -jar target/cqels-sparql-endpoint.jar
# prints: listening on http://127.0.0.1:8089/sparql
```

```bash
curl -G http://127.0.0.1:8089/sparql --data-urlencode 'query=SELECT (COUNT(*) AS ?n) WHERE { ?s ?p ?o }'
```

It starts loopback-only, read-only and without federation. Binding any other address
requires a bearer token (`CQELS_SPARQL_AUTH_TOKEN`), or start-up is refused. SPARQL Update is off
until `CQELS_SPARQL_UPDATE_ENABLED=true`. An update made through it reaches the running continuous
queries on their next element, even under `FROM <g> CACHE 5m`. In your own Servlet 6 container,
mount `SparqlProtocolServlet` over `engine::getRepository` instead of running the jar. The
endpoint's [README](sparql-endpoint/README.md) has the configuration, the protocol and status
tables, and the security model.

**Continuous queries:** the published MCP server. The SPARQL Protocol has no shape for a query
that keeps running, so registering one and reading its results over HTTP goes through
`org.cqels:cqels-mcp` and its Streamable-HTTP transport (`CQELS_MCP_TRANSPORT=http`):
`register_stream_query`, `push_stream_events`, bounded result buffers, per-client limits. See
[`mcp-server/README.md`](mcp-server/README.md), *ChatGPT and other remote (URL) clients*.

To serve continuous results to a plain SPARQL client instead, have the query's listener write
each row into the repository. The endpoint then serves those rows like any other triples.
`SparqlProtocolServletTest.continuousResultsWrittenBackAreQueryable` shows the shape.

Serving both on one port, one token and one engine is planned: the endpoint's servlet mounted
inside the MCP server's own Jetty. That needs a mount point in `cqels-mcp`, requested upstream;
progress is tracked in [#104](https://github.com/cqels/CQELS4J/issues/104).

---

## 8. Where to go next

- **Examples:** [`examples/`](examples/) — the runnable scenarios above are the
  fastest way to learn the query shapes.
- **CQELS-QL language reference:** [`CQELS-QL_SPEC.md`](CQELS-QL_SPEC.md) — the full specification of
  the streaming extensions over SPARQL (windows incl. directional/LARS, named windows, stream–static
  joins, CEP, the grammar). Beyond the examples, the language also supports `OPTIONAL` / `UNION` /
  `FILTER NOT EXISTS`, `BIND`, `HAVING` / `ORDER BY` / `LIMIT`, joins against a static graph
  (`FROM STATIC`), and declarative CEP (`FILTER(SEQ(...))` with quantifiers `?e+` / `?e{m,n}` and
  negation `NOT ?e`). (`MINUS` is not executed — registration rejects it with a hint to use
  `FILTER NOT EXISTS`; `FROM NAMED WINDOW` parses but is not yet executed in this alpha — see the spec.)
- **Cypher & CEP:** `engine.registerCypherQuery(...)` for property-graph patterns and
  `engine.registerCepQuery(...)` for event sequences.
- **Release verification:** [2.0.0-alpha.22](https://raw.githubusercontent.com/cqels/maven/main/releases/supply-chain/2.0.0-alpha.22/VERIFY.md)

Questions or issues? Open one at https://github.com/cqels/CQELS4J/issues.
