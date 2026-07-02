package org.cqels.examples.mcp;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.engine.cep.CepExecutionOptions;
import org.cqels.reasoning.config.RDFSProfile;
import org.cqels.reasoning.engine.ReactiveReteAdapter;
import org.eclipse.rdf4j.model.IRI;
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
 * <em>and</em> the streaming engine that the {@code examples/} demonstrate. The tools are
 * vocabulary-agnostic, so an MCP client can drive the very same <strong>electric-vehicle fleet /
 * V2G</strong> world the examples run: {@code push_event} a VSS {@code sosa:Observation}
 * (speed / battery SoC), {@code register_stream_query} a per-vehicle speeding monitor,
 * {@code detect_sequence} a road-rage CEP pattern, {@code define_subclass} the
 * {@code ex:ElectricBus ⊑ vsso:Vehicle} taxonomy — see {@code README.md} for the walkthrough:
 * <ul>
 *   <li><b>Memory (static):</b> {@code store_fact} adds a {@code (subject, predicate, object)}
 *       triple; {@code query} runs a SPARQL SELECT over everything stored.</li>
 *   <li><b>Streaming (continuous):</b> {@code push_event} ingests a triple into a named
 *       stream; {@code register_stream_query} registers a continuous CQELS-QL query (windows,
 *       aggregates, CEP — the same shapes as the examples) and buffers its emitted rows;
 *       {@code poll_results} drains the rows a query has emitted so far.</li>
 *   <li><b>Agent-memory patterns (alpha.7):</b> {@code record_event}/{@code recall_episodes}
 *       (episodic — timestamped "what happened when"), {@code save_procedure}/
 *       {@code list_procedures}/{@code run_procedure} (procedural — named stored queries), and
 *       {@code assemble_context} (working memory — one facts+episodes+procedures bundle per
 *       entity). These demonstrate the memory-type patterns of the CQELS engine repo's
 *       {@code cqels-mcp} server on the published engine API; the full production surface
 *       (19 tools) ships there.</li>
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
    /** Matches the `REGISTER QUERY <name>` id so we can reject a colliding registration up front. */
    private static final Pattern REGISTER_NAME = Pattern.compile("REGISTER\\s+QUERY\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
    /** Matches `FILTER(SEQ(...))` so register_stream_query can route ordered CEP to detect_sequence. */
    private static final Pattern SEQ_FILTER = Pattern.compile("FILTER\\s*\\(\\s*SEQ", Pattern.CASE_INSENSITIVE);
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

    // ---- agent-memory demo vocabulary (alpha.7 patterns — see README "Agent memory types") ----
    // Marker predicates for the episodic/procedural facts this server writes into the same
    // static store store_fact/query/recall use; the entity/event-type VALUES stay caller-supplied
    // (vocabulary-agnostic), so the memory tools drive any domain, V2G included.
    private static final String MEM = "https://example.org/memory#";
    private static final String EPISODE_CLASS = MEM + "Episode";
    private static final String EPISODE_EVENT_TYPE = MEM + "eventType";
    private static final String EPISODE_ABOUT = MEM + "about";
    private static final String EPISODE_AT_MILLIS = MEM + "atMillis";
    private static final String EPISODE_DATA = MEM + "data";
    private static final String PROCEDURE_CLASS = MEM + "Procedure";
    private static final String PROCEDURE_NAME = MEM + "name";
    private static final String PROCEDURE_DESCRIPTION = MEM + "description";
    private static final String PROCEDURE_SPARQL = MEM + "sparql";
    private static final String EPISODE_NS = "https://example.org/memory/episode/";
    private static final String PROCEDURE_NS = "https://example.org/memory/procedure/";
    /** Generates episode IRIs for record_event. */
    private static final AtomicLong EPISODE_SEQ = new AtomicLong();
    /** Procedural memory: name → saved procedure. Facts describing each procedure are also
     *  written to the store, so `query` can find them; execution goes through the registry. */
    private static final Map<String, Procedure> PROCEDURES = new ConcurrentHashMap<>();

    /** A saved procedure: an optional description plus the SPARQL SELECT it runs. */
    private record Procedure(String description, String sparql) { }

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
                        recordEventTool(engine),        // episodic memory (alpha.7 pattern)
                        recallEpisodesTool(engine),
                        saveProcedureTool(engine),      // procedural memory (alpha.7 pattern)
                        listProceduresTool(),
                        runProcedureTool(engine),
                        assembleContextTool(engine),    // working memory / GraphRAG bundle (alpha.7 pattern)
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

    // ---- tool: record_event (episodic memory, alpha.7 pattern) ------------------------------

    private static McpServerFeatures.SyncToolSpecification recordEventTool(CQELSEngine engine) {
        String schema = """
                {
                  "type": "object",
                  "required": ["event_type", "entity"],
                  "properties": {
                    "event_type":   {"type": "string", "description": "Event-type IRI (e.g. https://covesa.global/fleet#ChargeStartEvent) or a plain label"},
                    "entity":       {"type": "string", "description": "IRI of the entity the episode is about (e.g. a vehicle)"},
                    "data":         {"type": "string", "description": "Optional free-form payload stored with the episode"},
                    "timestamp_ms": {"type": "integer", "description": "Optional event time in epoch millis (default: now)"}
                  }
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("record_event", null,
                        "Episodic memory: record a timestamped episode — what happened, to which entity, "
                                + "when. Recall it later with recall_episodes (time-filtered, newest first).",
                        parseSchema(schema), null, null, null),
                (exchange, request) -> {
                    try {
                        Map<String, Object> a = request.arguments();
                        String eventType = str(a, "event_type");
                        String entity = str(a, "entity");
                        Object data = a.get("data");
                        Long tsArg = optionalLong(a, "timestamp_ms");
                        long at = tsArg == null ? System.currentTimeMillis() : tsArg;
                        String episode = EPISODE_NS + EPISODE_SEQ.incrementAndGet();
                        try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                            conn.add(VF.createIRI(episode), VF.createIRI(RDF_TYPE), VF.createIRI(EPISODE_CLASS));
                            conn.add(VF.createIRI(episode), VF.createIRI(EPISODE_EVENT_TYPE), asValue(eventType));
                            conn.add(VF.createIRI(episode), VF.createIRI(EPISODE_ABOUT), asValue(entity));
                            conn.add(VF.createIRI(episode), VF.createIRI(EPISODE_AT_MILLIS), VF.createLiteral(at));
                            if (data != null) {
                                conn.add(VF.createIRI(episode), VF.createIRI(EPISODE_DATA),
                                        VF.createLiteral(data.toString()));
                            }
                        }
                        return text("recorded episode <" + episode + ">: " + eventType + " about "
                                + entity + " at " + at + " ms");
                    } catch (Exception e) {
                        return error("record_event failed: " + e.getMessage());
                    }
                });
    }

    // ---- tool: recall_episodes (episodic recall, alpha.7 pattern) ---------------------------

    private static McpServerFeatures.SyncToolSpecification recallEpisodesTool(CQELSEngine engine) {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "entity":     {"type": "string", "description": "Only episodes about this entity"},
                    "event_type": {"type": "string", "description": "Only episodes of this event type"},
                    "since_ms":   {"type": "integer", "description": "Only episodes at or after this epoch-millis time (inclusive)"},
                    "until_ms":   {"type": "integer", "description": "Only episodes before this epoch-millis time (exclusive — half-open [since, until))"},
                    "limit":      {"type": "integer", "description": "Max episodes to return (default 20, max 100)"}
                  }
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("recall_episodes", null,
                        "Episodic memory: recall recorded episodes, newest first, optionally filtered by "
                                + "entity, event type, and a [since_ms, until_ms) time range.",
                        parseSchema(schema), null, null, null),
                (exchange, request) -> {
                    try {
                        Map<String, Object> a = request.arguments();
                        Object entity = a.get("entity");
                        Object eventType = a.get("event_type");
                        Long since = optionalLong(a, "since_ms");
                        Long until = optionalLong(a, "until_ms");
                        int limit = intInRange(a, "limit", 20, 1, MAX_ROWS);
                        return text(runSparql(engine, episodesSparql(
                                entity == null ? null : entity.toString(),
                                eventType == null ? null : eventType.toString(),
                                since, until, limit)));
                    } catch (Exception e) {
                        return error("recall_episodes failed: " + e.getMessage());
                    }
                });
    }

    // ---- tool: save_procedure (procedural memory, alpha.7 pattern) --------------------------

    private static McpServerFeatures.SyncToolSpecification saveProcedureTool(CQELSEngine engine) {
        String schema = """
                {
                  "type": "object",
                  "required": ["name", "sparql"],
                  "properties": {
                    "name":        {"type": "string", "description": "Procedure name (simple identifier: letters, digits, _ or -)"},
                    "description": {"type": "string", "description": "Optional: what the procedure answers"},
                    "sparql":      {"type": "string", "description": "The SPARQL SELECT to run (same path as the query tool)"}
                  }
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("save_procedure", null,
                        "Procedural memory: save a named SPARQL SELECT so it can be re-run by name with "
                                + "run_procedure. Facts describing the procedure are stored too, so query/"
                                + "assemble_context can find it. Saving an existing name updates it.",
                        parseSchema(schema), null, null, null),
                (exchange, request) -> {
                    try {
                        Map<String, Object> a = request.arguments();
                        String name = str(a, "name");
                        String sparql = str(a, "sparql");
                        Object desc = a.get("description");
                        if (!name.matches("[A-Za-z_][A-Za-z0-9_-]*")) {
                            return error("'name' must be a simple identifier matching "
                                    + "[A-Za-z_][A-Za-z0-9_-]* — got: " + name);
                        }
                        String description = desc == null ? null : desc.toString();
                        Procedure previous = PROCEDURES.put(name, new Procedure(description, sparql));
                        IRI proc = VF.createIRI(PROCEDURE_NS + name);
                        try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                            conn.remove(proc, null, null);   // idempotent re-save: replace the old facts
                            conn.add(proc, VF.createIRI(RDF_TYPE), VF.createIRI(PROCEDURE_CLASS));
                            conn.add(proc, VF.createIRI(PROCEDURE_NAME), VF.createLiteral(name));
                            if (description != null) {
                                conn.add(proc, VF.createIRI(PROCEDURE_DESCRIPTION), VF.createLiteral(description));
                            }
                            conn.add(proc, VF.createIRI(PROCEDURE_SPARQL), VF.createLiteral(sparql));
                        }
                        return text((previous == null ? "saved" : "updated") + " procedure '" + name
                                + "' — run it with run_procedure, browse with list_procedures.");
                    } catch (Exception e) {
                        return error("save_procedure failed: " + e.getMessage());
                    }
                });
    }

    // ---- tool: list_procedures ---------------------------------------------------------------

    private static McpServerFeatures.SyncToolSpecification listProceduresTool() {
        String schema = """
                {
                  "type": "object",
                  "properties": {}
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("list_procedures", null,
                        "Procedural memory: list the saved procedures (name + description).",
                        parseSchema(schema), null, null, null),
                (exchange, request) -> {
                    try {
                        if (PROCEDURES.isEmpty()) {
                            return text("(no procedures saved)");
                        }
                        StringBuilder out = new StringBuilder();
                        PROCEDURES.entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .forEach(e -> out.append(e.getKey()).append(" — ")
                                        .append(e.getValue().description() == null
                                                ? "(no description)" : e.getValue().description())
                                        .append('\n'));
                        return text(out.toString().trim());
                    } catch (Exception e) {
                        return error("list_procedures failed: " + e.getMessage());
                    }
                });
    }

    // ---- tool: run_procedure -----------------------------------------------------------------

    private static McpServerFeatures.SyncToolSpecification runProcedureTool(CQELSEngine engine) {
        String schema = """
                {
                  "type": "object",
                  "required": ["name"],
                  "properties": {
                    "name": {"type": "string", "description": "Name of a procedure saved with save_procedure"}
                  }
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("run_procedure", null,
                        "Procedural memory: run a saved procedure by name — executes its SPARQL SELECT "
                                + "through the same path as the query tool and returns the rows.",
                        parseSchema(schema), null, null, null),
                (exchange, request) -> {
                    try {
                        String name = str(request.arguments(), "name");
                        Procedure proc = PROCEDURES.get(name);
                        if (proc == null) {
                            return error("no procedure named '" + name + "'"
                                    + (PROCEDURES.isEmpty() ? " (none saved yet)"
                                    : " — saved: " + new java.util.TreeSet<>(PROCEDURES.keySet())));
                        }
                        return text(runSparql(engine, proc.sparql()));
                    } catch (Exception e) {
                        return error("run_procedure failed: " + e.getMessage());
                    }
                });
    }

    // ---- tool: assemble_context (working memory / GraphRAG bundle, alpha.7 pattern) ----------

    private static McpServerFeatures.SyncToolSpecification assembleContextTool(CQELSEngine engine) {
        String schema = """
                {
                  "type": "object",
                  "required": ["entity"],
                  "properties": {
                    "entity": {"type": "string", "description": "IRI of the entity to assemble context for"},
                    "limit":  {"type": "integer", "description": "Max recent episodes to include (default 5, max 100)"}
                  }
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("assemble_context", null,
                        "Working memory: assemble one compact context bundle for an entity — its direct "
                                + "facts, its most recent episodes, and saved procedures that mention it — "
                                + "ready to inject into an agent prompt.",
                        parseSchema(schema), null, null, null),
                (exchange, request) -> {
                    try {
                        Map<String, Object> a = request.arguments();
                        String entity = str(a, "entity");
                        if (!entity.startsWith("http://") && !entity.startsWith("https://")) {
                            return error("'entity' must be an IRI (http…) — got: " + entity);
                        }
                        int limit = intInRange(a, "limit", 5, 1, MAX_ROWS);
                        StringBuilder ctx = new StringBuilder();
                        ctx.append("=== context: ").append(entity).append(" ===\n");
                        ctx.append("-- facts --\n");
                        ctx.append(runSparql(engine, "SELECT ?predicate ?object WHERE { "
                                + sparqlTerm(entity) + " ?predicate ?object }")).append('\n');
                        ctx.append("-- recent episodes (newest first) --\n");
                        ctx.append(runSparql(engine, episodesSparql(entity, null, null, null, limit)))
                                .append('\n');
                        ctx.append("-- procedures mentioning this entity --\n");
                        String localName = entity.substring(
                                Math.max(entity.lastIndexOf('/'), entity.lastIndexOf('#')) + 1);
                        StringBuilder procs = new StringBuilder();
                        PROCEDURES.entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .filter(e -> mentions(e.getValue(), entity)
                                        || (!localName.isEmpty() && mentions(e.getValue(), localName)))
                                .forEach(e -> procs.append(e.getKey()).append(" — ")
                                        .append(e.getValue().description() == null
                                                ? "(no description)" : e.getValue().description())
                                        .append('\n'));
                        ctx.append(procs.isEmpty() ? "(none)" : procs.toString().trim());
                        return text(ctx.toString());
                    } catch (Exception e) {
                        return error("assemble_context failed: " + e.getMessage());
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
                    "object":    {"type": "string", "description": "Object: an IRI (http…) or a literal value"},
                    "timestamp_ms": {"type": "integer", "description": "Optional explicit EVENT-TIME timestamp in ms (default: arrival time). Needed to demonstrate out-of-order arrival for event_time detect_sequence."}
                  }
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("push_event", null,
                        "Push one RDF triple as an event into a named CQELS stream (continuous queries "
                                + "registered on that stream will see it). Pass timestamp_ms to stamp an "
                                + "explicit event time (e.g. to replay out-of-order data).",
                        parseSchema(schema), null, null, null),
                (exchange, request) -> {
                    try {
                        Map<String, Object> a = request.arguments();
                        String streamName = str(a, "stream");
                        String s = str(a, "subject");
                        String p = str(a, "predicate");
                        String o = str(a, "object");
                        Long timestampMs = optionalLong(a, "timestamp_ms");
                        if (p.equals("a") || p.equals("rdf:type")) {  // convenience: the rdf:type shorthand
                            p = RDF_TYPE;
                        }
                        pushTyped(ensureStream(engine, streamName), s, p, o, timestampMs);
                        return text("pushed to stream '" + streamName + "': <" + s + "> <" + p + "> " + o
                                + (timestampMs == null ? "" : " @ " + timestampMs + " ms"));
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
                        "Register a continuous CQELS-QL query (windows + aggregates). Returns a query "
                                + "id; emitted rows are buffered — read them with poll_results. For ordered "
                                + "event sequences (FILTER(SEQ(...))) use detect_sequence instead.",
                        parseSchema(schema), null, null, null),
                (exchange, request) -> {
                    try {
                        String query = str(request.arguments(), "query");
                        // Ordered CEP must go through detect_sequence: registerCqelsQuery treats
                        // FILTER(SEQ(...)) as an UNORDERED conjunction, so reject it here rather than
                        // silently matching the steps out of order. Strip line comments first (a `#`
                        // preceded by whitespace, or `--`) so they can't hide the SEQ from the guard;
                        // an IRI's `#` (e.g. vss#Speed) has no leading space so it is left intact.
                        String guardText = query.replaceAll("\\s#[^\\n]*", " ").replaceAll("--[^\\n]*", " ");
                        if (SEQ_FILTER.matcher(guardText).find()) {
                            return error("this query uses FILTER(SEQ(...)) — register ordered event "
                                    + "sequences with detect_sequence; register_stream_query runs windows "
                                    + "+ aggregates only and would match SEQ steps out of order.");
                        }
                        // Ensure every stream the query references exists before registering.
                        Matcher m = FROM_STREAM.matcher(query);
                        while (m.find()) {
                            ensureStream(engine, m.group(1));
                        }
                        // Reject a colliding id BEFORE registering, so we never create a duplicate
                        // engine registration that would then be left orphaned.
                        Matcher nm = REGISTER_NAME.matcher(query);
                        if (nm.find() && BUFFERS.containsKey(nm.group(1))) {
                            return error("a query with id '" + nm.group(1) + "' is already registered; "
                                    + "use a different REGISTER QUERY name, or unregister it first.");
                        }
                        BlockingQueue<String> buffer = new LinkedBlockingQueue<>(MAX_BUFFER);
                        AtomicLong dropped = new AtomicLong();
                        String queryId = engine.registerCqelsQuery(query,
                                row -> boundedOffer(buffer, dropped, String.valueOf(row)));
                        if (BUFFERS.putIfAbsent(queryId, buffer) != null) {  // racy backstop: undo our reg
                            engine.unregisterQuery(queryId);
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
                    "withinSeconds": {"type": "integer", "description": "Time window for the whole sequence (default 30)"},
                    "event_time":  {"type": "boolean", "description": "Match the sequence in EVENT time (timestamp order) instead of arrival order (default false). Out-of-order arrivals are reordered before matching."},
                    "lateness_ms": {"type": "integer", "description": "Event-time lateness budget in ms — how far behind the max seen timestamp an event may arrive and still be reordered. REQUIRED when event_time=true: the engine fails fast without a budget (a silent 0 would drop the very out-of-order events the mode exists to reorder)."}
                  }
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("detect_sequence", null,
                        "Watch a stream for an ordered sequence of event types (a CEP pattern) — builds and "
                                + "registers the FILTER(SEQ(...)) query for you. Returns a query id; push events "
                                + "as (eventIri, a, typeIri) via push_event, then read matches with poll_results. "
                                + "By default steps match in ARRIVAL order; set event_time=true (+ lateness_ms) "
                                + "to match in event-timestamp order even when events arrive out of order.",
                        parseSchema(schema), null, null, null),
                (exchange, request) -> {
                    try {
                        Map<String, Object> a = request.arguments();
                        String stream = str(a, "stream");
                        Object stepsObj = a.get("steps");
                        if (!(stepsObj instanceof List<?> steps) || steps.size() < 2) {
                            return error("'steps' must be an array of at least 2 event-type IRIs");
                        }
                        // Validate client-supplied values before interpolating them into CQELS-QL below,
                        // so they cannot inject query structure or yield opaque parse errors.
                        if (!stream.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                            return error("'stream' must be a simple identifier matching [A-Za-z_][A-Za-z0-9_]* — got: " + stream);
                        }
                        for (Object step : steps) {
                            String iri = String.valueOf(step);
                            if (iri.isEmpty() || iri.matches(".*[\\s<>\"{}|^`\\\\].*")) {
                                return error("each step must be a valid absolute IRI (no whitespace or <>\"{}|^`\\ chars) — got: " + iri);
                            }
                        }
                        Object wObj = a.get("withinSeconds");
                        long within = 30L;
                        if (wObj != null) {
                            if (!(wObj instanceof Number n) || n.doubleValue() != Math.floor(n.doubleValue()) || n.longValue() <= 0) {
                                return error("'withinSeconds' must be a positive integer");
                            }
                            within = n.longValue();
                        }
                        // Event-time mode (#429 F3, alpha.7): reorder out-of-order arrivals by their
                        // event timestamps before the sequence matcher. The lateness budget is
                        // deliberately NOT defaulted here — with lateness_ms absent the engine fails
                        // fast at registration (its message is surfaced below), because a silent
                        // 0ms budget would drop the very out-of-order events the mode reorders.
                        boolean eventTime = Boolean.TRUE.equals(a.get("event_time"));
                        Long latenessMs = optionalLong(a, "lateness_ms");
                        if (latenessMs != null && latenessMs < 0) {
                            return error("'lateness_ms' must be >= 0");
                        }
                        if (!eventTime && latenessMs != null) {
                            return error("'lateness_ms' only applies with event_time=true");
                        }
                        CepExecutionOptions options = !eventTime
                                ? CepExecutionOptions.arrivalOrder()
                                : latenessMs == null
                                        ? CepExecutionOptions.eventTime()               // engine fails fast
                                        : CepExecutionOptions.eventTime(Duration.ofMillis(latenessMs));
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
                        final String qid = name;
                        String query = "REGISTER QUERY " + qid + " AS SELECT ?e1 FROM STREAM " + stream
                                + " [RANGE " + within + "s] WHERE { " + where + "FILTER(SEQ(" + seq + ")) }";
                        try {
                            // registerCepQuery enforces event ORDER; registerCqelsQuery treats FILTER(SEQ)
                            // as an unordered conjunction (would match out-of-order), so it is NOT used here.
                            // Look the buffer up by id so unregister_stream_query (which removes it) cleanly
                            // stops delivery — note the CEP matcher itself can't be individually stopped in
                            // this release (unregisterQuery does not cover CEP queries).
                            final BlockingQueue<String> buf = buffer;
                            final AtomicLong drp = dropped;
                            engine.registerCepQuery(query, options, match -> {
                                // Deliver only while this id still maps to OUR buffer. After
                                // unregister (or if the id is later reused) the identity check
                                // fails, so this un-stoppable CEP matcher can never write into a
                                // different query's buffer.
                                if (BUFFERS.get(qid) == buf) {
                                    boundedOffer(buf, drp, String.valueOf(match));
                                }
                            });
                        } catch (Exception ex) {
                            BUFFERS.remove(qid);            // don't leave a phantom id on failure
                            DROPPED.remove(qid);
                            return error("detect_sequence failed: " + ex.getMessage());
                        }
                        return text("watching '" + stream + "' for the ORDERED sequence " + steps + " within "
                                + within + "s in " + (eventTime
                                        ? "EVENT-TIME order (lateness budget " + latenessMs + " ms)"
                                        : "arrival order")
                                + " — query id='" + qid + "'. Push events with predicate \"a\" and "
                                + "object the type IRI via push_event, then poll_results('" + qid + "'). "
                                + "unregister_stream_query frees the buffer + stops delivery (the CEP matcher "
                                + "itself keeps running — this alpha has no per-CEP-query stop).");
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
                    "stream":     {"type": "string", "description": "Stream used to inject the axiom (the rule applies engine-wide)"},
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
     * anything else a plain string literal. A non-null {@code timestampMs} stamps the element
     * with that explicit EVENT time (out-of-order replay for event_time detect_sequence);
     * null keeps the default arrival-time stamping.
     */
    private static void pushTyped(DataStream stream, String s, String p, String o, Long timestampMs) {
        if (timestampMs == null) {
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
            return;
        }
        IRI subject = VF.createIRI(s);
        IRI predicate = VF.createIRI(p);
        long ts = timestampMs;
        if (o.startsWith("http://") || o.startsWith("https://")) {
            stream.push(subject, predicate, VF.createIRI(o), ts);   // IRI object
            return;
        }
        try {
            stream.push(subject, predicate, Long.parseLong(o), ts); // xsd:integer
            return;
        } catch (NumberFormatException ignored) {
            // not an integer
        }
        try {
            stream.push(subject, predicate, Double.parseDouble(o), ts); // xsd:double
            return;
        } catch (NumberFormatException ignored) {
            // not a number
        }
        stream.push(subject, predicate, o, ts);                     // string literal
    }

    /**
     * SPARQL for episodes newest-first: optional entity / event-type equality filters and a
     * half-open {@code [sinceMs, untilMs)} time range, LIMITed. Shared by recall_episodes and
     * assemble_context.
     */
    private static String episodesSparql(String entity, String eventType,
                                         Long sinceMs, Long untilMs, int limit) {
        StringBuilder q = new StringBuilder();
        q.append("SELECT ?episode ?type ?entity ?atMillis ?data WHERE { ")
                .append("?episode <").append(RDF_TYPE).append("> <").append(EPISODE_CLASS).append("> ; ")
                .append("<").append(EPISODE_EVENT_TYPE).append("> ?type ; ")
                .append("<").append(EPISODE_ABOUT).append("> ?entity ; ")
                .append("<").append(EPISODE_AT_MILLIS).append("> ?atMillis . ")
                .append("OPTIONAL { ?episode <").append(EPISODE_DATA).append("> ?data } ");
        if (entity != null) {
            q.append("FILTER(?entity = ").append(sparqlTerm(entity)).append(") ");
        }
        if (eventType != null) {
            q.append("FILTER(?type = ").append(sparqlTerm(eventType)).append(") ");
        }
        if (sinceMs != null) {
            q.append("FILTER(?atMillis >= ").append(sinceMs).append(") ");
        }
        if (untilMs != null) {
            q.append("FILTER(?atMillis < ").append(untilMs).append(") ");
        }
        q.append("} ORDER BY DESC(?atMillis) LIMIT ").append(limit);
        return q.toString();
    }

    /** True when the procedure's SPARQL or description mentions the needle (working-memory match). */
    private static boolean mentions(Procedure proc, String needle) {
        return proc.sparql().contains(needle)
                || (proc.description() != null && proc.description().contains(needle));
    }

    /**
     * Format a caller value for interpolation into SPARQL, mirroring {@link #asValue}: an
     * {@code http(s)} value becomes a bracketed IRI (rejected if it can't be one), anything else
     * an escaped string literal — so recall filters compare equal to what record_event stored.
     */
    private static String sparqlTerm(String v) {
        if (v.startsWith("http://") || v.startsWith("https://")) {
            if (v.matches(".*[\\s<>\"{}|^`\\\\].*")) {
                throw new IllegalArgumentException("not a valid IRI: " + v);
            }
            return "<" + v + ">";
        }
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Optional integral argument: {@code null} when absent; rejects non-integral values. */
    private static Long optionalLong(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof Number n) || n.doubleValue() != Math.floor(n.doubleValue())) {
            throw new IllegalArgumentException("'" + key + "' must be an integer");
        }
        return n.longValue();
    }

    /** Optional bounded int argument with a default; rejects values outside {@code [min, max]}. */
    private static int intInRange(Map<String, Object> args, String key, int def, int min, int max) {
        Long v = optionalLong(args, key);
        if (v == null) {
            return def;
        }
        if (v < min || v > max) {
            throw new IllegalArgumentException("'" + key + "' must be between " + min + " and " + max);
        }
        return v.intValue();
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
