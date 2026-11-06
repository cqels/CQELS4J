# CQELS SPARQL 1.1 Protocol endpoint

A standard SPARQL endpoint over an embedded CQELS engine's **background graph** — the graph the
engine joins its streams against. Any SPARQL 1.1 Protocol client (curl, YASGUI, rdflib, Jena,
RDF4J's `SPARQLRepository`) can query it. It is a plain `jakarta.servlet` on **Jetty 12**, the
same stack the published MCP server (`org.cqels:cqels-mcp`) embeds, for a reason explained
under *Continuous queries* below.

It is **not** a full triple-store server: no repository management, no transactions, no
Workbench. Those are store features; for them, run RDF4J Server as the store and point the
engine at it (see *Alternatives*).

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
| `CQELS_SPARQL_MAX_CONCURRENT_QUERIES` | `16` | Queries and updates evaluating at once. Beyond it the request is answered `503` with `Retry-After: 1` immediately, not queued. |
| `CQELS_SPARQL_MAX_ROWS` | `0` | SELECT rows beyond this are dropped and `X-CQELS-Truncated: true` is set. `0` = unlimited. |
| `CQELS_SPARQL_UPDATE_ENABLED` | `false` | Accept SPARQL Update. |
| `CQELS_SPARQL_FEDERATION_ENABLED` | `false` | Accept `SERVICE` (in queries and updates) and `LOAD`. Both make the endpoint itself send requests to a URL the client names; see *Security*. |
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
| `Accept` | negotiated through RDF4J's writer registries per RFC 9110: `q` values honoured, `q=0` excludes, the most specific range wins, repeated `Accept` fields form one list, parameters count (`text/csv;charset=UTF-8` is more specific than `text/csv`; `charset=ISO-8859-1` matches nothing, since the writers emit UTF-8), and the response is labelled with the media type that matched (`application/json` if that is what was asked for). Defaults: SPARQL JSON for SELECT/ASK, Turtle for CONSTRUCT/DESCRIBE. Also XML, CSV, TSV, binary; N-Triples, N-Quads, TriG, JSON-LD, RDF/XML as far as the RDF4J Rio modules on the classpath go. |
| `GET /health`, `GET /ready` | `200` once the engine has started and Jetty is bound (never behind the token). |

| Status | When |
|---|---|
| `400` | malformed query or update, graph parameter that is not an absolute IRI, missing `query`, update dataset parameters plus `USING`/`WITH`, malformed percent-encoding |
| `401` | token set and missing or wrong |
| `403` | update while `CQELS_SPARQL_UPDATE_ENABLED` is false; `SERVICE` or `LOAD` while `CQELS_SPARQL_FEDERATION_ENABLED` is false; `Host` not in `CQELS_SPARQL_ALLOWED_HOSTS` (no token set); `Origin` not in `CQELS_SPARQL_ALLOWED_ORIGINS`; a browser request marked `Sec-Fetch-Site: cross-site` or `same-site` with no allowed `Origin` |
| `405` | update sent via GET |
| `406` | nothing in `Accept` matches a registered writer |
| `413` | body over `CQELS_SPARQL_MAX_REQUEST_BYTES` |
| `415` | unknown POST `Content-Type` or charset |
| `500` | evaluation failed |
| `503` | execution limit exceeded by a query or an update (as Fuseki answers), or every query slot busy (then with `Retry-After: 1`) |

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
  Measured on `2.0.0-alpha.21`. That is also why update is off by default.
- The endpoint opens one repository connection per request beside the engine's own static
  lookups. Both the memory and the native store allow that. Contention under many concurrent
  clients is not measured yet.
- The `listening on` line is printed **after** `engine.start()`, so the first request can
  already join against the graph.

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
  Enable federation only behind a token.
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
  start-up, unless `CQELS_SPARQL_ALLOW_UNAUTHENTICATED_REMOTE=true`.
- TLS is not terminated here. Front the endpoint with a TLS-terminating proxy, as the MCP
  README says for its own HTTP transport.
- A bearer token is compared in constant time. `/health` and `/ready` are never behind it.

## Alternatives

- **Full RDF4J Server features** (transactions, repository management, Workbench, federation):
  run stock RDF4J Server as the store and start the engine with
  `CQELSEngine.builder().repository(new HTTPRepository(serverUrl, repoId))`. The engine then
  reads its background graph over HTTP and, for a repository that is not a local Sail, falls
  back to time-based lookup-cache expiry instead of live invalidation. How stale a background
  update can get under `CACHE 5m` on that route has **not** been measured yet.
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

Give it the engine's `Repository` object, never a store path: a NativeStore holds a directory
lock, and a second open on the same path fails. A repository you hand to
`CQELSEngine.builder().repository(…)` stays yours to shut down — `engine.close()` leaves it
initialized (measured on `2.0.0-alpha.21`), so the launcher shuts its NativeStore down itself.

## Dependency notes

- `jetty.version` must equal the Jetty `org.cqels:cqels-mcp` embeds at the pinned engine version.
- Declaring any RDF4J module directly pulls `jackson-core 2.13.5` and `slf4j-api 1.7.36` nearer
  than the engine's `2.17.0` / `2.0.9`, and Maven's nearest-wins rule would mix Jackson versions.
  The pom pins both in `dependencyManagement`; keep the pins equal to the engine's versions.
