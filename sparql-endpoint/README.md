# CQELS SPARQL 1.1 Protocol endpoint

A standard SPARQL endpoint over an embedded CQELS engine's **background graph** — the graph the
engine joins its streams against. Any SPARQL 1.1 Protocol client (curl, YASGUI, rdflib, Jena,
RDF4J's `SPARQLRepository`) can query it. It is a plain `jakarta.servlet` on **Jetty 12**, the
same stack the published MCP server (`org.cqels:cqels-mcp`) embeds, for a reason explained
under *Continuous queries* below.

It is **not** a full triple-store server: no repository management, no transactions, no
Workbench.

## Run

```bash
cd sparql-endpoint && mvn -q package        # -> target/cqels-sparql-endpoint.jar
CQELS_SPARQL_SEED_FLEET=true java -jar target/cqels-sparql-endpoint.jar
```

It prints one line on stdout, `listening on http://127.0.0.1:8089/sparql`, once the engine has
started. Then:

```bash
curl -G http://127.0.0.1:8089/sparql \
  --data-urlencode 'query=SELECT ?v ?name WHERE { ?v <https://covesa.global/fleet#assignedDriver> ?d . ?d <https://covesa.global/fleet#name> ?name }'
```

```bash
scripts/smoke.sh        # starts it on an ephemeral port and drives the protocol with curl
```

`CQELS_SPARQL_SEED_FLEET=true` loads the examples' electric-vehicle fleet / V2G world (the same
facts `mcp-server` seeds). Without it the graph starts empty; load it with SPARQL Update once
`CQELS_SPARQL_UPDATE_ENABLED=true`, or from your own code.

## Run in Docker

No image is published; build one from this directory. The build compiles the jar inside
Docker, so it needs no JDK or Maven on the host:

```bash
cd sparql-endpoint && docker build -t cqels-sparql-endpoint .
```

Inside a container, `127.0.0.1` is the container's own loopback and a published port cannot
reach it, so the image binds `0.0.0.0`. The non-loopback rule under *Security* therefore
applies: **`docker run` with no options refuses to start** (exit 2). Choose one of two modes.

**With a token.** Use this whenever anything beyond your own machine can reach the port:

```bash
docker run --rm -p 8089:8089 \
  -e CQELS_SPARQL_AUTH_TOKEN=change-me -e CQELS_SPARQL_SEED_FLEET=true \
  cqels-sparql-endpoint
```

It runs in the foreground; from another terminal, once it prints its `listening on` line:

```bash
curl -G http://127.0.0.1:8089/sparql -H 'Authorization: Bearer change-me' \
  --data-urlencode 'query=ASK { ?s ?p ?o }'
```

**Local only, no token.** Publish the port on the host's loopback **only** and opt in:

```bash
docker run --rm -p 127.0.0.1:8089:8089 \
  -e CQELS_SPARQL_ALLOW_UNAUTHENTICATED_REMOTE=true -e CQELS_SPARQL_SEED_FLEET=true \
  cqels-sparql-endpoint
```

Never combine this mode with `-p 8089:8089`, which publishes the port on every interface of
the host. The `Host` check still accepts only the loopback names and the bind address `0.0.0.0`, but that
check guards against browsers (DNS rebinding), not against people: anyone who can reach the
port can send `Host: localhost`.

The `listening on http://0.0.0.0:8089/sparql` line names the container's address; connect to
the port you published. Set any other variable in the *Configuration* table below with `-e`.

**A durable graph.** Mount a named volume at `/data` and point the store at it:

```bash
docker run --rm -p 8089:8089 -v cqels-data:/data -e CQELS_SPARQL_STORE_PATH=/data \
  -e CQELS_SPARQL_AUTH_TOKEN=change-me -e CQELS_SPARQL_UPDATE_ENABLED=true \
  cqels-sparql-endpoint
```

The container runs as uid 10001, not root. A new named volume takes the image's `/data`
ownership, so it is writable. A host directory mounted there must be writable by uid 10001.
`docker stop` sends SIGTERM to the JVM, which stops Jetty, the engine and the store cleanly.

```bash
scripts/docker-smoke.sh   # builds the image and checks every claim in this section
```

## Configuration

All settings are environment variables, named like the MCP server's `CQELS_MCP_HTTP_*`.

| Variable | Default | Meaning |
|---|---|---|
| `CQELS_SPARQL_HOST` | `127.0.0.1` | Bind address. A non-loopback host **without a token refuses to start** (see *Security*). |
| `CQELS_SPARQL_PORT` | `8089` | `0` picks an ephemeral port, printed in the `listening on` line. |
| `CQELS_SPARQL_PATH` | `/sparql` | |
| `CQELS_SPARQL_AUTH_TOKEN` | unset | Bearer token. Missing or wrong → `401` with `WWW-Authenticate: Bearer`. |
| `CQELS_SPARQL_ALLOWED_HOSTS` | unset | Comma-separated host names a request's `Host` header may carry **in addition to** `localhost`, `127.0.0.1`, `::1` and the bind host (or `*` for any). Checked whenever no token is set, as the defence against DNS rebinding; behind a proxy or under a DNS name, list the name clients use. |
| `CQELS_SPARQL_ALLOWED_ORIGINS` | unset | Comma-separated browser origins allowed to call the endpoint (or `*`); they get CORS headers. A request carrying any **other** `Origin` is refused with `403` (see *Security*). Requests without `Origin` — curl, rdflib, Jena — are unaffected. |
| `CQELS_SPARQL_MAX_REQUEST_BYTES` | `1048576` | Larger bodies → `413`, form bodies and chunked bodies included. |
| `CQELS_SPARQL_MAX_EXECUTION_SECONDS` | `30` | `Query.setMaxExecutionTime`; exceeding it → `503`, or an aborted response if rows were already sent. `0` = unlimited. **Best effort** — see *The execution limit*. |
| `CQELS_SPARQL_MAX_CONCURRENT_QUERIES` | `16` | Queries and updates in progress at once, counted from the moment their body starts to arrive. Beyond it the request is answered `503` with `Retry-After: 1` immediately, not queued. |
| `CQELS_SPARQL_MAX_UPLOAD_SECONDS` | `30` | A POST body must arrive within this, or `408`. `0` = no deadline. Checked as bytes arrive; a connection that stalls completely is ended by Jetty's 30 s idle timeout. |
| `CQELS_SPARQL_MAX_ROWS` | `0` | SELECT rows, or CONSTRUCT / DESCRIBE statements, beyond this are dropped and `X-CQELS-Truncated: true` is set. `0` = unlimited, streamed. |
| `CQELS_SPARQL_MAX_BUFFERED_BYTES` | `33554432` | With `MAX_ROWS` set the result is held before it is sent; this bounds what is held (estimated from the values' lengths), truncating the same way. See *Memory*. |
| `CQELS_SPARQL_UPDATE_ENABLED` | `false` | Accept SPARQL Update. |
| `CQELS_SPARQL_FEDERATION_ENABLED` | `false` | Accept `SERVICE` (in queries and updates) and `LOAD`. Both make the endpoint itself send requests to a URL the client names; only `http` and `https` IRIs are accepted (redirects too), and both give up after `MAX_EXECUTION_SECONDS`. `LOAD` must be the only operation in its request. See *Security*. |
| `CQELS_SPARQL_METADATA_ENABLED` | `false` | Serve the engine's streams and status as the read-only graph `cqels://endpoint/metadata`; see *The metadata graph*. |
| `CQELS_SPARQL_STORE_PATH` | unset | NativeStore directory for a graph that survives restarts; unset = memory store. |
| `CQELS_SPARQL_SEED_FLEET` | `false` | Seed the fleet world. |
| `CQELS_SPARQL_MAX_THREADS` | `200` | Jetty request threads. Must cover the query slots, the connector's own threads and 4 spares, or start-up is refused: with fewer, requests would queue for a thread before reaching a slot, and neither the immediate `503` nor `/health` would hold. Ignored on virtual threads. |
| `CQELS_SPARQL_VIRTUAL_THREADS` | `false` | Run requests on virtual threads (JDK 21+ runtime; ignored with a warning otherwise). |
| `CQELS_SPARQL_ALLOW_UNAUTHENTICATED_REMOTE` | `false` | Override the start-up refusal. |

## Protocol

| Request | Result |
|---|---|
| `GET /sparql?query=…` | query |
| `POST /sparql` — `application/x-www-form-urlencoded`, `query=` or `update=` | query / update; percent-escapes decode in the declared `charset`, UTF-8 when none |
| `POST /sparql` — `application/sparql-query` | query, body is the query |
| `POST /sparql` — `application/sparql-update` | update, body is the update |
| `default-graph-uri`, `named-graph-uri` | query dataset; overrides `FROM` / `FROM NAMED` in the query |
| `using-graph-uri`, `using-named-graph-uri` | update dataset; together with `USING`, `USING NAMED` or `WITH` in the update → `400` (§2.2.3) |
| relative IRIs | resolved against the request URL, the service-defined base of §2.3 |
| `Accept` | negotiated through RDF4J's writer registries per RFC 9110: `q` values honoured, `q=0` excludes, the most specific range wins, repeated `Accept` fields form one list, parameters count (`text/csv;charset=UTF-8` is more specific than `text/csv`; `charset=ISO-8859-1` matches nothing, since the writers emit UTF-8), and the response is labelled with the media type that matched (`application/json` if that is what was asked for). Defaults: SPARQL JSON for SELECT/ASK, Turtle for CONSTRUCT/DESCRIBE. Also XML, CSV, TSV, binary for results; N-Triples, N-Quads, TriG, RDF/XML, JSON-LD, NDJSON-LD for graphs. JSON-LD and NDJSON-LD are held before they are written, so they are bounded by `CQELS_SPARQL_MAX_BUFFERED_BYTES`; the others stream, Turtle and TriG without pretty-printing (statements are grouped only when consecutive), since RDF4J's pretty-printer buffers. |
| `GET /health`, `GET /ready` | `200` once the engine has started and Jetty is bound (never behind the token). |

| Status | When |
|---|---|
| `400` | malformed query or update, graph parameter that is not an absolute IRI, missing `query`, update dataset parameters plus `USING`/`WITH`, malformed percent-encoding, a request nested too deeply to parse (thousands of parentheses) |
| `401` | token set and missing or wrong |
| `403` | update while `CQELS_SPARQL_UPDATE_ENABLED` is false; `SERVICE` or `LOAD` while `CQELS_SPARQL_FEDERATION_ENABLED` is false, or naming anything but an `http`/`https` IRI while it is true; `Host` not in `CQELS_SPARQL_ALLOWED_HOSTS` (no token set); `Origin` not in `CQELS_SPARQL_ALLOWED_ORIGINS`; a browser request marked `Sec-Fetch-Site: cross-site` or `same-site` with no allowed `Origin` |
| `405` | update sent via GET |
| `406` | nothing in `Accept` matches a registered writer |
| `408` | body not received within `CQELS_SPARQL_MAX_UPLOAD_SECONDS` |
| `413` | body over `CQELS_SPARQL_MAX_REQUEST_BYTES` |
| `415` | unknown POST `Content-Type` or charset |
| `500` | evaluation failed, or the request ran out of heap (see *Memory*) |
| `503` | execution limit exceeded by a query or an update (as Fuseki answers), every query slot busy (then with `Retry-After: 1`), or a JSON-LD / NDJSON-LD result larger than `CQELS_SPARQL_MAX_BUFFERED_BYTES` |
| `502` | a `LOAD` source failed: an HTTP error, a redirect to anything but `http`/`https`, a body over `CQELS_SPARQL_MAX_BUFFERED_BYTES`, or no parseable RDF (`LOAD SILENT`: `204`, nothing changed) |
| `504` | a `SERVICE` or `LOAD` target did not answer within `CQELS_SPARQL_MAX_EXECUTION_SECONDS` |

A result streams as it is produced, so the status is sent with the first buffer. If the
execution limit trips **after** that, the response is aborted — the connection closes without
the final chunk, and curl reports `(18) transfer closed with outstanding read data` — rather
than ending as a short `200` that a client would take for the whole answer.
`CQELS_SPARQL_MAX_ROWS` avoids that for SELECT: with a row cap the result is buffered (up to
the cap) before anything is sent, so a timeout is a clean `503`.

Error responses to an allowed origin keep their CORS headers, and `X-CQELS-Truncated` and
`Retry-After` are exposed to it, so a browser client can read all of them.

A query with no dataset parameters sees **every graph** in the store, which is RDF4J's rule and
why the fleet seed, written to the MCP server's long-term memory graph, answers a plain `SELECT`.

## What the engine sees

- An **update through this endpoint reaches the engine on its next element**, including under
  `FROM <g> CACHE 5m`: the engine listens to its own store for lookup-cache invalidation.
  Measured on `2.0.0-alpha.22`. That is also why update is off by default.
- The endpoint opens one repository connection per request beside the engine's own static
  lookups. Both the memory and the native store allow that. Contention under many concurrent
  clients is not measured yet.
- The `listening on` line is printed **after** `engine.start()`, so the first request can
  already join against the graph.

## The metadata graph

With `CQELS_SPARQL_METADATA_ENABLED=true`, a query whose dataset names
`cqels://endpoint/metadata` reads what the engine says about itself instead of the store:

```sparql
PREFIX cqe: <cqels://ontology/endpoint#>
SELECT ?stream ?name ?key ?value
FROM <cqels://endpoint/metadata>
WHERE {
  <cqels://endpoint/engine> cqe:stream ?stream .
  ?stream cqe:name ?name .
  OPTIONAL { ?stream cqe:attribute [ cqe:key ?key ; cqe:value ?value ] }
}
```

| Node | Triples |
|---|---|
| `<cqels://endpoint/engine>` | `a cqe:Engine`; `cqe:engineId`, `cqe:running`, `cqe:registeredQueries` (a count), `cqe:persistence` (`NO_PERSISTENCE`, `HEALTHY`, …), `cqe:snapshotAt`; with a persistence journal also `cqe:journalId`, `cqe:durablePosition`, `cqe:appliedPosition`; one `cqe:stream` per live stream |
| each stream | `a cqe:Stream`; `cqe:name` (the name as given); `cqe:attribute [ cqe:key …; cqe:value … ]` per attribute. The node is the name itself when that is an absolute IRI, otherwise `cqels://streams/<name>` percent-encoded (the prefix `cqels-mcp` uses) — or a blank node, if that IRI is the engine's node or another stream's name |

- **It is a snapshot, built for each query that names it.** Nothing is cached, so a stream
  created or removed shows in the next query.
- **It is not in the store.** Written into the engine's repository, these triples would turn up
  in every query without `FROM` (RDF4J's default graph is the union of all graphs) — the
  engine's own static joins included. So the snapshot is answered from a memory store of its
  own, which has three consequences:
  - The dataset must name **only** that graph (`FROM`, `FROM NAMED`, or the
    `default-graph-uri` / `named-graph-uri` parameters). Joining it with the store's graphs in
    one query is a `400`.
  - `GRAPH <cqels://endpoint/metadata> { … }` without naming the graph in the dataset is a
    `400` that says so, rather than an empty answer.
  - The graph name is reserved: an update that would leave any triple under it in the store
    is rolled back with a `403`, however it is written.
- **Window contents and the registered queries' texts are not here.** The engine's public API
  has no accessor for either at `2.0.0-alpha.22`; it reports how many queries are registered,
  not which.
- **Off by default**, and a query naming the graph is then a `403`. Stream attributes are
  whatever the program that created the stream put there.
- **The standalone launcher creates no streams**, so on its own it reports the engine's status
  and an empty stream list. The graph pays off when the servlet runs beside an engine that has
  streams: [embedded](#embedding-the-servlet) in your program, or, once the mount point exists,
  in the MCP server's Jetty (see *Continuous queries*).

## The execution limit, measured

RDF4J enforces `setMaxExecutionTime` at operator boundaries, so whether a runaway query stops
depends on where it is. Measured on RDF4J 4.3.8's memory store, 300 triples, a 1-second limit:

| Query | Stopped? |
|---|---|
| streaming rows of a 4-way cross product | yes, after 1.0 s and 7.1 M rows |
| `ORDER BY … LIMIT 1` over the 4-way cross product | yes, after 1.1 s |
| `COUNT(*)` over a 3-way cross product (27 M rows) | yes, but after 3.0 s |
| `COUNT(*)` over the 4-way cross product | **no** — not by the limit, not by closing the connection from another thread, not by interrupting its thread; it runs to completion |

So the limit catches most things and is not a guarantee. A query that is not stopped holds
one query slot until it finishes; `CQELS_SPARQL_MAX_CONCURRENT_QUERIES` bounds how many can,
further requests are turned away with `503` + `Retry-After` at once, and `/health` needs no slot
so probes keep working. Both need a free request thread, which is why start-up refuses a
`CQELS_SPARQL_MAX_THREADS` that does not exceed the slots. The only cure for a stuck query is a restart. This is one more reason to
keep an exposed endpoint behind a token.

## Memory

The endpoint runs in the engine's JVM and shares its heap — it has to, to share the engine's
repository object. RDF4J builds `GROUP BY` groups, sorts, `DISTINCT` sets and computed strings
in that heap, and nothing outside RDF4J can bound them: measured on a 256 MB heap, one
`GROUP BY` over a two-way cross product of 3,000 triples ran out of memory in 3 s. On a 1 GB
heap, a five-way `GROUP BY` over the 43-triple fleet seed was stopped by the execution limit
instead (after 40 s, under a 30 s limit). So:

- **Size the heap for the queries you accept** (`java -Xmx…`), with the engine's own windows
  on top. The execution limit is the main guard: most such queries hit it before the heap.
- **A query that runs out of heap gets a plain `500`** and gives its slot back; the log says
  what happened. Other threads — the engine's included — can still fail while the heap is
  full, which is why the heap size, not this answer, is the defence.
- **What the endpoint itself holds is bounded.** Results stream unless `MAX_ROWS` is set; then
  at most `MAX_ROWS` rows (or statements) and `MAX_BUFFERED_BYTES` of them are held per query,
  so `MAX_CONCURRENT_QUERIES × MAX_BUFFERED_BYTES` in all. A row cap alone did not bound bytes:
  one computed literal made an 8 MB response under `MAX_ROWS=1`. JSON-LD and NDJSON-LD are the
  exceptions to streaming — RDF4J's writers hold the graph, or 1,024 statements of any size —
  so they are always held to `MAX_BUFFERED_BYTES`, and refused (`503`) beyond it when no row
  cap is set. Turtle and TriG stream with pretty-printing off, because the pretty-printer
  buffers too. One path stays unbounded: RDF4J's **binary** SELECT format
  (`application/x-binary-rdf-results-table`) keeps a table of every distinct IRI namespace it
  has written, so a result of many IRIs with long, distinct namespaces grows the heap in
  proportion; a 128 MB heap ran out where SPARQL JSON did not. Capping it would cut legitimate
  large binary results at `MAX_BUFFERED_BYTES`, so it is left as is: if untrusted callers can
  query, expect them to choose it, and size the heap or set `MAX_ROWS`.
- Behind a token, only token holders can send such queries. Without one, anyone who can reach
  the port can.

## Continuous queries

This endpoint serves one-shot SPARQL only: the SPARQL Protocol has no request/response shape for
a query that keeps running. Two routes exist today:

1. **Write results back.** Register the CQELS-QL query in your own code and have its listener
   write each row into the repository (for example into a graph per query); standard clients
   then query the rows here. `SparqlProtocolServletTest.continuousResultsWrittenBackAreQueryable`
   shows the shape.
2. **The MCP server.** `org.cqels:cqels-mcp` has `register_stream_query`, bounded result
   buffers, per-client limits and a Streamable-HTTP transport — see
   [`../mcp-server/README.md`](../mcp-server/README.md).

The planned third route is this servlet **mounted into the MCP server's own Jetty**, so SPARQL
and MCP share one port, one token and one engine. That needs a mount point in `cqels-mcp`,
requested upstream as cqels/claude#1387; the Jetty version here is pinned to the one
`cqels-mcp` embeds so the mount will not split across two Jettys. Tracking: cqels/CQELS4J#104.

## Security

- Defaults are loopback, read-only, no federation, no browser origins, 30-second execution limit, 1 MiB bodies.
- **`SERVICE` and `LOAD` are refused unless `CQELS_SPARQL_FEDERATION_ENABLED=true`.** They make
  the endpoint send requests of its own. A `SERVICE` clause sends the bindings it has so far,
  taken from the graph, to whatever URL the query names, and the trigger can be a plain GET:
  a cross-site `<img src="http://127.0.0.1:8089/sparql?query=…">` reads nothing back, but the
  server has already posted the data to the attacker's service. CORS cannot stop that, since the
  data never passes through the browser. The test suite reproduces it with federation on (the
  stand-in remote receives the vehicles' IRIs) and checks that nothing leaves with it off.
  Enable federation only behind a token, and only for callers you would let fetch any web
  URL from the server's network position — there is no allow-list of hosts.
- **With federation on, only `http` and `https` are reachable.** `LOAD <file:///…>` read an RDF
  file off the server's disk into the store, where the next query returned it; `SERVICE` and
  `LOAD` naming any other scheme are now refused (`403`). RDF4J's own `LOAD` also followed
  redirects by hand, to any scheme — an http URL redirecting to `file:` read the file — and
  waited without a deadline, so **the endpoint performs `LOAD` itself**, with Java's HTTP
  client: redirects are followed only to `http`/`https` (never from `https` to `http`), the
  whole fetch must finish within `CQELS_SPARQL_MAX_EXECUTION_SECONDS` (`504`), and at most
  `CQELS_SPARQL_MAX_BUFFERED_BYTES` of body is held, after undoing a `gzip` or `deflate`
  `Content-Encoding` and unpacking a `.gz` file or `.zip` archive as RDF4J's loader did
  (`502` beyond). A JSON-LD `@context` the
  document names is fetched under the same rules and the same deadline (jsonld-java's own loader
  read `file:` contexts), and RDF/XML and TriX are parsed with no doctype and no external
  entities (RDF4J's defaults let an entity read a local file). The cost: `LOAD` must be
  the only operation in its request (`400` otherwise). `SERVICE` gets the same deadline
  through a client installed on the engine repository's federated-service resolver
  (`SparqlEndpointLauncher.limitOutboundRequests`). The deadline is absolute: a request still
  running at it is aborted, so a remote that trickles its headers or body a byte at a time
  cannot hold the slot. The client stays installed for the repository's lifetime, so the
  engine's own `SERVICE` calls get the deadline too (with `MAX_EXECUTION_SECONDS=0` it is
  installed with no deadline, for the format rule below); its connections are released when Jetty
  stops and reopen if it is used again. A `SERVICE` endpoint must answer SPARQL JSON, binary,
  TSV or CSV: SPARQL XML is refused, because RDF4J parses it with settings under which an XML
  entity read a local file into the results. Nothing is set JVM-wide.
- **Browser requests from other origins are refused (`403`).** Loopback is not a boundary
  against a browser: any page the user visits can submit a cross-origin form POST — a CORS
  "simple" request, sent without a preflight — to `127.0.0.1:8089`. Withholding CORS headers
  only hides the response; the update would still run. Browsers attach `Origin` to every
  cross-origin POST, so the endpoint refuses any `Origin` not on `CQELS_SPARQL_ALLOWED_ORIGINS`.
  Setting that to `*` with update enabled and no token undoes this; don't.
- **DNS rebinding is refused by `Host`.** An attacker's page can re-resolve its own domain to
  `127.0.0.1`; its requests are then same-origin to the browser (no `Origin`,
  `Sec-Fetch-Site: same-origin`) and it can read the answers. Only the `Host` header still names
  the attacker's domain, so without a token the endpoint accepts only `localhost`, `127.0.0.1`,
  `::1` and the bind host, plus `CQELS_SPARQL_ALLOWED_HOSTS`. With a token there is no host
  check unless you list hosts: the browser never sends the token for the attacker's page.
  Binding `0.0.0.0` with `CQELS_SPARQL_ALLOWED_HOSTS` unset and `…_ALLOW_UNAUTHENTICATED_REMOTE`
  therefore answers only to loopback names; list the names remote clients use.
- A browser GET from an `<img>` or a link carries no `Origin`, so it also checks Fetch Metadata:
  `Sec-Fetch-Site: cross-site` or `same-site` with no allowed `Origin` is refused (`403`).
  `none` (a URL typed into the address bar), `same-origin`, and clients that send no
  `Sec-Fetch-Site` at all (curl, rdflib, Jena) pass. Browsers that predate Fetch Metadata
  are not covered; the federation default above does not depend on it.
- Binding anything but a loopback address with no `CQELS_SPARQL_AUTH_TOKEN` is refused at
  start-up, unless `CQELS_SPARQL_ALLOW_UNAUTHENTICATED_REMOTE=true`. `startServer` refuses it
  too (`EndpointOptions.allowUnauthenticatedRemote`), so a program embedding the endpoint
  through it gets the same rule.
- A request takes its query slot before its body is read, so a client that trickles a body
  holds a slot — the next request beyond the cap is still told `503` at once and `/health`
  still answers — until `CQELS_SPARQL_MAX_UPLOAD_SECONDS` gives the slot back.
- TLS is not terminated here. Front the endpoint with a TLS-terminating proxy, as the MCP
  README says for its own HTTP transport.
- A bearer token is compared in constant time. `/health` and `/ready` are never behind it.

## Alternatives

- **RDF4J's own Spring Boot endpoint** (`rdf4j-spring-boot-sparql-web`) works over
  `engine.getRepository()` too, but at RDF4J 4.3.8 it is Spring Boot 2.7 on `javax.servlet`,
  read-only, and its BOM downgrades the engine's Reactor, Jackson and SLF4J unless you fight
  it. This project reuses RDF4J's protocol library layer instead and writes the HTTP adapter
  (a few hundred lines) itself.

## Embedding the servlet

`SparqlProtocolServlet` depends only on `jakarta.servlet` and RDF4J. Mount it in any Servlet 6
container. It parses its own parameters and enforces `maxRequestBytes` itself, so the
container's form limit does not matter. It checks **neither tokens, hosts nor origins**:
install `AccessFilters.Hosts` (without a token), `AccessFilters.Origins` (always) and
`AccessFilters.BearerToken` (when exposed) in front of it, as the launcher does, or your
container's equivalents:

```java
EndpointOptions options = EndpointOptions.defaults().withMaxRows(10_000);
var requests = EnumSet.of(DispatcherType.REQUEST);
// without a token, Hosts is what stops DNS rebinding: list every name clients reach you by
context.addFilter(new FilterHolder(new AccessFilters.Hosts(List.of("localhost", "127.0.0.1", "::1"))), "/sparql", requests);
context.addFilter(new FilterHolder(new AccessFilters.Origins(List.of())), "/sparql", requests);
context.addServlet(new ServletHolder(new SparqlProtocolServlet(engine::getRepository, options)), "/sparql");
```

With federation enabled, also call `SparqlEndpointLauncher.limitOutboundRequests(engine.getRepository(),
seconds)` once, before the first request: it gives `SERVICE` its deadline (see *Security*);
`startServer` does this for you. `LOAD` needs nothing: the servlet performs it itself.

To serve [the metadata graph](#the-metadata-graph), pass a third argument,
`EngineMetadata.of(engine)`: `new SparqlProtocolServlet(engine::getRepository, options,
EngineMetadata.of(engine))`.

Give it the engine's `Repository` object, never a store path: a NativeStore holds a directory
lock, and a second open on the same path fails. A repository you hand to
`CQELSEngine.builder().repository(…)` stays yours to shut down — `engine.close()` leaves it
initialized (measured on `2.0.0-alpha.22`), so the launcher shuts its NativeStore down itself.

## Dependency notes

- `jetty.version` must equal the Jetty `org.cqels:cqels-mcp` embeds at the pinned engine version.
- Declaring any RDF4J module directly pulls `jackson-core 2.13.5` and `slf4j-api 1.7.36` nearer
  than the engine's `2.17.0` / `2.0.9`, and Maven's nearest-wins rule would mix Jackson versions.
  The pom pins both in `dependencyManagement`; keep the pins equal to the engine's versions.
