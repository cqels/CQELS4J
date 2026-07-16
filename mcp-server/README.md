# CQELS as an MCP server

This module packages the **published CQELS MCP server** — `org.cqels:cqels-mcp` on GitHub
Packages — as a single runnable jar, wrapped in a thin launcher that seeds the same
**electric-vehicle fleet / V2G** world the [`examples/`](../examples/) run. An MCP client
(Claude Desktop, an IDE assistant, or any [Model Context Protocol](https://modelcontextprotocol.io/)
agent) connects over **stdio** and gets the full production tool surface — agent memory
(semantic / episodic / procedural / working), continuous CQELS-QL/Cypher stream queries, CEP,
RDFS/OWL reasoning, SHACL validation and standing SHACL invariants, ASP solving and standing
ASP rules, decision lineage, and fail-closed governance — with a fleet knowledge graph already
loaded, so there is something to query, watch, and reason over from the first tool call.

The launcher ([`FleetMcpServerLauncher`](src/main/java/org/cqels/examples/mcp/FleetMcpServerLauncher.java))
does exactly two things — in an order that leaves no startup race:

1. **Seeds the V2G world first.** It writes the examples' fleet background graph — three
   pseudonymous EVs with drivers and depot assignments, two charging stations, three geofenced
   zones with speed limits, a traffic sensor, and signal→unit facts (W3C SOSA/SSN → VSSo →
   COVESA VSS layering) — into a per-launch RDF store (an RDF4J NativeStore in a fresh temp
   directory), under the server's **long-term memory graph** (`cqels://memory/longterm`), the
   graph `store_memory` writes to and `recall_memory` pattern recall reads. Then it shuts the
   store down, releasing its lock.
2. **Embeds the real server, pointed at that store.** It builds a `CqelsMcpServerConfig` with
   demo defaults (stdio transport) plus `rdfStorePath` set to the seeded directory, and starts
   `org.cqels.mcp.server.CqelsMcpServer` — the same server class the published `-shaded` jar
   runs. No tools are re-implemented here, and because the world is on disk **before the
   transport comes up**, even a client's very first `tools/call` sees the seed. Seeded facts
   are indistinguishable from client-stored knowledge for SPARQL `query` and pattern
   `recall_memory`. (One boundary: `recall_memory`'s free-text / vector search only indexes
   facts that arrive through `store_memory` at runtime, so use SPARQL or pattern recall to
   reach the seed.)

The per-launch temp store keeps demo semantics **ephemeral** (a fresh world each run — the
directory is removed on shutdown, and two launches can never collide on a store lock). Set
`CQELS_FLEET_STORE_DIR=/some/dir` to keep the store — facts and saved procedures then survive
restarts (re-seeding an existing store is idempotent). The embedded server also supports a
durable operator-state backend via `CqelsMcpServerConfig.builder()` if you adapt the launcher
(see [Extending it](#extending-it)).

## Tools exposed (24)

Enumerated from a live `tools/list` against this jar.

**Memory (the four agent-memory types):**

| Tool | What it does |
|------|--------------|
| `store_memory` | Store facts into long-term (RDF knowledge graph) or short-term (time-windowed stream) memory — subject/predicate/object triples or a Turtle block. |
| `recall_memory` | Retrieve knowledge via SPARQL, natural-language text search, graph pattern matching — or **drain buffered results of a registered stream query** by `queryId`. |
| `forget_memory` | Remove facts: individual triples, a SPARQL DELETE, or clear a named graph. |
| `record_event` | Episodic memory: record a timestamped event (subject/predicate/object + time). |
| `recall_episodes` | Recall past events by time range and/or entity, ordered by time. |
| `save_procedure` | Procedural memory: save a named, runnable procedure (ASP/SHACL/SPARQL/CQELS-QL/Cypher/CEP) with `{{param}}` placeholders. |
| `list_procedures` | List saved procedures with kind and description. |
| `run_procedure` | Run a saved procedure by name, binding `{{param}}` values. |
| `assemble_context` | Working memory: a ranked, budget-bounded context bundle for a task — relevant facts (with KG neighborhood) plus matching procedures. |

**Query + streaming:**

| Tool | What it does |
|------|--------------|
| `query` | One-shot SPARQL / CQELS-QL / Cypher query over the knowledge graph and streams. |
| `create_stream` | Create a named stream (idempotent) so a query can be registered over it **before** events arrive — streams are hot, with no replay. |
| `push_stream_events` | Push timestamped observations onto a named stream. Each event is one atomic observation: simple `facts` triples or an RDF-Messages `nquads` body (typed literals, multi-triple observations). |
| `register_stream_query` | Register a standing CQELS-QL or Cypher continuous query; results are buffered and drained via `recall_memory(queryId)` (CEP `FILTER(SEQ(...))` via `cep: true`). |
| `validate_stream_query` | Dry-run: statically validate CQELS-QL without registering it. |
| `forget_stream_query` | Stop a continuous query and discard its buffer. |

**Standing reasoning over streams:**

| Tool | What it does |
|------|--------------|
| `watch_invariant` | Register a **continuous SHACL invariant** over a stream — each pushed observation is validated whole; violations are buffered like query results. |
| `register_rules` | Register a **standing ASP program** over a stream — facts accumulate as `rdf(S,P,O)` atoms, each observation triggers a solve, new derivations are buffered. |

**One-shot reasoning:**

| Tool | What it does |
|------|--------------|
| `reason` | RDFS/OWL inference over the knowledge graph (RDFS_MINIMAL … OWL2_RL profiles). |
| `validate` | SHACL validation with violation details and repair suggestions. |
| `solve` | ASP (Answer Set Programming, ASP-Core-2) solving. |
| `register_reasoning` | Materialize the entailment closure of semantic memory; `recall_memory(entail: true)` then returns asserted + inferred facts. |

**Lineage + governance:**

| Tool | What it does |
|------|--------------|
| `explain_decision` | Reconstruct a recorded decision trace: inputs, procedure, output, policy, override flag. |
| `recall_decisions` | Find recorded decisions by time range / policy / override flag. |
| `set_access_policy` | PRIVILEGED: grant a caller role a set of access labels; any active policy fail-closes the read surface. |

**Resources (9 + 1 template):** `cqels://engine/status`, `cqels://kg/stats`,
`cqels://kg/namespaces`, `cqels://streams`, `cqels://queries`,
`cqels://reasoning/capabilities`, and three syntax/usage docs — `cqels://docs/cqelsql`,
`cqels://docs/cep`, `cqels://docs/memory-profile`. Per-query buffered results are readable
(and subscribable) at the `cqels://queries/{queryId}/results` template.

**Prompts (10):** `recent_events_window`, `value_over_window`, `entity_by_type`,
`recall_about`, `store_knowledge`, `extract_facts`, `validate_data`, `reasoning_workflow`,
`memory_routing`, `spatial_recall`.

> The natural streaming order is **create_stream → register_stream_query →
> push_stream_events → recall_memory(queryId)**: streams are hot with no replay, so register
> the standing query before feeding it. The server's `initialize` response carries these usage
> instructions, and `cqels://docs/*` resources document the CQELS-QL/CEP syntax — an agent can
> discover all of this without reading this README.
>
> **stdout is reserved for the JSON-RPC protocol** — the server and launcher log only to
> stderr. Keep it that way if you extend the launcher: don't `System.out.println`, and don't
> set `org.slf4j.simpleLogger.logFile=System.out`.

## Build

```bash
# One-time: a GitHub Packages token with read:packages in ~/.m2/settings.xml
# (see ../GETTING_STARTED.md) — needed only to download org.cqels:cqels-mcp at build time.
mvn -q package
# -> target/cqels-mcp-server.jar  (a single runnable jar)
```

### Smoke test

[`scripts/smoke.sh`](scripts/smoke.sh) drives one scripted JSON-RPC session over stdio —
`initialize` → SPARQL over the **seeded** fleet as the **first, zero-delay** `tools/call`
(proving the seed is in place before the server accepts requests) → `tools/list` → pattern
recall → `store_memory` → `create_stream` → `register_stream_query` (a `[NOW]` speeding
monitor) → `push_stream_events` (three typed speed readings) → drain → `forget_stream_query`
— and asserts the responses, including that the 135 and 128 km/h vehicles surface while the
90 km/h one is filtered out, and that stdout carried only JSON-RPC frames:

```bash
scripts/smoke.sh
# ...
# ok:   drain surfaced the 135 km/h speeder
# ok:   90 km/h reading filtered out
# ok:   stdout carried only JSON-RPC frames
# SMOKE OK
```

The server is **long-running** — like a real MCP client, the script holds the connection open
and terminates the process when done. If you drive it by hand, follow the same pattern
(background the pipeline, `kill` when finished).

## Use it from an MCP client

A standard stdio MCP server: any stdio-capable client launches it the same way
(`java -jar …/cqels-mcp-server.jar`); only the config file shape differs. Use the **absolute
path** to the jar everywhere below. (Remote **URL** clients such as ChatGPT connect
differently — see [ChatGPT and other remote clients](#chatgpt-and-other-remote-url-clients).)

### Claude Desktop

Add to `claude_desktop_config.json` (macOS `~/Library/Application Support/Claude/…`; Windows
`%APPDATA%\Claude\…`):

```json
{
  "mcpServers": {
    "cqels-fleet": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/CQELS4J/mcp-server/target/cqels-mcp-server.jar"]
    }
  }
}
```

Restart Claude Desktop; the `cqels-fleet` tools appear under the tools menu.

### Claude Code

Add the same `mcpServers` entry to `.mcp.json` in your project root.

### Codex CLI

Add to `~/.codex/config.toml`:

```toml
[mcp_servers.cqels-fleet]
command = "java"
args = ["-jar", "/absolute/path/to/CQELS4J/mcp-server/target/cqels-mcp-server.jar"]
```

### Cursor / Windsurf / other MCP clients

Same `command` + `args` (`java -jar …`) in whatever `mcpServers` block your client expects.

### ChatGPT and other remote (URL) clients

ChatGPT connectors reach an MCP server as a **remote HTTPS URL**, not a local stdio process.
Two options:

- **The published server, natively.** `org.cqels:cqels-mcp` ships a ready-to-run `-shaded`
  jar with a built-in Streamable-HTTP transport: fetch it with
  `mvn dependency:copy -Dartifact=org.cqels:cqels-mcp:2.0.0-alpha.13:jar:shaded` (same GitHub
  Packages token setup as in GETTING_STARTED) and run it with `CQELS_MCP_TRANSPORT=http`
  (host/port/path, bearer-token auth, and origin allow-lists are configurable via
  `CQELS_MCP_HTTP_*` environment variables). This is the production remote path — it starts
  empty (no fleet seed).
- **This launcher, via a gateway.** This jar is stdio-only; wrap it with a stdio→HTTP gateway
  such as [`supergateway`](https://github.com/supercorp-ai/supergateway) to keep the V2G seed:

  ```bash
  npx -y supergateway --stdio "java -jar /absolute/path/to/CQELS4J/mcp-server/target/cqels-mcp-server.jar"
  ```

Either way, front the endpoint with **TLS + authentication** and do not expose it directly to
the internet.

### Try it

> *Memory:* "What do we know about vehicle EV-7Q2?" → the assistant calls `recall_memory` (or
> `query` with SPARQL) and finds the seeded driver, depot, route, and duty-reserve facts.
> "Remember that EV-7Q2 is back in service." → `store_memory`.
>
> *Streaming:* "Watch the telemetry stream for vehicles over 120 km/h, then send speeds 135,
> 90 and 128." → `create_stream`, `register_stream_query`, `push_stream_events`, then
> `recall_memory(queryId)` reports the two speeding vehicles.
>
> *Standing invariants:* "Alert me if any battery reading drops below the vehicle's next-duty
> reserve." → `watch_invariant` with a SHACL shape, or `register_rules` with an ASP program —
> then every pushed observation is checked continuously.

## Extending it

The launcher is deliberately small — the embedding API is two classes from
`org.cqels:cqels-mcp`:

```java
CqelsMcpServerConfig config = CqelsMcpServerConfig.builder()
        .serverName("cqels-fleet-mcp")
        .engineId("cqels-fleet-engine")
        .rdfStorePath(storeDir.toString())           // the pre-seeded RDF store (NativeStore)
        // .storageBackend("lmdb")                      // durable operator-state backend
        //     .storageBackendPath("/var/lib/cqels/state")
        // .streamReasoning("rdfs")                     // incremental RDFS inference over streams
        // .transport(CqelsMcpServerConfig.Transport.HTTP) // Streamable-HTTP instead of stdio
        .build();
CqelsMcpServer server = new CqelsMcpServer(config);
server.start();                       // reopens the store, wires tools/resources/prompts, starts the engine
```

Swap the seed for your own domain by editing `seedFleetWorld` — write your background graph
into `cqels://memory/longterm` (or a `cqels://memory/user/...` graph) so `recall_memory`
pattern recall sees it, and shut your seeding repository down before `start()` (NativeStore
holds a directory lock). To add domain-specific tools alongside the built-ins, register them
on `server.getMcpServer()` after `start()`.
