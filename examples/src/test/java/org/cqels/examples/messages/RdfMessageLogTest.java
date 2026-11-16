package org.cqels.examples.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.Test;

/**
 * {@link RdfMessageLog} against the examples in the RDF Messages draft (22 October 2026, §2.1),
 * plus the edge cases the Turtle/TriG splitter has to get right.
 */
class RdfMessageLogTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final String BASE = "http://example.org/base/";

    private static List<List<Statement>> ttl(String log) {
        return RdfMessageLog.parse(log, RDFFormat.TURTLE, BASE);
    }

    private static List<List<Statement>> trig(String log) {
        return RdfMessageLog.parse(log, RDFFormat.TRIG, BASE);
    }

    private static List<List<Statement>> nt(String log) {
        return RdfMessageLog.parse(log, RDFFormat.NTRIPLES, BASE);
    }

    private static List<Integer> sizes(List<List<Statement>> messages) {
        return messages.stream().map(List::size).collect(Collectors.toList());
    }

    private static IRI iri(String s) {
        return VF.createIRI(s);
    }

    // ------------------------------------------------------------- §2.1.2 examples, N-Triples

    private static final String NT_A =
            "<http://example.org/message_1> <http://example.org/hasValue> \"Hello, world!\" .\n";
    private static final String NT_B =
            "<http://example.org/message_1> <http://example.org/hasNumber> \"1\" .\n";
    private static final String NT_C =
            "<http://example.org/message_2> <http://example.org/hasValue> \"Goodbye, world!\" .\n";
    private static final String V = "VERSION \"1.2-messages\"\n";

    @Test
    void ntriplesSpecExamples() {
        assertEquals(List.of(2, 1), sizes(nt(V + NT_A + NT_B + "MESSAGE\n" + NT_C)));
        assertEquals(List.of(0, 2), sizes(nt(V + "MESSAGE\n" + NT_A + NT_B)));
        assertEquals(List.of(1), sizes(nt(V + NT_A + "MESSAGE\n")));
    }

    @Test
    void ntriplesMessageFollowedByCommentIsADelimiter() {
        // The spec's own example; the engine codec alone rejects it (#126).
        String log = V + NT_A + "MESSAGE\nMESSAGE\n"
                + "MESSAGE # last delimiter ignored, as it is not followed by statements or delimiters\n";
        assertEquals(List.of(1, 0, 0), sizes(nt(log)));
    }

    // --------------------------------------------------------- §2.1.2 examples, Turtle and TriG

    @Test
    void trigSpecExampleTwoMessages() {
        String log = """
                VERSION "1.2-messages"
                PREFIX ex: <http://example.org/>
                ex:message_1 {
                  ex:person ex:says "Hello, world!" ;
                }
                ex:message_1 ex:hasNumber 1 .
                MESSAGE
                ex:message_2 {
                  ex:person ex:says "Goodbye, world!" ;
                }
                ex:message_2 ex:hasNumber 2 .
                MESSAGE
                """;
        List<List<Statement>> messages = trig(log);
        assertEquals(List.of(2, 2), sizes(messages));
        // the named graph survives, and the prefix from message 1 is still in force in message 2
        Set<Resource> graphs = messages.get(1).stream().map(Statement::getContext)
                .filter(g -> g != null).collect(Collectors.toSet());
        assertEquals(Set.of(iri("http://example.org/message_2")), graphs);
    }

    @Test
    void turtleOldStyleDirectives() {
        String log = """
                @version "1.2-messages" .
                @prefix ex: <http://example.org/> .
                ex:person ex:says "Hello, world!" .
                @message .
                ex:person ex:says "Goodbye, world!" .
                """;
        List<List<Statement>> messages = ttl(log);
        assertEquals(List.of(1, 1), sizes(messages));
        assertEquals("Goodbye, world!", messages.get(1).get(0).getObject().stringValue());
    }

    @Test
    void repeatedPrefixOverridesFromThatPointOn() {
        // §2.1.3's example
        String log = """
                VERSION "1.2-messages"
                PREFIX ex: <http://example.org/>
                ex:person ex:says "Hello, world!" .
                MESSAGE
                PREFIX ex: <http://different-iri.org/>
                ex:person ex:says "Goodbye, world!" .
                """;
        List<List<Statement>> messages = ttl(log);
        assertEquals(iri("http://example.org/person"), messages.get(0).get(0).getSubject());
        assertEquals(iri("http://different-iri.org/person"), messages.get(1).get(0).getSubject());
    }

    @Test
    void emptyFirstMessageAndTrailingDelimiters() {
        assertEquals(List.of(0, 1), sizes(ttl(V + "MESSAGE\n<http://e/s> <http://e/p> 1 .\n")));
        assertEquals(List.of(1), sizes(ttl(V + "<http://e/s> <http://e/p> 1 .\nMESSAGE\n")));
        assertEquals(List.of(1, 0, 0), sizes(ttl(V + "<http://e/s> <http://e/p> 1 .\nMESSAGE\nMESSAGE\nMESSAGE\n")));
        assertEquals(List.of(), sizes(ttl(V)));
    }

    @Test
    void messageWithCommentOnTheSameLine() {
        assertEquals(List.of(1, 1), sizes(ttl(V + "<http://e/s> <http://e/p> 1 .\nMESSAGE # next one\n"
                + "<http://e/s> <http://e/p> 2 .\n")));
    }

    // ---------------------------------------------------------------- blank-node scoping (§1.6)

    @Test
    void blankNodeLabelsAreScopedToTheirMessage() {
        String log = """
                VERSION "1.2-messages"
                PREFIX ex: <http://example.org/>
                ex:obs1 ex:result _:r . _:r ex:value 21.5 .
                MESSAGE
                ex:obs2 ex:result _:r . _:r ex:value 23.0 .
                """;
        List<List<Statement>> messages = ttl(log);
        Resource r1 = (Resource) messages.get(0).stream()
                .filter(st -> st.getPredicate().stringValue().endsWith("result")).findFirst().orElseThrow().getObject();
        Resource r2 = (Resource) messages.get(1).stream()
                .filter(st -> st.getPredicate().stringValue().endsWith("result")).findFirst().orElseThrow().getObject();
        assertNotEquals(r1, r2);
    }

    // ------------------------------------------------- the splitter must not cut in these places

    @Test
    void messageInsideLiteralsIrisCommentsAndNamesIsNotADelimiter() {
        String log = """
                VERSION "1.2-messages"
                PREFIX ex: <http://example.org/>
                # MESSAGE in a comment
                ex:a ex:p "one line MESSAGE" .
                ex:a ex:p \"""a long
                MESSAGE
                string\""" .
                ex:a ex:p '''MESSAGE''' .
                ex:a ex:p <http://example.org/iri/MESSAGE> .
                ex:a ex:p ex:MESSAGE .
                ex:a ex:p ex:x.MESSAGE .
                ex:a ex:p 0.5 .
                ex:a ex:p "it's \\" MESSAGE" .
                """;
        assertEquals(List.of(8), sizes(ttl(log)));
    }

    @Test
    void messageInsideABlankNodePropertyListIsNotADelimiterPosition() {
        // a delimiter can only stand where a statement could start; inside [ ] it is a syntax error
        // in the message, not a cut
        String log = V + "<http://e/s> <http://e/p> [ <http://e/q> 1 ] .\nMESSAGE\n<http://e/s> <http://e/p> 2 .\n";
        assertEquals(List.of(1 + 1, 1), sizes(ttl(log)));
    }

    // ---------------------------------------------------------------------------- strictness

    @Test
    void delimiterWithoutAMessagesLabelIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ttl("<http://e/s> <http://e/p> 1 .\nMESSAGE\n<http://e/s> <http://e/p> 2 .\n"));
        assertThrows(IllegalArgumentException.class,
                () -> ttl("VERSION \"1.2\"\n<http://e/s> <http://e/p> 1 .\nMESSAGE\n<http://e/s> <http://e/p> 2 .\n"));
    }

    @Test
    void plainTurtleWithoutDelimitersIsOneMessage() {
        assertEquals(List.of(2), sizes(ttl("<http://e/s> <http://e/p> 1, 2 .\n")));
    }

    @Test
    void allThreeMessagesLabelsAreAccepted() {
        for (String label : List.of("1.1-messages", "1.2-basic-messages", "1.2-messages")) {
            assertEquals(List.of(1, 1), sizes(ttl("VERSION \"" + label + "\"\n<http://e/s> <http://e/p> 1 .\n"
                    + "MESSAGE\n<http://e/s> <http://e/p> 2 .\n")), label);
        }
    }

    // ------------------------------------------------------------------------------- writing

    @Test
    void writersRoundTripEmptyMessagesAnywhere() {
        List<Statement> a = List.of(VF.createStatement(iri("http://e/s"), iri("http://e/p"), VF.createLiteral(1)));
        List<List<List<Statement>>> cases = List.of(
                List.of(a, List.of()), List.of(List.of()), List.of(List.of(), List.of()),
                List.of(List.of(), a), List.of(a, List.of(), a), List.of());
        for (List<List<Statement>> messages : cases) {
            List<Integer> expected = sizes(messages);
            assertEquals(expected, sizes(nt(RdfMessageLog.writeNQuads(messages))), "N-Quads " + expected);
            assertEquals(expected, sizes(ttl(RdfMessageLog.writeTurtle(messages, Map.of("e", "http://e/")))),
                    "Turtle " + expected);
        }
    }

    @Test
    void turtleWriterKeepsBlankNodesApartAcrossMessages() {
        var b = VF.createBNode("x");
        List<List<Statement>> messages = List.of(
                List.of(VF.createStatement(b, iri("http://e/p"), VF.createLiteral(1))),
                List.of(VF.createStatement(b, iri("http://e/p"), VF.createLiteral(2))));
        List<List<Statement>> back = ttl(RdfMessageLog.writeTurtle(messages, Map.of()));
        assertNotEquals(back.get(0).get(0).getSubject(), back.get(1).get(0).getSubject());
        assertTrue(back.get(0).get(0).getSubject().isBNode());
    }
}
