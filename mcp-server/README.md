# CQELS as an MCP server

A minimal [Model Context Protocol](https://modelcontextprotocol.io/) (MCP) server that
exposes a **CQELS engine as AI-accessible tools** over stdio — both the static RDF store
*and* the **streaming engine** that the [`examples/`](../examples/) demonstrate. An MCP
client (Claude Desktop, an IDE assistant, or any MCP-capable agent) can use a CQELS RDF
store as **queryable memory**, and also **register continuous CQELS-QL queries** (windows,
aggregates, CEP — the same query shapes as the examples), feed them events, and read what
the engine emits. The tools are **vocabulary-agnostic**, so the same server drives the
[`examples/`](../examples/) **electric-vehicle fleet / V2G** world from an AI client: push VSS
`sosa:Observation`s, register a per-vehicle speeding monitor, detect a road-rage CEP sequence,
or teach the `ex:ElectricBus ⊑ vsso:Vehicle` taxonomy (walkthrough below). This example uses
`.withMemoryStore()`, so the store is in-memory and **session-scoped (cleared when the process
exits)**; swap in a durable storage backend for persistence across restarts.

Its core dependencies are the published `cqels-engine` and the official
[MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk) (`io.modelcontextprotocol.sdk:mcp`),
plus an `slf4j-simple` binding (so logs go to **stderr**, keeping stdout clean for JSON-RPC)
and a `jackson-annotations` version pin (see the pom). It's a self-contained starting point
for wrapping CQELS in your own MCP server.

## Tools exposed

**Static memory (request/response):**

| Tool | Arguments | What it does |
|------|-----------|--------------|
| `store_fact` | `subject`, `predicate`, `object` | Add one RDF triple to memory. `object` is an IRI if it starts with `http(s)://`, otherwise a literal. |
| `query` | `sparql` | Run a SPARQL `SELECT` over everything stored so far; returns the rows as text (first 100, with a truncation marker beyond that). |
| `recall` | `subject`, *(opt)* `predicate` | **Intent-shaped memory retrieval** — returns what's known about an entity (builds the SPARQL for you, so the model needn't write a query). |

**Agent-memory demo tools (alpha.7 patterns — see [Agent memory types](#agent-memory-types-alpha7)):**

| Tool | Arguments | What it does |
|------|-----------|--------------|
| `record_event` | `event_type`, `entity`, *(opt)* `data`, *(opt)* `timestamp_ms` | **Episodic memory** — record a timestamped episode (what happened, to which entity, when). Defaults to "now"; pass `timestamp_ms` to backfill history. |
| `recall_episodes` | *(all opt)* `entity`, `event_type`, `since_ms`, `until_ms`, `limit` | Recall episodes **newest first**, filtered by entity / event type / a half-open `[since_ms, until_ms)` time range. |
| `save_procedure` | `name`, *(opt)* `description`, `sparql` | **Procedural memory** — save a named SPARQL SELECT. Facts describing the procedure are stored too, so `query` can find them; re-saving a name updates it. |
| `list_procedures` | — | List saved procedures (name + description). |
| `run_procedure` | `name` | Run a saved procedure by name — through the same path as the `query` tool. |
| `assemble_context` | `entity`, *(opt)* `limit` | **Working memory / GraphRAG** — one compact bundle per entity: its direct facts + its most recent episodes + saved procedures that mention it, ready to inject into an agent prompt. |

**Streaming (continuous queries — mirrors the [`examples/`](../examples/)):**

| Tool | Arguments | What it does |
|------|-----------|--------------|
| `push_event` | `stream`, `subject`, `predicate`, `object`, *(opt)* `timestamp_ms` | Push one triple as an event into a named stream (created on first use). Numeric objects become numeric literals so `FILTER`/aggregates work; `http(s)://` becomes an IRI; predicate `"a"` is shorthand for `rdf:type`. `timestamp_ms` stamps an explicit **event time** (needed to replay out-of-order data for event-time CEP). |
| `register_stream_query` | `query` | Register a continuous CQELS-QL query (`REGISTER QUERY … FROM STREAM … [window] …`) — windows + aggregates. Returns a query **id**; emitted rows are buffered server-side (bounded — see below). Ordered `FILTER(SEQ(...))` patterns are rejected here — use `detect_sequence`. |
| `poll_results` | `queryId` | Drain and return up to 100 rows the query has emitted since the last poll; flags if more remain or if any were dropped. |
| `unregister_stream_query` | `queryId` | Stop a query and free its buffer when you're done with it. |

**Intent-shaped streaming (higher-level wrappers):**

| Tool | Arguments | What it does |
|------|-----------|--------------|
| `detect_sequence` | `stream`, `steps[]`, *(opt)* `withinSeconds`, *(opt)* `event_time`, *(opt)* `lateness_ms` | **Event-pattern matching** — builds + registers a CEP `FILTER(SEQ(...))` from an ordered list of event-type IRIs, returns a query id. Push events as `(eventIri, "a", typeIri)`, then `poll_results`. Default matches in **arrival order**; `event_time: true` matches in **event-timestamp order** (out-of-order arrivals are reordered) and **requires `lateness_ms`** — the engine fails fast without a budget (see below). |
| `define_subclass` | `stream`, `subclass`, `superclass` | **RDFS reasoning** — declares `subclass ⊑ superclass`; afterwards an event `(x, a, subclass)` is also inferred as `(x, a, superclass)` for stream queries. The reasoner is engine-wide (applies to all streams) and stream-side only (does not affect `recall`/the static store). |

> Continuous queries push results asynchronously, but MCP tools are request/response — so a
> registered query's rows are **buffered** and returned on demand. The natural call order is
> **register → push_event(s) → poll_results** (a client awaits each response before the next,
> so the query is registered before events arrive), and **unregister_stream_query** when done.
> Each query's buffer is **bounded** (10 000 rows): if a hot query is left unpolled, the
> oldest rows are dropped and `poll_results` reports the count — so the server can't grow
> memory without bound.
>
> **stdout is reserved for the JSON-RPC protocol** — this server logs only to stderr, and
> never prints results to stdout. Keep it that way: don't set
> `org.slf4j.simpleLogger.logFile=System.out`, and don't `System.out.println` from a tool
> handler — either would interleave bytes into the JSON-RPC stream and break the protocol.

## Build

```bash
# One-time: a GitHub Packages token with read:packages in ~/.m2/settings.xml
# (see ../GETTING_STARTED.md) — needed only to download cqels-engine at build time.
mvn -q package
# -> target/cqels-mcp-server.jar  (a single runnable jar)
```

Quick smoke test without an MCP client (pipe a JSON-RPC session in). The server is
**long-running** — like a real MCP client, you hold the connection open and then terminate
the process, so the snippet below backgrounds it and kills it after the replies arrive
(piping straight into `java …` would print the replies and then appear to hang, because the
server keeps running until signalled):

```bash
( printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"cli","version":"1.0"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"store_fact","arguments":{"subject":"https://example.org/fleet/vehicle/EV-7Q2","predicate":"https://covesa.global/fleet#assignedDriver","object":"https://example.org/fleet/driver/alice"}}}' \
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"query","arguments":{"sparql":"SELECT ?s ?p ?o WHERE { ?s ?p ?o }"}}}'; \
  sleep 2 ) | java -jar target/cqels-mcp-server.jar &
sleep 4; kill %1 2>/dev/null   # stop the long-running server
```

You should see the `initialize` result, then `Stored: …`, then the queried triple. (An MCP
client such as Claude Desktop manages this lifecycle for you — it launches the process and
sends SIGTERM on shutdown, which the server's hook handles cleanly.)

### Streaming smoke test

The same way, drive a **continuous query** over vehicle telemetry: register a `[NOW]` speed
filter, push three vehicles' `vss:Speed` readings, then poll. `push_event` sends one triple
per call, so this uses the flat `?vehicle vss:Speed ?kmh` form (the examples' `Fleet` helper
batches the richer 5-triple `sosa:Observation`). Send requests with a small gap so each
completes before the next — the order must be register → push → poll.

```bash
{ emit() { printf '%s\n' "$1"; sleep 0.6; }
  emit '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"cli","version":"1.0"}}}'
  emit '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  emit '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"register_stream_query","arguments":{"query":"PREFIX vss: <https://covesa.global/vss#> REGISTER QUERY Speeding AS SELECT ?vehicle ?kmh FROM STREAM Telemetry [NOW] WHERE { STREAM Telemetry { ?vehicle vss:Speed ?kmh . } FILTER(?kmh > 120) }"}}}'
  emit '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"push_event","arguments":{"stream":"Telemetry","subject":"https://example.org/fleet/vehicle/EV-7Q2","predicate":"https://covesa.global/vss#Speed","object":"135"}}}'
  emit '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"push_event","arguments":{"stream":"Telemetry","subject":"https://example.org/fleet/vehicle/EV-3K8","predicate":"https://covesa.global/vss#Speed","object":"90"}}}'
  emit '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"push_event","arguments":{"stream":"Telemetry","subject":"https://example.org/fleet/vehicle/EV-9TZ","predicate":"https://covesa.global/vss#Speed","object":"128"}}}'
  emit '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"poll_results","arguments":{"queryId":"Speeding"}}}'
} | java -jar target/cqels-mcp-server.jar &
sleep 6; kill %1 2>/dev/null
```

The final `poll_results` returns the two speeding vehicles
(`{kmh=135, vehicle=…/EV-7Q2}` and `{kmh=128, vehicle=…/EV-9TZ}`); EV-3K8 at 90 km/h is filtered out.

## Agent memory types (alpha.7)

CQELS 2.0.0-alpha.7's headline is an **agent-memory surface**: the four cognitive memory
types an AI agent needs — *semantic* (facts), *episodic* (what happened when), *procedural*
(how to do things), and *working* (what's relevant right now) — backed by one streaming
knowledge-graph engine. This demo server implements each **pattern** on the same published
`cqels-engine` store that `store_fact`/`query`/`recall` already use:

- **Semantic memory** — `store_fact` / `recall` (facts about entities; unchanged).
- **Episodic memory** — `record_event` stores a timestamped episode; `recall_episodes`
  replays them newest-first, filtered by entity, event type, and time range.
- **Procedural memory** — `save_procedure` names a SPARQL SELECT; `run_procedure` re-runs it
  by name through the same path as `query`; `list_procedures` browses them. Facts describing
  each procedure land in the store too, so plain `query` can discover them.
- **Working memory** — `assemble_context` bundles an entity's facts + most recent episodes +
  matching procedures into one compact block an agent can inject into its prompt (a
  GraphRAG-style context assembly).

A stdio session that exercises all four (same pattern as the smoke tests above; the full
scripted version with assertions is [`scripts/smoke-memory.sh`](scripts/smoke-memory.sh)):

```bash
{ emit() { printf '%s\n' "$1"; sleep 0.6; }
  emit '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"cli","version":"1.0"}}}'
  emit '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  emit '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"store_fact","arguments":{"subject":"https://example.org/fleet/vehicle/EV-7Q2","predicate":"https://covesa.global/fleet#assignedDriver","object":"https://example.org/fleet/driver/alice"}}}'
  emit '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"record_event","arguments":{"event_type":"https://covesa.global/fleet#ChargeStartEvent","entity":"https://example.org/fleet/vehicle/EV-7Q2","data":"station=depot-north","timestamp_ms":1000000}}}'
  emit '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"record_event","arguments":{"event_type":"https://covesa.global/fleet#ChargeStopEvent","entity":"https://example.org/fleet/vehicle/EV-7Q2","timestamp_ms":2000000}}}'
  emit '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"recall_episodes","arguments":{"entity":"https://example.org/fleet/vehicle/EV-7Q2","since_ms":0}}}'
  emit '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"save_procedure","arguments":{"name":"vehicle_profile","description":"Everything known about EV-7Q2","sparql":"SELECT ?p ?o WHERE { <https://example.org/fleet/vehicle/EV-7Q2> ?p ?o }"}}}'
  emit '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"run_procedure","arguments":{"name":"vehicle_profile"}}}'
  emit '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"assemble_context","arguments":{"entity":"https://example.org/fleet/vehicle/EV-7Q2"}}}'
} | java -jar target/cqels-mcp-server.jar &
sleep 8; kill %1 2>/dev/null
```

`recall_episodes` returns the two charge episodes newest-first (stop before start);
`run_procedure` returns the stored driver fact; `assemble_context` returns one
`facts / recent episodes / procedures` block for EV-7Q2.

### Event-time sequence detection (out-of-order events)

`detect_sequence` matches steps in **arrival order** by default — fine when events arrive in
the order they happened, wrong when they don't (multi-hop vehicle uplinks routinely deliver
telemetry late). alpha.7's engine adds opt-in **event-time SEQ ordering**: pass
`event_time: true` plus a `lateness_ms` budget and out-of-order arrivals are reordered by
their event timestamps before matching. The budget is **required** — the engine *fails fast*
at registration without one, because a silent `0` would drop exactly the out-of-order events
the mode exists to reorder (the tool surfaces that error verbatim).

The V2G road-rage fixture, delivered out of order — the REAL order was a speed **drop**
(t=1000 ms) then a speed **spike** (t=2000 ms), but the spike *arrives* first:

```bash
{ emit() { printf '%s\n' "$1"; sleep 0.6; }
  emit '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"cli","version":"1.0"}}}'
  emit '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  emit '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"detect_sequence","arguments":{"stream":"Incidents","steps":["https://covesa.global/fleet#SpeedDropEvent","https://covesa.global/fleet#SpeedSpikeEvent"],"withinSeconds":60,"event_time":true,"lateness_ms":3000}}}'
  emit '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"detect_sequence","arguments":{"stream":"Incidents","steps":["https://covesa.global/fleet#SpeedDropEvent","https://covesa.global/fleet#SpeedSpikeEvent"],"withinSeconds":60}}}'
  emit '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"push_event","arguments":{"stream":"Incidents","subject":"https://example.org/fleet/event/e-spike","predicate":"a","object":"https://covesa.global/fleet#SpeedSpikeEvent","timestamp_ms":2000}}}'
  emit '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"push_event","arguments":{"stream":"Incidents","subject":"https://example.org/fleet/event/e-drop","predicate":"a","object":"https://covesa.global/fleet#SpeedDropEvent","timestamp_ms":1000}}}'
  emit '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"push_event","arguments":{"stream":"Incidents","subject":"https://example.org/fleet/event/e-heartbeat","predicate":"a","object":"https://covesa.global/fleet#HeartbeatEvent","timestamp_ms":10000}}}'
  sleep 1
  emit '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"poll_results","arguments":{"queryId":"seq_1"}}}'
  emit '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"poll_results","arguments":{"queryId":"seq_2"}}}'
} | java -jar target/cqels-mcp-server.jar &
sleep 12; kill %1 2>/dev/null
```

The **event-time** query (`seq_1`) reorders within its 3 s budget and matches —
`PatternMatch{events=2, … startTime=1000, endTime=2000}`, i.e. drop-then-spike as it really
happened. The **arrival-order** query (`seq_2`) sees spike-then-drop and returns
`(no new results)` — it *missed* the sequence. (The `t=10000` heartbeat matters: event-time
release is watermark-gated, so a later event must advance the watermark past the buffered
pair; on a live telemetry stream the next reading does this naturally.)

> **Scope, honestly.** The full production memory surface — **19 tools** including RDF-star
> statement-level context dimensions (provenance/confidence/validity/access), PROV-O decision
> lineage (`explain_decision`/`recall_decisions`), fail-closed governance, and vector/hybrid
> semantic recall — ships in the **CQELS engine repository's `cqels-mcp` server**, which is a
> shaded runtime jar built from source there (not a published Maven artifact). This demo
> server shows the memory-type *patterns* on the published `cqels-engine` API so you can build
> your own; it is not a substitute for that server.

## Use it from Claude Desktop

Add an entry to your `claude_desktop_config.json` (macOS:
`~/Library/Application Support/Claude/claude_desktop_config.json`; Windows:
`%APPDATA%\Claude\claude_desktop_config.json`), using the **absolute path** to the jar:

```json
{
  "mcpServers": {
    "cqels-memory": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/CQELS4J/mcp-server/target/cqels-mcp-server.jar"]
    }
  }
}
```

Restart Claude Desktop. The `cqels-memory` server's tools appear under the tools menu.
Any MCP client works the same way — the launch command is just
`java -jar …/cqels-mcp-server.jar` speaking MCP over stdio.

### Try it

> *Memory:* "Remember that vehicle EV-7Q2 is driven by Alice and hosted at the north depot." →
> the assistant calls `store_fact`. "What do we know about EV-7Q2?" → it calls `recall` on the
> vehicle.
>
> *Streaming:* "Watch the Telemetry stream for vehicles over 120 km/h, then send speeds 135, 90
> and 128." → the assistant calls `register_stream_query`, three `push_event`s, then
> `poll_results` and reports back the two speeding vehicles.
>
> *Event patterns + reasoning:* "Alert me when a vehicle has a speed drop then a speed spike
> (road rage)." → `detect_sequence` with the two event-type IRIs. "Treat ex:ElectricBus as a
> kind of vsso:Vehicle." → `define_subclass`, after which an event typed `ex:ElectricBus` also
> matches a query on `vsso:Vehicle`.

## Extending it

`CqelsMemoryMcpServer` registers its tools on the builder before serving:
`McpServer.sync(transport)…​.tools(storeFactTool(engine), queryTool(engine), pushEventTool(engine), …).build()`.
To add your own (e.g. SHACL validation, or a directional/CEP query tool), write another
`SyncToolSpecification` — a `McpSchema.Tool` with a JSON input schema plus a handler
returning a `CallToolResult` — and pass it to `.tools(…)` alongside the existing ones.
(You can also register at runtime on the built server via `server.addTool(spec)`, but
registering before `build()` avoids any startup race.) The engine is a full `CQELSEngine`,
so the streaming, CEP, reasoning, and SHACL APIs are all available — the `register_stream_query`
tool already routes arbitrary CQELS-QL (windows, aggregates, CEP) through to it.
