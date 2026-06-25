# CQELS Engine — Getting-Started Examples

Four small, self-contained programs that demonstrate the core capabilities of the
CQELS 2.0 continuous query engine. Each is a `main()` you can run directly.

## Prerequisites

- JDK 17+ and Maven 3.8+
- A GitHub Packages token configured in `~/.m2/settings.xml` (one-time setup) —
  see [`../GETTING_STARTED.md`](../GETTING_STARTED.md#2-authenticate-to-github-packages-one-time).

## Build & run

```bash
mvn -q compile

# Default (HelloCqels):
mvn -q exec:java

# Any scenario:
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.WindowedAggregation
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.SlidingWindowTrends
mvn -q exec:java -Dexec.mainClass=org.cqels.examples.ComplexEventPattern
```

Each program prints what it pushes and what the engine emits, then exits on its own.

## The scenarios

| # | Class | CQELS feature | Scenario |
|---|-------|---------------|----------|
| 1 | [`HelloCqels`](src/main/java/org/cqels/examples/HelloCqels.java) | `[NOW]` window + `FILTER` | Alert on every sensor reading above a threshold — the minimal continuous query. |
| 2 | [`WindowedAggregation`](src/main/java/org/cqels/examples/WindowedAggregation.java) | `[RANGE 3s]` tumbling + `GROUP BY` | Per-sensor average / count / peak temperature, one row per 3-second window. |
| 3 | [`SlidingWindowTrends`](src/main/java/org/cqels/examples/SlidingWindowTrends.java) | `[SLIDE 4s STEP 2s]` | Overlapping trailing-window stats (moving average / min / max) re-emitted every 2s. |
| 4 | [`ComplexEventPattern`](src/main/java/org/cqels/examples/ComplexEventPattern.java) | declarative CEP `FILTER(SEQ(...))` | Detect a temporal sequence — overheat **then** stall — within a window. |

## Adapting them

- Change the CQELS-QL string to try other windows (`[TRIPLES N]`), aggregates
  (`SUM`, `MIN`), or clauses (`HAVING`, `ORDER BY`, `LIMIT`).
- `engine.createStream(name)` returns a `DataStream` with several `push(...)`
  overloads (RDF `Statement`, `(subject, predicate, value)` with `String`/`double`/
  `long`/`IRI` objects, and explicit-timestamp variants).
- Bump `<cqels.version>` in [`pom.xml`](pom.xml) to track new releases.
