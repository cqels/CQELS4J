package org.cqels.examples.messages;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.Characters;
import javax.xml.stream.events.Namespace;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import org.eclipse.rdf4j.common.net.ParsedIRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;

/**
 * Reads and writes RDF Message Logs in <strong>RDF/XML</strong>, as defined by §2.4 of the W3C RSP
 * CG <a href="https://w3c-cg.github.io/rsp/spec/messages">RDF Messages</a> draft (22 October
 * 2026): an {@code <rdfm:MessageLog>} root element holding zero or more {@code <rdf:RDF>}
 * elements, one per message. Each message comes back as one {@code List<Statement>}, one atomic
 * CQELS stream element.
 *
 * <p>The log is read with StAX, one {@code <rdf:RDF>} element at a time, as the draft suggests.
 * Each element is copied out as a standalone RDF/XML document and parsed by RDF4J 4.3.8's own
 * RDF/XML parser, which gives:
 * <ul>
 *   <li>blank-node identifiers scoped to their message (§2.4.1): the same {@code rdf:nodeID} in
 *       two messages gives two blank nodes, and within one message the same one;</li>
 *   <li>namespace declarations, {@code xml:base} and {@code xml:lang} inherited from
 *       {@code <rdfm:MessageLog>} unless the message overrides them (§2.4.2). A relative
 *       {@code xml:base} is resolved against the one in force.</li>
 * </ul>
 *
 * <p>Refused, as §2.4 requires: a root element other than {@code <rdfm:MessageLog>}, any child of
 * it other than {@code <rdf:RDF>}, and non-whitespace text between messages. Comments and
 * processing instructions between messages are ignored. After {@code </rdfm:MessageLog>} only
 * comments, processing instructions and whitespace may follow. Messages are copied out as XML 1.0,
 * so a character only XML 1.1 allows is refused.
 *
 * <p>Also refused:
 * <ul>
 *   <li>a {@code rdf:version} label on {@code <rdfm:MessageLog>} without the {@code -messages}
 *       suffix (§1.7), as the Turtle reader refuses one;</li>
 *   <li>a document type declaration: DTDs and external entities are not processed;</li>
 *   <li>RDF 1.2 syntax that RDF4J 4.3.8 would read as different data:
 *       {@code rdf:parseType="Triple"} (read as an XML literal), {@code rdf:annotation} and
 *       {@code rdf:annotationNodeID} (read as an extra triple), all tracked in
 *       <a href="https://github.com/cqels/CQELS4J/issues/133">#133</a>.</li>
 * </ul>
 *
 * <p>The writer reads each message back and refuses it unless it is the same: RDF/XML cannot hold
 * every graph (characters XML 1.0 forbids, an ill-formed {@code rdf:XMLLiteral}, {@code rdf:li},
 * which reads back as {@code rdf:_1}, a predicate with no XML name), and RDF4J writes some of these
 * anyway.
 *
 * <p>Note that each message is handed to a fresh RDF4J parser; the log itself is read once, as
 * one continuous stream, which is what the companion tests ask for.
 */
public final class RdfXmlMessageLog {

    private RdfXmlMessageLog() {
    }

    /** The namespace of the {@code rdfm} prefix (§2.4). */
    public static final String RDFM = "https://w3c-cg.github.io/rsp/spec/messages#";

    /** The media type §2.4 proposes for an RDF/XML RDF Message Log. */
    public static final String MEDIA_TYPE = "application/x-rdfm+xml";

    private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final QName MESSAGE_LOG = new QName(RDFM, "MessageLog");
    private static final QName RDF_RDF = new QName(RDF, "RDF");
    private static final QName RDF_VERSION = new QName(RDF, "version");
    private static final QName RDF_PARSE_TYPE = new QName(RDF, "parseType");
    private static final QName RDF_ANNOTATION = new QName(RDF, "annotation");
    private static final QName RDF_ANNOTATION_NODE_ID = new QName(RDF, "annotationNodeID");
    private static final QName XML_BASE = new QName(XMLConstants.XML_NS_URI, "base");
    private static final QName XML_LANG = new QName(XMLConstants.XML_NS_URI, "lang");
    private static final Pattern XML_DECLARATION = Pattern.compile("^\\s*<\\?xml[^?]*\\?>\\s*");

    private static final XMLEventFactory EVENTS = XMLEventFactory.newInstance();

    // ------------------------------------------------------------------------------------ reading

    /**
     * Parse an RDF/XML RDF Message Log.
     *
     * @param log     the whole log
     * @param baseIri base IRI for relative IRIs, overridden by {@code xml:base}
     * @return one statement list per message, in order; an empty message is an empty list
     * @throws IllegalArgumentException if the log is malformed
     */
    public static List<List<Statement>> parse(String log, String baseIri) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.IS_COALESCING, false);
        try {
            XMLEventReader reader = factory.createXMLEventReader(new StringReader(log));
            return read(reader, baseIri);
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("not well-formed XML: " + e.getMessage(), e);
        }
    }

    private static List<List<Statement>> read(XMLEventReader reader, String baseIri) throws XMLStreamException {
        StartElement root = null;
        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.getEventType() == XMLEvent.DTD) {
                throw new IllegalArgumentException("a document type declaration is not supported");
            }
            if (event.isStartElement()) {
                root = event.asStartElement();
                break;
            }
        }
        if (root == null || !root.getName().equals(MESSAGE_LOG)) {
            throw new IllegalArgumentException("the root element must be rdfm:MessageLog ({" + RDFM
                    + "}MessageLog), found " + (root == null ? "none" : root.getName()));
        }
        Attribute version = root.getAttributeByName(RDF_VERSION);
        if (version != null && !version.getValue().endsWith("-messages")) {
            throw new IllegalArgumentException("rdf:version \"" + version.getValue()
                    + "\" on rdfm:MessageLog has no -messages suffix");
        }
        String logBase = resolve(baseIri, root.getAttributeByName(XML_BASE));
        String logLang = value(root.getAttributeByName(XML_LANG));
        List<Namespace> logNamespaces = namespaces(root);

        List<List<Statement>> messages = new ArrayList<>();
        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            switch (event.getEventType()) {
                case XMLEvent.START_ELEMENT -> {
                    StartElement element = event.asStartElement();
                    if (!element.getName().equals(RDF_RDF)) {
                        throw new IllegalArgumentException("rdfm:MessageLog may hold only rdf:RDF elements,"
                                + " found " + element.getName() + " at " + where(element));
                    }
                    int n = messages.size() + 1;
                    String base = resolve(logBase, element.getAttributeByName(XML_BASE));
                    String text = copyMessage(reader, element, logNamespaces, base, logLang, n);
                    messages.add(parseMessage(text, base, n));
                }
                case XMLEvent.CHARACTERS, XMLEvent.CDATA, XMLEvent.SPACE -> {
                    Characters chars = event.asCharacters();
                    if (!chars.isWhiteSpace() && !chars.getData().isBlank()) {
                        throw new IllegalArgumentException("text between messages: '"
                                + chars.getData().strip() + "' at " + where(event));
                    }
                }
                case XMLEvent.COMMENT, XMLEvent.PROCESSING_INSTRUCTION -> {
                    // ignored, as §2.4 requires
                }
                case XMLEvent.END_ELEMENT -> {                 // </rdfm:MessageLog>
                    while (reader.hasNext()) {                 // only comments, PIs, whitespace after it
                        XMLEvent after = reader.nextEvent();
                        boolean ignorable = after.getEventType() == XMLEvent.COMMENT
                                || after.getEventType() == XMLEvent.PROCESSING_INSTRUCTION
                                || after.getEventType() == XMLEvent.END_DOCUMENT
                                || after.isCharacters() && after.asCharacters().getData().isBlank();
                        if (!ignorable) {
                            throw new IllegalArgumentException("content after </rdfm:MessageLog> at " + where(after));
                        }
                    }
                    return messages;
                }
                case XMLEvent.ENTITY_REFERENCE -> throw new IllegalArgumentException(
                        "an entity reference between messages at " + where(event));
                default -> throw new IllegalArgumentException("unexpected XML event "
                        + event.getEventType() + " at " + where(event));
            }
        }
        throw new IllegalArgumentException("rdfm:MessageLog is not closed");
    }

    /**
     * Copy one {@code <rdf:RDF>} element out as a standalone document: its start tag gains the
     * log's namespace declarations it does not redeclare, the resolved base and the inherited
     * language.
     */
    private static String copyMessage(XMLEventReader reader, StartElement start, List<Namespace> logNamespaces,
                                      String base, String logLang, int n) throws XMLStreamException {
        List<Namespace> namespaces = new ArrayList<>(namespaces(start));
        for (Namespace inherited : logNamespaces) {
            if (namespaces.stream().noneMatch(ns -> ns.getPrefix().equals(inherited.getPrefix()))) {
                namespaces.add(inherited);
            }
        }
        List<Attribute> attributes = new ArrayList<>();
        for (Iterator<Attribute> it = start.getAttributes(); it.hasNext(); ) {
            Attribute a = it.next();
            if (!a.getName().equals(XML_BASE)) {
                attributes.add(a);
            }
        }
        if (base != null) {
            attributes.add(EVENTS.createAttribute("xml", XMLConstants.XML_NS_URI, "base", base));
        }
        if (logLang != null && start.getAttributeByName(XML_LANG) == null) {
            attributes.add(EVENTS.createAttribute("xml", XMLConstants.XML_NS_URI, "lang", logLang));
        }
        refuseRdf12(start, n);

        // Written out by hand rather than with an XMLEventWriter, which would write a decoded CR,
        // tab or newline back raw, where XML's end-of-line and attribute-value normalisation turn
        // them into LF or spaces: here they are written as character references.
        StringBuilder out = new StringBuilder();
        writeStart(out, start.getName(), attributes, namespaces);
        int depth = 1;
        int literalDepth = 0;                // > 0 inside the content of an XML-literal property
        while (depth > 0) {
            if (!reader.hasNext()) {
                throw new IllegalArgumentException("message " + n + " is not closed");
            }
            XMLEvent event = reader.nextEvent();
            switch (event.getEventType()) {
                case XMLEvent.START_ELEMENT -> {
                    StartElement element = event.asStartElement();
                    depth++;
                    if (literalDepth > 0) {
                        literalDepth++;
                    } else {
                        refuseRdf12(element, n);
                        Attribute parseType = element.getAttributeByName(RDF_PARSE_TYPE);
                        if (parseType != null && !parseType.getValue().equals("Resource")
                                && !parseType.getValue().equals("Collection")) {
                            literalDepth = 1;        // RDF/XML reads any other parseType as Literal
                        }
                    }
                    List<Attribute> own = new ArrayList<>();
                    element.getAttributes().forEachRemaining(own::add);
                    writeStart(out, element.getName(), own, namespaces(element));
                }
                case XMLEvent.END_ELEMENT -> {
                    depth--;
                    if (literalDepth > 0) {
                        literalDepth--;
                    }
                    out.append("</").append(qname(event.asEndElement().getName())).append('>');
                }
                case XMLEvent.CHARACTERS, XMLEvent.CDATA, XMLEvent.SPACE ->
                        escape(out, event.asCharacters().getData(), false);
                case XMLEvent.COMMENT -> out.append("<!--").append(((javax.xml.stream.events.Comment) event).getText())
                        .append("-->");
                case XMLEvent.PROCESSING_INSTRUCTION -> {
                    javax.xml.stream.events.ProcessingInstruction pi = (javax.xml.stream.events.ProcessingInstruction) event;
                    out.append("<?").append(pi.getTarget());
                    if (pi.getData() != null && !pi.getData().isEmpty()) {
                        out.append(' ').append(pi.getData());
                    }
                    out.append("?>");
                }
                default -> throw new IllegalArgumentException("unexpected XML event " + event.getEventType()
                        + " in message " + n + " at " + where(event));
            }
        }
        return out.toString();
    }

    private static void writeStart(StringBuilder out, QName name, List<Attribute> attributes, List<Namespace> namespaces) {
        out.append('<').append(qname(name));
        for (Namespace ns : namespaces) {
            out.append(ns.getPrefix().isEmpty() ? " xmlns" : " xmlns:" + ns.getPrefix()).append("=\"");
            escape(out, ns.getNamespaceURI() == null ? "" : ns.getNamespaceURI(), true);  // xmlns=""
            out.append('"');
        }
        for (Attribute a : attributes) {
            out.append(' ').append(qname(a.getName())).append("=\"");
            escape(out, a.getValue(), true);
            out.append('"');
        }
        out.append('>');
    }

    private static String qname(QName name) {
        return name.getPrefix() == null || name.getPrefix().isEmpty() ? name.getLocalPart()
                : name.getPrefix() + ":" + name.getLocalPart();
    }

    /** XML-escape {@code text}; CR always, and tab and LF in an attribute, as character references. */
    private static void escape(StringBuilder out, String text, boolean attribute) {
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append(attribute ? "&quot;" : "\"");
                case '\r' -> out.append("&#13;");
                case '\n' -> out.append(attribute ? "&#10;" : "\n");
                case '\t' -> out.append(attribute ? "&#9;" : "\t");
                default -> out.append(c);
            }
        }
    }

    private static List<Statement> parseMessage(String text, String base, int n) {
        try {
            Model model = Rio.parse(new StringReader(text), base == null ? "" : base, RDFFormat.RDFXML);
            return new ArrayList<>(model);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("message " + n + " does not parse as RDF/XML: " + e.getMessage(), e);
        }
    }

    private static void refuseRdf12(StartElement element, int n) {
        Attribute parseType = element.getAttributeByName(RDF_PARSE_TYPE);
        String found = parseType != null && parseType.getValue().equals("Triple") ? "rdf:parseType=\"Triple\""
                : element.getAttributeByName(RDF_ANNOTATION) != null ? "rdf:annotation"
                : element.getAttributeByName(RDF_ANNOTATION_NODE_ID) != null ? "rdf:annotationNodeID"
                : null;
        if (found != null) {
            throw new IllegalArgumentException("message " + n + " uses the RDF 1.2 " + found + " at "
                    + where(element) + ", which RDF4J 4.3.8 would read as different data (#133)");
        }
    }

    private static List<Namespace> namespaces(StartElement element) {
        List<Namespace> list = new ArrayList<>();
        for (Iterator<Namespace> it = element.getNamespaces(); it.hasNext(); ) {
            list.add(it.next());
        }
        return list;
    }

    private static String resolve(String base, Attribute xmlBase) {
        if (xmlBase == null) {
            return base;
        }
        try {
            return base == null || base.isEmpty() ? xmlBase.getValue()
                    : new ParsedIRI(base).resolve(xmlBase.getValue());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("bad base IRI " + base + ": " + e.getMessage(), e);
        }
    }

    private static String value(Attribute attribute) {
        return attribute == null ? null : attribute.getValue();
    }

    private static String where(XMLEvent event) {
        return "line " + event.getLocation().getLineNumber() + ", column " + event.getLocation().getColumnNumber();
    }

    // ------------------------------------------------------------------------------------ writing

    /**
     * Write an RDF/XML RDF Message Log: {@code <rdfm:MessageLog rdf:version="1.2-messages">}
     * holding each message as the {@code <rdf:RDF>} element RDF4J writes for it.
     *
     * @param namespaces prefix to namespace pairs to declare in every message (may be empty)
     * @throws IllegalArgumentException if a statement has a graph name (RDF/XML has none) or holds
     *                                  a triple term (#133), or a message would not read back
     *                                  unchanged (each one is checked)
     */
    public static String write(List<? extends Collection<Statement>> messages, Map<String, String> namespaces) {
        RdfMessageLog.checkWritable(messages, false, "RDF/XML");
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<rdfm:MessageLog xmlns:rdfm=\"").append(RDFM).append("\" xmlns:rdf=\"").append(RDF)
                .append("\" rdf:version=\"").append(RdfMessageLog.VERSION_LABEL).append("\">\n");
        int n = 0;
        for (Collection<Statement> message : messages) {
            n++;
            Model model = new LinkedHashModel(message);
            namespaces.forEach(model::setNamespace);
            StringWriter text = new StringWriter();
            String problem;
            try {
                Rio.write(model, text, RDFFormat.RDFXML);
                // RDF/XML cannot hold everything: characters XML 1.0 forbids, an ill-formed
                // rdf:XMLLiteral, predicates such as rdf:li (read back as rdf:_1) or rdf:nodeID, or
                // one with no XML name. RDF4J writes some of these anyway, so the message is read
                // back here and refused unless it is the same.
                Model back = Rio.parse(new StringReader(text.toString()), "", RDFFormat.RDFXML);
                problem = org.eclipse.rdf4j.model.util.Models.isomorphic(model, back) ? null
                        : "it would read back as " + back;
            } catch (IOException | RuntimeException e) {
                problem = e.getMessage();
            }
            if (problem != null) {
                throw new IllegalArgumentException("message " + n + " cannot be written as RDF/XML that"
                        + " reads back unchanged: " + problem);
            }
            out.append(XML_DECLARATION.matcher(text.toString()).replaceFirst("").strip()).append('\n');
        }
        return out.append("</rdfm:MessageLog>\n").toString();
    }
}
