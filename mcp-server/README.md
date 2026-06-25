# CQELS as an MCP server

A minimal [Model Context Protocol](https://modelcontextprotocol.io/) (MCP) server that
exposes a **CQELS engine as AI-accessible tools** over stdio. An MCP client — Claude
Desktop, an IDE assistant, or any MCP-capable agent — can then use a CQELS RDF store as
**queryable memory** it can write to and query. This example uses
`.withMemoryStore()`, so the store is in-memory and **session-scoped (cleared when the
process exits)**; swap in a durable storage backend for persistence across restarts.

Its core dependencies are the published `cqels-engine` and the official
[MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk) (`io.modelcontextprotocol.sdk:mcp`),
plus an `slf4j-simple` binding (so logs go to **stderr**, keeping stdout clean for JSON-RPC)
and a `jackson-annotations` version pin (see the pom). It's a self-contained starting point
for wrapping CQELS in your own MCP server.

## Tools exposed

| Tool | Arguments | What it does |
|------|-----------|--------------|
| `store_fact` | `subject`, `predicate`, `object` | Add one RDF triple to memory. `object` is treated as an IRI if it starts with `http(s)://`, otherwise as a literal. |
| `query` | `sparql` | Run a SPARQL `SELECT` over everything stored so far; returns the rows as text (capped at the first 100, with a truncation marker beyond that). |

> Both tools are synchronous request/response — the natural fit for MCP. Continuous CQELS-QL
> queries (windows, CEP) push results asynchronously; see the [`examples/`](../examples/)
> for those. **stdout is reserved for the JSON-RPC protocol** — this server logs only to
> stderr, which is why it never prints stream results to stdout. (Keep it that way: don't set
> `org.slf4j.simpleLogger.logFile=System.out`, and don't `System.out.println` from a tool
> handler — either would interleave bytes into the JSON-RPC stream and break the protocol.)

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

> "Store that alice knows bob, and that bob works at Acme." → the assistant calls
> `store_fact` twice.
>
> "Who does alice know?" → the assistant calls `query` with
> `SELECT ?o WHERE { <http://ex/alice> <http://ex/knows> ?o }`.

## Extending it

`CqelsMemoryMcpServer` registers each tool with
`server.addTool(new McpServerFeatures.SyncToolSpecification(tool, handler))`. To add your
own (e.g. a `register_stream_query` tool, or SHACL validation), add another
`SyncToolSpecification` with a JSON input schema and a handler returning a
`CallToolResult`. The engine is a full `CQELSEngine`, so you have the streaming, CEP,
reasoning, and SHACL APIs available too.
