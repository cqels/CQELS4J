package org.cqels.playground.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The W3C RSP CG <a href="https://w3c-cg.github.io/rsp/spec/messages-tests">RDF Messages Tests</a>
 * (22 October 2026), run against this package's readers and writers. Inputs and expectations are
 * read from {@code src/test/resources/rdf-messages-tests/}, copied byte for byte from the page
 * (see the README there).
 *
 * <p>Every test of the page runs. Each one is named after its section, with the format in
 * brackets where a test applies to several.
 */
class RdfMessagesTestSuiteTest {

    private static final String DIR = "/rdf-messages-tests/";
    private static final String BASE = "http://example.org/base/";

    /** Stand-ins for the remote contexts the NDJSON-LD tests name: nothing is fetched. */
    private static final Map<String, String> CONTEXTS = Map.of(
            "https://schema.org/", "{\"@context\": {\"@vocab\": \"https://schema.org/\"}}",
            "https://example.org/other-vocab/", "{\"@context\": {\"@vocab\": \"https://example.org/other-vocab/\"}}");

    private static final Map<String, Function<String, List<List<Statement>>>> TEXT_READERS = new LinkedHashMap<>();

    static {
        TEXT_READERS.put("N-Triples", log -> RdfMessageLog.parse(log, RDFFormat.NTRIPLES, BASE));
        TEXT_READERS.put("N-Quads", log -> RdfMessageLog.parse(log, RDFFormat.NQUADS, BASE));
        TEXT_READERS.put("Turtle", log -> RdfMessageLog.parse(log, RDFFormat.TURTLE, BASE));
        TEXT_READERS.put("TriG", log -> RdfMessageLog.parse(log, RDFFormat.TRIG, BASE));
    }

    /** Which formats each §1.1 input is written in. */
    private static final Map<String, List<String>> FORMATS_1_1 = Map.ofEntries(
            Map.entry("1.1.1", List.of("N-Triples", "N-Quads", "Turtle", "TriG")),
            Map.entry("1.1.2", List.of("N-Triples", "N-Quads", "Turtle", "TriG")),
            Map.entry("1.1.3", List.of("Turtle", "TriG")),
            Map.entry("1.1.4", List.of("N-Triples", "N-Quads", "Turtle", "TriG")),
            Map.entry("1.1.5", List.of("N-Triples", "N-Quads", "Turtle", "TriG")),
            Map.entry("1.1.6", List.of("N-Triples", "N-Quads", "Turtle", "TriG")),
            Map.entry("1.1.7", List.of("N-Quads")),
            Map.entry("1.1.8", List.of("TriG")),
            Map.entry("1.1.9", List.of("N-Triples", "N-Quads", "Turtle", "TriG")),
            Map.entry("1.1.10", List.of("Turtle", "TriG")),
            Map.entry("1.1.11", List.of("TriG")));

    // ------------------------------------------------------------------- §1.1 text formats

    @TestFactory
    Stream<DynamicTest> parsingTurtleTrigNTriplesNQuads() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (String id : ids("1.1.")) {
            String input = resource(id + ".txt");
            for (String format : FORMATS_1_1.get(id)) {
                Function<String, List<List<Statement>>> reader = TEXT_READERS.get(format);
                if (id.equals("1.1.9")) {
                    tests.add(dynamicTest(name(id) + " [" + format + "]",
                            () -> twoMessagesWithDistinctBlankSubjects(reader.apply(input))));
                } else {
                    String expected = resource(id + ".expected");
                    tests.add(dynamicTest(name(id) + " [" + format + "]",
                            () -> assertMessages(expectedMessages(expected), reader.apply(input))));
                }
            }
        }
        return tests.stream();
    }

    // ------------------------------------------------------------------------ §1.2 NDJSON-LD

    @TestFactory
    Stream<DynamicTest> parsingNdjsonLd() throws IOException {
        String schema = "PREFIX s: <https://schema.org/>\n";
        Map<String, List<String>> expected = Map.of(
                "1.2.1", List.of(schema + "[] a s:Product ; s:name \"Hairdryer DRY 2000\" ; s:color \"red\" ."),
                "1.2.2", List.of("", schema + "[] a s:Product ; s:name \"27-inch 4K Monitor 2700P\" ; s:color \"black\" ."),
                "1.2.3", List.of(schema + "[] a s:Product ; s:name \"Hairdryer DRY 2000\" ; s:color \"red\" .",
                        "PREFIX o: <https://example.org/other-vocab/>\n[] a o:OtherType ; o:name \"Piotr\" ."),
                "1.2.4", List.of("PREFIX ex: <https://example.org/base/>\n[] a ex:Thing ; ex:baseName \"Alice\" ; ex:label \"A\" .",
                        "PREFIX ex: <https://example.org/base/>\n[] a ex:Thing ; ex:baseName \"Bob\" ."),
                "1.2.5", List.of("<https://example.org/p1> a <https://example.org/Product> ."));
        List<DynamicTest> tests = new ArrayList<>();
        for (String id : ids("1.2.")) {
            String input = resource(id + ".ndjson");
            tests.add(dynamicTest(name(id), () -> {
                List<List<Statement>> messages = JsonLdMessageLog.parseNdjson(input, BASE, CONTEXTS);
                List<List<Statement>> want = new ArrayList<>();
                for (String turtle : expected.get(id)) {
                    want.add(turtle.isEmpty() ? List.of()
                            : new ArrayList<>(Rio.parse(new StringReader(turtle), BASE, RDFFormat.TURTLE)));
                }
                assertMessages(want, messages);
                if (id.equals("1.2.3")) {                // two messages, two different blank nodes
                    assertNotEquals(messages.get(0).get(0).getSubject(), messages.get(1).get(0).getSubject());
                }
            }));
        }
        return tests.stream();
    }

    // -------------------------------------------------------------------------- §1.3 RDF/XML

    @TestFactory
    Stream<DynamicTest> parsingRdfXml() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (String id : ids("1.3.")) {
            String input = resource(id + ".rdf");
            String expected = resource(id + ".expected");
            tests.add(dynamicTest(name(id), () -> {
                List<List<Statement>> messages = RdfXmlMessageLog.parse(input, BASE);
                switch (id) {
                    case "1.3.2" -> assertEquals(List.of(), messages);
                    case "1.3.4" -> twoMessagesWithDistinctBlankSubjects(messages);
                    case "1.3.5" -> {
                        assertEquals(1, messages.size());
                        List<Statement> message = messages.get(0);
                        assertEquals(2, message.size());
                        assertTrue(message.get(0).getSubject() instanceof BNode);
                        assertEquals(message.get(0).getSubject(), message.get(1).getSubject());
                    }
                    default -> assertMessages(expectedMessages(expected), messages);
                }
            }));
        }
        return tests.stream();
    }

    // ---------------------------------------------------------------------- §1.4 round trips

    @TestFactory
    Stream<DynamicTest> serializationRoundTrips() throws IOException {
        Map<String, String> ns = Map.of("ex", "http://example.org/");
        Map<String, Function<List<List<Statement>>, List<List<Statement>>>> roundTrips = new LinkedHashMap<>();
        roundTrips.put("N-Triples", m -> RdfMessageLog.parse(RdfMessageLog.writeNQuads(m), RDFFormat.NTRIPLES, BASE));
        roundTrips.put("N-Quads", m -> RdfMessageLog.parse(RdfMessageLog.writeNQuads(m), RDFFormat.NQUADS, BASE));
        roundTrips.put("Turtle", m -> RdfMessageLog.parse(RdfMessageLog.writeTurtle(m, ns), RDFFormat.TURTLE, BASE));
        roundTrips.put("TriG", m -> RdfMessageLog.parse(RdfMessageLog.writeTriG(m, ns), RDFFormat.TRIG, BASE));
        // not in the page's list of formats, but the same round trip
        roundTrips.put("NDJSON-LD", m -> JsonLdMessageLog.parseNdjson(JsonLdMessageLog.writeNdjson(m), BASE, Map.of()));
        roundTrips.put("YAML-LD", m -> JsonLdMessageLog.parseYaml(JsonLdMessageLog.writeYaml(m), BASE, Map.of()));
        roundTrips.put("RDF/XML", m -> RdfXmlMessageLog.parse(RdfXmlMessageLog.write(m, ns), BASE));
        List<DynamicTest> tests = new ArrayList<>();
        for (int n = 1; n <= 2; n++) {
            List<List<Statement>> input = expectedMessages(resource("1.4-" + n + ".input"));
            List<List<Statement>> expected = expectedMessages(resource("1.4-" + n + ".expected"));
            boolean graphs = input.stream().flatMap(List::stream).anyMatch(st -> st.getContext() != null);
            for (var e : roundTrips.entrySet()) {
                String format = e.getKey();
                boolean noGraphs = format.equals("N-Triples") || format.equals("Turtle") || format.equals("RDF/XML");
                String title = "1.4 round trip " + n + (graphs ? " (named graphs)" : "") + " [" + format + "]";
                if (graphs && noGraphs) {
                    if (!format.equals("N-Triples")) {           // the N-Quads writer has no such check
                        tests.add(dynamicTest(title + ": refused, the format has no named graphs",
                                () -> assertThrows(IllegalArgumentException.class, () -> e.getValue().apply(input))));
                    }
                } else {
                    tests.add(dynamicTest(title, () -> assertMessages(expected, e.getValue().apply(input))));
                }
            }
        }
        return tests.stream();
    }

    // ---------------------------------------------------------------------------- §1.5 errors

    @TestFactory
    Stream<DynamicTest> errors() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        // 1.5.1 is about a parser without message support: plain RDF4J for each format, and the
        // Turtle/TriG reader here, which also needs a "-messages" label to accept a delimiter
        String noLabel = resource("1.5.1.txt");
        for (RDFFormat format : List.of(RDFFormat.NTRIPLES, RDFFormat.NQUADS, RDFFormat.TURTLE, RDFFormat.TRIG)) {
            tests.add(dynamicTest(name("1.5.1") + " [plain RDF4J " + format.getName() + "]",
                    () -> assertThrows(Exception.class, () -> Rio.parse(new StringReader(noLabel), BASE, format))));
        }
        for (String format : List.of("Turtle", "TriG")) {
            tests.add(dynamicTest(name("1.5.1") + " [" + format + ", no -messages label]",
                    () -> assertThrows(IllegalArgumentException.class, () -> TEXT_READERS.get(format).apply(noLabel))));
        }
        String atMessage = resource("1.5.2.txt");
        for (String format : TEXT_READERS.keySet()) {
            tests.add(dynamicTest(name("1.5.2") + " [" + format + "]",
                    () -> assertThrows(RuntimeException.class, () -> TEXT_READERS.get(format).apply(atMessage))));
        }
        String inGraph = resource("1.5.3.txt");
        tests.add(dynamicTest(name("1.5.3") + " [TriG]",
                () -> assertThrows(IllegalArgumentException.class, () -> TEXT_READERS.get("TriG").apply(inGraph))));
        for (String id : List.of("1.5.4", "1.5.5", "1.5.6")) {
            String input = resource(id + ".rdf");
            tests.add(dynamicTest(name(id),
                    () -> assertThrows(IllegalArgumentException.class, () -> RdfXmlMessageLog.parse(input, BASE))));
        }
        String log = resource("1.5.7.rdf");
        tests.add(dynamicTest(name("1.5.7") + " [plain RDF4J RDF/XML]",
                () -> assertThrows(Exception.class, () -> Rio.parse(new StringReader(log), BASE, RDFFormat.RDFXML))));
        tests.add(dynamicTest(name("1.5.7") + " [control: the message-aware reader accepts it]",
                () -> assertEquals(1, RdfXmlMessageLog.parse(log, BASE).size())));
        // every expectation file of §1.5 says the same
        for (String id : ids("1.5.")) {
            assertEquals("Parse error.\n", resource(id + ".expected"), id);
        }
        return tests.stream();
    }

    // ------------------------------------------------------------------------------- helpers

    /** The page's expectation format: "Message n:" blocks of N-Quads lines, or "empty". */
    static List<List<Statement>> expectedMessages(String text) throws IOException {
        List<List<Statement>> messages = new ArrayList<>();
        StringBuilder block = null;
        for (String line : text.split("\n")) {
            if (line.matches("Message \\d+:")) {
                if (block != null) {
                    messages.add(nquads(block.toString()));
                }
                block = new StringBuilder();
            } else if (!line.isBlank()) {
                String t = line.strip();
                if (block == null) {
                    throw new IllegalStateException("unexpected expectation line: " + line);
                }
                if (!t.equals("empty") && !t.equals("(empty)")) {
                    block.append(t).append('\n');
                }
            }
        }
        if (block != null) {
            messages.add(nquads(block.toString()));
        }
        return messages;
    }

    private static List<Statement> nquads(String text) throws IOException {
        return new ArrayList<>(Rio.parse(new StringReader(text), BASE, RDFFormat.NQUADS));
    }

    /** Same number of messages, and each message the same dataset up to blank-node naming. */
    static void assertMessages(List<List<Statement>> expected, List<List<Statement>> actual) {
        assertEquals(expected.size(), actual.size(), "number of messages: " + actual);
        for (int i = 0; i < expected.size(); i++) {
            Model want = new LinkedHashModel(expected.get(i));
            Model got = new LinkedHashModel(actual.get(i));
            assertTrue(Models.isomorphic(want, got), "message " + (i + 1) + ": expected " + want + ", got " + got);
        }
    }

    private static void twoMessagesWithDistinctBlankSubjects(List<List<Statement>> messages) {
        assertEquals(2, messages.size());
        assertEquals(1, messages.get(0).size());
        assertEquals(1, messages.get(1).size());
        assertTrue(messages.get(0).get(0).getSubject() instanceof BNode);
        assertTrue(messages.get(1).get(0).getSubject() instanceof BNode);
        assertFalse(messages.get(0).get(0).getSubject().equals(messages.get(1).get(0).getSubject()),
                "the two blank nodes must not be the same RDF term");
    }

    private static List<String> ids(String prefix) throws IOException {
        return index().keySet().stream().filter(id -> id.startsWith(prefix)).toList();
    }

    private static String name(String id) throws IOException {
        return id + " " + index().get(id);
    }

    private static Map<String, String> index() throws IOException {
        Map<String, String> index = new LinkedHashMap<>();
        for (String line : resource("INDEX.tsv").split("\n")) {
            String[] cols = line.split("\t", 2);
            index.put(cols[0], cols[1]);
        }
        return index;
    }

    static String resource(String name) throws IOException {
        try (InputStream in = RdfMessagesTestSuiteTest.class.getResourceAsStream(DIR + name)) {
            if (in == null) {
                throw new IOException("missing test resource " + DIR + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
