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
import org.eclipse.rdf4j.model.Statement;
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
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

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
 *   CQELS_SPARQL_ALLOWED_HOSTS            (loopback)  comma-separated Host names, or *; checked when
 *                                                     no token is set (DNS rebinding)
 *   CQELS_SPARQL_ALLOWED_ORIGINS          (none)      comma-separated browser origins, or *; any
 *                                                     other Origin is refused with 403
 *   CQELS_SPARQL_MAX_REQUEST_BYTES        1048576
 *   CQELS_SPARQL_MAX_EXECUTION_SECONDS    30          0 = unlimited; best effort, see the servlet
 *   CQELS_SPARQL_MAX_CONCURRENT_QUERIES   16          beyond it: 503 + Retry-After at once
 *   CQELS_SPARQL_MAX_ROWS                 0           0 = unlimited; also caps CONSTRUCT/DESCRIBE statements
 *   CQELS_SPARQL_MAX_BUFFERED_BYTES       33554432    what a MAX_ROWS buffer may hold, estimated
 *   CQELS_SPARQL_MAX_UPLOAD_SECONDS       30          a POST body must arrive within this; 0 = none
 *   CQELS_SPARQL_UPDATE_ENABLED           false
 *   CQELS_SPARQL_FEDERATION_ENABLED       false       accept SERVICE and LOAD (outbound requests): http(s)
 *                                                     only, within the execution limit
 *   CQELS_SPARQL_METADATA_ENABLED         false       serve the engine's streams and status as the
 *                                                     graph cqels://endpoint/metadata (EngineMetadata)
 *   CQELS_SPARQL_STORE_PATH               (memory)    NativeStore directory for a durable graph
 *   CQELS_SPARQL_SEED_FLEET               false       seed the examples' V2G fleet world
 *   CQELS_SPARQL_MAX_THREADS              200         Jetty request threads; must exceed the query
 *                                                     slots plus the connector's threads plus spares
 *   CQELS_SPARQL_VIRTUAL_THREADS          false       JDK 21+ runtime only; ignored otherwise
 *   CQELS_SPARQL_ALLOW_UNAUTHENTICATED_REMOTE  false  see below
 * </pre>
 *
 * <p><strong>The one refusal.</strong> Binding a non-loopback host with no token is refused at
 * start-up unless {@code CQELS_SPARQL_ALLOW_UNAUTHENTICATED_REMOTE=true} — by {@link #startServer}
 * itself, so a program embedding the endpoint through it gets the same refusal. The graph the engine
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
                .withAllowedHosts(csv(env("CQELS_SPARQL_ALLOWED_HOSTS", "")))
                .withMaxRequestBytes(Long.parseLong(env("CQELS_SPARQL_MAX_REQUEST_BYTES",
                        Long.toString(EndpointOptions.DEFAULT_MAX_REQUEST_BYTES))))
                .withMaxExecutionSeconds(Integer.parseInt(env("CQELS_SPARQL_MAX_EXECUTION_SECONDS",
                        Integer.toString(EndpointOptions.DEFAULT_MAX_EXECUTION_SECONDS))))
                .withMaxConcurrentQueries(Integer.parseInt(env("CQELS_SPARQL_MAX_CONCURRENT_QUERIES",
                        Integer.toString(EndpointOptions.DEFAULT_MAX_CONCURRENT_QUERIES))))
                .withMaxRows(Integer.parseInt(env("CQELS_SPARQL_MAX_ROWS", "0")))
                .withMaxBufferedBytes(Long.parseLong(env("CQELS_SPARQL_MAX_BUFFERED_BYTES",
                        Long.toString(EndpointOptions.DEFAULT_MAX_BUFFERED_BYTES))))
                .withMaxUploadSeconds(Integer.parseInt(env("CQELS_SPARQL_MAX_UPLOAD_SECONDS",
                        Integer.toString(EndpointOptions.DEFAULT_MAX_UPLOAD_SECONDS))))
                .withUpdateEnabled(Boolean.parseBoolean(env("CQELS_SPARQL_UPDATE_ENABLED", "false")))
                .withFederationEnabled(Boolean.parseBoolean(env("CQELS_SPARQL_FEDERATION_ENABLED", "false")))
                .withMetadataEnabled(Boolean.parseBoolean(env("CQELS_SPARQL_METADATA_ENABLED", "false")))
                .withAllowUnauthenticatedRemote(Boolean.parseBoolean(env("CQELS_SPARQL_ALLOW_UNAUTHENTICATED_REMOTE", "false")));
        String storePath = env("CQELS_SPARQL_STORE_PATH", null);
        boolean seedFleet = Boolean.parseBoolean(env("CQELS_SPARQL_SEED_FLEET", "false"));
        int maxThreads = Integer.parseInt(env("CQELS_SPARQL_MAX_THREADS", "200"));
        boolean virtualThreads = Boolean.parseBoolean(env("CQELS_SPARQL_VIRTUAL_THREADS", "false"));

        // Checked here too, not only in startServer, so the refusal exits 2 before an engine
        // (and a NativeStore lock) exists.
        try {
            requireTokenForRemoteBind(host, options.authToken(), options.allowUnauthenticatedRemote());
        } catch (IllegalStateException e) {
            log.error("refusing to start: {}", e.getMessage());
            System.exit(2);
            return;
        }

        // A NativeStore handed to the builder is the caller's to shut down: engine.close() leaves
        // a supplied repository initialized (measured on 2.0.0-alpha.22). The memory store the
        // builder creates itself is closed with the engine.
        Repository store = openNativeStore(storePath);
        CQELSEngine engine = buildEngine(store);
        AtomicReference<Server> serverRef = new AtomicReference<>();
        // Registered before anything can fail, and every failure below exits through
        // System.exit, so the hook also runs when Jetty cannot bind: Jetty first (no new
        // requests), then the engine, then the store it was reading.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Server s = serverRef.get();
            if (s != null) {
                try {
                    s.stop();
                } catch (Exception e) {
                    log.warn("Jetty did not stop cleanly: {}", e.toString());
                }
            }
            engine.close();
            if (store != null) {
                store.shutDown();
            }
        }, "sparql-endpoint-shutdown"));

        Server server;
        try {
            if (seedFleet) {
                int n = FleetSeed.seed(engine.getRepository());
                log.info("seeded {} fleet facts into <{}>", n, FleetSeed.LONGTERM_GRAPH);
            }
            engine.start();
            server = startServer(engine, host, port, options, maxThreads, virtualThreads);
            serverRef.set(server);
        } catch (Exception e) {
            log.error("failed to start: {}", e.toString(), e);
            System.exit(1);
            return;
        }
        int bound = ((ServerConnector) server.getConnectors()[0]).getLocalPort();

        System.out.println("listening on " + listeningUrl(host, bound, options.path()));
        System.out.flush();
        log.info("SPARQL endpoint ready: update {}, federation {}, metadata graph {}, token {}, execution limit {} s, {} query slots, max rows {}, upload limit {} s, store {}",
                options.updateEnabled() ? "enabled" : "disabled", options.federationEnabled() ? "enabled" : "disabled",
                options.metadataEnabled() ? "enabled" : "disabled",
                options.authToken() == null ? "none" : "set",
                options.maxExecutionSeconds(), options.maxConcurrentQueries(),
                options.maxRows() == 0 ? "unlimited" : options.maxRows() + " / " + options.maxBufferedBytes() + " bytes",
                options.maxUploadSeconds(), storePath == null ? "memory" : storePath);
        server.join();
    }

    /**
     * Builds and starts Jetty with the servlet, the two access filters, and {@code /health} and
     * {@code /ready}. Separate from {@link #main} so tests (and a program embedding this) can
     * start it on port 0 against their own engine.
     */
    public static Server startServer(CQELSEngine engine, String host, int port, EndpointOptions options,
                                     int maxThreads, boolean virtualThreads) throws Exception {
        requireTokenForRemoteBind(host, options.authToken(), options.allowUnauthenticatedRemote());
        AutoCloseable outbound = options.federationEnabled()
                ? limitOutboundRequests(engine.getRepository(), options.maxExecutionSeconds()) : () -> { };
        Server server = startServer(engine::getRepository, options.metadataEnabled() ? EngineMetadata.of(engine) : null,
                host, port, options, maxThreads, virtualThreads);
        // Let the SERVICE client's connections go when Jetty stops; it stays installed and
        // reopens if used again (a restart, or the engine's own SERVICE).
        server.addEventListener(new org.eclipse.jetty.util.component.LifeCycle.Listener() {
            @Override
            public void lifeCycleStopped(org.eclipse.jetty.util.component.LifeCycle event) {
                try {
                    outbound.close();
                } catch (Exception e) {
                    log.warn("could not release the SERVICE client's connections: {}", e.toString());
                }
            }
        });
        return server;
    }

    /**
     * Gives {@code SERVICE} a deadline, for an endpoint with federation enabled. RDF4J 4.3.8 builds
     * its {@code SERVICE} client with no connect or socket timeout, so an endpoint that stopped
     * answering held the query's slot past the execution limit (found in the audit after #121:
     * 2.6 s under a 1 s limit). This installs, on {@code repository}'s own federated-service
     * resolver, a client that gives up after {@code timeoutSeconds} — answered 504.
     * {@link #startServer(CQELSEngine, String, int, EndpointOptions, int, boolean)} calls it; a
     * program mounting the servlet itself calls it once. ({@code LOAD} needs none of this: the
     * servlet performs it with its own bounded client.)
     *
     * <p>The deadline is absolute — a request still running at it is aborted — not only a wait
     * per byte, which a remote trickling its headers never trips; see {@link TimedServiceClient}.
     *
     * <p><strong>The client stays installed</strong>, for as long as the repository lives, and the
     * engine's own {@code SERVICE} calls use it too. It cannot be handed back: RDF4J's
     * {@code setHttpClient} closes the client RDF4J made and accepts no {@code null}, so
     * "restoring" it on stop left the engine's resolver holding a closed client, and a first
     * attempt that closed this one on stop broke federation after a Jetty restart with
     * "Connection pool shut down" (both found in co-review of #123). What the returned handle
     * closes is the client's connection pool, which reopens if the client is used again; RDF4J
     * itself never closes a client it was handed. Calling this again for the same repository
     * replaces the client and closes the one this method installed before. Nothing here is
     * JVM-wide.
     *
     * @param timeoutSeconds the deadline; {@code 0} sets none — the client is installed all the
     *                       same, since its refusal of XML answers does not depend on it
     * @return releases the client's connections (the client stays installed)
     * @throws IllegalStateException when the client cannot be installed — the repository is not a
     *         {@code SailRepository}, or its resolver does not take the client — so that federation
     *         is refused rather than run without its limits
     */
    public static AutoCloseable limitOutboundRequests(Repository repository, int timeoutSeconds) {
        if (!(repository instanceof SailRepository sail)
                || !(sail.getFederatedServiceResolver() instanceof org.eclipse.rdf4j.http.client.HttpClientDependent dependent)) {
            throw new IllegalStateException("federation is enabled but the repository ("
                    + (repository == null ? "null" : repository.getClass().getName()) + ") exposes no SERVICE HTTP client "
                    + "to limit; disable CQELS_SPARQL_FEDERATION_ENABLED");
        }
        TimedServiceClient client = new TimedServiceClient((int) Math.min(Integer.MAX_VALUE, Math.max(0, timeoutSeconds) * 1000L));
        dependent.setHttpClient(client);
        // A resolver given a session manager of its own ignores setHttpClient without a word:
        // the protections were then silently absent, and an XML answer read a local file again
        // (found in co-review of #123). Install through that manager, and check.
        if (dependent.getHttpClient() != client
                && dependent instanceof org.eclipse.rdf4j.http.client.SessionManagerDependent managed
                && managed.getHttpClientSessionManager() instanceof org.eclipse.rdf4j.http.client.HttpClientDependent manager) {
            manager.setHttpClient(client);
        }
        if (dependent.getHttpClient() != client) {
            client.release();
            throw new IllegalStateException("federation is enabled but the SERVICE client could not be replaced ("
                    + dependent.getClass().getName() + "); disable CQELS_SPARQL_FEDERATION_ENABLED");
        }
        TimedServiceClient earlier = INSTALLED.put(dependent, client);
        if (earlier != null && earlier != client) {
            earlier.release();
        }
        return client::release;
    }

    /** Whether {@code repository}'s SERVICE requests go through a {@link TimedServiceClient}. */
    static boolean outboundLimited(Repository repository) {
        return repository instanceof SailRepository sail
                && sail.getFederatedServiceResolver() instanceof org.eclipse.rdf4j.http.client.HttpClientDependent d
                && d.getHttpClient() instanceof TimedServiceClient;
    }

    /** The client {@link #limitOutboundRequests} installed on each resolver, so a second call can close it. */
    private static final java.util.Map<Object, TimedServiceClient> INSTALLED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * Request threads kept free beyond the query slots, so that a request finding every slot
     * taken still gets a thread to be told 503, and {@code /health} still gets one to answer.
     */
    static final int SPARE_THREADS = 4;

    /** The smallest pool that keeps the slot cap meaningful, given the threads Jetty's connector leases. */
    static int minimumThreads(int maxConcurrentQueries, int leasedThreads) {
        return maxConcurrentQueries + SPARE_THREADS + leasedThreads;
    }

    static Server startServer(Supplier<Repository> repository, Supplier<? extends Collection<Statement>> metadata,
                              String host, int port, EndpointOptions options,
                              int maxThreads, boolean virtualThreads) throws Exception {
        requireTokenForRemoteBind(host, options.authToken(), options.allowUnauthenticatedRemote());
        QueuedThreadPool pool = new QueuedThreadPool(Math.max(maxThreads, 8));
        pool.setName("sparql-endpoint");
        boolean onVirtualThreads = false;
        if (virtualThreads) {
            // Jetty 12 can run request handling on virtual threads when the runtime has them.
            // RDF4J evaluation blocks, so that is a real win under many slow clients; the
            // compile target stays 17, hence the runtime probe rather than an API call.
            Executor vt = VirtualThreads.getDefaultVirtualThreadsExecutor();
            if (vt != null) {
                pool.setVirtualThreadsExecutor(vt);
                onVirtualThreads = true;
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
        // Host check first: a rebound page is same-origin to the browser, so only Host betrays it.
        List<String> hosts = allowedHosts(host, options);
        if (!hosts.isEmpty()) {
            context.addFilter(new FilterHolder(new AccessFilters.Hosts(hosts)), options.path(),
                    EnumSet.of(DispatcherType.REQUEST));
        }
        // Always installed, even with an empty allow-list: that is what refuses a cross-origin
        // form POST, which a browser sends without a preflight (see AccessFilters.Origins).
        context.addFilter(new FilterHolder(new AccessFilters.Origins(options.allowedOrigins())),
                options.path(), EnumSet.of(DispatcherType.REQUEST));
        if (options.authToken() != null) {
            context.addFilter(new FilterHolder(new AccessFilters.BearerToken(options.authToken())),
                    options.path(), EnumSet.of(DispatcherType.REQUEST));
        }
        context.addServlet(new ServletHolder(new SparqlProtocolServlet(repository, options, metadata)), options.path());
        context.addServlet(new ServletHolder(new TextServlet("ok")), "/health");
        context.addServlet(new ServletHolder(new TextServlet("ready")), "/ready");
        server.setHandler(context);
        server.start();

        // The slots only bound anything if a request reaches them. With fewer request threads
        // than slots, requests queue in Jetty for a thread instead: the 503 is no longer
        // immediate and /health waits behind stuck queries (found reviewing #105: 8 threads,
        // 16 slots, eight slow queries, and /health timed out). So refuse that configuration. The connector
        // leases its acceptor and selector threads from the same pool, which is why the
        // number is read after start. Virtual threads have no such ceiling.
        if (!onVirtualThreads) {
            int leased = pool.getThreadPoolBudget() == null ? 0 : pool.getThreadPoolBudget().getLeasedThreads();
            int needed = minimumThreads(options.maxConcurrentQueries(), leased);
            if (pool.getMaxThreads() < needed) {
                server.stop();
                throw new IllegalStateException("CQELS_SPARQL_MAX_THREADS=" + pool.getMaxThreads() + " is too few for "
                        + options.maxConcurrentQueries() + " query slots: need at least " + needed + " ("
                        + leased + " leased by the connector, " + SPARE_THREADS + " spare for rejections and "
                        + "/health). Raise CQELS_SPARQL_MAX_THREADS or lower CQELS_SPARQL_MAX_CONCURRENT_QUERIES.");
            }
        }
        return server;
    }

    /**
     * The {@code Host} names to accept, or empty for no check: none when {@code *} is listed, or
     * when a token is set and no list was given (the token already defeats DNS rebinding).
     * Otherwise the loopback names and the bind host, <em>plus</em> any names given — adding a
     * public name must not lock out a curl on the machine itself.
     */
    static List<String> allowedHosts(String bindHost, EndpointOptions options) {
        List<String> given = options.allowedHosts();
        if (given.contains("*") || (given.isEmpty() && options.authToken() != null)) {
            return List.of();
        }
        List<String> hosts = new java.util.ArrayList<>(List.of("localhost", "127.0.0.1", "::1"));
        if (bindHost != null && !bindHost.isBlank()) {
            hosts.add(bindHost);
        }
        hosts.addAll(given);
        return hosts.stream().map(AccessFilters.Hosts::normalise).distinct().toList();
    }

    /** A NativeStore at {@code storePath} for a graph that survives restarts, or {@code null} for memory. */
    static Repository openNativeStore(String storePath) throws IOException {
        if (storePath == null || storePath.isBlank()) {
            return null;
        }
        Path dir = Files.createDirectories(Path.of(storePath.strip()));
        Repository repo = new SailRepository(new NativeStore(dir.toFile()));
        repo.init();
        return repo;
    }

    /** Over {@code store} when given (the caller keeps ownership), else over a memory store the engine owns. */
    static CQELSEngine buildEngine(Repository store) {
        CQELSEngine.Builder builder = CQELSEngine.builder().id("sparql-endpoint");
        return store == null ? builder.withMemoryStore().build() : builder.repository(store).build();
    }

    /** The advertised URL. An IPv6 literal needs brackets ({@code http://[::1]:8089/sparql}), or scripts cannot use it. */
    static String listeningUrl(String host, int port, String path) {
        String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return "http://" + h + ":" + port + path;
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
        // No host is not loopback: Jetty binds every interface for it ([::]), so a null host
        // let startServer serve unauthenticated to the network (found in co-review of #123).
        if (host == null || host.isBlank()) {
            return false;
        }
        if ("localhost".equalsIgnoreCase(host)) {
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
