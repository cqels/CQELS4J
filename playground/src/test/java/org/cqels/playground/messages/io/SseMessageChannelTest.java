package org.cqels.playground.messages.io;

import static org.cqels.playground.messages.io.RdfMessagesTestSuiteTest.assertMessages;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.Test;

/** {@link SseMessageChannel}: the event-stream rules, and a loopback producer to consumer run. */
class SseMessageChannelTest {

    private static final String BASE = "https://example.org/fleet/";

    private static List<String> dispatch(String... lines) {
        List<String> data = new ArrayList<>();
        int n = SseMessageChannel.dispatch(List.of(lines).iterator(), data::add);
        assertEquals(data.size(), n);
        return data;
    }

    @Test
    void eventStreamRules() {
        // several data lines join with a newline; one leading space is dropped, not two
        assertEquals(List.of("a\n  b"), dispatch("event: rdf-message", "data: a", "data:   b", ""));
        // "data" with no colon is an empty data line; an event with only that dispatches ""
        assertEquals(List.of(""), dispatch("event: rdf-message", "data", ""));
        // comments, ids and retry are not data; a field with no space after ':' still counts
        assertEquals(List.of("x"), dispatch(": keep-alive", "event:rdf-message", "id: 7", "retry: 10", "data:x", ""));
        // other event types, including the default "message", are skipped
        assertEquals(List.of(), dispatch("data: x", "", "event: other", "data: y", ""));
        // an event with no data is not dispatched, and its type does not leak into the next
        assertEquals(List.of(), dispatch("event: rdf-message", "", "data: z", ""));
        // an unterminated last event is dropped
        assertEquals(List.of("a"), dispatch("event: rdf-message", "data: a", "", "event: rdf-message", "data: b"));
        // a byte-order mark at the start is ignored
        assertEquals(List.of("a"), dispatch("﻿event: rdf-message", "data: a", ""));
    }

    @Test
    void producerToConsumerKeepsEveryMessage() throws Exception {
        List<List<Statement>> messages = RdfMessageLog.parse(
                readResource("/messages/cabin-temperatures.ttl"), RDFFormat.TURTLE, BASE);
        for (SseMessageChannel.Payload payload : SseMessageChannel.Payload.values()) {
            List<List<Statement>> received = Collections.synchronizedList(new ArrayList<>());
            try (SseMessageChannel.Producer producer = new SseMessageChannel.Producer(messages, payload, 0)) {
                int n = SseMessageChannel.consume(producer.uri(), BASE, Map.of(), received::add);
                assertEquals(messages.size(), n, payload.name());
            }
            assertMessages(messages, received);
            // every observation's result is _:r in the log; each event keeps its own
            assertNotEquals(resultNode(received.get(1)), resultNode(received.get(2)), payload.name());
        }
    }

    private static Object resultNode(List<Statement> message) {
        return message.stream().filter(st -> st.getPredicate().getLocalName().equals("hasResult"))
                .map(Statement::getObject).findFirst().orElseThrow();
    }

    @Test
    void theStreamIsUtf8WhateverCharsetItNamesAndLinesMayEndInCr() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        byte[] body = ("event: rdf-message\rdata: {\"@id\":\"http://e/s\",\"http://e/p\":\"caf\u00e9\"}\r\r")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        server.createContext("/m", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=iso-8859-1");
            exchange.getResponseHeaders().set(SseMessageChannel.PAYLOAD_HEADER, "application/ld+json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            List<List<Statement>> received = new ArrayList<>();
            int n = SseMessageChannel.consume(java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/m"),
                    BASE, Map.of(), received::add);
            assertEquals(1, n);
            assertEquals("caf\u00e9", received.get(0).get(0).getObject().stringValue());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mediaTypesAreCaseInsensitiveAndClientsAreServedInParallel() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        byte[] body = "event: rdf-message\ndata: {}\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        server.createContext("/m", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "Text/Event-Stream");
            exchange.getResponseHeaders().set(SseMessageChannel.PAYLOAD_HEADER, "Application/LD+JSON");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            assertEquals(1, SseMessageChannel.consume(java.net.URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/m"), BASE, Map.of(), m -> { }));
        } finally {
            server.stop(0);
        }
        // six events 300 ms apart take 1.8 s per client; served one after the other, two take 3.6 s
        List<List<Statement>> six = java.util.Collections.nCopies(6, List.of());
        try (SseMessageChannel.Producer producer = new SseMessageChannel.Producer(six, SseMessageChannel.Payload.JSON_LD, 300)) {
            long start = System.nanoTime();
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            List<java.util.concurrent.Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> SseMessageChannel.consume(producer.uri(), BASE, Map.of(), m -> { })));
            }
            for (java.util.concurrent.Future<Integer> run : runs) {
                assertEquals(6, run.get());
            }
            pool.shutdown();
            long ms = (System.nanoTime() - start) / 1_000_000;
            org.junit.jupiter.api.Assertions.assertTrue(ms < 3000, "two clients took " + ms + " ms");
        }
    }

    /** The demos' cabin-temperature log, read from the classpath as {@code MessageLogReplay} reads it. */
    private static String readResource(String path) throws IOException {
        try (InputStream in = SseMessageChannelTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
