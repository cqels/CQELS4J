package org.cqels.examples.messages;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.RDFFormat;

/**
 * Example — replaying a <strong>W3C RSP CG RDF Message Log</strong> into CQELS, and writing the
 * results back out as one.
 *
 * <p>The <a href="https://w3c-cg.github.io/rsp/spec/messages">RDF Messages</a> draft (22 October
 * 2026) defines an RDF Message as a dataset "intended to be interpreted atomically as a single
 * communicative act", and an RDF Message Log as a file of them separated by {@code MESSAGE}. In
 * CQELS a message is one atomic stream element: one {@code push(List<Statement>, t)}, which
 * {@code [TRIPLES n]} counts once however many statements it has.
 *
 * <p>The log, {@code src/main/resources/messages/cabin-temperatures.ttl}, is Turtle: a message
 * describing the stream, four observation messages and one empty heartbeat. Every observation's
 * result is the blank node {@code _:r} — the <em>same label</em> in every message, which the
 * draft allows because blank nodes are scoped to their message.
 *
 * <p>What the demo shows, in order:
 * <ol>
 *   <li><em>Reading the log.</em> {@link RdfMessageLog} splits it into six messages (the trailing
 *       delimiter adds none) and parses each on its own, so the four {@code _:r} become four
 *       different blank nodes.</li>
 *   <li><em>One message, one world.</em> {@code Reading} runs over {@code [TRIPLES 1]}: it sees
 *       one message at a time, and joins the observation to its own result through the blank
 *       node. Four readings, one per observation.</li>
 *   <li><em>Messages taken together.</em> {@code CabinAverage} runs over {@code [RANGE 30s]} and
 *       averages each sensor across messages. The draft leaves combining messages to an "RDF
 *       Message Stream Profile" (its §3, still unwritten); in CQELS the window is that choice.</li>
 *   <li><em>What message scoping prevents.</em> The same log read as one plain Turtle document,
 *       delimiters removed, merges the four {@code _:r} into one blank node with four values. The
 *       same {@code Reading} query then joins every observation to every value: 16 rows instead
 *       of 4.</li>
 *   <li><em>Results as messages.</em> Each {@code Reading} row is written as one RDF Message of a
 *       new Turtle log, which is read back to check it round-trips. CQELS-QL has no continuous
 *       {@code CONSTRUCT} yet (<a href="https://github.com/cqels/CQELS4J/issues/45">#45</a>), so
 *       the message is built from the row with a template here.</li>
 * </ol>
 *
 * <p>Event time: each message's {@code sosa:resultTime}; a message without one (the stream
 * description, the heartbeat) takes the previous message's time, and the first message the log's
 * first {@code sosa:resultTime}. The heartbeat adds no reading, but it is still an arrival:
 * {@code CabinAverage} re-reports its groups on it, as a windowed {@code GROUP BY} does on every
 * arrival (see "Reading the output" in {@code examples/README.md}).
 *
 * <p>Not shown, because the engine cannot do it yet: reading the <em>named graphs</em> of a
 * message. The draft defines a message as a dataset, the engine keeps a quad's graph name, but
 * {@code GRAPH} inside a {@code STREAM} block is refused
 * (<a href="https://github.com/cqels/CQELS4J/issues/125">#125</a>).
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.messages.MessageLogReplay}
 */
public class MessageLogReplay {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final String LOG = "/messages/cabin-temperatures.ttl";
    private static final String BASE = "https://example.org/fleet/";
    private static final String RESULT_TIME = "http://www.w3.org/ns/sosa/resultTime";
    private static final String HAS_RESULT = "http://www.w3.org/ns/sosa/hasResult";
    private static final String OUT = "https://example.org/fleet/readings#";

    static final String PREFIXES = """
            PREFIX sosa: <http://www.w3.org/ns/sosa/>
            PREFIX qudt: <http://qudt.org/schema/qudt/>
            """;

    /** One message per observation: its own blank-node result, joined inside the message. */
    static String readingQuery(String name, String stream, String window) {
        return PREFIXES + """
                REGISTER QUERY %s AS
                SELECT ?obs ?sensor ?value ?unit
                FROM STREAM %s %s
                WHERE {
                  STREAM %s {
                    ?obs sosa:madeBySensor ?sensor ; sosa:hasResult ?r .
                    ?r qudt:value ?value ; qudt:hasUnit ?unit .
                  }
                }
                """.formatted(name, stream, window, stream);
    }

    public static void main(String[] args) throws Exception {
        String log = readResource(LOG);

        // 1 — read the log: one statement list per message
        List<List<Statement>> messages = RdfMessageLog.parse(log, RDFFormat.TURTLE, BASE);
        System.out.println("read " + LOG + ": " + messages.size() + " messages, sizes "
                + messages.stream().map(List::size).toList());

        List<Map<String, Object>> readings = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger mergedRows = new AtomicInteger();

        try (CQELSEngine engine = CQELSEngine.builder()
                .id("rdf-message-log-replay")
                .withMemoryStore()
                .build()) {

            DataStream cabin = engine.createStream("Cabin");
            DataStream merged = engine.createStream("Merged");

            // 2 — one message at a time
            engine.registerCqelsQuery(readingQuery("Reading", "Cabin", "[TRIPLES 1]"), row -> {
                readings.add(row);
                System.out.println("  [reading] " + brief(row));
            });

            // 3 — messages taken together, per sensor, over the last 30 s of event time
            engine.registerCqelsQuery(PREFIXES + """
                    REGISTER QUERY CabinAverage AS
                    SELECT ?sensor (AVG(?value) AS ?avg) (COUNT(*) AS ?readings)
                    FROM STREAM Cabin [RANGE 30s]
                    WHERE {
                      STREAM Cabin {
                        ?obs sosa:madeBySensor ?sensor ; sosa:hasResult ?r .
                        ?r qudt:value ?value .
                      }
                    }
                    GROUP BY ?sensor
                    """, row -> System.out.println("  [average] " + brief(row)));

            // 4 — the same query over the log read as ONE document (delimiters removed)
            engine.registerCqelsQuery(readingQuery("ReadingMerged", "Merged", "[NOW]"),
                    row -> mergedRows.incrementAndGet());

            engine.start();

            long eventTime = messages.stream().map(MessageLogReplay::resultTime)
                    .flatMap(java.util.Optional::stream).findFirst().orElse(0L);
            int n = 0;
            for (List<Statement> message : messages) {
                n++;
                eventTime = resultTime(message).orElse(eventTime);
                System.out.println("\npush message " + n + " (" + message.size() + " statements"
                        + (message.isEmpty() ? ", empty: a heartbeat" : "") + ") at "
                        + Instant.ofEpochMilli(eventTime));
                cabin.push(message, eventTime);
                Thread.sleep(400);
            }

            System.out.println("\n--- the same log read as one plain Turtle document ---");
            List<Statement> asOneDocument = RdfMessageLog.parse(withoutDelimiters(log), RDFFormat.TURTLE, BASE).get(0);
            long results = asOneDocument.stream()
                    .filter(st -> st.getPredicate().stringValue().equals(HAS_RESULT)).count();
            long resultNodes = asOneDocument.stream()
                    .filter(st -> st.getPredicate().stringValue().equals(HAS_RESULT))
                    .map(Statement::getObject).distinct().count();
            System.out.println("one document: " + asOneDocument.size() + " statements; its " + results
                    + " sosa:hasResult _:r point at " + resultNodes + " blank node(s)");
            merged.push(asOneDocument, eventTime);
            Thread.sleep(600);
            System.out.println("  Reading over it: " + mergedRows.get() + " rows (every observation joined to"
                    + " every value), against " + readings.size() + " from the message log");
        }

        // 5 — each reading as one RDF Message of a new log
        List<List<Statement>> out = new ArrayList<>();
        synchronized (readings) {
            for (Map<String, Object> row : readings) {
                out.add(readingMessage(row));
            }
        }
        Map<String, String> ns = new LinkedHashMap<>();
        ns.put("r", OUT);
        String written = RdfMessageLog.writeTurtle(out, ns);
        System.out.println("\n--- the readings as an RDF Message Log (Turtle) ---");
        System.out.print(written);
        System.out.println("--- end ---");
        List<List<Statement>> back = RdfMessageLog.parse(written, RDFFormat.TURTLE, BASE);
        System.out.println("read back: " + back.size() + " messages, sizes " + back.stream().map(List::size).toList());
        System.out.println("\nDone.");
    }

    /** One output message for one result row: a blank-node reading pointing at its observation. */
    static List<Statement> readingMessage(Map<String, Object> row) {
        BNode reading = VF.createBNode();
        List<Statement> message = new ArrayList<>();
        message.add(VF.createStatement(reading, VF.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                VF.createIRI(OUT + "CabinReading")));
        message.add(VF.createStatement(reading, VF.createIRI(OUT + "observation"), toValue(row.get("obs"))));
        message.add(VF.createStatement(reading, VF.createIRI(OUT + "sensor"), toValue(row.get("sensor"))));
        message.add(VF.createStatement(reading, VF.createIRI(OUT + "value"), toValue(row.get("value"))));
        message.add(VF.createStatement(reading, VF.createIRI(OUT + "unit"), toValue(row.get("unit"))));
        return message;
    }

    /** A result cell as an RDF term: the engine hands back RDF4J values or plain Java values. */
    static Value toValue(Object cell) {
        if (cell instanceof Value v) {
            return v;
        }
        String s = String.valueOf(cell);
        if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("urn:")) {
            return VF.createIRI(s);
        }
        if (cell instanceof Number number) {
            return VF.createLiteral(number.toString(), VF.createIRI("http://www.w3.org/2001/XMLSchema#decimal"));
        }
        return VF.createLiteral(s);
    }

    /** The message's sosa:resultTime, in epoch millis. */
    static java.util.Optional<Long> resultTime(List<Statement> message) {
        return message.stream()
                .filter(st -> st.getPredicate().stringValue().equals(RESULT_TIME))
                .map(st -> ((Literal) st.getObject()).calendarValue().toGregorianCalendar().getTimeInMillis())
                .findFirst();
    }

    /** The log as plain Turtle: version announcement and delimiters removed. For step 4 only. */
    static String withoutDelimiters(String log) {
        return log.replaceAll("(?m)^\\s*(VERSION|MESSAGE)\\b.*$", "");
    }

    static String brief(Map<String, Object> row) {
        return String.valueOf(row)
                .replace("https://example.org/fleet/", "")
                .replace("http://qudt.org/vocab/unit/", "unit:");
    }

    static String readResource(String path) throws IOException {
        try (InputStream in = MessageLogReplay.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

}
