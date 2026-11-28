package org.cqels.playground.probe;

import org.cqels.engine.CQELSEngine;
import org.cqels.stream.codec.RdfMessageCodec;
import org.eclipse.rdf4j.model.Statement;

import java.util.List;

import static org.cqels.playground.probe.ProbeSupport.*;

/**
 * RDF Messages: the engine's {@code RdfMessageCodec} and Turtle parser, as the readers in
 * {@code org.cqels.playground.messages.io} and {@code RDF_MESSAGES_MAPPING.md} describe them.
 *
 * <p>{@link CapabilityProbe} runs these checks and names the documentation each one guards.
 */
final class MessageProbes {

    private static final String MSG_V = "VERSION \"1.2-messages\"\n";
    private static final String MSG_T = "<https://example.org/probe#s> <https://example.org/probe#p> \"1\" .\n";

    private MessageProbes() {
    }

    /**
     * #126: "rejected" when the codec fails on two triples split by {@code MESSAGE # c} with the
     * documented parse error, else how many messages it read. The same document without the comment
     * is the control: it must read as 2 messages, or the verdict means nothing.
     */
    static String codecMessageWithComment() {
        try {
            int control = RdfMessageCodec.parseNQuads(MSG_V + MSG_T + "MESSAGE\n" + MSG_T).size();
            if (control != 2) {
                return "control read " + control + " messages, not 2";
            }
        } catch (RuntimeException e) {
            return "control failed: " + e;
        }
        try {
            return RdfMessageCodec.parseNQuads(MSG_V + MSG_T + "MESSAGE # c\n" + MSG_T).size() + " messages";
        } catch (org.eclipse.rdf4j.rio.RDFParseException e) {
            return String.valueOf(e.getMessage()).contains("found: M") ? "rejected" : "other parse error: " + e.getMessage();
        } catch (RuntimeException e) {
            return "unexpected " + e;
        }
    }

    /** #126: the message sizes the codec reads from one triple, MESSAGE, then a comment-only line. */
    static String codecCommentAfterLastDelimiter() {
        return RdfMessageCodec.parseNQuads(MSG_V + MSG_T + "MESSAGE\n# tail\n").stream()
                .map(List::size).toList().toString();
    }

    /** #126: the message sizes read back after writing [one triple, empty]. */
    static String codecTrailingEmptyMessage() {
        Statement t = VF.createStatement(iri("s"), iri("p"), VF.createLiteral("1"));
        List<List<Statement>> back = RdfMessageCodec.parseNQuads(
                RdfMessageCodec.writeNQuads(List.of(List.of(t), List.of())));
        return back.stream().map(List::size).toList().toString();
    }

    /**
     * #133: "rejected" when the codec fails on a triple term with a parse error, "1 triple term" when
     * it reads one message whose object is a triple term. The same document with an IRI object is
     * the control.
     */
    static String codecTripleTerm() {
        String head = MSG_V + "<https://example.org/probe#s> <https://example.org/probe#p> ";
        try {
            if (RdfMessageCodec.parseNQuads(head + "<https://example.org/probe#o> .\n").size() != 1) {
                return "control did not read 1 message";
            }
        } catch (RuntimeException e) {
            return "control failed: " + e;
        }
        try {
            List<List<Statement>> m = RdfMessageCodec.parseNQuads(head
                    + "<<( <https://example.org/probe#a> <https://example.org/probe#b> <https://example.org/probe#c> )>> .\n");
            return m.size() == 1 && m.get(0).size() == 1 && m.get(0).get(0).getObject() instanceof org.eclipse.rdf4j.model.Triple
                    ? "1 triple term" : "read as " + m;
        } catch (org.eclipse.rdf4j.rio.RDFParseException e) {
            return "rejected";
        } catch (RuntimeException e) {
            return "unexpected " + e;
        }
    }

    /**
     * #133: how the engine's RDF4J Turtle parser reads {@code << ex:a ex:b ex:c >> ex:p ex:o}:
     * "quoted triple" when the subject is the triple itself (RDF-star), "reifier" when the subject is
     * a node that rdf:reifies the triple (RDF 1.2).
     */
    static String turtleReifiedTriple() {
        try {
            org.eclipse.rdf4j.model.Model m = org.eclipse.rdf4j.rio.Rio.parse(new java.io.StringReader(
                    "PREFIX ex: <https://example.org/probe#>\n<< ex:a ex:b ex:c >> ex:p ex:o .\n"),
                    "", org.eclipse.rdf4j.rio.RDFFormat.TURTLE);
            boolean reifies = m.stream().anyMatch(st -> st.getPredicate().stringValue()
                    .equals("http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies"));
            boolean quoted = m.size() == 1 && m.iterator().next().getSubject() instanceof org.eclipse.rdf4j.model.Triple;
            return reifies ? "reifier" : quoted ? "quoted triple" : "read as " + m;
        } catch (Exception e) {
            return "unexpected " + e;
        }
    }

    /** #125: "refused" when GRAPH inside STREAM fails to register for that reason, else "registered". */
    static String graphInsideStream() throws Exception {
        try (CQELSEngine engine = CQELSEngine.builder().id("probe-" + System.nanoTime()).withMemoryStore().build()) {
            engine.createStream("S");
            try {
                engine.registerCqelsQuery(withRegister("""
                        SELECT ?g ?o FROM STREAM S [NOW]
                        WHERE { STREAM S { GRAPH ?g { ?s <%sp> ?o } } }
                        """.formatted(C)), r -> { });
                return "registered";
            } catch (Exception e) {
                String m = String.valueOf(e.getMessage());
                return m.contains("GRAPH") ? "refused" : "refused for another reason: " + m;
            }
        }
    }

    /**
     * What the messages demo relies on from the engine codec: an empty message, blank nodes scoped
     * per message, and a quad's graph name kept.
     */
    static boolean codecMessageSemantics() {
        boolean empty = RdfMessageCodec.parseNQuads(MSG_V + "MESSAGE\n" + MSG_T).stream()
                .map(List::size).toList().equals(List.of(0, 1));
        List<List<Statement>> bn = RdfMessageCodec.parseNQuads(MSG_V
                + "_:x <https://example.org/probe#p> \"1\" .\nMESSAGE\n_:x <https://example.org/probe#p> \"2\" .\n");
        boolean scoped = bn.size() == 2 && !bn.get(0).get(0).getSubject().equals(bn.get(1).get(0).getSubject());
        List<List<Statement>> q = RdfMessageCodec.parseNQuads(MSG_V + "<https://example.org/probe#s> "
                + "<https://example.org/probe#p> <https://example.org/probe#o> <https://example.org/probe#g> .\n");
        boolean graph = iri("g").equals(q.get(0).get(0).getContext());
        return empty && scoped && graph;
    }
}
