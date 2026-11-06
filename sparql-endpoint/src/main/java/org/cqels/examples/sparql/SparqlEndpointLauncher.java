package org.cqels.examples.sparql;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.cqels.engine.CQELSEngine;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Runs {@link SparqlProtocolServlet} on Jetty 12 over an embedded CQELS engine, configured
 * from {@code CQELS_SPARQL_*} environment variables (the naming follows the MCP server's
 * {@code CQELS_MCP_HTTP_*}):
 *
 * <pre>
 *   CQELS_SPARQL_HOST                     127.0.0.1   bind address; see the refusal below
 *   CQELS_SPARQL_PORT                     8089        0 = ephemeral, printed on stdout
 *   CQELS_SPARQL_PATH                     /sparql
 *   CQELS_SPARQL_AUTH_TOKEN               (none)      bearer token
 *   CQELS_SPARQL_ALLOWED_ORIGINS          (none)      comma-separated CORS allow-list, or *
 *   CQELS_SPARQL_MAX_REQUEST_BYTES        1048576
 *   CQELS_SPARQL_MAX_EXECUTION_SECONDS    30          0 = unlimited; best effort, see the servlet
 *   CQELS_SPARQL_MAX_CONCURRENT_QUERIES   16          beyond it: 503 + Retry-After at once
 *   CQELS_SPARQL_MAX_ROWS                 0           0 = unlimited
 *   CQELS_SPARQL_UPDATE_ENABLED           false
 *   CQELS_SPARQL_STORE_PATH               (memory)    NativeStore directory for a durable graph
 *   CQELS_SPARQL_SEED_FLEET               false       seed the examples' V2G fleet world
 *   CQELS_SPARQL_MAX_THREADS              200         Jetty request threads
 *   CQELS_SPARQL_VIRTUAL_THREADS          false       JDK 21+ runtime only; ignored otherwise
 *   CQELS_SPARQL_ALLOW_UNAUTHENTICATED_REMOTE  false  see below
 * </pre>
 *
 * <p><strong>The one refusal.</strong> Binding a non-loopback host with no token is refused at
 * start-up unless {@code CQELS_SPARQL_ALLOW_UNAUTHENTICATED_REMOTE=true}. The graph the engine
 * joins its streams against is not something to expose by accident; with update enabled it is
 * writable. TLS is not terminated here — front the endpoint, as the MCP README says.
 *
 * <p>stdout carries exactly one line, {@code listening on http://host:port/path}, once the
 * engine has started and Jetty is bound; scripts and the gate parse it (port 0 in particular).
 * Everything else goes to stderr through SLF4J.
 */
public final class SparqlEndpointLauncher {

    private static final Logger log = LoggerFactory.getLogger(SparqlEndpointLauncher.class);

    private SparqlEndpointLauncher() { }

    public static void main(String[] args) throws Exception {
        String host = env("CQELS_SPARQL_HOST", "127.0.0.1");
        int port = Integer.parseInt(env("CQELS_SPARQL_PORT", "8089"));
        EndpointOptions options = EndpointOptions.defaults()
                .withPath(env("CQELS_SPARQL_PATH", EndpointOptions.DEFAULT_PATH))
                .withAuthToken(env("CQELS_SPARQL_AUTH_TOKEN", null))
                .withAllowedOrigins(csv(env("CQELS_SPARQL_ALLOWED_ORIGINS", "")))
                .withMaxRequestBytes(Long.parseLong(env("CQELS_SPARQL_MAX_REQUEST_BYTES",
                        Long.toString(EndpointOptions.DEFAULT_MAX_REQUEST_BYTES))))
                .withMaxExecutionSeconds(Integer.parseInt(env("CQELS_SPARQL_MAX_EXECUTION_SECONDS",
                        Integer.toString(EndpointOptions.DEFAULT_MAX_EXECUTION_SECONDS))))
                .withMaxConcurrentQueries(Integer.parseInt(env("CQELS_SPARQL_MAX_CONCURRENT_QUERIES",
                        Integer.toString(EndpointOptions.DEFAULT_MAX_CONCURRENT_QUERIES))))
                .withMaxRows(Integer.parseInt(env("CQELS_SPARQL_MAX_ROWS", "0")))
                .withUpdateEnabled(Boolean.parseBoolean(env("CQELS_SPARQL_UPDATE_ENABLED", "false")));
        boolean allowRemoteUnauthenticated = Boolean.parseBoolean(env("CQELS_SPARQL_ALLOW_UNAUTHENTICATED_REMOTE", "false"));
        String storePath = env("CQELS_SPARQL_STORE_PATH", null);
        boolean seedFleet = Boolean.parseBoolean(env("CQELS_SPARQL_SEED_FLEET", "false"));
        int maxThreads = Integer.parseInt(env("CQELS_SPARQL_MAX_THREADS", "200"));
        boolean virtualThreads = Boolean.parseBoolean(env("CQELS_SPARQL_VIRTUAL_THREADS", "false"));

        try {
            requireTokenForRemoteBind(host, options.authToken(), allowRemoteUnauthenticated);
        } catch (IllegalStateException e) {
            log.error("refusing to start: {}", e.getMessage());
            System.exit(2);
            return;
        }

        CQELSEngine engine = buildEngine(storePath);
        if (seedFleet) {
            int n = FleetSeed.seed(engine.getRepository());
            log.info("seeded {} fleet facts into <{}>", n, FleetSeed.LONGTERM_GRAPH);
        }
        engine.start();

        Server server = startServer(engine, host, port, options, maxThreads, virtualThreads);
        int bound = ((ServerConnector) server.getConnectors()[0]).getLocalPort();

        // Jetty first (no new requests), then the engine (its repository closes with it).
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stop();
            } catch (Exception e) {
                log.warn("Jetty did not stop cleanly: {}", e.toString());
            }
            engine.close();
        }, "sparql-endpoint-shutdown"));

        System.out.println("listening on http://" + host + ":" + bound + options.path());
        System.out.flush();
        log.info("SPARQL endpoint ready: update {}, token {}, execution limit {} s, {} query slots, max rows {}, store {}",
                options.updateEnabled() ? "enabled" : "disabled", options.authToken() == null ? "none" : "set",
                options.maxExecutionSeconds(), options.maxConcurrentQueries(),
                options.maxRows() == 0 ? "unlimited" : options.maxRows(), storePath == null ? "memory" : storePath);
        server.join();
    }

    /**
     * Builds and starts Jetty with the servlet, the two access filters, and {@code /health} and
     * {@code /ready}. Separate from {@link #main} so tests (and a program embedding this) can
     * start it on port 0 against their own engine.
     */
    public static Server startServer(CQELSEngine engine, String host, int port, EndpointOptions options,
                                     int maxThreads, boolean virtualThreads) throws Exception {
        QueuedThreadPool pool = new QueuedThreadPool(Math.max(maxThreads, 8));
        pool.setName("sparql-endpoint");
        if (virtualThreads) {
            // Jetty 12 can run request handling on virtual threads when the runtime has them.
            // RDF4J evaluation blocks, so that is a real win under many slow clients; the
            // compile target stays 17, hence the runtime probe rather than an API call.
            Executor vt = VirtualThreads.getDefaultVirtualThreadsExecutor();
            if (vt != null) {
                pool.setVirtualThreadsExecutor(vt);
            } else {
                log.warn("CQELS_SPARQL_VIRTUAL_THREADS=true but this runtime has no virtual threads; using platform threads");
            }
        }
        Server server = new Server(pool);
        ServerConnector connector = new ServerConnector(server);
        connector.setHost(host);
        connector.setPort(port);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.setMaxFormContentSize((int) Math.min(options.maxRequestBytes(), Integer.MAX_VALUE));
        if (!options.allowedOrigins().isEmpty()) {
            context.addFilter(new FilterHolder(new AccessFilters.Cors(options.allowedOrigins())),
                    options.path(), EnumSet.of(DispatcherType.REQUEST));
        }
        if (options.authToken() != null) {
            context.addFilter(new FilterHolder(new AccessFilters.BearerToken(options.authToken())),
                    options.path(), EnumSet.of(DispatcherType.REQUEST));
        }
        context.addServlet(new ServletHolder(new SparqlProtocolServlet(engine::getRepository, options)), options.path());
        context.addServlet(new ServletHolder(new TextServlet("ok")), "/health");
        context.addServlet(new ServletHolder(new TextServlet("ready")), "/ready");
        server.setHandler(context);
        server.start();
        return server;
    }

    /** Memory store by default; a NativeStore at {@code storePath} for a graph that survives restarts. */
    static CQELSEngine buildEngine(String storePath) throws IOException {
        CQELSEngine.Builder builder = CQELSEngine.builder().id("sparql-endpoint");
        if (storePath == null || storePath.isBlank()) {
            return builder.withMemoryStore().build();
        }
        Path dir = Files.createDirectories(Path.of(storePath.strip()));
        Repository repo = new SailRepository(new NativeStore(dir.toFile()));
        repo.init();
        return builder.repository(repo).build();
    }

    /** The start-up refusal described in the class comment. */
    static void requireTokenForRemoteBind(String host, String token, boolean allowRemoteUnauthenticated) {
        if (token != null || allowRemoteUnauthenticated || isLoopback(host)) {
            return;
        }
        throw new IllegalStateException("CQELS_SPARQL_HOST=" + host + " is not a loopback address and no "
                + "CQELS_SPARQL_AUTH_TOKEN is set. Set a token, bind 127.0.0.1 behind a proxy, or set "
                + "CQELS_SPARQL_ALLOW_UNAUTHENTICATED_REMOTE=true if that is really intended.");
    }

    private static boolean isLoopback(String host) {
        if (host == null || host.isBlank() || "localhost".equalsIgnoreCase(host)) {
            return true;
        }
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static String env(String name, String dflt) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? dflt : v.strip();
    }

    private static List<String> csv(String s) {
        return s.isBlank() ? List.of() : Arrays.stream(s.split(",")).map(String::strip).filter(x -> !x.isEmpty()).toList();
    }

    /** {@code /health} and {@code /ready}: both answer once Jetty is up, which is after the engine started. */
    static final class TextServlet extends HttpServlet {
        private final String body;

        TextServlet(String body) {
            this.body = body;
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("text/plain; charset=UTF-8");
            resp.getWriter().write(body + "\n");
        }
    }
}
