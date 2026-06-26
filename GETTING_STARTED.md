# Getting Started with CQELS 2.0

This guide takes you from zero to a running continuous query in a few minutes:
install the prerequisites, pull the engine from GitHub Packages, run the bundled
examples, then wire CQELS into your own project.

> **Current release:** `2.0.0-alpha.3` — coordinates `org.cqels:cqels-*`, entry point `cqels-engine`.

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

## 2. Authenticate to GitHub Packages (one-time)

CQELS artifacts are published to **GitHub Packages**, which requires authentication
even for public packages. You need a GitHub **Personal Access Token (classic)** with
the **`read:packages`** scope.

1. Create the token: GitHub → *Settings → Developer settings → Personal access tokens
   → Tokens (classic)* → *Generate new token (classic)* → tick **`read:packages`**.
2. Add a matching server to your `~/.m2/settings.xml` (create the file if it doesn't
   exist). The `<id>` must match the repository id used in the POM (`github-cqels`):

```xml
<settings>
  <servers>
    <server>
      <id>github-cqels</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_GITHUB_TOKEN</password>
    </server>
  </servers>
</settings>
```

> Keep the token out of source control. For CI, inject it via a secret and a generated
> `settings.xml` rather than committing it.

---

## 3. Run the bundled examples

The [`examples/`](examples/) folder is a self-contained Maven project. Clone this repo
and run any demo:

```bash
git clone https://github.com/cqels/CQELS4J.git
cd CQELS4J/examples

# Build
mvn -q compile

# Run the "hello world" of continuous queries
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.HelloCqels
```

A few representative scenarios (the full categorized list of 17 is in
[`examples/README.md`](examples/README.md)) — run any with `-Dexec.mainClass`:

| Class | What it shows |
|-------|---------------|
| `org.cqels.examples.HelloCqels` | `[NOW]` window + `FILTER` — low-battery alert (SoC < 20 %), the minimal continuous query |
| `org.cqels.examples.WindowedAggregation` | `[RANGE 3s]` tumbling window + `GROUP BY` — per-vehicle avg/peak speed |
| `org.cqels.examples.SlidingWindowTrends` | `[SLIDE 4s STEP 2s]` overlapping windows — per-vehicle moving state-of-charge trend |
| `org.cqels.examples.AdvancedQueryOperators` | `OPTIONAL` / `UNION` / `FILTER NOT EXISTS` / `BIND` enriching a speed reading against the static fleet graph |
| `org.cqels.examples.ComplexEventPattern` | declarative CEP — `FILTER(SEQ(?drop; ?spike))` road-rage detection |
| `org.cqels.examples.CypherGraphQuery` | CypherQL — `MATCH (o:Observation) RETURN o` over the telemetry stream |
| `org.cqels.examples.RdfsReasoning` | RDFS inference — `ex:DepotVehicle rdfs:subClassOf vsso:Vehicle` (`cqels-reasoning-rete`) |
| `org.cqels.examples.GeoSpatialFilter` | GeoSPARQL `geof:sfWithin` — vehicles inside the depot geofence (`cqels-geo`) |
| `org.cqels.examples.SosaObservations` | W3C SOSA/SSN observations + multi-pattern stream join — per vehicle × VSS signal |
| `org.cqels.examples.VehicleSignalsCdsp` | COVESA VSS (CDSP) — per-vehicle speeding via `GROUP BY` + `HAVING` |

See [`examples/README.md`](examples/README.md) for a description of each (grouped by
category: Basics, Windowing, Advanced query, CEP, Query dialects, Reasoning & validation,
Geospatial, Standard vocabularies).

---

## 4. Add CQELS to your own project

Add the repository and the engine dependency to your `pom.xml`:

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

Optional add-on modules (same group/version): `cqels-reasoning-rete` (RDFS/OWL
inference), `cqels-shacl` (validation), `cqels-geo` (GeoSPARQL), `cqels-asp`
(Answer-Set Programming), `cqels-storage-*` (durable backends).

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

**Window types:** `[NOW]` (per element), `[RANGE Ns]` (tumbling), `[SLIDE Ws STEP Ss]`
(sliding), `[TRIPLES N]` (count-based).

---

## 6. Where to go next

- **Examples:** [`examples/`](examples/) — the runnable scenarios above are the
  fastest way to learn the query shapes.
- **CQELS-QL language reference:** [`CQELS-QL_SPEC.md`](CQELS-QL_SPEC.md) — the full specification of
  the streaming extensions over SPARQL (windows incl. directional/LARS, named windows, stream–static
  joins, CEP, the grammar). Beyond the examples, the language also supports `OPTIONAL` / `UNION` /
  `FILTER NOT EXISTS`, `BIND`, `HAVING` / `ORDER BY` / `LIMIT`, joins against a static graph
  (`FROM STATIC`), and declarative CEP (`FILTER(SEQ(...))` with quantifiers `?e+` / `?e{m,n}` and
  negation `NOT ?e`). (`MINUS` and `FROM NAMED WINDOW` parse but are not yet executed in this alpha —
  see the spec.)
- **Cypher & CEP:** `engine.registerCypherQuery(...)` for property-graph patterns and
  `engine.registerCepQuery(...)` for event sequences.
- **Releases:** https://github.com/cqels/CQELS4J/releases

Questions or issues? Open one at https://github.com/cqels/CQELS4J/issues.
