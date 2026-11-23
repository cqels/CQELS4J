package org.cqels.examples.messages;

import static org.cqels.examples.messages.RdfMessagesTestSuiteTest.assertMessages;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
                MessageLogReplay.readResource("/messages/cabin-temperatures.ttl"), RDFFormat.TURTLE, BASE);
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
}
