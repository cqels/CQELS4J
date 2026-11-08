package org.cqels.examples.sparql;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.engine.persistence.PersistenceHealth;
import org.eclipse.rdf4j.common.net.ParsedIRI;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;

import java.io.ByteArrayOutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * What the engine's public API says about itself, as RDF in
 * {@link SparqlProtocolServlet#METADATA_GRAPH}. Built fresh for every query that names that
 * graph, so it is a snapshot as of the request, never a cache that can lag.
 *
 * <p><strong>Why a snapshot beside the store, not triples written into it.</strong> RDF4J's
 * default graph is the union of every graph in the store (the endpoint's own tests rely on it:
 * {@code datasetParameterRestrictsTheDefaultGraph}). Triples written into the engine's
 * repository would therefore turn up in every query without {@code FROM} — the endpoint's
 * clients' and the engine's own static joins alike — and each refresh would be a write the
 * engine's lookup-cache invalidation reacts to. Kept apart, the metadata cannot change what any
 * other query sees.
 *
 * <p><strong>What is and is not here.</strong> Only what {@link CQELSEngine} exposes publicly at
 * {@code 2.0.0-alpha.22}: the engine's id and whether it is running, how many queries are
 * registered (a count — the engine lists no ids or texts), the live streams with their
 * attributes, and the persistence state. Window contents are not: the engine has no accessor
 * for them.
 *
 * <p>Vocabulary, prefix {@code cqe: <cqels://ontology/endpoint#>} (the {@code cqels://ontology/}
 * space follows {@code cqels-mcp}'s own vocabularies; this one is defined here, not upstream):
 * <pre>
 *   &lt;cqels://endpoint/engine&gt; a cqe:Engine ;
 *       cqe:engineId "…" ; cqe:running true ; cqe:registeredQueries 3 ;
 *       cqe:persistence "NO_PERSISTENCE" ;          # PersistenceHealth.State, by name
 *       cqe:journalId "…" ; cqe:durablePosition 42 ; cqe:appliedPosition 42 ;   # only when journalled
 *       cqe:snapshotAt "2026-11-07T…Z"^^xsd:dateTime ;
 *       cqe:stream &lt;…&gt; .
 *   &lt;…&gt; a cqe:Stream ; cqe:name "…" ;
 *       cqe:attribute [ cqe:key "…" ; cqe:value "…" ] .
 * </pre>
 * A stream whose name is an absolute IRI (as CQELS-QL's {@code STREAM <…>} uses them) is that
 * IRI; any other name becomes {@code cqels://streams/<name>}, percent-encoded — the prefix
 * {@code cqels-mcp} gives its streams — and {@code cqe:name} always carries the name as given.
 * When that generated IRI is itself another stream's name, the stream gets a blank node instead
 * ({@link #streamNode}).
 */
public final class EngineMetadata {

    public static final String NS = "cqels://ontology/endpoint#";
    public static final String STREAM_PREFIX = "cqels://streams/";
    public static final String ENGINE = "cqels://endpoint/engine";

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private EngineMetadata() { }

    /**
     * A supplier of fresh snapshots of {@code engine}: the third argument of
     * {@link SparqlProtocolServlet#SparqlProtocolServlet(Supplier, EndpointOptions, Supplier)}.
     */
    public static Supplier<List<Statement>> of(CQELSEngine engine) {
        return () -> snapshot(engine);
    }

    static List<Statement> snapshot(CQELSEngine engine) {
        List<Statement> out = new ArrayList<>();
        IRI g = SparqlProtocolServlet.METADATA_GRAPH;
        IRI e = VF.createIRI(ENGINE);
        add(out, e, RDF.TYPE, term("Engine"), g);
        add(out, e, term("engineId"), VF.createLiteral(engine.getEngineId()), g);
        add(out, e, term("running"), VF.createLiteral(engine.isRunning()), g);
        add(out, e, term("registeredQueries"), VF.createLiteral(engine.registeredQueryCount()), g);
        add(out, e, term("snapshotAt"), VF.createLiteral(OffsetDateTime.now(ZoneOffset.UTC).toString(),
                VF.createIRI("http://www.w3.org/2001/XMLSchema#dateTime")), g);

        PersistenceHealth health = engine.persistenceHealth();
        add(out, e, term("persistence"), VF.createLiteral(health.state().name()), g);
        health.journalId().ifPresent(id -> {
            add(out, e, term("journalId"), VF.createLiteral(id), g);
            add(out, e, term("durablePosition"), VF.createLiteral(health.durablePosition()), g);
            add(out, e, term("appliedPosition"), VF.createLiteral(health.appliedPosition()), g);
        });

        // getStreamNames, not liveStreamCatalog: the catalog is the persistence journal's view and
        // is empty on an engine without persistence (measured on 2.0.0-alpha.22). Sorted, so the
        // graph reads the same way twice. A stream removed between the two calls is skipped.
        TreeSet<String> names = new TreeSet<>(engine.getStreamNames());
        for (String name : names) {
            DataStream stream = engine.getStream(name);
            if (stream == null) {
                continue;
            }
            Map<String, String> attributes = stream.attributes();
            Resource s = streamNode(name, names);
            add(out, e, term("stream"), s, g);
            add(out, s, RDF.TYPE, term("Stream"), g);
            add(out, s, term("name"), VF.createLiteral(name), g);
            new TreeMap<>(attributes == null ? Map.<String, String>of() : attributes).forEach((k, v) -> {
                Resource a = VF.createBNode();
                add(out, s, term("attribute"), a, g);
                add(out, a, term("key"), VF.createLiteral(k), g);
                add(out, a, term("value"), VF.createLiteral(v == null ? "" : v), g);
            });
        }
        return out;
    }

    /**
     * {@link #streamIri}, unless that IRI is also the name of another stream: then a blank node.
     * Streams {@code Telemetry} and {@code cqels://streams/Telemetry} are two streams, and one
     * node for both would give each the other's attributes — found in co-review of #121. Only a
     * generated IRI can collide (the encoding is injective, and two absolute names that are equal
     * are one stream), so the absolute name keeps its IRI and the other gets the blank node.
     */
    static Resource streamNode(String name, java.util.Set<String> allNames) {
        IRI iri = streamIri(name);
        return !iri.stringValue().equals(name) && allNames.contains(iri.stringValue()) ? VF.createBNode() : iri;
    }

    static IRI streamIri(String name) {
        try {
            if (new ParsedIRI(name).isAbsolute()) {
                return VF.createIRI(name);
            }
        } catch (URISyntaxException ignored) {
            // not an IRI: encode it below
        }
        return VF.createIRI(STREAM_PREFIX + encode(name));
    }

    /** Percent-encodes everything but RFC 3986 unreserved characters, as UTF-8. */
    private static String encode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                out.write(c);
            } else {
                out.writeBytes(String.format("%%%02X", c).getBytes(StandardCharsets.US_ASCII));
            }
        }
        return out.toString(StandardCharsets.US_ASCII);
    }

    private static IRI term(String local) {
        return VF.createIRI(NS + local);
    }

    private static void add(List<Statement> out, Resource s, IRI p, Value o, IRI g) {
        out.add(VF.createStatement(s, p, o, g));
    }
}
