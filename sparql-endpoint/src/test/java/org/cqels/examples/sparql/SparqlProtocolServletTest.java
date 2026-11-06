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
            Server server = SparqlEndpointLauncher.startServer(engine, "127.0.0.1", 0, options, 16, false);
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
        var r = HTTP.send(HttpRequest.newBuilder(URI.create(shared.url() + "?query=" + enc("ASK {}")
                + "&default-graph-uri=not-an-iri")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, r.statusCode());
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
            var other = HTTP.send(HttpRequest.newBuilder(URI.create(cors.url() + "?query=" + enc("ASK {}")))
                    .header("Origin", "https://evil.example").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, other.statusCode());
            assertTrue(other.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
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

    @Test
    void concurrencyCapAnswers503WithRetryAfterAtOnce() throws Exception {
        try (Endpoint one = Endpoint.start(EndpointOptions.defaults().withMaxExecutionSeconds(2).withMaxConcurrentQueries(1), 300)) {
            var holdsTheSlot = HTTP.sendAsync(HttpRequest.newBuilder(URI.create(one.url() + "?query=" + enc(SLOW_BUT_STOPPABLE))).build(),
                    HttpResponse.BodyHandlers.ofString());
            Thread.sleep(300);   // let the first request take the only slot
            long t0 = System.nanoTime();
            var turnedAway = get(one.url(), "ASK {}", null);
            assertEquals(503, turnedAway.statusCode(), turnedAway.body());
            assertEquals("1", turnedAway.headers().firstValue("Retry-After").orElse(""));
            assertTrue(turnedAway.body().contains("too many concurrent queries"), turnedAway.body());
            assertTrue((System.nanoTime() - t0) / 1e9 < 1, "a busy endpoint must answer at once, not queue");
            assertEquals(503, holdsTheSlot.get().statusCode());   // the slow one hits the execution limit
            assertEquals(200, get(one.url(), "ASK {}", null).statusCode());   // slot released
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
        assertEquals(json, SparqlProtocolServlet.negotiate(null, reg, json).orElseThrow());
        assertEquals(json, SparqlProtocolServlet.negotiate("*/*", reg, json).orElseThrow());
        assertEquals(org.eclipse.rdf4j.query.resultio.TupleQueryResultFormat.CSV,
                SparqlProtocolServlet.negotiate("text/csv", reg, json).orElseThrow());
        assertTrue(SparqlProtocolServlet.negotiate("image/png", reg, json).isEmpty());
        assertTrue(SparqlProtocolServlet.negotiate("text/*", reg, json).isPresent());
    }

    @SuppressWarnings("unused")
    private static void silence(IOException ignored) { }
}
