package org.cqels.playground.messages.io;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.jsonldjava.core.DocumentLoader;
import com.github.jsonldjava.core.JsonLdError;
import com.github.jsonldjava.core.RemoteDocument;
import com.github.jsonldjava.utils.JsonUtils;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.BasicWriterSettings;
import org.eclipse.rdf4j.rio.helpers.JSONLDMode;
import org.eclipse.rdf4j.rio.helpers.JSONLDSettings;
import org.eclipse.rdf4j.rio.helpers.StatementCollector;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.common.ScalarStyle;
import org.snakeyaml.engine.v2.events.AliasEvent;
import org.snakeyaml.engine.v2.events.Event;
import org.snakeyaml.engine.v2.events.MappingEndEvent;
import org.snakeyaml.engine.v2.events.MappingStartEvent;
import org.snakeyaml.engine.v2.events.ScalarEvent;
import org.snakeyaml.engine.v2.events.SequenceEndEvent;
import org.snakeyaml.engine.v2.events.SequenceStartEvent;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;
import org.snakeyaml.engine.v2.nodes.Tag;
import org.snakeyaml.engine.v2.schema.CoreSchema;

/**
 * Reads and writes RDF Message Logs in the two JSON-LD based formats of the W3C RSP CG
 * <a href="https://w3c-cg.github.io/rsp/spec/messages">RDF Messages</a> draft (22 October 2026):
 * <strong>NDJSON-LD</strong> (§2.2) and <strong>YAML-LD</strong> (§2.3). Each message comes back as
 * one {@code List<Statement>}, one atomic CQELS stream element.
 *
 * <p><strong>NDJSON-LD</strong>, as §2.2 defines it: every line is exactly one JSON object, and
 * blank lines are not allowed (a final newline is; so are CRLF line ends and a byte-order mark
 * before the first line). A line is one of
 * <ul>
 *   <li>{@code {}}: an empty message;</li>
 *   <li>an object whose only key is {@code @context}: a context message, which emits nothing and
 *       becomes the active context C for the lines after it;</li>
 *   <li>anything else: a data message, converted to RDF as {@code {"@context": C, "@graph": L}}
 *       when C is set, as itself otherwise. Its own {@code @context} applies to it alone.</li>
 * </ul>
 * RDF4J 4.3.8 has an NDJSON-LD parser, but it reads all lines as one document, which shares blank
 * nodes across lines and knows no context messages, so it is not used here.
 *
 * <p><strong>YAML-LD</strong>, as §2.3 proposes it: a YAML stream in which each document is one
 * message, read as a JSON-LD document. The draft marks this format as still under discussion.
 * Choices made here where it is silent:
 * <ul>
 *   <li>YAML 1.2 with the Core schema (snakeyaml-engine), so {@code yes} is a string and
 *       {@code 0o17} a number; a {@code %YAML} directive for another version is refused. Aliases
 *       are expanded. Refused, since none of them has a JSON form: a recursive alias, an explicit
 *       tag other than the Core schema's ({@code !!binary}, {@code !!set}, custom tags), a
 *       {@code !!bool} or {@code !!null} tag on a value not spelled that way (snakeyaml-engine
 *       would make {@code !!bool yes} null), a key that is not a string, and {@code .inf} or
 *       {@code .nan}, and a {@code <<} merge key, which snakeyaml-engine merges as YAML 1.1
 *       did.</li>
 *   <li>An empty document (such as a {@code ---} with nothing after it) is refused rather than
 *       guessed at; an empty message is written {@code {}}.</li>
 *   <li>No context messages: §2.3 does not define them, so every document stands alone.</li>
 * </ul>
 *
 * <p><strong>Both formats.</strong>
 * <ul>
 *   <li>Blank nodes are scoped to their message: each message is converted on its own.</li>
 *   <li>Remote contexts are never fetched. A {@code @context} given as a URL is served from the
 *       {@code contexts} map the caller passes (URL to JSON document), and any other URL is an
 *       error.</li>
 *   <li>The JSON-LD processor is jsonld-java 0.13.4, the one {@code rdf4j-rio-jsonld} 4.3.8
 *       uses, and it implements JSON-LD 1.0. JSON-LD 1.1 keywords it would drop or reject, and a
 *       term's scoped {@code @context}, which it would ignore, are refused with an error naming
 *       <a href="https://github.com/cqels/CQELS4J/issues/133">#133</a>, and so is a
 *       property-valued {@code @index}, whose triples it would drop. The check looks at keys only,
 *       without the context, so it also refuses such a keyword used as a key of an index map,
 *       where JSON-LD 1.0 would read it as data; a keyword alias defined in the context is
 *       rejected by jsonld-java itself. Input that JSON-LD 1.0 and 1.1 read differently is read
 *       with the 1.0 meaning, without an error: any term acts as a prefix (1.1 needs a
 *       gen-delim at the end of a plain-string definition, or {@code @prefix}), a whole-valued
 *       number below 10^21 written with a fraction or exponent ({@code 1.0}, {@code 1e2}) is an
 *       {@code xsd:double} (1.1: integer) and an integer of 10^21 or more an {@code xsd:integer}
 *       (1.1: double); see RDF_MESSAGES_MAPPING.md §4.3. Otherwise numbers are typed as RDF4J
 *       types them directly; a floating-point number beyond {@code xsd:double} is refused.</li>
 * </ul>
 *
 * <p>The writers emit each message as expanded JSON-LD wrapped in {@code {"@graph": [...]}} (an
 * empty message as {@code {}}), with no context, so the output re-reads to the same messages.
 */
public final class JsonLdMessageLog {

    private JsonLdMessageLog() {
    }

    /** The media type RDF4J uses for NDJSON-LD; the draft asks (§2.2) what it should be. */
    public static final String NDJSON_LD_MEDIA_TYPE = "application/x-ld+ndjson";

    /** The media type §2.3 proposes for a YAML-LD RDF Message Log. */
    public static final String YAML_LD_MEDIA_TYPE = "application/rdfm+yaml";

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** JSON-LD 1.1 keywords that a JSON-LD 1.0 processor drops or rejects. */
    private static final Set<String> JSON_LD_11_KEYWORDS = Set.of(
            "@direction", "@import", "@included", "@json", "@nest", "@none", "@prefix",
            "@propagate", "@protected", "@version");

    // ------------------------------------------------------------------------------- NDJSON-LD

    /**
     * Parse an NDJSON-LD RDF Message Log (§2.2).
     *
     * @param log      the whole log
     * @param baseIri  base IRI for relative IRIs
     * @param contexts remote contexts by URL (may be empty); nothing is fetched over the network
     * @return one statement list per message, in order; an empty message is an empty list
     * @throws IllegalArgumentException if the log is malformed, uses an unknown remote context or a
     *                                  JSON-LD 1.1 feature (#133)
     */
    public static List<List<Statement>> parseNdjson(String log, String baseIri, Map<String, String> contexts) {
        List<List<Statement>> messages = new ArrayList<>();
        if (log.startsWith("\uFEFF")) {
            log = log.substring(1);                     // a byte-order mark before the first line
        }
        if (log.isEmpty()) {
            return messages;
        }
        String[] lines = log.split("\n", -1);
        int count = log.endsWith("\n") ? lines.length - 1 : lines.length;
        JsonNode context = null;
        boolean contextSet = false;
        for (int n = 0; n < count; n++) {
            String line = lines[n].endsWith("\r") ? lines[n].substring(0, lines[n].length() - 1) : lines[n];
            String where = "line " + (n + 1);
            if (line.isBlank()) {
                throw new IllegalArgumentException(where + " is blank; NDJSON-LD allows no blank lines");
            }
            JsonNode value;
            try {
                value = JSON.readTree(line);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException(where + " is not one JSON value: " + e.getOriginalMessage(), e);
            }
            if (!(value instanceof ObjectNode object)) {
                throw new IllegalArgumentException(where + " is not a JSON object");
            }
            if (object.isEmpty()) {
                messages.add(List.of());
            } else if (object.size() == 1 && object.has("@context")) {
                context = object.get("@context");
                contextSet = true;
            } else {
                JsonNode document = object;
                if (contextSet) {
                    ObjectNode wrapped = JSON.createObjectNode();
                    wrapped.set("@context", context);
                    wrapped.set("@graph", object);
                    document = wrapped;
                }
                messages.add(toStatements(document, baseIri, contexts, where));
            }
        }
        return messages;
    }

    /** Write an NDJSON-LD RDF Message Log: one line per message, no context messages. */
    public static String writeNdjson(List<? extends Collection<Statement>> messages) {
        RdfMessageLog.checkWritable(messages, true, "NDJSON-LD");
        StringBuilder out = new StringBuilder();
        for (Collection<Statement> message : messages) {
            out.append(messageObject(message)).append('\n');
        }
        return out.toString();
    }

    /**
     * One message as a single-line JSON-LD object: {@code {}} when empty, otherwise its expanded
     * form wrapped in {@code {"@graph": [...]}}. Also the payload of one Server-Sent Event.
     */
    public static String messageObject(Collection<Statement> message) {
        if (message.isEmpty()) {
            return "{}";
        }
        RdfMessageLog.checkWritable(List.of(message), true, "JSON-LD");
        StringWriter text = new StringWriter();
        RDFWriter writer = Rio.createWriter(RDFFormat.JSONLD, text);
        writer.getWriterConfig().set(JSONLDSettings.JSONLD_MODE, JSONLDMode.EXPAND);
        writer.getWriterConfig().set(BasicWriterSettings.PRETTY_PRINT, false);
        Rio.write(message, writer);
        try {
            ObjectNode wrapped = JSON.createObjectNode();
            wrapped.set("@graph", (ArrayNode) JSON.readTree(text.toString()));
            return JSON.writeValueAsString(wrapped);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("RDF4J wrote JSON-LD that does not re-read", e);
        }
    }

    /**
     * Parse one standalone JSON-LD document, such as the payload of one Server-Sent Event, as one
     * message. The same checks apply as for a log; {@code {}} is an empty message.
     */
    public static List<Statement> parseMessage(String json, String baseIri, Map<String, String> contexts) {
        JsonNode value;
        try {
            value = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not one JSON value: " + e.getOriginalMessage(), e);
        }
        return toStatements(value, baseIri, contexts, "message");
    }

    // ---------------------------------------------------------------------------------- YAML-LD

    /**
     * Parse a YAML-LD RDF Message Log (§2.3): one message per YAML document.
     *
     * @param contexts remote contexts by URL (may be empty); nothing is fetched over the network
     * @throws IllegalArgumentException if the log is malformed, holds an empty document or a value
     *                                  with no JSON form, or uses a JSON-LD 1.1 feature (#133)
     */
    public static List<List<Statement>> parseYaml(String log, String baseIri, Map<String, String> contexts) {
        LoadSettings settings = LoadSettings.builder().setSchema(new CoreSchema())
                .setVersionFunction(version -> {
                    if (version.getMajor() != 1 || version.getMinor() != 2) {
                        throw new IllegalArgumentException("%YAML " + version.getRepresentation()
                                + ": only YAML 1.2 is read (1.1 would read yes as true)");
                    }
                    return version;
                }).build();
        try {
            refuseMergeKeys(new Parse(settings).parseString(log));
            for (Node node : new Compose(settings).composeAllFromString(log)) {
                checkTaggedScalars(node, Collections.newSetFromMap(new IdentityHashMap<>()));
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not YAML: " + e.getMessage(), e);
        }
        List<List<Statement>> messages = new ArrayList<>();
        int n = 0;
        Iterator<Object> documents;
        try {
            documents = new Load(settings).loadAllFromString(log).iterator();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not YAML: " + e.getMessage(), e);
        }
        while (true) {
            Object document;
            try {
                if (!documents.hasNext()) {
                    break;
                }
                document = documents.next();
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("YAML document " + (n + 1) + ": " + e.getMessage(), e);
            }
            n++;
            String where = "YAML document " + n;
            if (document == null) {
                throw new IllegalArgumentException(where + " is empty; write {} for an empty message");
            }
            if (!(document instanceof Map) && !(document instanceof List)) {
                throw new IllegalArgumentException(where + " is a scalar, not a JSON-LD document");
            }
            checkJsonForm(document, where, new IdentityHashMap<>());
            messages.add(toStatements(JSON.valueToTree(document), baseIri, contexts, where));
        }
        return messages;
    }

    /** Write a YAML-LD RDF Message Log: one {@code ---} document per message, {@code {}} if empty. */
    public static String writeYaml(List<? extends Collection<Statement>> messages) {
        RdfMessageLog.checkWritable(messages, true, "YAML-LD");
        List<Object> documents = new ArrayList<>();
        for (Collection<Statement> message : messages) {
            try {
                documents.add(JSON.readValue(messageObject(message), Object.class));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
        DumpSettings settings = DumpSettings.builder().setSchema(new CoreSchema())
                .setExplicitStart(true).setDefaultFlowStyle(FlowStyle.BLOCK).build();
        return new Dump(settings).dumpAllToString(documents.iterator());
    }

    /**
     * A plain {@code <<} mapping key (or a {@code !!merge} tag): snakeyaml-engine merges it as YAML
     * 1.1 did, already while composing nodes, but in YAML 1.2 it is an ordinary key. Found here in
     * the event stream, tracking which scalars stand in key position.
     */
    private static void refuseMergeKeys(Iterable<Event> events) {
        java.util.Deque<boolean[]> open = new java.util.ArrayDeque<>();   // {is a mapping, next is a key}
        for (Event event : events) {
            if (event instanceof MappingStartEvent || event instanceof SequenceStartEvent) {
                open.push(new boolean[] {event instanceof MappingStartEvent, true});
            } else if (event instanceof MappingEndEvent || event instanceof SequenceEndEvent) {
                open.pop();
                completed(open);
            } else if (event instanceof ScalarEvent scalar) {
                boolean key = !open.isEmpty() && open.peek()[0] && open.peek()[1];
                if (key && scalar.getScalarStyle() == ScalarStyle.PLAIN && scalar.getTag().isEmpty()
                        && scalar.getValue().equals("<<")
                        || scalar.getTag().filter(t -> t.equals(Tag.MERGE.getValue()) || t.equals("!!merge")).isPresent()) {
                    throw new IllegalArgumentException("a '<<' merge key, which snakeyaml-engine merges as"
                            + " YAML 1.1 did; in YAML 1.2 it is an ordinary key (quote it as \"<<\")");
                }
                completed(open);
            } else if (event instanceof AliasEvent) {
                completed(open);
            }
        }
    }

    private static void completed(java.util.Deque<boolean[]> open) {
        if (!open.isEmpty() && open.peek()[0]) {
            open.peek()[1] = !open.peek()[1];
        }
    }

    /**
     * An explicit {@code !!bool} or {@code !!null} tag on a value the Core schema does not spell that
     * way ({@code !!bool yes}) is constructed as null by snakeyaml-engine, and JSON-LD would then drop
     * the value: refused.
     */
    private static void checkTaggedScalars(Node node, Set<Node> seen) {
        if (!seen.add(node)) {
            return;
        }
        if (node instanceof ScalarNode scalar) {
            String v = scalar.getValue();
            if (scalar.getTag().equals(Tag.BOOL) && !v.matches("true|True|TRUE|false|False|FALSE")
                    || scalar.getTag().equals(Tag.NULL) && !v.matches("null|Null|NULL|~|")) {
                throw new IllegalArgumentException("'" + v + "' tagged " + scalar.getTag()
                        + " is not a YAML 1.2 Core schema value of that type");
            }
        } else if (node instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                checkTaggedScalars(tuple.getKeyNode(), seen);
                checkTaggedScalars(tuple.getValueNode(), seen);
            }
        } else if (node instanceof SequenceNode sequence) {
            for (Node item : sequence.getValue()) {
                checkTaggedScalars(item, seen);
            }
        }
    }

    /** Refuse YAML values that have no JSON form: recursion, non-string keys, NaN and infinities. */
    private static void checkJsonForm(Object value, String where, Map<Object, Boolean> path) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            if (path.put(value, Boolean.TRUE) != null) {
                throw new IllegalArgumentException(where + " holds a recursive alias");
            }
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!(e.getKey() instanceof String)) {
                        throw new IllegalArgumentException(where + " has a key that is not a string: " + e.getKey());
                    }
                    checkJsonForm(e.getValue(), where, path);
                }
            } else {
                for (Object item : (List<?>) value) {
                    checkJsonForm(item, where, path);
                }
            }
            path.remove(value);
        } else if (value instanceof Double d && (d.isNaN() || d.isInfinite())) {
            throw new IllegalArgumentException(where + " holds " + d + ", which JSON cannot hold");
        } else if (value != null && !(value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long || value instanceof BigInteger
                || value instanceof Double || value instanceof BigDecimal)) {
            throw new IllegalArgumentException(where + " holds a " + value.getClass().getSimpleName()
                    + ", which has no JSON form");
        }
    }

    // ------------------------------------------------------------------------------ conversion

    private static List<Statement> toStatements(JsonNode document, String baseIri,
                                                Map<String, String> contexts, String where) {
        if (document instanceof ObjectNode object && object.isEmpty()
                || document instanceof ArrayNode array && array.isEmpty()) {
            return List.of();
        }
        if (!(document instanceof ObjectNode) && !(document instanceof ArrayNode)) {
            throw new IllegalArgumentException(where + " is not a JSON-LD document");
        }
        refuseJsonLd11(document, where);
        RDFParser parser = Rio.createParser(RDFFormat.JSONLD);
        parser.getParserConfig().set(JSONLDSettings.DOCUMENT_LOADER, new OfflineLoader(contexts));
        StatementCollector collector = new StatementCollector(new LinkedHashModel());
        parser.setRDFHandler(collector);
        try {
            parser.parse(new StringReader(JSON.writeValueAsString(document)), baseIri);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException(where + " does not convert to RDF: " + rootMessage(e), e);
        }
        return new ArrayList<>((Model) collector.getStatements());
    }

    /**
     * Refuse what jsonld-java 0.13.4 (JSON-LD 1.0) would silently drop or misread: a JSON-LD 1.1
     * keyword used as a key, and a term definition with its own {@code @context}.
     */
    static void refuseJsonLd11(JsonNode node, String where) {
        // Jackson reads 1e400 as a double Infinity, which it would write back as the string
        // "Infinity"; integers are kept exactly (up to Jackson's 1000 digits)
        if (node.isDouble() && !Double.isFinite(node.doubleValue())) {
            throw new IllegalArgumentException(where + " holds the number " + node.asText()
                    + ", beyond the range of xsd:double");
        }
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                if (JSON_LD_11_KEYWORDS.contains(field.getKey())) {
                    throw new IllegalArgumentException(where + " uses the JSON-LD 1.1 keyword "
                            + field.getKey() + ", which the JSON-LD 1.0 processor of RDF4J 4.3.8"
                            + " would drop or reject (#133)");
                }
                if (field.getKey().equals("@context")) {
                    refuseScopedContexts(field.getValue(), where);
                }
                refuseJsonLd11(field.getValue(), where);
            }
        } else if (node instanceof ArrayNode array) {
            for (JsonNode item : array) {
                refuseJsonLd11(item, where);
            }
        }
    }

    private static void refuseScopedContexts(JsonNode context, String where) {
        if (context instanceof ArrayNode array) {
            for (JsonNode item : array) {
                refuseScopedContexts(item, where);
            }
        } else if (context instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> term : object.properties()) {
                if (term.getValue() instanceof ObjectNode definition && definition.has("@context")) {
                    throw new IllegalArgumentException(where + " gives the term '" + term.getKey()
                            + "' a scoped @context (JSON-LD 1.1), which the JSON-LD 1.0 processor"
                            + " of RDF4J 4.3.8 would ignore (#133)");
                }
                if (term.getValue() instanceof ObjectNode definition && definition.has("@index")) {
                    throw new IllegalArgumentException(where + " gives the term '" + term.getKey()
                            + "' a property-valued @index (JSON-LD 1.1), whose triples the JSON-LD 1.0"
                            + " processor of RDF4J 4.3.8 would drop (#133)");
                }
            }
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        StringBuilder message = new StringBuilder(String.valueOf(e.getMessage()));
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
            message.append(": ").append(root.getMessage());
        }
        return message.toString();
    }

    /** Serves remote contexts from a map, after the same JSON-LD 1.1 checks; fetches nothing. */
    private static final class OfflineLoader extends DocumentLoader {
        private final Map<String, String> contexts;

        OfflineLoader(Map<String, String> contexts) {
            this.contexts = new LinkedHashMap<>(contexts);
        }

        @Override
        public RemoteDocument loadDocument(String url) throws JsonLdError {
            String json = contexts.get(url);
            if (json == null) {
                throw new JsonLdError(JsonLdError.Error.LOADING_REMOTE_CONTEXT_FAILED,
                        url + " is not among the contexts provided; remote contexts are not fetched");
            }
            try {
                refuseJsonLd11(JSON.readTree(json), "the context " + url);
                return new RemoteDocument(url, JsonUtils.fromString(json));
            } catch (IOException e) {
                throw new JsonLdError(JsonLdError.Error.LOADING_DOCUMENT_FAILED, url + ": " + e.getMessage());
            }
        }
    }
}
