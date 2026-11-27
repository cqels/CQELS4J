package org.cqels.playground.messages;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;

/**
 * Carries an RDF Message Stream over <strong>Server-Sent Events</strong>, the arrangement the W3C
 * RSP CG <a href="https://w3c-cg.github.io/rsp/spec/messages">RDF Messages</a> draft illustrates in
 * §4.1.1 (22 October 2026): a {@code text/event-stream} response whose
 * {@code RDF-Message-Content-Type} header names the RDF serialization of the payloads, and one
 * {@code rdf-message} event per message. The draft calls these headers and names illustrative;
 * they are followed here as written.
 *
 * <p>Payloads are JSON-LD (the draft's example), one line per event, or N-Quads, one {@code data:}
 * line per statement. Each event is a standalone document, so blank nodes are scoped to their
 * message, and an empty message is the payload {@code {}} (JSON-LD) or no statements (N-Quads).
 *
 * <p>The consumer follows the WHATWG event-stream parsing rules: the stream is UTF-8 whatever
 * charset the response names; lines end in CR, LF or CRLF; a
 * line starting with ':' is a comment; a field's value loses one leading space; several
 * {@code data:} lines join with a newline; an event is dispatched at a blank line, and not at all
 * if its data is empty. Events of other types are skipped; media types are compared
 * case-insensitively. JSON-LD payloads go through
 * {@link JsonLdMessageLog#parseMessage}, with its checks and no network access.
 *
 * <p>This is a demonstration transport: the producer serves one fixed list of messages to each
 * client (clients in parallel), on the loopback address only, and closes the stream at the end; it does not resume from
 * {@code Last-Event-ID}.
 */
public final class SseMessageChannel {

    private SseMessageChannel() {
    }

    /** The event type the draft's example uses. */
    public static final String EVENT = "rdf-message";

    /** The response header naming the payloads' RDF serialization (§4.1.1). */
    public static final String PAYLOAD_HEADER = "RDF-Message-Content-Type";

    /** The payload serializations this channel carries. */
    public enum Payload {
        JSON_LD("application/ld+json"), N_QUADS("application/n-quads");

        private final String mediaType;

        Payload(String mediaType) {
            this.mediaType = mediaType;
        }

        public String mediaType() {
            return mediaType;
        }
    }

    // ----------------------------------------------------------------------------------- producer

    /**
     * Serves {@code messages} on {@code http://127.0.0.1:<port>/messages}, one event per message,
     * {@code delayMillis} apart, then ends the response.
     */
    public static final class Producer implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newCachedThreadPool();

        public Producer(List<? extends List<Statement>> messages, Payload payload, long delayMillis)
                throws IOException {
            List<String> events = new ArrayList<>();
            int n = 0;
            for (List<Statement> message : messages) {
                events.add(event(++n, encode(message, payload)));
            }
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/messages", exchange -> serve(exchange, events, payload, delayMillis));
            server.setExecutor(executor);                  // one thread per client, not one for all
            server.start();
        }

        /** The stream's URL. */
        public URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/messages");
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }

        private static void serve(HttpExchange exchange, List<String> events, Payload payload, long delayMillis)
                throws IOException {
            try (exchange) {
                if (!exchange.getRequestMethod().equals("GET")) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                exchange.getResponseHeaders().set(PAYLOAD_HEADER, payload.mediaType());
                exchange.sendResponseHeaders(200, 0);
                OutputStream body = exchange.getResponseBody();
                for (String event : events) {
                    body.write(event.getBytes(StandardCharsets.UTF_8));
                    body.flush();
                    if (delayMillis > 0) {
                        Thread.sleep(delayMillis);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** One message's payload text. */
    static String encode(List<Statement> message, Payload payload) {
        if (payload == Payload.JSON_LD) {
            return JsonLdMessageLog.messageObject(message);
        }
        RdfMessageLog.checkWritable(List.of(message), true, "N-Quads");
        StringWriter text = new StringWriter();
        Rio.write(message, text, RDFFormat.NQUADS);
        return text.toString().strip();
    }

    /** One event: its type, its number as id, and one {@code data:} line per payload line. */
    static String event(int id, String payload) {
        StringBuilder out = new StringBuilder("event: ").append(EVENT).append('\n')
                .append("id: ").append(id).append('\n');
        // an empty N-Quads payload still needs a data field, or the event is not dispatched
        for (String line : payload.isEmpty() ? List.of("") : payload.lines().toList()) {
            out.append("data: ").append(line).append('\n');
        }
        return out.append('\n').toString();
    }

    // ----------------------------------------------------------------------------------- consumer

    /**
     * Reads the event stream at {@code uri} to its end and hands each {@code rdf-message} event's
     * message to {@code sink}, in order.
     *
     * @param contexts remote JSON-LD contexts by URL (may be empty); nothing is fetched
     * @return the number of messages handed over
     * @throws IOException if the response is not an event stream or names no known payload type
     */
    public static int consume(URI uri, String baseIri, Map<String, String> contexts,
                              Consumer<List<Statement>> sink) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(uri).header("Accept", "text/event-stream").GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        // an event stream is always UTF-8, whatever charset the Content-Type names (WHATWG); a
        // BufferedReader ends a line at CR, LF or CRLF, as the event-stream grammar does
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String type = response.headers().firstValue("Content-Type").orElse("");
            if (response.statusCode() != 200 || !type.toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream")) {
                throw new IOException("not an event stream: HTTP " + response.statusCode() + ", " + type);
            }
            String payloadType = response.headers().firstValue(PAYLOAD_HEADER)
                    .orElseThrow(() -> new IOException("no " + PAYLOAD_HEADER + " header"));
            Payload payload = Stream.of(Payload.values())
                    .filter(p -> p.mediaType().equalsIgnoreCase(payloadType.split(";")[0].strip()))
                    .findFirst()
                    .orElseThrow(() -> new IOException("unsupported payload type " + payloadType));
            return dispatch(lines.lines().iterator(), data -> sink.accept(decode(data, payload, baseIri, contexts)));
        }
    }

    /**
     * The WHATWG event-stream rules over already split lines: returns the number of
     * {@code rdf-message} events whose data went to {@code sink}.
     */
    static int dispatch(Iterator<String> lines, Consumer<String> sink) {
        String eventType = "";
        StringBuilder data = new StringBuilder();
        int dispatched = 0;
        boolean first = true;
        while (lines.hasNext()) {
            String line = lines.next();
            if (first && line.startsWith("﻿")) {
                line = line.substring(1);
            }
            first = false;
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    data.setLength(data.length() - 1);              // the last data line's newline
                    if ((eventType.isEmpty() ? "message" : eventType).equals(EVENT)) {
                        sink.accept(data.toString());
                        dispatched++;
                    }
                }
                eventType = "";
                data.setLength(0);
                continue;
            }
            if (line.startsWith(":")) {
                continue;                                          // a comment
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            switch (field) {
                case "event" -> eventType = value;
                case "data" -> data.append(value).append('\n');
                default -> {
                    // id and retry matter only for reconnecting, which this consumer does not do
                }
            }
        }
        return dispatched;                                         // an unterminated last event is dropped
    }

    private static List<Statement> decode(String data, Payload payload, String baseIri, Map<String, String> contexts) {
        if (payload == Payload.JSON_LD) {
            return JsonLdMessageLog.parseMessage(data, baseIri, contexts);
        }
        try {
            Model model = Rio.parse(new StringReader(data), baseIri, RDFFormat.NQUADS);
            return new ArrayList<>(model);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("an event's N-Quads payload does not parse: " + e.getMessage(), e);
        }
    }
}
