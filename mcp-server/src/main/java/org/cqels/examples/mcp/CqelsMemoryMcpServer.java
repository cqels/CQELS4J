package org.cqels.examples.mcp;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.reasoning.config.RDFSProfile;
import org.cqels.reasoning.engine.ReactiveReteAdapter;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A minimal Model Context Protocol (MCP) server backed by a CQELS engine.
 *
 * <p>It exposes a CQELS engine as AI-callable tools over stdio — both the static RDF store
 * <em>and</em> the streaming engine that the {@code examples/} demonstrate:
 * <ul>
 *   <li><b>Memory (static):</b> {@code store_fact} adds a {@code (subject, predicate, object)}
 *       triple; {@code query} runs a SPARQL SELECT over everything stored.</li>
 *   <li><b>Streaming (continuous):</b> {@code push_event} ingests a triple into a named
 *       stream; {@code register_stream_query} registers a continuous CQELS-QL query (windows,
 *       aggregates, CEP — the same shapes as the examples) and buffers its emitted rows;
 *       {@code poll_results} drains the rows a query has emitted so far.</li>
 * </ul>
 *
 * <p>Continuous queries push results asynchronously, but MCP tools are request/response — so
 * a registered query's rows are buffered server-side and returned on demand by
 * {@code poll_results} (register → push events → poll).
 *
 * <p>An MCP client (e.g. Claude Desktop) launches this jar and talks JSON-RPC over the
 * process's stdin/stdout. <strong>stdout is reserved for the protocol</strong> — all logging
 * goes to stderr (slf4j-simple's default), and this class never writes to stdout itself.
 *
 * <p>Build: {@code mvn -q package} → {@code target/cqels-mcp-server.jar}. See README.md for
 * the Claude Desktop client configuration.
 */
public final class CqelsMemoryMcpServer {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_ROWS = 100;
    /** Per-query cap on buffered rows; past this the oldest are dropped (with accounting) so an
     *  unpolled or high-volume query can't grow memory without bound. */
    private static final int MAX_BUFFER = 10_000;

    /** Matches the stream name(s) in `FROM STREAM <name> [...]` so we can create them on demand. */
    private static final Pattern FROM_STREAM = Pattern.compile("FROM\\s+STREAM\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
    /** Streams created so far (created once per name; createStream twice would throw). */
    private static final Map<String, DataStream> STREAMS = new ConcurrentHashMap<>();
    /** Per-registered-query bounded buffer of emitted result rows, drained by poll_results. */
    private static final Map<String, BlockingQueue<String>> BUFFERS = new ConcurrentHashMap<>();
    /** Per-query count of rows dropped because the buffer was full; surfaced by poll_results. */
    private static final Map<String, AtomicLong> DROPPED = new ConcurrentHashMap<>();
    /** Generates ids for detect_sequence's REGISTER QUERY clause. */
    private static final AtomicInteger SEQ_COUNTER = new AtomicInteger();

    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String RDFS_SUBCLASSOF = "http://www.w3.org/2000/01/rdf-schema#subClassOf";

    public static void main(String[] args) throws InterruptedException {
        // RDFS reasoner wired into the stream pipeline: inferred triples (e.g. an instance's
        // superclass types) flow to registered stream queries. Schema is supplied at runtime
        // via define_subclass. Reasoning is stream-side — it does not materialise into the
        // static store that store_fact/query/recall use.
        ReactiveReteAdapter reasoner = new ReactiveReteAdapter(RDFSProfile.INSTANCE.createConfig());
        CQELSEngine engine = CQELSEngine.builder()
                .id("cqels-mcp-memory")
                .withMemoryStore()
                .addStreamProcessor(reasoner::apply)
                .build();
        engine.start();

        // stdio transport with the Jackson-3 mapper the SDK requires.
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                new JacksonMcpJsonMapper(JsonMapper.builder().build()));

        // Register all tools on the builder BEFORE build() so they exist before the
        // transport starts processing requests (no startup race).
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("cqels-memory", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .tools(
                        storeFactTool(engine),          // static memory (low-level)
                        queryTool(engine),
                        recallTool(engine),             // memory retrieval (intent-shaped)
                        pushEventTool(engine),          // streaming (low-level)
                        registerStreamQueryTool(engine),
                        pollResultsTool(),
                        unregisterStreamQueryTool(engine),
                        detectSequenceTool(engine),     // event-pattern matching (intent-shaped)
                        defineSubclassTool(engine))     // reasoning (intent-shaped)
                .build();

        // An MCP stdio server is long-running: the client (e.g. Claude Desktop) keeps the
        // connection open and terminates the process with SIGTERM/SIGINT when done. The
        // shutdown hook is the cleanup path — drain the server (closeGracefully() blocks
        // unbounded in SDK 1.0.0, so bound it), close the engine, then release main.
        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.getAsyncServer().closeGracefully().block(SHUTDOWN_TIMEOUT);
            } catch (RuntimeException ignored) {
                // best-effort
            }
            engine.close();
            shutdown.countDown();
        }));

        // Park main so the JVM stays alive serving requests until the process is signalled.
        // (We intentionally do NOT exit on stdin EOF: that would race in-flight tool calls.)
        shutdown.await();
    }

    // ---- tool: store_fact ------------------------------------------------------------------

    private static McpServerFeatures.SyncToolSpecification storeFactTool(CQELSEngine engine) {
        String schema = """
                {
                  "type": "object",
                  "required": ["subject", "predicate", "object"],
                  "properties": {
                    "subject":   {"type": "string", "description": "Subject IRI"},
                    "predicate": {"type": "string", "description": "Predicate IRI"},
                    "object":    {"type": "string", "description": "Object: an IRI (http…) or a literal value"}
                  }
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("store_fact", null,
                        "Store a single RDF fact (subject, predicate, object) in CQELS memory.",
                        parseSchema(schema), null, null, null),
                (exchange, request) -> {
                    try {
                        Map<String, Object> a = request.arguments();
                        String s = str(a, "subject");
                        String p = str(a, "predicate");
                        String o = str(a, "object");
                        try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                            conn.add(VF.createIRI(s), VF.createIRI(p), asValue(o));
                        }
                        return text("Stored: <" + s + "> <" + p + "> " + o);
                    } catch (Exception e) {
                        return error("store_fact failed: " + e.getMessage());
                    }
                });
    }

    // ---- tool: query -----------------------------------------------------------------------

    private static McpServerFeatures.SyncToolSpecification queryTool(CQELSEngine engine) {
        String schema = """
                {
                  "type": "object",
                  "required": ["sparql"],
                  "properties": {
                    "sparql": {"type": "string", "description": "A SPARQL SELECT query over stored facts"}
                  }
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("query", null,
                        "Run a SPARQL SELECT query over everything stored in CQELS memory.",
                        parseSchema(schema), null, null, null),
                (exchange, request) -> {
                    try {
                        return text(runSparql(engine, str(request.arguments(), "sparql")));
                    } catch (Exception e) {
                        return error("query failed: " + e.getMessage());
                    }
                });
    }

    // ---- tool: recall (intent-shaped memory retrieval) -------------------------------------

    private static McpServerFeatures.SyncToolSpecification recallTool(CQELSEngine engine) {
        String schema = """
                {
                  "type": "object",
                  "required": ["subject"],
                  "properties": {
                    "subject":   {"type": "string", "description": "The entity IRI to recall facts about"},
                    "predicate": {"type": "string", "description": "Optional: only recall this predicate IRI"}
                  }
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("recall", null,
                        "Recall what is known about an entity from memory — returns its stored facts "
                                + "without you writing SPARQL.",
                        parseSchema(schema), null, null, null),
                (exchange, request) -> {
                    try {
                        Map<String, Object> a = request.arguments();
                        String subject = str(a, "subject");
                        Object predObj = a.get("predicate");
                        String sparql = (predObj == null)
                                ? "SELECT ?predicate ?object WHERE { <" + subject + "> ?predicate ?object }"
                                : "SELECT ?object WHERE { <" + subject + "> <" + predObj + "> ?object }";
                        return text(runSparql(engine, sparql));
                    } catch (Exception e) {
                        return error("recall failed: " + e.getMessage());
                    }
                });
    }

    // ---- tool: push_event (streaming ingest) -----------------------------------------------

    private static McpServerFeatures.SyncToolSpecification pushEventTool(CQELSEngine engine) {
        String schema = """
                {
                  "type": "object",
                  "required": ["stream", "subject", "predicate", "object"],
                  "properties": {
                    "stream":    {"type": "string", "description": "Stream name (created on first use)"},
                    "subject":   {"type": "string", "description": "Subject IRI"},
                    "predicate": {"type": "string", "description": "Predicate IRI"},
                    "object":    {"type": "string", "description": "Object: an IRI (http…) or a literal value"}
                  }
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("push_event", null,
                        "Push one RDF triple as an event into a named CQELS stream (continuous queries "
                                + "registered on that stream will see it).",
                        parseSchema(schema), null, null, null),
                (exchange, request) -> {
                    try {
                        Map<String, Object> a = request.arguments();
                        String streamName = str(a, "stream");
                        String s = str(a, "subject");
                        String p = str(a, "predicate");
                        String o = str(a, "object");
                        if (p.equals("a") || p.equals("rdf:type")) {  // convenience: the rdf:type shorthand
                            p = RDF_TYPE;
                        }
                        pushTyped(ensureStream(engine, streamName), s, p, o);
                        return text("pushed to stream '" + streamName + "': <" + s + "> <" + p + "> " + o);
                    } catch (Exception e) {
                        return error("push_event failed: " + e.getMessage());
                    }
                });
    }

    // ---- tool: register_stream_query (continuous query) ------------------------------------

    private static McpServerFeatures.SyncToolSpecification registerStreamQueryTool(CQELSEngine engine) {
        String schema = """
                {
                  "type": "object",
                  "required": ["query"],
                  "properties": {
                    "query": {"type": "string",
                              "description": "A continuous CQELS-QL query (REGISTER QUERY … FROM STREAM … [window] …)"}
                  }
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("register_stream_query", null,
                        "Register a continuous CQELS-QL query (windows, aggregates, CEP). Returns a query "
                                + "id; emitted rows are buffered — read them with poll_results.",
                        parseSchema(schema), null, null, null),
                (exchange, request) -> {
                    try {
                        String query = str(request.arguments(), "query");
                        // Ensure every stream the query references exists before registering.
                        Matcher m = FROM_STREAM.matcher(query);
                        while (m.find()) {
                            ensureStream(engine, m.group(1));
                        }
                        BlockingQueue<String> buffer = new LinkedBlockingQueue<>(MAX_BUFFER);
                        AtomicLong dropped = new AtomicLong();
                        String queryId = engine.registerCqelsQuery(query,
                                row -> boundedOffer(buffer, dropped, String.valueOf(row)));
                        if (BUFFERS.putIfAbsent(queryId, buffer) != null) {  // don't clobber an existing buffer
                            return error("a query with id '" + queryId + "' is already registered; "
                                    + "use a different REGISTER QUERY name, or unregister it first.");
                        }
                        DROPPED.put(queryId, dropped);
                        return text("registered continuous query, id='" + queryId
                                + "'. Push events to its stream, then call poll_results with this id"
                                + " (and unregister_stream_query when done).");
                    } catch (Exception e) {
                        return error("register_stream_query failed: " + e.getMessage());
                    }
                });
    }

    // ---- tool: poll_results (drain a query's emitted rows) ---------------------------------

    private static McpServerFeatures.SyncToolSpecification pollResultsTool() {
        String schema = """
                {
                  "type": "object",
                  "required": ["queryId"],
                  "properties": {
                    "queryId": {"type": "string", "description": "Id returned by register_stream_query"}
                  }
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("poll_results", null,
                        "Drain and return the rows a registered continuous query has emitted since the last poll.",
                        parseSchema(schema), null, null, null),
                (exchange, request) -> {
                    try {
                        String queryId = str(request.arguments(), "queryId");
                        BlockingQueue<String> buffer = BUFFERS.get(queryId);
                        if (buffer == null) {
                            return error("unknown query id: '" + queryId
                                    + "' (use the id returned by register_stream_query)");
                        }
                        StringBuilder out = new StringBuilder();
                        int rows = 0;
                        String row;
                        while (rows < MAX_ROWS && (row = buffer.poll()) != null) {  // cap, keeping the rest
                            out.append(row).append('\n');
                            rows++;
                        }
                        boolean more = !buffer.isEmpty();
                        AtomicLong droppedCtr = DROPPED.get(queryId);
                        long drops = droppedCtr == null ? 0 : droppedCtr.getAndSet(0);
                        String body = rows == 0 ? "(no new results)" : out.toString().trim();
                        if (more) {
                            body += "\n… more rows buffered; poll again.";
                        }
                        if (drops > 0) {
                            body += "\n(note: " + drops + " earlier row(s) dropped — buffer cap "
                                    + MAX_BUFFER + "; poll more often or narrow the query.)";
                        }
                        return text(body);
                    } catch (Exception e) {
                        return error("poll_results failed: " + e.getMessage());
                    }
                });
    }

    // ---- tool: unregister_stream_query (stop a query + free its buffer) ---------------------

    private static McpServerFeatures.SyncToolSpecification unregisterStreamQueryTool(CQELSEngine engine) {
        String schema = """
                {
                  "type": "object",
                  "required": ["queryId"],
                  "properties": {
                    "queryId": {"type": "string", "description": "Id returned by register_stream_query"}
                  }
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("unregister_stream_query", null,
                        "Stop a continuous query and free its result buffer (call this when you no "
                                + "longer need a registered query).",
                        parseSchema(schema), null, null, null),
                (exchange, request) -> {
                    try {
                        String queryId = str(request.arguments(), "queryId");
                        boolean existed = BUFFERS.remove(queryId) != null;
                        DROPPED.remove(queryId);
                        boolean stopped = engine.unregisterQuery(queryId);
                        return text(existed || stopped
                                ? "unregistered query '" + queryId + "' and freed its buffer."
                                : "no such query '" + queryId + "' (already unregistered?).");
                    } catch (Exception e) {
                        return error("unregister_stream_query failed: " + e.getMessage());
                    }
                });
    }

    // ---- tool: detect_sequence (intent-shaped CEP) -----------------------------------------

    private static McpServerFeatures.SyncToolSpecification detectSequenceTool(CQELSEngine engine) {
        String schema = """
                {
                  "type": "object",
                  "required": ["stream", "steps"],
                  "properties": {
                    "stream": {"type": "string", "description": "Stream name to watch"},
                    "steps":  {"type": "array", "items": {"type": "string"},
                               "description": "Ordered event-type IRIs to match in sequence (>= 2)"},
                    "withinSeconds": {"type": "integer", "description": "Time window for the whole sequence (default 30)"}
                  }
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("detect_sequence", null,
                        "Watch a stream for an ordered sequence of event types (a CEP pattern) — builds and "
                                + "registers the FILTER(SEQ(...)) query for you. Returns a query id; push events "
                                + "as (eventIri, a, typeIri) via push_event, then read matches with poll_results.",
                        parseSchema(schema), null, null, null),
                (exchange, request) -> {
                    try {
                        Map<String, Object> a = request.arguments();
                        String stream = str(a, "stream");
                        Object stepsObj = a.get("steps");
                        if (!(stepsObj instanceof List<?> steps) || steps.size() < 2) {
                            return error("'steps' must be an array of at least 2 event-type IRIs");
                        }
                        long within = (a.get("withinSeconds") instanceof Number n) ? n.longValue() : 30L;
                        ensureStream(engine, stream);
                        // Reserve a buffer under a free generated id (won't clobber an existing query).
                        BlockingQueue<String> buffer = new LinkedBlockingQueue<>(MAX_BUFFER);
                        AtomicLong dropped = new AtomicLong();
                        String name;
                        do {
                            name = "seq_" + SEQ_COUNTER.incrementAndGet();
                        } while (BUFFERS.putIfAbsent(name, buffer) != null);
                        DROPPED.put(name, dropped);
                        StringBuilder where = new StringBuilder();
                        StringBuilder seq = new StringBuilder();
                        for (int i = 0; i < steps.size(); i++) {
                            String var = "?e" + (i + 1);
                            where.append(var).append(" a <").append(steps.get(i)).append("> . ");
                            if (i > 0) {
                                seq.append(" ; ");
                            }
                            seq.append(var);
                        }
                        String query = "REGISTER QUERY " + name + " AS SELECT ?e1 FROM STREAM " + stream
                                + " [RANGE " + within + "s] WHERE { " + where + "FILTER(SEQ(" + seq + ")) }";
                        // FILTER(SEQ(...)) runs through registerCqelsQuery (returns the id, so
                        // unregister_stream_query can stop it — registerCepQuery returns void / can't be).
                        // The id == our reserved REGISTER QUERY name, so the buffer is already keyed correctly.
                        engine.registerCqelsQuery(query, row -> boundedOffer(buffer, dropped, String.valueOf(row)));
                        return text("watching '" + stream + "' for sequence " + steps + " within " + within
                                + "s — query id='" + name + "'. Push events with predicate \"a\" (rdf:type) and "
                                + "object the type IRI via push_event, then poll_results('" + name + "'); "
                                + "unregister_stream_query when done.");
                    } catch (Exception e) {
                        return error("detect_sequence failed: " + e.getMessage());
                    }
                });
    }

    // ---- tool: define_subclass (intent-shaped RDFS reasoning) -------------------------------

    private static McpServerFeatures.SyncToolSpecification defineSubclassTool(CQELSEngine engine) {
        String schema = """
                {
                  "type": "object",
                  "required": ["stream", "subclass", "superclass"],
                  "properties": {
                    "stream":     {"type": "string", "description": "Stream the rule applies to"},
                    "subclass":   {"type": "string", "description": "Subclass IRI"},
                    "superclass": {"type": "string", "description": "Superclass IRI"}
                  }
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("define_subclass", null,
                        "Teach the reasoner that subclass ⊑ superclass. Afterwards an event pushed as "
                                + "(x, a, subclass) is also inferred as (x, a, superclass), so a registered "
                                + "query/sequence on the superclass matches it. Note: the RDFS reasoner is "
                                + "engine-wide, so the rule applies across ALL streams (the 'stream' arg is "
                                + "just where the axiom is injected); it is stream-side only and does not "
                                + "affect the static store / recall.",
                        parseSchema(schema), null, null, null),
                (exchange, request) -> {
                    try {
                        Map<String, Object> a = request.arguments();
                        String stream = str(a, "stream");
                        String sub = str(a, "subclass");
                        String sup = str(a, "superclass");
                        ensureStream(engine, stream).pushTriple(sub, RDFS_SUBCLASSOF, sup);
                        return text("declared <" + sub + "> rdfs:subClassOf <" + sup + "> (injected via stream '"
                                + stream + "'; the reasoner is engine-wide, so this applies to all streams). "
                                + "Instances pushed as (x, a, <" + sub + ">) are now also inferred as <" + sup
                                + "> for stream queries.");
                    } catch (Exception e) {
                        return error("define_subclass failed: " + e.getMessage());
                    }
                });
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Create a stream once per name (createStream twice would throw), reusing it thereafter. */
    private static DataStream ensureStream(CQELSEngine engine, String name) {
        return STREAMS.computeIfAbsent(name, engine::createStream);
    }

    /** Run a SPARQL SELECT over the static store and format up to MAX_ROWS rows as text. */
    private static String runSparql(CQELSEngine engine, String sparql) {
        StringBuilder out = new StringBuilder();
        int rows = 0;
        boolean truncated = false;
        try (RepositoryConnection conn = engine.getRepository().getConnection();
             TupleQueryResult result = conn.prepareTupleQuery(QueryLanguage.SPARQL, sparql).evaluate()) {
            List<String> vars = result.getBindingNames();
            while (result.hasNext()) {
                if (rows >= MAX_ROWS) {
                    truncated = true;
                    break;
                }
                BindingSet bs = result.next();
                StringBuilder row = new StringBuilder();
                for (String v : vars) {
                    Value val = bs.getValue(v);
                    if (val != null) {
                        row.append(v).append('=').append(val.stringValue()).append("  ");
                    }
                }
                out.append(row.toString().trim()).append('\n');
                rows++;
            }
        }
        String body = rows == 0 ? "(no results)" : out.toString().trim();
        if (truncated) {
            body += "\n… (truncated at " + MAX_ROWS + " rows)";
        }
        return body;
    }

    /** Append a row to a query's bounded buffer, evicting + counting the oldest when full. */
    private static void boundedOffer(BlockingQueue<String> buffer, AtomicLong dropped, String row) {
        while (!buffer.offer(row)) {
            buffer.poll();
            dropped.incrementAndGet();
        }
    }

    /**
     * Push a triple with a sensibly-typed object so numeric filters/aggregates work:
     * an {@code http(s)} value becomes an IRI; an integer/decimal becomes a numeric literal;
     * anything else a plain string literal.
     */
    private static void pushTyped(DataStream stream, String s, String p, String o) {
        if (o.startsWith("http://") || o.startsWith("https://")) {
            stream.pushTriple(s, p, o);                 // IRI object
            return;
        }
        try {
            stream.push(s, p, Long.parseLong(o));       // xsd:integer
            return;
        } catch (NumberFormatException ignored) {
            // not an integer
        }
        try {
            stream.push(s, p, Double.parseDouble(o));   // xsd:double
            return;
        } catch (NumberFormatException ignored) {
            // not a number
        }
        stream.push(s, p, o);                           // string literal
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing argument: " + key);
        }
        return v.toString();
    }

    /** IRIs start with http(s)://; everything else is stored as a plain literal. */
    private static Value asValue(String o) {
        return (o.startsWith("http://") || o.startsWith("https://"))
                ? VF.createIRI(o) : VF.createLiteral(o);
    }

    @SuppressWarnings("unchecked")
    private static McpSchema.JsonSchema parseSchema(String json) {
        try {
            Map<String, Object> m = JSON.readValue(json, Map.class);
            return new McpSchema.JsonSchema(
                    (String) m.get("type"),
                    (Map<String, Object>) m.get("properties"),
                    (List<String>) m.get("required"),
                    null, null, null);
        } catch (Exception e) {
            throw new IllegalArgumentException("bad tool schema: " + e.getMessage(), e);
        }
    }

    private static McpSchema.CallToolResult text(String s) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(s)), false, null, null);
    }

    private static McpSchema.CallToolResult error(String s) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(s)), true, null, null);
    }

    private CqelsMemoryMcpServer() { }
}
