package org.cqels.examples.messages;

import static org.cqels.examples.messages.RdfMessagesTestSuiteTest.assertMessages;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.Test;

/** {@link RdfXmlMessageLog} (§2.4) beyond the companion tests. */
class RdfXmlMessageLogTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final String BASE = "http://example.org/base/";
    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<rdfm:MessageLog xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\""
            + " xmlns:rdfm=\"https://w3c-cg.github.io/rsp/spec/messages#\" xmlns:ex=\"http://example.org/\"";
    private static final String END = "</rdfm:MessageLog>\n";

    private static List<List<Statement>> xml(String attributes, String body) {
        return RdfXmlMessageLog.parse(HEAD + attributes + ">\n" + body + END, BASE);
    }

    private static String message(String inner) {
        return "<rdf:RDF>" + inner + "</rdf:RDF>\n";
    }

    private static final String ONE =
            message("<rdf:Description rdf:about=\"http://example.org/s\"><ex:p>v</ex:p></rdf:Description>");

    @Test
    void theDraftsExampleScopesNodeIdsPerMessage() {
        String inner = "<rdf:Description rdf:nodeID=\"b3\"><rdf:type rdf:resource=\"http://schema.org/Product\"/>"
                + "<ex:name>x</ex:name></rdf:Description>";
        List<List<Statement>> messages = xml("", message(inner) + message(inner));
        assertEquals(2, messages.size());
        assertTrue(messages.get(0).get(0).getSubject() instanceof BNode);
        assertNotEquals(messages.get(0).get(0).getSubject(), messages.get(1).get(0).getSubject());
    }

    @Test
    void versionLabel() {
        assertEquals(1, xml(" rdf:version=\"1.2-messages\"", ONE).size());
        assertEquals(1, xml(" rdf:version=\"1.1-messages\"", ONE).size());
        assertThrows(IllegalArgumentException.class, () -> xml(" rdf:version=\"1.2\"", ONE));
    }

    @Test
    void inheritanceAndOverrides() {
        // a relative xml:base on a message resolves against the log's
        List<Statement> m = xml(" xml:base=\"http://example.org/one/\"",
                "<rdf:RDF xml:base=\"two/\"><rdf:Description rdf:about=\"s\"><ex:p>v</ex:p></rdf:Description></rdf:RDF>")
                .get(0);
        assertEquals("http://example.org/one/two/s", m.get(0).getSubject().stringValue());
        // xml:lang="" on a message cancels the log's language
        m = xml(" xml:lang=\"en\"", "<rdf:RDF xml:lang=\"\"><rdf:Description rdf:about=\"http://example.org/s\">"
                + "<ex:p>v</ex:p></rdf:Description></rdf:RDF>").get(0);
        assertTrue(((Literal) m.get(0).getObject()).getLanguage().isEmpty(), m.toString());
        // with no xml:base anywhere, the caller's base applies
        m = xml("", message("<rdf:Description rdf:about=\"s\"><ex:p>v</ex:p></rdf:Description>")).get(0);
        assertEquals(BASE + "s", m.get(0).getSubject().stringValue());
        // a message written with the RDF namespace as default namespace
        m = xml("", "<RDF xmlns=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><Description about=\"http://example.org/s\""
                + " xmlns:q=\"http://example.org/q#\"><q:p>v</q:p></Description></RDF>").get(0);
        assertEquals("http://example.org/q#p", m.get(0).getPredicate().stringValue());
        // an XML literal using a namespace declared only on the log
        m = xml(" xmlns:h=\"http://www.w3.org/1999/xhtml\"", message("<rdf:Description rdf:about=\"http://example.org/s\">"
                + "<ex:p rdf:parseType=\"Literal\"><h:b>bold</h:b></ex:p></rdf:Description>")).get(0);
        assertTrue(m.get(0).getObject().stringValue().contains("http://www.w3.org/1999/xhtml"), m.toString());
    }

    @Test
    void refusals() {
        for (String body : List.of(
                ONE + "<![CDATA[text]]>",                                                  // text between messages
                message("<rdf:Description rdf:about=\"http://example.org/s\"><ex:p rdf:parseType=\"Triple\">"
                        + "<rdf:Description rdf:about=\"http://example.org/a\"><ex:b rdf:resource=\"http://example.org/c\"/>"
                        + "</rdf:Description></ex:p></rdf:Description>"),
                message("<rdf:Description rdf:about=\"http://example.org/s\"><ex:p rdf:annotation=\"http://example.org/r\""
                        + " rdf:resource=\"http://example.org/o\"/></rdf:Description>"),
                message("<rdf:Description rdf:about=\"http://example.org/s\"><ex:p rdf:annotationNodeID=\"r\""
                        + " rdf:resource=\"http://example.org/o\"/></rdf:Description>"),
                "<rdf:RDF>",                                                              // not closed
                message("<rdf:Description rdf:about=\"http://example.org/s\"><ex:p>v</ex:q></rdf:Description>"))) {
            assertThrows(IllegalArgumentException.class, () -> xml("", body), body);
        }
        assertThrows(IllegalArgumentException.class, () -> RdfXmlMessageLog.parse(
                "<?xml version=\"1.0\"?>\n<!DOCTYPE rdfm:MessageLog [<!ENTITY e \"x\">]>\n" + HEAD.substring(HEAD.indexOf('\n') + 1)
                        + ">\n" + ONE + END, BASE));
        assertThrows(IllegalArgumentException.class, () -> RdfXmlMessageLog.parse("", BASE));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> xml("", message(
                "<rdf:Description rdf:about=\"http://example.org/s\"><ex:p rdf:annotation=\"http://example.org/r\""
                        + " rdf:resource=\"http://example.org/o\"/></rdf:Description>")));
        assertTrue(e.getMessage().contains("#133"), e.getMessage());
    }

    @Test
    void writerRoundTrips() {
        BNode b = VF.createBNode();
        List<List<Statement>> messages = List.of(
                List.of(),
                List.of(VF.createStatement(b, VF.createIRI("http://example.org/p"), VF.createLiteral("x <&> y", "en")),
                        VF.createStatement(b, VF.createIRI("http://example.org/q"), VF.createLiteral("1.5", XSD.DECIMAL))),
                List.of(VF.createStatement(VF.createIRI("http://example.org/s"), VF.createIRI("http://example.org/p"), b)),
                List.of());
        String log = RdfXmlMessageLog.write(messages, Map.of("ex", "http://example.org/"));
        assertTrue(log.contains("rdf:version=\"1.2-messages\""), log);
        assertMessages(messages, RdfXmlMessageLog.parse(log, BASE));
        assertThrows(IllegalArgumentException.class, () -> RdfXmlMessageLog.write(List.of(List.of(VF.createStatement(
                b, VF.createIRI("http://example.org/p"), b, VF.createIRI("http://example.org/g")))), Map.of()));
    }

    // ------------------------------------------------------------------ co-review round 5

    /** The same element parsed as a standalone RDF/XML document by RDF4J. */
    private static org.eclipse.rdf4j.model.Model direct(String inner) throws Exception {
        return org.eclipse.rdf4j.rio.Rio.parse(new java.io.StringReader("<?xml version=\"1.0\"?>\n"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" xmlns:ex=\"http://example.org/\">"
                + inner + "</rdf:RDF>"), BASE, org.eclipse.rdf4j.rio.RDFFormat.RDFXML);
    }

    @Test
    void characterReferencesSurviveTheCopy() throws Exception {
        for (String inner : List.of(
                "<rdf:Description rdf:about=\"http://example.org/s\"><ex:p>a&#13;b&#13;&#10;c</ex:p></rdf:Description>",
                "<rdf:Description rdf:about=\"http://example.org/s\" ex:p=\"a&#9;b&#10;c&#13;d &amp; &lt;&quot;\"/>",
                "<rdf:Description rdf:about=\"http://example.org/s\"><ex:p><![CDATA[a<b & c]]></ex:p></rdf:Description>")) {
            assertMessages(List.of(new java.util.ArrayList<>(direct(inner))), xml("", message(inner)));
        }
        Statement cr = VF.createStatement(VF.createIRI("http://example.org/s"), VF.createIRI("http://example.org/p"),
                VF.createLiteral("a\rb\tc\n"));
        assertMessages(List.of(List.of(cr)), RdfXmlMessageLog.parse(RdfXmlMessageLog.write(List.of(List.of(cr)), Map.of()), BASE));
    }

    @Test
    void anXmlLiteralMayHoldWhatTheReaderRefusesElsewhere() throws Exception {
        for (String content : List.of("<ex:q rdf:annotation=\"http://example.org/r\"/>",
                "<ex:q rdf:parseType=\"Triple\"/>", "<ex:q rdf:annotationNodeID=\"r\">x</ex:q>")) {
            String inner = "<rdf:Description rdf:about=\"http://example.org/s\"><ex:p rdf:parseType=\"Literal\">"
                    + content + "</ex:p><ex:after rdf:resource=\"http://example.org/o\"/></rdf:Description>";
            assertMessages(List.of(new java.util.ArrayList<>(direct(inner))), xml("", message(inner)));
        }
        // after the literal ends, the checks apply again
        assertThrows(IllegalArgumentException.class, () -> xml("", message("<rdf:Description rdf:about=\"http://example.org/s\">"
                + "<ex:p rdf:parseType=\"Literal\"><b/></ex:p><ex:q rdf:annotation=\"http://example.org/r\""
                + " rdf:resource=\"http://example.org/o\"/></rdf:Description>")));
    }
}
