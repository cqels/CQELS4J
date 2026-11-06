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
        Server server = SparqlEndpointLauncher.startServer(holder, "127.0.0.1", 0,
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
        Server probe = SparqlEndpointLauncher.startServer(engine::getRepository, "127.0.0.1", 0,
                EndpointOptions.defaults().withMaxConcurrentQueries(slots), 64, false);
        int leased = ((org.eclipse.jetty.util.thread.QueuedThreadPool) probe.getThreadPool()).getThreadPoolBudget().getLeasedThreads();
        probe.stop();
        int minimum = Math.max(8, SparqlEndpointLauncher.minimumThreads(slots, leased));

        SlotHolder holder = new SlotHolder(engine, slots);
        Server server = SparqlEndpointLauncher.startServer(holder, "127.0.0.1", 0,
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
