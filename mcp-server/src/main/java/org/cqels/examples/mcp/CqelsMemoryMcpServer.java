package org.cqels.examples.mcp;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.cqels.engine.CQELSEngine;
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
import java.util.concurrent.CountDownLatch;

/**
 * A minimal Model Context Protocol (MCP) server backed by a CQELS engine.
 *
 * <p>It exposes a CQELS in-memory RDF store as two AI-callable tools over stdio:
 * <ul>
 *   <li>{@code store_fact} — add a {@code (subject, predicate, object)} triple to memory;</li>
 *   <li>{@code query} — run a SPARQL query over everything stored so far.</li>
 * </ul>
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

    public static void main(String[] args) throws InterruptedException {
        CQELSEngine engine = CQELSEngine.builder()
                .id("cqels-mcp-memory")
                .withMemoryStore()
                .build();
        engine.start();

        // stdio transport with the Jackson-3 mapper the SDK requires.
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                new JacksonMcpJsonMapper(JsonMapper.builder().build()));

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("cqels-memory", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .build();

        server.addTool(storeFactTool(engine));
        server.addTool(queryTool(engine));

        // An MCP stdio server runs until the client terminates the process (SIGTERM/SIGINT).
        // The shutdown hook is the cleanup path: drain the server (closeGracefully() blocks
        // unbounded in SDK 1.0.0, so bound it) and close the engine; then release main.
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

        // Park the main thread until shutdown so the JVM stays alive serving requests.
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

    // ---- helpers ---------------------------------------------------------------------------

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
