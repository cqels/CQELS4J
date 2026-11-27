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
`docker stop` sends SIGTERM to the JVM, which stops Jetty (letting requests in progress finish;
see *Operations*), the engine and the store cleanly.

```bash
scripts/docker-smoke.sh   # builds the image and checks every claim in this section
```

## Configuration

All settings are environment variables, named like the MCP server's `CQELS_MCP_HTTP_*`.

| Variable | Default | Meaning |
|---|---|---|
| `CQELS_SPARQL_HOST` | `127.0.0.1` | Bind address. A non-loopback host **without a read token refuses to start** (see *Security*). |
| `CQELS_SPARQL_PORT` | `8089` | `0` picks an ephemeral port, printed in the `listening on` line. |
| `CQELS_SPARQL_PATH` | `/sparql` | |
| `CQELS_SPARQL_AUTH_TOKEN` | unset | Bearer token. Missing or wrong → `401` with `WWW-Authenticate: Bearer`. Read-only when `CQELS_SPARQL_UPDATE_TOKEN` is set. |
| `CQELS_SPARQL_UPDATE_TOKEN` | unset | A second bearer token, the only one that may update; must differ from `AUTH_TOKEN`. An update with the read token (or, if `AUTH_TOKEN` is unset, with no token) → `403`. See *Security*. |
| `CQELS_SPARQL_ALLOWED_HOSTS` | unset | Comma-separated host names a request's `Host` header may carry **in addition to** `localhost`, `127.0.0.1`, `::1` and the bind host (or `*` for any). Checked whenever no read token is set (an update token alone leaves reads open), as the defence against DNS rebinding; behind a proxy or under a DNS name, list the name clients use. |
| `CQELS_SPARQL_ALLOWED_ORIGINS` | unset | Comma-separated browser origins allowed to call the endpoint (or `*`); they get CORS headers. A request carrying any **other** `Origin` is refused with `403` (see *Security*). Requests without `Origin` — curl, rdflib, Jena — are unaffected. |
| `CQELS_SPARQL_MAX_REQUEST_BYTES` | `1048576` | Larger bodies → `413`, form bodies and chunked bodies included. |
| `CQELS_SPARQL_MAX_EXECUTION_SECONDS` | `30` | `Query.setMaxExecutionTime`; exceeding it → `503`, or an aborted response if rows were already sent. `0` = unlimited. **Best effort** — see *The execution limit*. |
| `CQELS_SPARQL_MAX_CONCURRENT_QUERIES` | `16` | Queries and updates in progress at once, counted from the moment their body starts to arrive. Beyond it the request is answered `503` with `Retry-After: 1` immediately, not queued. |
| `CQELS_SPARQL_MAX_UPLOAD_SECONDS` | `30` | A POST body must arrive within this, or `408`. `0` = no deadline. A watchdog enforces it while the read is blocked too, so an upload that stops sending, or keeps a chunked body's framing busy without delivering a byte, is ended at the deadline. |
| `CQELS_SPARQL_MAX_ROWS` | `0` | SELECT rows, or CONSTRUCT / DESCRIBE statements, beyond this are dropped and `X-CQELS-Truncated: true` is set. `0` = unlimited, streamed. |
| `CQELS_SPARQL_MAX_BUFFERED_BYTES` | `33554432` | With `MAX_ROWS` set the result is held before it is sent; this bounds what is held (estimated from the values' lengths), truncating the same way. See *Memory*. |
| `CQELS_SPARQL_UPDATE_ENABLED` | `false` | Accept SPARQL Update. |
| `CQELS_SPARQL_FEDERATION_ENABLED` | `false` | Accept `SERVICE` (in queries and updates) and `LOAD`. Both make the endpoint itself send requests to a URL the client names; only `http` and `https` are reached (redirects and endpoints bound at run time too), all of one query's `SERVICE` calls together must finish within `MAX_EXECUTION_SECONDS`, and `LOAD` must be the only operation in its request. See *Federation*. |
| `CQELS_SPARQL_FEDERATION_DESTINATIONS` | `public` | Where `SERVICE` and `LOAD` may send requests, comma-separated: `public` (any host whose every address is public), `http(s)` origins such as `http://localhost:3030` (that scheme, host and port, whatever its addresses), `*` (anywhere). See *Destinations*. |
| `CQELS_SPARQL_MAX_SERVICE_REQUESTS` | `100` | The most `SERVICE` requests one query or update makes (RDF4J joins in batches of 15 rows); beyond it, `503`. |
| `CQELS_SPARQL_MAX_SERVICE_BYTES` | `16777216` | The most one query or update reads from `SERVICE` answers in all, decoded, error answers included; beyond it, `502`, and no further request. Each answer is held whole while it is checked, so the heap it takes is a multiple of this (the bytes, a copy, and what parsing builds). |
| `CQELS_SPARQL_METADATA_ENABLED` | `false` | Serve the engine's streams and status as the read-only graph `cqels://endpoint/metadata`; see *The metadata graph*. |
| `CQELS_SPARQL_STORE_PATH` | unset | NativeStore directory for a graph that survives restarts; unset = memory store. |
| `CQELS_SPARQL_SEED_FLEET` | `false` | Seed the fleet world. |
| `CQELS_SPARQL_MAX_THREADS` | `200` | Jetty request threads. Must cover the query slots, the connector's own threads and 4 spares, or start-up is refused: with fewer, requests would queue for a thread before reaching a slot, and neither the immediate `503` nor `/health` would hold. Ignored on virtual threads. |
| `CQELS_SPARQL_VIRTUAL_THREADS` | `false` | Run requests on virtual threads (JDK 21+ runtime; ignored with a warning otherwise). |
| `CQELS_SPARQL_SHUTDOWN_SECONDS` | `5` | On stop, requests in progress get this long to finish while new ones are refused; then the rest are cut off, and an update among them that has not committed by the time the endpoint has stopped is rolled back. `0` = cut off at once. See *Operations*. |
| `CQELS_SPARQL_ACCESS_LOG` | `true` | One stderr line per request: client, method, path **without the query string**, status, bytes, milliseconds. |
| `CQELS_SPARQL_ALLOW_UNAUTHENTICATED_REMOTE` | `false` | Override the start-up refusal. |

## Protocol

| Request | Result |
|---|---|
| `GET /sparql?query=…` | query. Jetty refuses a request line over 8 KB with `414`, so send long queries by POST |
| `GET /sparql` (no query string) | the SPARQL 1.1 Service Description, Turtle by default or any RDF format `Accept` names; see below |
| `POST /sparql` — `application/x-www-form-urlencoded`, `query=` or `update=` | query / update; percent-escapes decode in the declared `charset`, UTF-8 when none |
| `POST /sparql` — `application/sparql-query` | query, body is the query |
| `POST /sparql` — `application/sparql-update` | update, body is the update |
| `query`, `update` | exactly once: given twice (in the URL twice, or in the URL and a form body) → `400`, as is either in the URL of a direct POST, whose body is the operation |
| `default-graph-uri`, `named-graph-uri` | query dataset; overrides `FROM` / `FROM NAMED` in the query. Repeatable |
| `using-graph-uri`, `using-named-graph-uri` | update dataset; together with `USING`, `USING NAMED` or `WITH` in the update → `400` (§2.2.3) |
| relative IRIs | resolved against the request URL, the service-defined base of §2.3 |
| a prefix declared twice | the last declaration wins, as in Jena and Virtuoso — a **deliberate leniency**: SPARQL 1.1 Query §19.5 says a prefix "may not be re-declared in the same query", and RDF4J refuses it ("Multiple prefix declarations"). rdflib's `SPARQLStore` prepends about 30 namespaces to every query, so a query declaring `skos:` itself was a `400`; the endpoint now blanks the earlier declarations before RDF4J parses. Only the leading prologue is read (in an update, the one before its first operation), and only while it follows the grammar exactly (ASCII prefix names); anything else is left to RDF4J, so a malformed request is still a `400`. Any backslash inside a comment (RDF4J decodes `\u` escapes before it reads the query) leaves the whole request as sent. So does an earlier declaration whose IRI is neither the same as the kept one's (rdflib's case) nor an absolute, strictly valid IRI: RDF4J could refuse it, and the request stays a `400` |
| bytes | decoded strictly in the declared charset (UTF-8 when none), the query string and percent-escapes included: anything that is not valid in it → `400`, never a silent U+FFFD |
| `Accept` | negotiated through RDF4J's writer registries per RFC 9110: `q` values honoured, `q=0` excludes, the most specific range wins, repeated `Accept` fields form one list, parameters count (`text/csv;charset=UTF-8` is more specific than `text/csv`; `charset=ISO-8859-1` matches nothing, since the writers emit UTF-8), quoted parameter values may hold `,` and `;`, and the response is labelled with the media type that matched (`application/json` if that is what was asked for) and carries `Vary: Accept` — errors included, the token, host and origin refusals too — so a cache keeps formats apart (the `503` Jetty itself sends while stopping does not carry it). Defaults: SPARQL JSON for SELECT/ASK, Turtle for CONSTRUCT/DESCRIBE. Also XML, CSV, TSV, binary for results; N-Triples, N-Quads, TriG, RDF/XML, JSON-LD, NDJSON-LD for graphs. JSON-LD and NDJSON-LD are held before they are written, so they are bounded by `CQELS_SPARQL_MAX_BUFFERED_BYTES`; the others stream, Turtle and TriG without pretty-printing (statements are grouped only when consecutive), since RDF4J's pretty-printer buffers. |
| `GET /health`, `GET /ready`, `GET /metrics` | probes and counters, never behind the token; see *Operations* |
| other methods | `405` with `Allow: GET, HEAD, POST, OPTIONS`; `TRACE` included |

| Status | When |
|---|---|
| `400` | malformed query or update, graph parameter that is not an absolute IRI, missing `query`, `query` or `update` given more than once (or in the URL of a direct POST), update dataset parameters plus `USING`/`WITH`, malformed percent-encoding, bytes not valid in the declared charset, a request nested too deeply to parse (thousands of parentheses); with federation on, `SERVICE` inside an `ORDER BY` expression, or a `SERVICE` IRI with a `query` parameter of its own (see *Federation*) |
| `401` | token set and missing or wrong |
| `403` | update while `CQELS_SPARQL_UPDATE_ENABLED` is false; update without the update token when `CQELS_SPARQL_UPDATE_TOKEN` is set (with `WWW-Authenticate: Bearer error="insufficient_scope"`); `SERVICE` or `LOAD` while `CQELS_SPARQL_FEDERATION_ENABLED` is false, or naming anything but an `http`/`https` IRI while it is true, or a destination `CQELS_SPARQL_FEDERATION_DESTINATIONS` does not admit (a `SERVICE ?var` bound at run time included, `SILENT` or not); `Host` not in `CQELS_SPARQL_ALLOWED_HOSTS` (checked when no read token is set, or when the list is given); `Origin` not in `CQELS_SPARQL_ALLOWED_ORIGINS`; a browser request marked `Sec-Fetch-Site: cross-site` or `same-site` with no allowed `Origin` |
| `405` | update sent via GET; any method but GET, HEAD, POST, OPTIONS. Always with `Allow` |
| `406` | nothing in `Accept` matches a registered writer |
| `408` | body not received within `CQELS_SPARQL_MAX_UPLOAD_SECONDS` |
| `413` | body over `CQELS_SPARQL_MAX_REQUEST_BYTES` |
| `414` | a GET whose request line is over 8 KB (Jetty's limit); POST the query instead |
| `415` | unknown POST `Content-Type` or charset |
| `500` | evaluation failed, or the request ran out of heap (see *Memory*) |
| `503` | execution limit exceeded by a query or an update (as Fuseki answers), a request arriving while the endpoint stops, a `LOAD` the endpoint's stop cuts off (`SILENT` or not), every query slot busy (then with `Retry-After: 1`), a JSON-LD / NDJSON-LD result larger than `CQELS_SPARQL_MAX_BUFFERED_BYTES` with no `MAX_ROWS` set (with one, it is truncated instead), `SERVICE` while the repository's `SERVICE` client lacks the endpoint's limits (an embedding that skipped `limitOutboundRequests`), or a query needing more than `CQELS_SPARQL_MAX_SERVICE_REQUESTS` `SERVICE` requests |
| `502` | a `LOAD` source failed: an HTTP error, a redirect to anything but `http`/`https`, a body over `CQELS_SPARQL_MAX_BUFFERED_BYTES`, or no parseable RDF (`LOAD SILENT`: `204`, nothing changed); a `SERVICE` or `LOAD` redirect, or a `LOAD` document's JSON-LD context, to a destination `CQELS_SPARQL_FEDERATION_DESTINATIONS` refuses (`SILENT` or not); a `SERVICE` endpoint failed: unreachable, an HTTP error (quoted: `answered HTTP 403: …`), a format other than SPARQL JSON, a malformed result, an answer that breaks off, a redirect off `http`/`https` or from `https` to `http`, or answers over `CQELS_SPARQL_MAX_SERVICE_BYTES` |
| `504` | a `SERVICE` or `LOAD` target did not answer within `CQELS_SPARQL_MAX_EXECUTION_SECONDS` — for `SERVICE`, counted from the start of the query, for all its calls together |

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

## Operations

| Path | Answers |
|---|---|
| `/health` | Liveness: `200 ok` while the engine runs, `503` with the reason once it has stopped. Busy is not dead, so a full set of query slots leaves it `200`. |
| `/ready` | Readiness: `200 ready`, or `503` with the reason when the engine is not running, the repository is not initialized, or every query slot is taken (a query would only be told `503`). |
| `/metrics` | Prometheus text: `cqels_sparql_requests_total{code="…"}` per status Jetty sent on the SPARQL path, whatever answered (the host, origin and token refusals, Jetty's own errors and the `503`s of a stop included), `cqels_sparql_aborted_total` (responses cut off after they started; each is also counted under the status it started with), `cqels_sparql_busy_total`, `cqels_sparql_slots_in_use`, `cqels_sparql_slots_limit`, `cqels_sparql_engine_running`. |

None of the three needs a query slot or the token; they carry counts and states only, no query
text, client address or token. Before the audit after #121, `/health` and `/ready` said `ok`
and `ready` after the engine had stopped and with every slot stuck, and nothing was counted.

- **Logs.** One access-log line per request on stderr (logger `org.cqels.playground.sparql.access`,
  `CQELS_SPARQL_ACCESS_LOG=false` turns it off) with the path but never the query string: a query
  can carry data that is not the log's to keep. A full set of query slots is also logged, at most
  once a minute.
- **Stopping.** On SIGTERM (`docker stop`, Ctrl-C) Jetty stops accepting, answers `503` to any
  request already on an open connection, and gives the requests in progress
  `CQELS_SPARQL_SHUTDOWN_SECONDS` (5) to finish; then it cuts off the rest, and the engine and the
  store close. The default leaves room inside Docker's 10 s before it kills the process. A query
  the execution limit cannot stop (see below) is among the ones cut off. Cutting a request off
  closes its connection but cannot stop its thread, so an update still running then would commit
  later for a client already told it failed; the endpoint refuses that commit once it has stopped
  and rolls the update back. The one window left is an update that commits in the instant between
  its connection closing and the endpoint finishing its stop: it takes effect, and its client
  cannot tell.
- **Not served:** the SPARQL 1.1 Graph Store Protocol (`PUT`, `DELETE`, … are `405`). It is a
  feature of its own; SPARQL Update covers what it would.

## The service description

A `GET` of the endpoint with no query string answers its
[SPARQL 1.1 Service Description](https://www.w3.org/TR/sparql11-service-description/) (§2), so a
client or a federation engine can find out how to talk to it. It was a `400`.

```bash
curl http://127.0.0.1:8089/sparql
```

| Says | When |
|---|---|
| `sd:endpoint` | always: the URL the request was sent to |
| `sd:supportedLanguage sd:SPARQL11Query` | always |
| `sd:supportedLanguage sd:SPARQL11Update` | `CQELS_SPARQL_UPDATE_ENABLED=true` (a read token still cannot update) |
| `sd:feature sd:BasicFederatedQuery` | `CQELS_SPARQL_FEDERATION_ENABLED=true` and `SERVICE` would run, not be refused with `503` (see *Embedding the servlet*) |
| `sd:resultFormat` | every format the endpoint can answer in that has a W3C format IRI |
| `sd:availableGraphs` naming `cqels://endpoint/metadata` | the metadata graph is served |

- **No `sd:UnionDefaultGraph`.** That feature means the default graph is the union of the named
  graphs; RDF4J's also holds the triples stored in no named graph, so a client relying on the
  feature's meaning would get different answers.
- **Nothing about the data.** No graph names from the store and no counts: listing them would
  scan the store on every request, and tell a reader what the token guards.
- **The same filters as a query.** The token, the `Host` and `Origin` checks run before it; it
  needs no query slot.
- **Only the bare endpoint.** Parameters without a `query` (`?default-graph-uri=…`) are still a `400`.

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
Mounted there, the servlet could not federate: `cqels-mcp` refuses `SERVICE` by replacing the
engine repository's federated-service resolver with one that refuses every call, so
`limitOutboundRequests` has no client to install and start-up with federation on fails. That is
safe (nothing leaves), but federation beside MCP needs `cqels-mcp` to refuse per query instead.

A CQELS-QL continuous query cannot use `SERVICE` at all: the language has no `SERVICE` clause, and
`registerCqelsQuery` rejects one as a syntax error. Its static patterns are looked up in the
engine's own repository only.

## Federation

With `CQELS_SPARQL_FEDERATION_ENABLED=true`, `SERVICE` works as SPARQL 1.1 Federated Query
describes, through RDF4J: a plain join, `SERVICE SILENT`, an endpoint taken from a variable
(`SERVICE ?e`), nested `SERVICE` (the inner one runs on the remote), and `SERVICE` inside an
update's `WHERE`. Measured against a second endpoint, the results are the ones a local join gives.
How the work is shared is RDF4J's choice: with few local rows it sends them along in batches of
15 (a `VALUES` block per request); with many, it may fetch every match of the remote pattern once
and join here, so a broad pattern against a large public endpoint fetches a large answer.

What the endpoint holds federation to — all measured in the SERVICE audit after #128, where each
was missing:

| | |
|---|---|
| **One deadline per query** | All of a query's `SERVICE` requests share `CQELS_SPARQL_MAX_EXECUTION_SECONDS`, counted from the start of the query: `504` when it runs out. Each request had the whole limit to itself before, so 20 batches against a remote taking 1.5 s each ran a query for 30 s under a 2 s limit and answered `200`. |
| **Requests per query** | At most `CQELS_SPARQL_MAX_SERVICE_REQUESTS` (`503` beyond). There was no limit: 10,000 rows would have been some 670 requests to someone else's server from one client query. |
| **Bytes per query** | At most `CQELS_SPARQL_MAX_SERVICE_BYTES` of answers in all, counted as they are read and after `gzip` / `deflate` are undone, error answers and answers that fail included (`502` beyond, at once, and no further request). Before, an answer was bounded only by deadline × bandwidth: 1.1 GB in 5 s. This bounds bytes, not heap: an answer is held and parsed whole, which takes a multiple of its size. |
| **Answers read whole, and checked** | Each answer is read to its end and checked before RDF4J sees it — the shape of a SPARQL JSON result in full (a `head` with `vars`, `results.bindings` naming only those variables, each term with a known `type` and a string `value`; or a `true`/`false` `boolean`; nothing after it), whether it is the form the query asked for (rows for a `SELECT`, a boolean for an `ASK`), then its terms by RDF4J's parser. A `2xx` without a result (a `204`) is a failure too. That is what makes `SERVICE SILENT` keep the query's own rows when a remote's answer breaks off or is malformed, as the standard says; RDF4J dropped every row then, so a query answered `200` with nothing and an update `204` having written nothing, and its parser alone took `{}` or a head with no results for an empty answer. Read whole, an answer also gives its connection back before RDF4J parses it, which avoids the pool deadlock of eclipse-rdf4j/rdf4j#5987. |
| **Failures say whose they are** | A remote that is unreachable, answers an HTTP error, or answers in a refused format is a `502` that quotes it (`answered HTTP 403: …`). It was a `500` "query evaluation failed", and a remote's own plain-text reason was reported as a refused `Content-Type`. |
| **Destinations** | Only where `CQELS_SPARQL_FEDERATION_DESTINATIONS` admits, by default public addresses; every request and redirect is checked, on the addresses it connects to. See *Destinations* below. Before, any `http`/`https` URL a client named was reached, the server's own loopback and private network included. |
| **`http` and `https` only, everywhere** | Checked in the HTTP client itself, so it holds for `SERVICE ?e` bound at run time (which skipped the check of constant IRIs) and through a JVM proxy (which Apache would route `ftp://…` through). With `SILENT`, a refused endpoint is still a `403`, not an empty match. |
| **Bindings in the body, not the URL** | Every `SERVICE` query goes as a URL-encoded `POST` (SPARQL 1.1 Protocol §2.1.2), the bindings it joins on with it. RDF4J sent it as a `GET` while the URL stayed under about 4 KB, so up to 15 of the caller's rows at a time sat in the remote's and every proxy's access log. A `SERVICE` IRI's own parameters (`?key=…`) stay in its URL. A remote that answers only `GET` cannot be queried. |
| **Redirects** | Followed by the endpoint, not by Apache: at most 5, each to `http`/`https` and never from `https` down to `http`, each counted as a request, and the redirect's own body is dropped unread. A `POST` is sent again, body and all, on `301`, `302`, `307` and `308`, so the query arrives (RFC 9110 lets a client keep the method on `301`/`302`; turned into a `GET` it would ask the next server nothing); a `303` is a `GET` of what it names. Before, only a `GET` was followed, and RDF4J's own `POST` for a long query was a `502` on any redirect. Inside Apache a redirect was followed past the request limit and its body drained with no cap. A refused redirect is a `502`, `SILENT` or not. |
| **Formats** | SPARQL JSON, which keeps every term as it was (and `text/boolean` for an `ASK`); not RDF-star triple terms, which RDF4J read as unbound. Not CSV or TSV — RDF4J re-types what it reads from them: from CSV, `"001"` became an integer, language tags were lost and an empty string read as unbound; from TSV, `true` became a string and `-1` an `xsd:negativeInteger`. Joins and filters over them gave different answers. Not SPARQL XML or RDF4J's binary format: see *Security*. |
| **Two shapes refused (`400`)** | `SERVICE` inside an `ORDER BY` expression (`ORDER BY (EXISTS { SERVICE … })`): RDF4J sorts in parallel, and the sort's worker threads made `SERVICE` calls outside the query's limits (19 requests under a limit of 1); bind the value first, then order by the variable. And a `SERVICE` IRI carrying a `query` parameter of its own: RDF4J adds the query it sends as a second one. |
| **Limits under `SILENT`** | A limit of the endpoint's own — deadline, request count, bytes, scheme, destination — is answered with its status even under `SERVICE SILENT`, and an update that ran into one is rolled back: `SILENT` excuses a remote's failure, not the endpoint's limits. |

Still true, and worth knowing before enabling it:

- **The bindings `SERVICE` sends go with the request,** in its body, to any destination
  `CQELS_SPARQL_FEDERATION_DESTINATIONS` admits — by default any public host. A read token is
  enough to send `SERVICE`; see *Security*. List origins, without `public`, to choose the
  remotes yourself.
- **No credentials go to a remote, ever.** Not the client's own token or cookies, not a
  `user:pass@` in the endpoint's URL, not the JVM's `Authenticator`: the client has no
  authentication schemes, so even a remote that asks gets nothing (Apache sent the URL's
  `user:pass` on its second request when challenged, before this). Nor is a URL's `user:pass@`
  kept, by `SERVICE` or `LOAD`: it was stored in the client for the client's lifetime. A remote
  that needs credentials cannot be queried, and neither can one behind a proxy that does.
- **The JVM's proxy settings apply** (`http.proxyHost`, `https.proxyHost`, `http.nonProxyHosts`),
  to `LOAD` as to `SERVICE`. Each client keeps the default `ProxySelector` it was built with
  (when first used, and again after a Jetty restart): the proxy properties are read through it
  on every request, but a later `ProxySelector.setDefault` does not reroute a client until it is
  rebuilt. A destination that only `public` admits is refused through a proxy:
  the proxy resolves it, where its addresses cannot be checked. List it as an origin, or use `*`
  and let the proxy enforce its own policy.
  A proxy is the operator's to choose, so it is trusted further than a remote: when an `https`
  proxy refuses a tunnel, Apache reads its whole error answer before the client sees it, outside
  `CQELS_SPARQL_MAX_SERVICE_BYTES`.
- **RDF4J keeps one small object per distinct endpoint URL** it has called, for the repository's
  lifetime; with `SERVICE ?e`, clients choose how many.
- **Calling this endpoint from itself** takes one more query slot per nesting level, and behind a
  token it fails with the remote's `401`, since no token is forwarded.
- **An embedding that registers its own `FederatedService`** on the resolver bypasses these limits:
  `limitOutboundRequests` replaces the HTTP client RDF4J creates services with, not services
  registered by hand.
- With `CQELS_SPARQL_MAX_EXECUTION_SECONDS=0` there is no deadline at all.

### Destinations

`CQELS_SPARQL_FEDERATION_DESTINATIONS` says where `SERVICE` and `LOAD` (and the JSON-LD contexts
a `LOAD` source names) may send requests. Each entry is one of:

| Entry | Reaches |
|---|---|
| `public` (the default) | any host **every** address of which is public: not loopback, private (`10/8`, `172.16/12`, `192.168/16`, `fc00::/7`), link-local (`169.254/16`, where cloud metadata services answer, and `fe80::/10`), shared (`100.64/10`), multicast, reserved, benchmarking or documentation ranges, nor IPv6 forms that carry such an IPv4 address (`::ffff:…`, `64:ff9b::…`, `2002:…`) |
| an origin: `http://host`, `https://host:port` | that scheme, host and port (the scheme's default when none is given), whatever its addresses: the way to reach one service on a private network. Nothing else of a URL — a path, a query, credentials — may be given |
| `*` | anywhere, as before the list existed |

Entries combine: `public,http://localhost:3030` reaches public hosts and one local service. A
mistyped entry stops start-up: anything but `public`, `*` or an `http(s)` origin naming a host
and, if any, a port written as digits from 1 to 65535. A host is an IPv4 literal, an IPv6
literal without a zone id, or ASCII dot-separated labels of letters, digits, `-` and `_` with
no trailing dot (an internationalized name in its `xn--` form), with no percent-encoding
anywhere; `http://*` is not a wildcard.

- **Checked on the addresses connected to.** The check is in the name resolver the HTTP client
  connects through, so the addresses checked are the ones the socket is opened to: a name that
  answers a public address to a check and a private one to the connection (DNS rebinding) gets
  nowhere. IP literals go through it too, so `http://2130706433/`, `http://0.0.0.0/` and
  `http://[::ffff:127.0.0.1]/` are refused as `127.0.0.1` is. A name with one public and one
  private address is refused: the client would try them in turn.
- **On every request and redirect.** A `SERVICE ?e` bound at run time, each redirect hop, each
  JSON-LD context. A destination refused is a `403` when the query or update named it, a `502`
  when a remote redirected to it or a `LOAD` document named it as its context — `SILENT` or not,
  and an update that ran into one is rolled back. A constant `SERVICE` or `LOAD` IRI that no
  entry admits by name is refused before anything runs.
- **`LOAD` needs a host `java.net.URI` reads as a server**, as it did before: a Docker Compose
  name with `_`, such as `sparql_store`, can be listed and is reached by `SERVICE`, but `LOAD`
  of it fails (`502`, `SILENT`: `204`), as do URLs whose port is not plain digits from 1 to 65535.
- **An origin is matched as written**, lower-cased: `http://localhost:3030` admits neither
  `http://127.0.0.1:3030` nor `https://localhost:3030`. Those fall to `public`, if it is listed,
  which refuses loopback.
- **The engine's own `SERVICE` calls** go through the same client (`limitOutboundRequests`), so
  they are held to the list too. An embedding that called `limitOutboundRequests` without a list
  gets `public`.

## Security

- Defaults are loopback, read-only, no federation, no browser origins, 30-second execution limit, 1 MiB bodies.
- **`SERVICE` and `LOAD` are refused unless `CQELS_SPARQL_FEDERATION_ENABLED=true`.** They make
  the endpoint send requests of its own. A `SERVICE` clause sends the bindings it has so far,
  taken from the graph, to whatever URL the query names, and the trigger can be a plain GET:
  a cross-site `<img src="http://127.0.0.1:8089/sparql?query=…">` reads nothing back, but the
  server has already posted the data to the attacker's service. CORS cannot stop that, since the
  data never passes through the browser. The test suite reproduces it with federation on (the
  stand-in remote receives the vehicles' IRIs) and checks that nothing leaves with it off.
  Enable federation only behind a token, and only for callers you would let send the graph's
  data to any destination `CQELS_SPARQL_FEDERATION_DESTINATIONS` admits — by default any public
  host; the server's own loopback and private network are refused (*Destinations*).
- **With federation on, only `http` and `https` are reachable.** `LOAD <file:///…>` read an RDF
  file off the server's disk into the store, where the next query returned it; `SERVICE` and
  `LOAD` naming any other scheme are now refused (`403`). RDF4J's own `LOAD` also followed
  redirects by hand, to any scheme — an http URL redirecting to `file:` read the file — and
  waited without a deadline, so **the endpoint performs `LOAD` itself**, with Apache's HTTP
  client, whose name resolver is where *Destinations* are checked: redirects are followed only
  to `http`/`https` (never from `https` to `http`), at most 5, each to an admitted destination, the
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
  cannot hold the slot; the host name lookup is held to it too, on a small pool of its own,
  since an abort cannot interrupt one. The client stays installed for the repository's lifetime, so
  direct RDF4J queries on `engine.getRepository()` get the deadline too, one request at a time (the
  per-query budget of *Federation* is the servlet's; with `MAX_EXECUTION_SECONDS=0` the client is
  installed with no deadline, for the format rule below); its connections are released when Jetty
  stops and reopen if it is used again. A `SERVICE` endpoint must answer SPARQL JSON, in
  exactly one `Content-Type` header (an allow-list, not a deny-list: an empty header followed by an
  XML one got past a check of the first): SPARQL XML is refused, because RDF4J parses it with
  settings under which an XML entity read a local file into the results, and so is RDF4J's binary
  format, whose parser sizes arrays from counts in the stream (a 12-byte answer ran it out of heap,
  and the query then reported an empty result). Nothing is set JVM-wide. *Federation* has the
  rest of what `SERVICE` is held to.
- **Browser requests from other origins are refused (`403`).** Loopback is not a boundary
  against a browser: any page the user visits can submit a cross-origin form POST — a CORS
  "simple" request, sent without a preflight — to `127.0.0.1:8089`. Withholding CORS headers
  only hides the response; the update would still run. Browsers attach `Origin` to every
  cross-origin POST, so the endpoint refuses any `Origin` not on `CQELS_SPARQL_ALLOWED_ORIGINS`.
  Setting that to `*` with update enabled and no token undoes this; don't.
- **DNS rebinding is refused by `Host`.** An attacker's page can re-resolve its own domain to
  `127.0.0.1`; its requests are then same-origin to the browser (no `Origin`,
  `Sec-Fetch-Site: same-origin`) and it can read the answers. Only the `Host` header still names
  the attacker's domain, so without a read token (`CQELS_SPARQL_AUTH_TOKEN`) the endpoint accepts
  only `localhost`, `127.0.0.1`, `::1` and the bind host, plus `CQELS_SPARQL_ALLOWED_HOSTS`. An
  update token alone does not lift the check, since reads are open then. With a read token there
  is no host check unless you list hosts: the browser never sends the token for the attacker's page.
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
- A bearer token is compared in constant time. `/health`, `/ready` and `/metrics` are never
  behind it.
- **Readers need not be writers.** With one token, everyone who could query could also
  `DROP ALL` once update was on. Set `CQELS_SPARQL_UPDATE_TOKEN` as well and `AUTH_TOKEN` becomes
  read-only: an update with it is a `403`. Set the update token **alone** for open reads with
  token-protected writes; the start-up refusal above still applies then, since reads are
  unauthenticated. `LOAD` is an update, so it needs the update token too; `SERVICE` in a query
  needs only the read token, so enable federation with that in mind.

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
install `AccessFilters.Hosts` (whenever there is no read token — an update token alone leaves
reads open, so it does not count), `AccessFilters.Origins` (always) and
`AccessFilters.BearerToken` (when exposed; `new BearerToken(readToken, updateToken)` for two) in
front of it, as the launcher does, or your container's equivalents. To keep a request from
updating, set the request attribute `AccessFilters.READ_ONLY` to `Boolean.TRUE` before it reaches
the servlet, which is what `BearerToken` does for the read token:

```java
EndpointOptions options = EndpointOptions.defaults().withMaxRows(10_000);
var requests = EnumSet.of(DispatcherType.REQUEST);
// without a read token, Hosts is what stops DNS rebinding: list every name clients reach you by
context.addFilter(new FilterHolder(new AccessFilters.Hosts(List.of("localhost", "127.0.0.1", "::1"))), "/sparql", requests);
context.addFilter(new FilterHolder(new AccessFilters.Origins(List.of())), "/sparql", requests);
context.addServlet(new ServletHolder(new SparqlProtocolServlet(engine::getRepository, options)), "/sparql");
```

With federation enabled, also call `SparqlEndpointLauncher.limitOutboundRequests(engine.getRepository(),
seconds, maxServiceBytes, options.federationDestinations())` once, before the first request: it
gives `SERVICE` its deadline, its refusal of XML answers (see *Security*) and its destinations
(the shorter overloads hold it to `public`); `startServer` does this for you, and refuses to start when the
repository's `SERVICE` client cannot be replaced. Until it has run, the servlet answers any
`SERVICE` with `503`, rather than run it without those limits. `LOAD` needs nothing: the
servlet performs it itself.

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
