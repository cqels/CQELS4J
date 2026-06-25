# CQELS as an MCP server

A minimal [Model Context Protocol](https://modelcontextprotocol.io/) (MCP) server that
exposes a **CQELS engine as AI-accessible tools** over stdio — both the static RDF store
*and* the **streaming engine** that the [`examples/`](../examples/) demonstrate. An MCP
client (Claude Desktop, an IDE assistant, or any MCP-capable agent) can use a CQELS RDF
store as **queryable memory**, and also **register continuous CQELS-QL queries** (windows,
aggregates, CEP — the same query shapes as the examples), feed them events, and read what
the engine emits. This example uses `.withMemoryStore()`, so the store is in-memory and
**session-scoped (cleared when the process exits)**; swap in a durable storage backend for
persistence across restarts.

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

**Streaming (continuous queries — mirrors the [`examples/`](../examples/)):**

| Tool | Arguments | What it does |
|------|-----------|--------------|
| `push_event` | `stream`, `subject`, `predicate`, `object` | Push one triple as an event into a named stream (created on first use). Numeric objects become numeric literals so `FILTER`/aggregates work; `http(s)://` becomes an IRI. |
| `register_stream_query` | `query` | Register a continuous CQELS-QL query (`REGISTER QUERY … FROM STREAM … [window] …`). Returns a query **id**; emitted rows are buffered server-side (bounded — see below). |
| `poll_results` | `queryId` | Drain and return up to 100 rows the query has emitted since the last poll; flags if more remain or if any were dropped. |
| `unregister_stream_query` | `queryId` | Stop a query and free its buffer when you're done with it. |

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
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"store_fact","arguments":{"subject":"http://ex/alice","predicate":"http://ex/knows","object":"http://ex/bob"}}}' \
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"query","arguments":{"sparql":"SELECT ?s ?p ?o WHERE { ?s ?p ?o }"}}}'; \
  sleep 2 ) | java -jar target/cqels-mcp-server.jar &
sleep 4; kill %1 2>/dev/null   # stop the long-running server
```

You should see the `initialize` result, then `Stored: …`, then the queried triple. (An MCP
client such as Claude Desktop manages this lifecycle for you — it launches the process and
sends SIGTERM on shutdown, which the server's hook handles cleanly.)

### Streaming smoke test

The same way, drive a **continuous query**: register a `[NOW]` filter, push three sensor
readings, then poll. (Send requests with a small gap so each completes before the next —
the order must be register → push → poll, just as a real client awaits each response.)

```bash
{ emit() { printf '%s\n' "$1"; sleep 0.6; }
  emit '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"cli","version":"1.0"}}}'
  emit '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  emit '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"register_stream_query","arguments":{"query":"PREFIX ex: <http://ex/> REGISTER QUERY Hot AS SELECT ?sensor ?t FROM STREAM Sensors [NOW] WHERE { STREAM Sensors { ?sensor ex:temp ?t . } FILTER(?t > 30) }"}}}'
  emit '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"push_event","arguments":{"stream":"Sensors","subject":"http://ex/s1","predicate":"http://ex/temp","object":"35"}}}'
  emit '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"push_event","arguments":{"stream":"Sensors","subject":"http://ex/s2","predicate":"http://ex/temp","object":"20"}}}'
  emit '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"push_event","arguments":{"stream":"Sensors","subject":"http://ex/s3","predicate":"http://ex/temp","object":"40"}}}'
  emit '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"poll_results","arguments":{"queryId":"Hot"}}}'
} | java -jar target/cqels-mcp-server.jar &
sleep 6; kill %1 2>/dev/null
```

The final `poll_results` returns the two readings above the threshold
(`{t=35, sensor=…/s1}` and `{t=40, sensor=…/s3}`); `s2=20` is correctly filtered out.

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

> *Memory:* "Store that alice knows bob, and that bob works at Acme." → the assistant calls
> `store_fact` twice. "Who does alice know?" → it calls `query` with
> `SELECT ?o WHERE { <http://ex/alice> <http://ex/knows> ?o }`.
>
> *Streaming:* "Watch the Sensors stream for readings over 30°C, then send me 35, 20 and 40."
> → the assistant calls `register_stream_query`, three `push_event`s, then `poll_results` and
> reports back the two hot readings.

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
