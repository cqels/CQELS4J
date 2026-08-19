# Getting Started with CQELS 2.0

This guide takes you from zero to a running continuous query in a few minutes:
install the prerequisites, pull the engine anonymously, run the bundled
examples, then wire CQELS into your own project.

> **Current release:** `2.0.0-alpha.18` — coordinates `org.cqels:cqels-*`, entry point `cqels-engine`.

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

Fixed versions only: the repository serves no metadata, so version ranges and `LATEST` do not
resolve. Pin the version, as the snippet below does.

> Every release is published from a cosign-signed manifest — see
> [SUPPLY_CHAIN.md](SUPPLY_CHAIN.md) to verify what you downloaded. The one artifact not served
> here is the runnable shaded `cqels-mcp` jar, which is attached to the
> [GitHub release](https://github.com/cqels/CQELS4J/releases/latest) instead.

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
same way, swapping `-Dexec.mainClass`. A few representative ones (the full categorized list of
30 is in [`examples/README.md`](examples/README.md)):

| Class | What it shows |
|-------|---------------|
| `org.cqels.examples.HelloCqels` | `[NOW]` window + `FILTER` — low-battery alert (SoC < 20 %), the minimal continuous query |
| `org.cqels.examples.WindowedAggregation` | `[RANGE 3s]` tumbling window + `GROUP BY` — per-vehicle avg/peak speed |
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
    <version>2.0.0-alpha.18</version>
  </dependency>
</dependencies>
```

Optional add-on modules (same group/version): `cqels-reasoning-rete` (RDFS/OWL
inference), `cqels-shacl` (validation), `cqels-geo` (GeoSPARQL), `cqels-asp`
(Answer-Set Programming), `cqels-storage-*` (durable backends), `cqels-functions-ext`
(user-defined SPARQL functions by IRI — e.g. `urn:cqels:fn:haversine`,
`urn:cqels:fn:levenshtein` — self-registering via ServiceLoader).

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

## 6. Known limitations on this release

CQELS 2.0 is an alpha, and a handful of constructs do not yet behave as SPARQL 1.1 specifies.
They are grouped here because they share one signature that makes them expensive to discover
alone: **nothing is rejected at registration.** The query goes live and simply returns wrong rows,
or none. Only the property-path case prints anything at all — a parser diagnostic on stderr, which
is easy to miss because registration succeeds anyway and the query then runs. If a query is
inexplicably quiet, or a guard reports more than it should, check this list before assuming your
data is at fault.

| What | Symptom | Do this instead |
|------|---------|-----------------|
| A static (non-`STREAM`) pattern that finds no match ([#54](https://github.com/cqels/CQELS4J/issues/54)) | The row survives instead of being eliminated, so patterns used as **guards** over-report | Re-check the guard condition in your result listener |
| A prefixed name inside `FILTER` ([#55](https://github.com/cqels/CQELS4J/issues/55)) | `FILTER(?x = ex:Thing)` never matches, though the same prefix works in triple patterns | Write the full IRI: `FILTER(?x = <http://.../Thing>)` |
| Property paths — `+`, `*`, `/`, `^` ([#56](https://github.com/cqels/CQELS4J/issues/56)) | A parser error is printed to **stderr**, but registration succeeds anyway and a *different* pattern is evaluated | Write the hops explicitly, or use a recursive rule |
| `REPLACE()` and `sameTerm()` | The variable is left unbound / the filter never matches | `?a = <full-iri>`; `STR(?a) = STR(?b)` **only if both are IRIs** — `STR` cannot tell an IRI from a string literal with the same text. `REPLACE` moves to the listener |
| Rules over atomically pushed multi-statement elements ([#57](https://github.com/cqels/CQELS4J/issues/57)) | The reasoner skips them entirely, so no inference fires | Push those statements one at a time |

| Rules matching the background graph ([#58](https://github.com/cqels/CQELS4J/issues/58)) | The reasoner reads stream elements only, so an ontology in the store is invisible to rules | Push the ontology onto the stream at start-up |

Everything else in [`CQELS-QL_SPEC.md`](CQELS-QL_SPEC.md) behaves as documented, and the demos in
[`examples/`](examples/) are all verified to run against this release. `scripts/ci/capability-probe.sh`
asserts every row above on each PR, so if one is fixed upstream this table fails CI rather than
quietly going out of date.

---

## 7. Where to go next

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
- **Release verification:** [2.0.0-alpha.18](https://raw.githubusercontent.com/cqels/maven/main/releases/supply-chain/2.0.0-alpha.18/VERIFY.md)

Questions or issues? Open one at https://github.com/cqels/CQELS4J/issues.
