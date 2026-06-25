package org.cqels.examples.mcp;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
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
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
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

    /** Matches the stream name(s) in `FROM STREAM <name> [...]` so we can create them on demand. */
    private static final Pattern FROM_STREAM = Pattern.compile("FROM\\s+STREAM\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
    /** Streams created so far (created once per name; createStream twice would throw). */
    private static final Map<String, DataStream> STREAMS = new ConcurrentHashMap<>();
    /** Per-registered-query buffer of emitted result rows, drained by poll_results. */
    private static final Map<String, Queue<String>> BUFFERS = new ConcurrentHashMap<>();

    public static void main(String[] args) throws InterruptedException {
        CQELSEngine engine = CQELSEngine.builder()
                .id("cqels-mcp-memory")
                .withMemoryStore()
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
                        storeFactTool(engine),          // static memory
                        queryTool(engine),
                        pushEventTool(engine),          // streaming
                        registerStreamQueryTool(engine),
                        pollResultsTool())
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
                        String sparql = str(request.arguments(), "sparql");
                        StringBuilder out = new StringBuilder();
                        int rows = 0;
                        boolean truncated = false;
                        try (RepositoryConnection conn = engine.getRepository().getConnection();
                             TupleQueryResult result =
                                     conn.prepareTupleQuery(QueryLanguage.SPARQL, sparql).evaluate()) {
                            List<String> vars = result.getBindingNames();
                            while (result.hasNext()) {
                                if (rows >= MAX_ROWS) {       // cap the response size
                                    truncated = true;
                                    break;
                                }
                                BindingSet bs = result.next();
                                StringBuilder row = new StringBuilder();
                                for (String v : vars) {
                                    Value val = bs.getValue(v);
                                    if (val != null) {
                                        row.append(v).append('=')
                                           .append(val.stringValue()).append("  ");
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
                        return text(body);
                    } catch (Exception e) {
                        return error("query failed: " + e.getMessage());
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
                        Queue<String> buffer = new ConcurrentLinkedQueue<>();
                        String queryId = engine.registerCqelsQuery(query, row -> buffer.add(String.valueOf(row)));
                        BUFFERS.put(queryId, buffer);
                        return text("registered continuous query, id='" + queryId
                                + "'. Push events to its stream, then call poll_results with this id.");
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
                        Queue<String> buffer = BUFFERS.get(queryId);
                        if (buffer == null) {
                            return error("unknown query id: '" + queryId
                                    + "' (use the id returned by register_stream_query)");
                        }
                        StringBuilder out = new StringBuilder();
                        int rows = 0;
                        boolean truncated = false;
                        String row;
                        while ((row = buffer.poll()) != null) {
                            if (rows >= MAX_ROWS) {
                                truncated = true;
                                break;
                            }
                            out.append(row).append('\n');
                            rows++;
                        }
                        String body = rows == 0 ? "(no new results)" : out.toString().trim();
                        if (truncated) {
                            body += "\n… (truncated at " + MAX_ROWS + " rows; poll again for more)";
                        }
                        return text(body);
                    } catch (Exception e) {
                        return error("poll_results failed: " + e.getMessage());
                    }
                });
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Create a stream once per name (createStream twice would throw), reusing it thereafter. */
    private static DataStream ensureStream(CQELSEngine engine, String name) {
        return STREAMS.computeIfAbsent(name, engine::createStream);
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
