package org.cqels.playground.messages;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.rio.RDFFormat;

/**
 * Example — an <strong>RDF Message Stream over Server-Sent Events</strong> into CQELS, following
 * §4.1.1 of the W3C RSP CG <a href="https://w3c-cg.github.io/rsp/spec/messages">RDF Messages</a>
 * draft (22 October 2026).
 *
 * <p>A producer serves the six messages of {@code messages/cabin-temperatures.ttl} as a
 * {@code text/event-stream}: header {@code RDF-Message-Content-Type: application/ld+json}, one
 * {@code rdf-message} event per message, with a JSON-LD payload ({@code {}} for the empty
 * heartbeat). A consumer reads the stream as it arrives and pushes each event's message into the
 * engine as one atomic stream element, which the {@code Reading} query over {@code [TRIPLES 1]}
 * joins on its own, as in {@link MessageLogReplay}: four readings, one per observation.
 *
 * <p>Producer and consumer run in this one process, over the loopback address; see
 * {@link SseMessageChannel} for what the transport does and does not do.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.playground.messages.MessageStreamOverSse}
 */
public class MessageStreamOverSse {

    private static final String LOG = "/messages/cabin-temperatures.ttl";
    private static final String BASE = "https://example.org/fleet/";

    public static void main(String[] args) throws Exception {
        List<List<Statement>> messages = RdfMessageLog.parse(MessageLogReplay.readResource(LOG), RDFFormat.TURTLE, BASE);
        List<Map<String, Object>> readings = Collections.synchronizedList(new ArrayList<>());

        try (SseMessageChannel.Producer producer =
                     new SseMessageChannel.Producer(messages, SseMessageChannel.Payload.JSON_LD, 300);
             CQELSEngine engine = CQELSEngine.builder().id("rdf-messages-over-sse").withMemoryStore().build()) {

            DataStream cabin = engine.createStream("Cabin");
            engine.registerCqelsQuery(MessageLogReplay.readingQuery("Reading", "Cabin", "[TRIPLES 1]"), row -> {
                readings.add(row);
                System.out.println("  [reading] " + MessageLogReplay.brief(row));
            });
            engine.start();

            // The port is ephemeral; printing only the path keeps the output the same every run.
            System.out.println("producer: " + messages.size() + " messages at http://127.0.0.1:<port>" + producer.uri().getPath()
                    + " (" + SseMessageChannel.PAYLOAD_HEADER + ": " + SseMessageChannel.Payload.JSON_LD.mediaType() + ")");
            System.out.println("first event on the wire:\n"
                    + SseMessageChannel.event(1, SseMessageChannel.encode(messages.get(0), SseMessageChannel.Payload.JSON_LD)).indent(2));

            // event time as in MessageLogReplay: sosa:resultTime, else the previous message's
            AtomicLong eventTime = new AtomicLong(messages.stream().map(MessageLogReplay::resultTime)
                    .flatMap(java.util.Optional::stream).findFirst().orElse(0L));
            AtomicInteger n = new AtomicInteger();
            int received = SseMessageChannel.consume(producer.uri(), BASE, Map.of(), message -> {
                long t = MessageLogReplay.resultTime(message).orElse(eventTime.get());
                eventTime.set(t);
                System.out.println("event " + n.incrementAndGet() + ": " + message.size() + " statements"
                        + (message.isEmpty() ? " (empty: a heartbeat)" : "") + " at " + Instant.ofEpochMilli(t));
                cabin.push(message, t);
            });
            Thread.sleep(600);
            System.out.println("\nconsumer: " + received + " events, " + readings.size() + " readings");
        }
        System.out.println("\nDone.");
    }
}
