package org.cqels.playground.messages;

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

    // ------------------------------------------------------ co-review round 1: token boundaries

    /** Our parse of one message must equal RDF4J's parse of the intended fragment. */
    private static void sameAsRio(String prologue, String fragment, List<Statement> parsed) throws Exception {
        org.eclipse.rdf4j.model.Model expected = org.eclipse.rdf4j.rio.Rio.parse(
                new java.io.StringReader(prologue + fragment), BASE, RDFFormat.TRIG);
        assertTrue(org.eclipse.rdf4j.model.util.Models.isomorphic(expected,
                new org.eclipse.rdf4j.model.impl.LinkedHashModel(parsed)), fragment + " -> " + parsed);
    }

    @Test
    void namesThatContainMessageAreOneToken() throws Exception {
        String prologue = "PREFIX ex: <http://example.org/>\nPREFIX MESSAGE.foo: <http://example.org/m/>\n";
        for (String fragment : List.of(
                "ex:a ex:p ex:x..MESSAGE .\n",
                "ex:a ex:p ex:x\\.MESSAGE .\n",
                "MESSAGE.foo:s ex:p 1 .\n",
                "ex:a ex:p ex:x\\#MESSAGE .\n",
                "ex:a ex:p ex:x\\(MESSAGE .\n",
                "ex:a ex:p ex:x:.MESSAGE .\n",
                "ex:a ex:p ex:x:y..MESSAGE .\n")) {
            List<List<Statement>> messages = ttl(V + prologue + fragment);
            assertEquals(List.of(1), sizes(messages), fragment);
            sameAsRio(prologue, fragment, messages.get(0));
        }
    }

    @Test
    void anIntegerThenADotThenMessageCuts() {
        assertEquals(List.of(1, 1), sizes(ttl(V + "<http://e/s> <http://e/p> 1.MESSAGE\n<http://e/s> <http://e/p> 2 .\n")));
        // numbers whose dots are not terminators
        assertEquals(List.of(4), sizes(ttl(V + "<http://e/s> <http://e/p> 1.5, .5, -1.5e3, +2 .\n")));
    }

    @Test
    void commentsInsideDirectives() {
        assertEquals(List.of(1), sizes(ttl(V + "PREFIX ex: #c\n<http://e/>\nex:s ex:p 1 .\n")));
        // the @prefix is carried into message 2 with its '.' even though a comment split it
        List<List<Statement>> carried = ttl(V + "@prefix ex: <http://e/> #c\n.\nex:s ex:p 1 .\nMESSAGE\nex:s ex:p 2 .\n");
        assertEquals(List.of(1, 1), sizes(carried));
        assertEquals(iri("http://e/s"), carried.get(1).get(0).getSubject());
        assertEquals(List.of(1, 1), sizes(ttl(V + "<http://e/s> <http://e/p> 1 .\n@message #c\n.\n<http://e/s> <http://e/p> 2 .\n")));
        assertEquals(List.of(1, 1), sizes(ttl("@version #c\n\"1.2-messages\" #c\n.\n<http://e/s> <http://e/p> 1 .\n"
                + "MESSAGE\n<http://e/s> <http://e/p> 2 .\n")));
    }

    @Test
    void versionLabelsApplyFromWhereTheyStand() {
        String a = "<http://e/s> <http://e/p> 1 .\n";
        // a later VERSION "1.2" does not invalidate the delimiter before it
        assertEquals(List.of(1, 2), sizes(ttl(V + a + "MESSAGE\n" + a.replace("1 .", "2 .") + "VERSION \"1.2\"\n" + a)));
        // but a delimiter after it is refused
        assertThrows(IllegalArgumentException.class,
                () -> ttl(V + a + "VERSION \"1.2\"\nMESSAGE\n" + a));
        // labels are decoded
        assertEquals(List.of(1, 1), sizes(ttl("VERSION \"1.2\\u002Dmessages\"\n" + a + "MESSAGE\n" + a)));
    }

    @Test
    void unterminatedTokensAreErrors() {
        assertThrows(IllegalArgumentException.class, () -> ttl("VERSION \"1.2-messages\n<http://e/s> <http://e/p> 1 .\n"));
        assertThrows(IllegalArgumentException.class, () -> ttl(V + "<http://e/s> <http://e/p> \"open .\n"));
        assertThrows(IllegalArgumentException.class, () -> ttl(V + "<http://e/s> <http://e/p> <http://e/o .\n"));
    }

    @Test
    void trigGraphKeywordAndDefaultGraphBlock() {
        String log = V + "PREFIX ex: <http://e/>\nGRAPH ex:g { ex:s ex:p 1 }\n{ ex:s ex:p 2 }\nMESSAGE\nex:g2 { ex:s ex:p 3 }\n";
        List<List<Statement>> messages = trig(log);
        assertEquals(List.of(2, 1), sizes(messages));
        assertEquals(iri("http://e/g2"), messages.get(1).get(0).getContext());
    }

    // --------------------------------------------- co-review round 1: N-Triples/N-Quads comments

    @Test
    void ntriplesCommentFormsAroundDelimiters() {
        assertEquals(List.of(1, 1), sizes(nt(V + NT_A + "MESSAGE#c\n" + NT_C)));
        assertEquals(List.of(1), sizes(nt(V + NT_A + "MESSAGE\n# tail\n")));
        assertEquals(List.of(), sizes(nt(V + "# only a comment\n")));
        assertEquals(List.of(1, 1), sizes(RdfMessageLog.parse(V + NT_A + "MESSAGE # c\n" + NT_C, RDFFormat.NQUADS, BASE)));
    }

    // ------------------------------------------------------------- co-review round 1: writers

    @Test
    void turtleWriterRefusesNamedGraphs() {
        Statement inGraph = VF.createStatement(iri("http://e/s"), iri("http://e/p"), VF.createLiteral(1), iri("http://e/g"));
        assertThrows(IllegalArgumentException.class, () -> RdfMessageLog.writeTurtle(List.of(List.of(inGraph)), Map.of()));
        // N-Quads keeps it
        assertEquals(iri("http://e/g"), nt(RdfMessageLog.writeNQuads(List.of(List.of(inGraph)))).get(0).get(0).getContext());
    }

    @Test
    void turtleWriterOutputWithMessageLikeNamesRereads() {
        List<List<Statement>> messages = List.of(
                List.of(VF.createStatement(iri("http://e/x..MESSAGE"), iri("http://e/p"), VF.createLiteral(1))),
                List.of(VF.createStatement(iri("http://e/x...MESSAGE"), iri("http://e/p"), VF.createLiteral(2))),
                List.of(VF.createStatement(iri("http://e/x:.MESSAGE"), iri("http://e/p"), VF.createLiteral(3))));
        List<List<Statement>> back = ttl(RdfMessageLog.writeTurtle(messages, Map.of("e", "http://e/")));
        assertEquals(List.of(1, 1, 1), sizes(back));
        assertEquals(iri("http://e/x:.MESSAGE"), back.get(2).get(0).getSubject());
        assertEquals(iri("http://e/x..MESSAGE"), back.get(0).get(0).getSubject());
        assertEquals(iri("http://e/x...MESSAGE"), back.get(1).get(0).getSubject());
    }

    // ------------------------------------------------------ co-review round 2: dots after tokens

    @Test
    void aTerminatingDotRightAfterATokenIsStillATerminator() throws Exception {
        String prologue = "PREFIX ex: <http://e/>\nPREFIX : <http://e/empty#>\n";
        for (String object : List.of("true", "\"x\"@en", ":", "ex:", "\"x\"^^ex:", "1.e2", "-1.e-2")) {
            for (String delimiter : List.of("@message .", "MESSAGE")) {
                String first = "ex:s ex:p " + object + ".";
                String log = V + prologue + first + delimiter + "\nex:s ex:p 2 .\n";
                List<List<Statement>> messages = ttl(log);
                assertEquals(List.of(1, 1), sizes(messages), log);
                sameAsRio(prologue, first + "\n", messages.get(0));
            }
        }
    }

    @Test
    void aBaseDirectionRightBeforeADotIsRefusedNotSwallowed() {
        String log = V + "PREFIX ex: <http://e/>\nex:s ex:p \"x\"@en--ltr.MESSAGE\nex:s ex:p 2 .\n";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ttl(log));
        assertTrue(e.getMessage().contains("#133"), e.getMessage());
    }

    @Test
    void aPrefixRightAfterATokenIsCarriedForward() {
        for (String object : List.of("\"x\"@en", "ex:", "1.e2", "true")) {
            String log = V + "PREFIX ex: <http://e/>\nex:s ex:p " + object + ".PREFIX ex: <http://changed/>\n"
                    + "MESSAGE\nex:t ex:p 2 .\n";
            List<List<Statement>> messages = ttl(log);
            assertEquals(iri("http://changed/t"), messages.get(1).get(0).getSubject(), object);
        }
    }

    @Test
    void nonAsciiPrefixesAndNamesAreNames() throws Exception {
        for (String prefix : List.of("١", "१", "１", "١a", "١e2")) {
            String prologue = "PREFIX " + prefix + ": <http://unicode/>\n";
            String fragment = prefix + ":s " + prefix + ":p 1 .\n";
            List<List<Statement>> messages = ttl(V + prologue + fragment + "MESSAGE\n" + fragment);
            assertEquals(List.of(1, 1), sizes(messages), prefix);
            sameAsRio(prologue, fragment, messages.get(1));
        }
    }

    @Test
    void malformedVersionLabelsAreRejected() {
        String a = "<http://e/s> <http://e/p> 1 .\nMESSAGE\n<http://e/s> <http://e/p> 2 .\n";
        for (String label : List.of("\"1.2\\-messages\"", "\"1.2\\u+02Dmessages\"", "\"1.2\\U+000002Dmessages\"",
                "\"1.2\\uD800-messages\"", "\"\"\"1.2-messages\"\"\"")) {
            assertThrows(IllegalArgumentException.class, () -> ttl("VERSION " + label + "\n" + a), label);
        }
        assertEquals(List.of(1, 1), sizes(ttl("VERSION '1.2-messages'\n" + a)));
    }

    @Test
    void rdf12SyntaxThatRdf4jWouldMisreadIsRefused() {
        // #133: RDF4J 4.3.8 cannot parse <<( )>> and reads << >>, {| |} and @ar--rtl as other data
        String prologue = "PREFIX ex: <http://e/>\n";
        for (String fragment : List.of(
                "ex:s ex:p <<( ex:a ex:b ex:c )>> .\n",
                "<< ex:a ex:b ex:c >> ex:p ex:o .\n",
                "ex:a ex:b ex:c {| ex:p ex:o |} .\n",
                "ex:s ex:p \"x\"@ar--rtl .\n")) {
            for (RDFFormat format : List.of(RDFFormat.TURTLE, RDFFormat.TRIG)) {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                        () -> RdfMessageLog.parse(V + prologue + fragment, format, BASE), fragment);
                assertTrue(e.getMessage().contains("#133"), e.getMessage());
            }
        }
        // the same characters inside a string, an IRI or a comment are not syntax
        String quiet = "ex:s ex:p \"<<( {| x\"@ar , <http://e/a?b=c> . # << {| @ar--rtl\n";
        assertEquals(List.of(2), sizes(ttl(V + prologue + quiet)));
        assertEquals(List.of(1), sizes(ttl(V + prologue + "ex:s ex:p \"x\"@ar-Latn-x-y .\n")));
    }
}
