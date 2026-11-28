package org.cqels.playground.messages.io;

import static org.cqels.playground.messages.io.RdfMessagesTestSuiteTest.assertMessages;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.junit.jupiter.api.Test;

/** {@link JsonLdMessageLog}: NDJSON-LD (§2.2) and YAML-LD (§2.3) beyond the companion tests. */
class JsonLdMessageLogTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final String BASE = "http://example.org/base/";
    private static final Map<String, String> SCHEMA =
            Map.of("https://schema.org/", "{\"@context\": {\"@vocab\": \"https://schema.org/\"}}");

    private static List<List<Statement>> nd(String log) {
        return JsonLdMessageLog.parseNdjson(log, BASE, Map.of());
    }

    private static List<List<Statement>> yaml(String log) {
        return JsonLdMessageLog.parseYaml(log, BASE, SCHEMA);
    }

    private static List<Integer> sizes(List<List<Statement>> messages) {
        return messages.stream().map(List::size).collect(Collectors.toList());
    }

    private static IRI iri(String s) {
        return VF.createIRI(s);
    }

    private static final String S1 = "{\"@id\":\"http://e/s\",\"http://e/p\":\"1\"}";

    // -------------------------------------------------------------------------------- NDJSON-LD

    @Test
    void lineRules() {
        assertEquals(List.of(), nd(""));
        assertEquals(List.of(1, 0, 1), sizes(nd(S1 + "\n{}\n" + S1)));          // no final newline
        assertEquals(List.of(1, 1), sizes(nd(S1 + "\r\n" + S1 + "\r\n")));      // CRLF
        assertThrows(IllegalArgumentException.class, () -> nd(S1 + "\n\n" + S1 + "\n"));   // blank line
        assertThrows(IllegalArgumentException.class, () -> nd(S1 + "\n \n"));               // blank line
        assertThrows(IllegalArgumentException.class, () -> nd("[" + S1 + "]\n"));          // not an object
        assertThrows(IllegalArgumentException.class, () -> nd("{} {}\n"));                 // two values
        assertThrows(IllegalArgumentException.class, () -> nd("{\"@id\":\"http://e/a\",\"@id\":\"http://e/b\"}\n"));
        assertThrows(IllegalArgumentException.class, () -> nd("{\"@id\":\n\"http://e/s\"}\n"));  // split object
    }

    @Test
    void blankNodesAreScopedPerLine() {
        String line = "{\"@id\":\"_:b0\",\"http://e/p\":\"x\"}";
        List<List<Statement>> messages = nd(line + "\n" + line + "\n");
        assertTrue(messages.get(0).get(0).getSubject() instanceof BNode);
        assertNotEquals(messages.get(0).get(0).getSubject(), messages.get(1).get(0).getSubject());
    }

    @Test
    void rdf4jsOwnNdjsonParserSharesBlankNodesAcrossLines() throws Exception {
        // the reason this class does not use it (documented in its Javadoc)
        String line = "{\"@id\":\"_:b0\",\"http://e/p\":\"x\"}";
        String line2 = "{\"@id\":\"_:b0\",\"http://e/q\":\"y\"}";
        Model model = Rio.parse(new StringReader(line + "\n" + line2 + "\n"), BASE, RDFFormat.NDJSONLD);
        assertEquals(1, model.subjects().size());
    }

    @Test
    void contextMessages() {
        String ctx = "{\"@context\":{\"p\":\"http://e/p\"}}";
        String data = "{\"@id\":\"http://e/s\",\"p\":\"v\"}";
        assertEquals(List.of(1), sizes(nd(ctx + "\n" + data + "\n")));
        // a context message emits nothing
        assertEquals(List.of(), nd(ctx + "\n"));
        // the context stays in force across an empty message
        assertEquals(List.of(0, 1), sizes(nd(ctx + "\n{}\n" + data + "\n")));
        // a null context resets it, and "p" is then not a term
        assertEquals(List.of(0), sizes(nd(ctx + "\n{\"@context\":null}\n" + data + "\n")));
        // an object with @context and anything else is a data message
        assertEquals(List.of(1), sizes(nd("{\"@context\":{\"p\":\"http://e/p\"},\"@id\":\"http://e/s\",\"p\":\"v\"}\n")));
    }

    @Test
    void remoteContextsAreNeverFetched() {
        String log = "{\"@context\":\"https://schema.org/\"}\n{\"@type\":\"Product\"}\n";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> nd(log));
        assertTrue(e.getMessage().contains("not among the contexts provided"), e.getMessage());
        assertEquals(List.of(1), sizes(JsonLdMessageLog.parseNdjson(log, BASE, SCHEMA)));
    }

    @Test
    void jsonLd11FeaturesAreRefusedNotDropped() {
        for (String line : List.of(
                "{\"@id\":\"http://e/s\",\"http://e/p\":{\"@value\":\"v\",\"@language\":\"ar\",\"@direction\":\"rtl\"}}",
                "{\"@context\":{\"@version\":1.1},\"@id\":\"http://e/s\",\"http://e/p\":\"v\"}",
                "{\"@id\":\"http://e/s\",\"@included\":[{\"@id\":\"http://e/t\",\"http://e/p\":\"v\"}]}",
                "{\"@context\":{\"t\":{\"@id\":\"http://e/t\",\"@context\":{\"q\":\"http://e/q\"}}},"
                        + "\"@id\":\"http://e/s\",\"t\":{\"@id\":\"http://e/o\",\"q\":\"v\"}}")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> nd(line + "\n"), line);
            assertTrue(e.getMessage().contains("#133"), e.getMessage());
        }
        // the same checks apply to a remote context
        Map<String, String> scoped = Map.of("http://e/ctx",
                "{\"@context\":{\"t\":{\"@id\":\"http://e/t\",\"@context\":{\"q\":\"http://e/q\"}}}}");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> JsonLdMessageLog.parseNdjson(
                "{\"@context\":\"http://e/ctx\"}\n{\"@id\":\"http://e/s\",\"t\":{\"q\":\"v\"}}\n", BASE, scoped));
        assertTrue(e.getMessage().contains("#133"), e.getMessage());
    }

    @Test
    void writerRoundTripsGraphsLiteralsAndEmptyMessages() {
        BNode b = VF.createBNode();
        List<List<Statement>> messages = List.of(
                List.of(),
                List.of(VF.createStatement(b, iri("http://e/p"), VF.createLiteral("x\ny \"z\"", "en")),
                        VF.createStatement(b, iri("http://e/q"), VF.createLiteral("1.50", XSD.DECIMAL), iri("http://e/g"))),
                List.of(VF.createStatement(iri("http://e/s"), iri("http://e/p"), VF.createLiteral("yes"))),
                List.of());
        String log = JsonLdMessageLog.writeNdjson(messages);
        assertEquals(4, log.lines().count());
        assertTrue(log.lines().allMatch(l -> l.startsWith("{")), log);
        assertMessages(messages, nd(log));
    }

    @Test
    void writersRefuseTripleTerms() {
        Statement quoted = VF.createStatement(iri("http://e/s"), iri("http://e/p"),
                VF.createTriple(iri("http://e/a"), iri("http://e/b"), iri("http://e/c")));
        for (Runnable write : List.<Runnable>of(
                () -> JsonLdMessageLog.writeNdjson(List.of(List.of(quoted))),
                () -> JsonLdMessageLog.writeYaml(List.of(List.of(quoted))),
                () -> RdfXmlMessageLog.write(List.of(List.of(quoted)), Map.of()),
                () -> RdfMessageLog.writeNQuads(List.of(List.of(quoted))),
                () -> RdfMessageLog.writeTriG(List.of(List.of(quoted)), Map.of()))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, write::run);
            assertTrue(e.getMessage().contains("#133"), e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------- YAML-LD

    @Test
    void theDraftsYamlExample() {
        String log = """
                "@context": https://schema.org/
                "@type": Product
                name: Hairdryer DRY 2000
                color: red
                ---
                "@context": https://schema.org/
                "@type": Product
                name: 27-inch 4K Monitor 2700P
                color: black
                """;
        List<List<Statement>> messages = yaml(log);
        assertEquals(List.of(3, 3), sizes(messages));
        assertNotEquals(messages.get(0).get(0).getSubject(), messages.get(1).get(0).getSubject());
    }

    @Test
    void yaml12CoreSchemaScalars() {
        List<Statement> m = yaml("""
                "@id": http://e/s
                http://e/yes: yes
                http://e/octal: 0o17
                http://e/dec: 0777
                http://e/date: 2026-11-23
                """).get(0);
        Map<String, String> byPredicate = m.stream().collect(Collectors.toMap(
                st -> st.getPredicate().getLocalName(), st -> st.getObject().toString()));
        assertEquals("\"yes\"", byPredicate.get("yes"));
        assertEquals("\"15\"^^<http://www.w3.org/2001/XMLSchema#integer>", byPredicate.get("octal"));
        assertEquals("\"777\"^^<http://www.w3.org/2001/XMLSchema#integer>", byPredicate.get("dec"));
        assertEquals("\"2026-11-23\"", byPredicate.get("date"));
    }

    @Test
    void yamlDocumentRules() {
        assertEquals(List.of(), yaml(""));
        assertEquals(List.of(0, 1), sizes(yaml("--- {}\n--- {\"@id\": \"http://e/s\", \"http://e/p\": v}\n")));
        assertEquals(List.of(2), sizes(yaml("\"@id\": http://e/s\nhttp://e/p: &v {\"@id\": http://e/o}\nhttp://e/q: *v\n")));
        for (String bad : List.of(
                "\"@id\": http://e/s\n---\n",                          // trailing empty document
                "---\n---\n\"@id\": http://e/s\n",                     // empty first document
                "&a [*a]\n",                                           // recursive alias
                "\"@id\": http://e/s\nhttp://e/p: !custom 1\n",        // unknown tag
                "1: v\n",                                              // key not a string
                "\"@id\": http://e/s\nhttp://e/p: .inf\n",             // no JSON form
                "just a scalar\n",
                "\"@id\": [unclosed\n")) {
            assertThrows(IllegalArgumentException.class, () -> yaml(bad), bad);
        }
    }

    @Test
    void yamlWriterRoundTripsStringsThatLookLikeOtherScalars() {
        IRI s = iri("http://e/s");
        List<Statement> message = List.of("true", "yes", "1", "null", "~", "0o17", ".inf", "", "#x", "a: b",
                        "line1\nline2", " lead", "@x", "---").stream()
                .map(v -> VF.createStatement(s, iri("http://e/p"), VF.createLiteral(v)))
                .collect(Collectors.toList());
        List<List<Statement>> messages = List.of(message, List.of(),
                List.of(VF.createStatement(VF.createBNode(), iri("http://e/q"), VF.createLiteral("v", "fr"), iri("http://e/g"))));
        String log = JsonLdMessageLog.writeYaml(messages);
        assertMessages(messages, JsonLdMessageLog.parseYaml(log, BASE, Map.of()));
    }

    // ------------------------------------------------------------------ co-review round 5

    @Test
    void aByteOrderMarkBeforeTheFirstLineIsSkipped() {
        assertEquals(List.of(1), sizes(nd("\uFEFF" + S1 + "\n")));
        assertEquals(List.of(), nd("\uFEFF"));
    }

    @Test
    void yamlTagsAndVersions() {
        for (String bad : List.of("\"@id\": http://e/s\nhttp://e/p: !!bool yes\n",
                "\"@id\": http://e/s\nhttp://e/p: !!null x\n",
                "%YAML 1.1\n--- {\"@id\": http://e/s, http://e/p: yes}\n",
                "\"@id\": http://e/s\nhttp://e/p: !!binary YQ==\n",
                "\"@id\": http://e/s\nhttp://e/p: !!set {x}\n")) {
            assertThrows(IllegalArgumentException.class, () -> yaml(bad), bad);
        }
        assertEquals(List.of(1), sizes(yaml("%YAML 1.2\n--- {\"@id\": http://e/s, http://e/p: !!bool true}\n")));
        assertEquals(List.of(1), sizes(yaml("\"@id\": http://e/s\nhttp://e/p: !!str 15\n")));
    }

    @Test
    void writersKeepNumberSpellingsAndRefuseUnwritableIris() {
        IRI s = iri("http://e/s");
        IRI p = iri("http://e/p");
        List<List<Statement>> numbers = List.of(List.of(
                VF.createStatement(s, p, VF.createLiteral("01", XSD.INTEGER)),
                VF.createStatement(s, p, VF.createLiteral("+1", XSD.INTEGER)),
                VF.createStatement(s, p, VF.createLiteral("1.00", XSD.DOUBLE)),
                VF.createStatement(s, p, VF.createLiteral("1.", XSD.DECIMAL))));
        Map<String, String> ns = Map.of("e", "http://e/");
        assertMessages(numbers, RdfMessageLog.parse(RdfMessageLog.writeTurtle(numbers, ns), RDFFormat.TURTLE, BASE));
        assertMessages(numbers, RdfMessageLog.parse(RdfMessageLog.writeTriG(numbers, ns), RDFFormat.TRIG, BASE));
        for (String bad : List.of("http://e/a b", "http://e/a\\b", "http://e/a>b", "http://e/a{b}")) {
            List<List<Statement>> m = List.of(List.of(VF.createStatement(s, p, iri(bad))));
            for (Runnable write : List.<Runnable>of(() -> RdfMessageLog.writeTriG(m, ns), () -> JsonLdMessageLog.writeNdjson(m),
                    () -> RdfXmlMessageLog.write(m, ns), () -> RdfMessageLog.writeNQuads(m))) {
                assertThrows(IllegalArgumentException.class, write::run, bad);
            }
        }
    }

    // ------------------------------------------------------------------ co-review round 6

    @Test
    void numbersBeyondDoubleAreRefusedNotTurnedIntoStrings() {
        assertThrows(IllegalArgumentException.class, () -> nd("{\"@id\":\"http://e/s\",\"http://e/p\":1e400}\n"));
        assertThrows(IllegalArgumentException.class, () -> nd("{\"@id\":\"http://e/s\",\"http://e/p\":-1e400}\n"));
        Statement st = nd("{\"@id\":\"http://e/s\",\"http://e/p\":1.5e2}\n").get(0).get(0);
        assertEquals(XSD.DOUBLE, ((org.eclipse.rdf4j.model.Literal) st.getObject()).getDatatype());
    }

    @Test
    void aPropertyValuedIndexIsRefused() {
        String line = "{\"@context\":{\"t\":{\"@id\":\"http://e/t\",\"@container\":\"@index\",\"@index\":\"http://e/i\"}},"
                + "\"@id\":\"http://e/s\",\"t\":{\"k\":{\"@id\":\"http://e/o\"}}}\n";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> nd(line));
        assertTrue(e.getMessage().contains("#133"), e.getMessage());
    }

    @Test
    void yamlMergeKeysAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> yaml(
                "\"@id\": http://e/s\nhttp://e/a: &a {http://e/q: 1}\nhttp://e/m: {<<: *a, http://e/r: 2}\n"));
        assertThrows(IllegalArgumentException.class, () -> yaml(
                "\"@id\": http://e/s\nhttp://e/a: &a {http://e/q: 1}\nhttp://e/m:\n  <<: *a\n  http://e/r: 2\n"));
        // as a plain value, snakeyaml-engine refuses it too (it has no constructor for the merge tag)
        assertThrows(IllegalArgumentException.class, () -> yaml("\"@id\": http://e/s\nhttp://e/r: <<\n"));
        assertEquals(List.of(1), sizes(yaml("\"@id\": http://e/s\nhttp://e/r: \"<<\"\n")));
        // quoted, "<<" is an ordinary key, which JSON-LD drops as it is not an IRI
        assertEquals(List.of(1), sizes(yaml("\"@id\": http://e/s\nhttp://e/r: 2\n\"<<\": x\n")));
    }

    // ------------------------------------------------------------------ co-review round 7

    @Test
    void numbersAreTypedAsDirectRdf4jTypesThem() throws Exception {
        String big = "1" + "0".repeat(400);
        for (String n : List.of("1.0", "0.0", "-0.0", "2.0", "-3.0", "1e2", "1.5e2", "10.0", "9007199254740993.0",
                "1.000000000000000000001", "123456789012345678901234567890", big)) {
            String line = "{\"@id\":\"http://e/s\",\"http://e/p\":" + n + "}";
            List<Statement> ours = nd(line + "\n").get(0);
            Model direct = Rio.parse(new StringReader(line), BASE, RDFFormat.JSONLD);
            assertMessages(List.of(new java.util.ArrayList<>(direct)), List.of(ours));
        }
    }

    @Test
    void onlyAnUntaggedPlainKeyIsAMergeKey() {
        assertEquals(List.of(1), sizes(yaml("\"@id\": http://e/s\nhttp://e/r: 2\n!!str <<: x\n")));
        assertEquals(List.of(1), sizes(yaml("\"@id\": http://e/s\nhttp://e/r: 2\n! <<: x\n")));
    }
}
