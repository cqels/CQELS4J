package org.cqels.examples.sparql;

import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.AbstractExecutionAwareRequest;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HttpContext;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The HTTP client {@link SparqlEndpointLauncher#limitOutboundRequests} gives the engine's
 * {@code SERVICE} resolver: every request has an <em>absolute</em> deadline — within a
 * {@link Budget}, the query's own, shared by all its requests — answers are read whole, capped
 * and checked before RDF4J sees them ({@link #doExecute}), only {@code http} and {@code https}
 * are reached, and the connection pool can be let go and comes back when used again.
 *
 * <p><strong>Why an absolute deadline, not only timeouts.</strong> Apache's connect and socket
 * timeouts bound the wait for each next byte. A remote that sends one byte of its response
 * headers every 200 ms never trips them, and held a query slot for 6.4 s under a 1 s limit
 * (found in co-review of #123). So a request still running at the deadline is aborted from a
 * timer, which ends a read in progress, headers included. The timer is cancelled once the answer
 * has been read; one that fires on a request already finished does nothing.
 *
 * <p><strong>Why the pool can be let go.</strong> RDF4J treats a client handed to
 * {@code setHttpClient} as the caller's and never closes it, so the pool and its sockets
 * outlived the endpoint and the engine (found in co-review of #123). But the client cannot be
 * taken back either: RDF4J closed its own when this one was installed, and accepts no
 * {@code null}. So {@link #release()} closes the pool and leaves the client installed; a later
 * request — after a Jetty restart, or the engine's own {@code SERVICE} — opens a new one.
 */
final class TimedServiceClient extends CloseableHttpClient {

    /**
     * One daemon timer for every instance: it only calls {@code abort()}. A cancelled deadline is
     * removed at once: by default a cancelled task stays queued until its delay runs out, and with
     * it the request it would abort — 20 finished SERVICE calls held about 10 MB of request
     * bodies for the whole deadline (found in co-review of #123).
     */
    private static final java.util.concurrent.ScheduledThreadPoolExecutor DEADLINES;

    static {
        DEADLINES = new java.util.concurrent.ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "sparql-service-deadline");
            t.setDaemon(true);
            return t;
        });
        DEADLINES.setRemoveOnCancelPolicy(true);
    }

    /** Deadlines armed and not yet fired or cancelled; for tests. */
    static int armedDeadlines() {
        return DEADLINES.getQueue().size();
    }

    /**
     * What a SERVICE endpoint is asked for, and all it may answer in. Not XML: RDF4J parses SPARQL
     * XML results with its default XML settings, under which an external entity in the response
     * read a local file into the results, and its session offers no way to change them. Not
     * RDF4J's binary format: its parser sizes arrays from counts in the stream, so a 12-byte
     * answer made it run out of heap — in a background thread whose failure the query then read as
     * an empty result, answered 200 (both found in co-review of #123). A response in either is
     * refused. Not CSV either: CSV carries lexical forms only, and RDF4J re-types what it reads —
     * {@code "001"} became an integer, language tags were lost, an empty string read as unbound —
     * so joins and filters over it gave different answers (found in the SERVICE audit after #128).
     * Not TSV: RDF4J 4.3.8's TSV parser re-types its shorthand too — {@code true} became a string,
     * {@code -1} an {@code xsd:negativeInteger} (found in co-review of #129). SPARQL JSON keeps
     * every term as it was; {@code text/boolean} answers an ASK.
     */
    static final String ACCEPT = "application/sparql-results+json, application/json;q=0.9, text/boolean;q=0.5";
    /** What a SELECT is asked to answer in: a boolean format could not answer it. */
    static final String ACCEPT_TUPLE = "application/sparql-results+json, application/json;q=0.9";

    /** The only media types a SERVICE answer may declare; see {@link #ACCEPT}. */
    private static final java.util.Set<String> ALLOWED = java.util.Set.of("application/sparql-results+json",
            "application/json", "text/boolean");

    /**
     * Whether a SERVICE answer with these {@code Content-Type} headers is refused unread. An
     * allow-list, over every header: refusing XML by reading the first header let
     * {@code Content-Type:} (empty) followed by {@code Content-Type: application/sparql-results+xml}
     * through, since RDF4J skips the empty one and parses the XML (found in co-review of #123).
     * So there must be one header, naming one media type, and it must be on the list. No header at
     * all is left to RDF4J, which refuses an answer it cannot type.
     */
    static boolean refused(org.apache.http.Header[] contentTypes) {
        if (contentTypes.length == 0) {
            return false;
        }
        if (contentTypes.length > 1) {
            return true;
        }
        org.apache.http.HeaderElement[] elements = contentTypes[0].getElements();
        return elements.length != 1
                || !ALLOWED.contains(elements[0].getName().trim().toLowerCase(java.util.Locale.ROOT));
    }

    private final int timeoutMillis;
    /** The most one SERVICE answer may be, decoded; see {@link #doExecute}. */
    private final long maxResponseBytes;

    /** Stands in for a deadline when there is none: cancelling it does nothing. */
    private static final ScheduledFuture<?> NO_DEADLINE = new ScheduledFuture<Object>() {
        @Override
        public long getDelay(TimeUnit unit) {
            return Long.MAX_VALUE;
        }

        @Override
        public int compareTo(java.util.concurrent.Delayed o) {
            return 1;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    };
    private CloseableHttpClient pool;   // guarded by this

    private final org.apache.http.conn.DnsResolver dns;

    TimedServiceClient(int timeoutMillis) {
        this(timeoutMillis, EndpointOptions.DEFAULT_MAX_SERVICE_BYTES);
    }

    TimedServiceClient(int timeoutMillis, long maxResponseBytes) {
        this(timeoutMillis, maxResponseBytes, org.apache.http.impl.conn.SystemDefaultDnsResolver.INSTANCE);
    }

    /** {@code dns} is the resolver the deadline is put around; tests pass one that stalls. */
    TimedServiceClient(int timeoutMillis, org.apache.http.conn.DnsResolver dns) {
        this(timeoutMillis, EndpointOptions.DEFAULT_MAX_SERVICE_BYTES, dns);
    }

    TimedServiceClient(int timeoutMillis, long maxResponseBytes, org.apache.http.conn.DnsResolver dns) {
        this.timeoutMillis = timeoutMillis;
        this.maxResponseBytes = maxResponseBytes;
        // always wrapped: a query's budget can set a deadline even when this client has none
        // (found in co-review of #129); with neither, the lookup runs unbounded as before
        this.dns = new BoundedDns(dns, timeoutMillis);
    }

    /**
     * Name resolution within the deadline. Apache resolves a host synchronously before it opens
     * a socket, and {@code abort()} cannot reach into that call: a stalled lookup held the slot
     * for as long as it stalled, the deadline notwithstanding (found in co-review of #123). So a
     * lookup runs on a small pool of its own and is waited for only until the deadline; a lookup
     * still pending then is given up on (it ends when the system resolver does, on its pool
     * thread, not the request's). With every lookup thread busy, a new one is refused at once
     * rather than queued.
     */
    static final class BoundedDns implements org.apache.http.conn.DnsResolver {
        private static final java.util.concurrent.ThreadPoolExecutor LOOKUPS = new java.util.concurrent.ThreadPoolExecutor(
                0, 8, 30, TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>(), r -> {
                    Thread t = new Thread(r, "sparql-service-dns");
                    t.setDaemon(true);
                    return t;
                });

        /**
         * The current request's absolute deadline ({@link System#nanoTime}), set by
         * {@code doExecute} on the thread Apache resolves on. A lookup waited the full timeout
         * afresh, so time already spent waiting for a pooled connection was counted twice: 1.8 s
         * under a 1 s limit (found in co-review of #123).
         */
        static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();

        private final org.apache.http.conn.DnsResolver delegate;
        private final int timeoutMillis;

        BoundedDns(org.apache.http.conn.DnsResolver delegate, int timeoutMillis) {
            this.delegate = delegate;
            this.timeoutMillis = timeoutMillis;
        }

        @Override
        public java.net.InetAddress[] resolve(String host) throws java.net.UnknownHostException {
            if (DEADLINE.get() == null && timeoutMillis <= 0) {
                return delegate.resolve(host);   // no deadline from anywhere: nothing to bound
            }
            java.util.concurrent.Future<java.net.InetAddress[]> lookup;
            try {
                lookup = LOOKUPS.submit(() -> delegate.resolve(host));
            } catch (java.util.concurrent.RejectedExecutionException e) {
                throw new java.net.UnknownHostException(host + ": too many name lookups pending");
            }
            Long deadline = DEADLINE.get();
            long waitNanos = deadline == null ? TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
                    : Math.max(0, deadline - System.nanoTime());
            try {
                return lookup.get(waitNanos, TimeUnit.NANOSECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                lookup.cancel(true);
                java.net.UnknownHostException timedOut = new java.net.UnknownHostException(
                        host + ": name lookup did not finish within " + timeoutMillis + " ms");
                timedOut.initCause(new java.net.SocketTimeoutException("name lookup deadline"));
                throw timedOut;
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof java.net.UnknownHostException u) {
                    throw u;
                }
                if (e.getCause() instanceof Error error) {
                    throw error;   // not a name that failed to resolve: passed on as it is
                }
                java.net.UnknownHostException u = new java.net.UnknownHostException(host + ": " + e.getCause());
                u.initCause(e.getCause());
                throw u;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lookup.cancel(true);
                throw new java.net.UnknownHostException(host + ": interrupted");
            }
        }
    }

    /** Redirects followed for one SERVICE request, at most. */
    static final int MAX_REDIRECTS = 5;

    /**
     * Why a redirect from {@code from} to {@code to} is not followed, or {@code null} when it is:
     * only to http or https, and never from https down to http — the rules the servlet's LOAD
     * keeps. Apache's default followed an https SERVICE to plain http, and, through a JVM proxy,
     * to other schemes (found in the SERVICE audit after #128).
     */
    static String refusedRedirect(java.net.URI from, java.net.URI to) {
        String scheme = to.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return "a SERVICE endpoint redirected to <" + strip(to) + ">: only http and https are followed";
        }
        if ("https".equalsIgnoreCase(from.getScheme()) && "http".equalsIgnoreCase(scheme)) {
            return "a SERVICE endpoint redirected from https to <" + strip(to) + ">: a redirect may not drop to plain http";
        }
        return null;
    }

    /**
     * Apache follows no redirect and retries nothing on its own: {@link #doExecute} does the
     * following itself, so that every hop is checked, counted against the query's budget and
     * dropped without its body being read. Inside Apache, a redirect was followed past a budget of
     * one request, and its body drained with no cap (found in co-review of #129). No credentials
     * either: with no authentication schemes, a {@code user:pass@} in an endpoint's URL, or the
     * JVM's {@code Authenticator}, is never sent, even when a remote asks for it.
     */
    private synchronized CloseableHttpClient pool() {
        if (pool == null) {
            pool = HttpClientBuilder.create()
                    .useSystemProperties()
                    .setDnsResolver(dns)
                    .disableRedirectHandling()
                    .disableAutomaticRetries()
                    .setDefaultAuthSchemeRegistry(org.apache.http.config.RegistryBuilder
                            .<org.apache.http.auth.AuthSchemeProvider>create().build())
                    .setDefaultCredentialsProvider(new org.apache.http.impl.client.BasicCredentialsProvider())
                    .setDefaultRequestConfig(RequestConfig.custom()
                            .setCookieSpec("standard")   // what RDF4J's own client sets
                            .setConnectTimeout(timeoutMillis)
                            .setConnectionRequestTimeout(timeoutMillis)
                            .setSocketTimeout(timeoutMillis).build())
                    .build();
        }
        return pool;
    }

    /** Whether a connection pool is open; for tests. */
    synchronized boolean poolOpen() {
        return pool != null;
    }

    /** Closes the connection pool, if open; the next request opens a new one. */
    synchronized void release() {
        if (pool != null) {
            try {
                pool.close();
            } catch (IOException ignored) {
                // closing sockets: nothing left to do
            }
            pool = null;
        }
    }

    /**
     * The scheme is checked here, before Apache picks a route: {@code SERVICE ?e} with {@code ?e}
     * bound at run time to a non-http IRI skipped the servlet's check of constant IRIs, and with a
     * JVM proxy configured Apache would route an {@code ftp://host:21} target through it (both
     * found in the SERVICE audit after #128). RDF4J calls this overload.
     */
    @Override
    public CloseableHttpResponse execute(org.apache.http.client.methods.HttpUriRequest request, HttpContext context)
            throws IOException {
        requireHttp(request.getURI());
        return super.execute(request, context);
    }

    @Override
    public CloseableHttpResponse execute(org.apache.http.client.methods.HttpUriRequest request) throws IOException {
        requireHttp(request.getURI());
        return super.execute(request);
    }

    private static void requireHttp(java.net.URI uri) throws Refusal {
        String scheme = uri == null ? null : uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            Refusal r = new Refusal(403, "SERVICE may name only http and https endpoints, not <" + strip(uri) + ">");
            Budget budget = Budget.CURRENT.get();
            throw budget == null ? r : budget.trip(r);   // SILENT must not turn a refusal into an empty match
        }
    }

    /** {@code uri} without its query, which for a SERVICE request is the whole query text. */
    private static String strip(java.net.URI uri) {
        if (uri == null) {
            return "";
        }
        String s = uri.toString();
        int q = s.indexOf('?');
        return q < 0 ? s : s.substring(0, q);
    }

    /**
     * One SERVICE request, its redirects followed here, and its answer read to its end, capped
     * and checked before RDF4J sees it.
     *
     * <p><strong>Why the answer is read here, whole.</strong> RDF4J's {@code SERVICE SILENT} keeps
     * the query's own rows when a remote fails before answering, but drops every one of them when
     * the failure comes while RDF4J iterates the answer — one that breaks off, or does not parse
     * (found in the SERVICE audit after #128: 200 with no rows, and an update answered 204 having
     * written nothing). Read and checked here, such an answer fails the request itself, which
     * SILENT handles as the standard says. Reading it here also bounds it, after Apache has undone
     * any gzip or deflate, and gives the connection back at once: before, RDF4J held it leased
     * while a background thread parsed (the pool deadlock of eclipse-rdf4j/rdf4j#5987).
     *
     * <p><strong>Status before format.</strong> A remote's 403 or 503 is answered in plain text,
     * and checking the media type first reported "answered Content-Type text/plain" instead of the
     * remote's own reason. A non-2xx answer fails as {@link RemoteFailure}, quoting the start of
     * its body; so does an answer that breaks off, or a remote that cannot be reached. The servlet
     * answers 502 for all of them.
     *
     * <p><strong>The query's budget.</strong> Within a {@link Budget} every request, redirects
     * included, counts against the query's deadline, request count and bytes — bytes as they are
     * read, error answers included. A limit that trips is recorded, and refuses every request after
     * it, so that SILENT cannot carry the query past it.
     */
    @Override
    protected CloseableHttpResponse doExecute(HttpHost target, HttpRequest request, HttpContext context)
            throws IOException {
        Budget budget = Budget.CURRENT.get();
        // read before anything is armed: nothing below needs cleaning up if it fails
        if (queryParameters(request) > 1) {
            throw trip(budget, new Refusal(400, "a SERVICE IRI may not carry a 'query' parameter of its own: "
                    + "the query RDF4J sends would be a second one"));
        }
        Boolean expectTuple = expectedForm(request);   // null: could not tell
        if (target == null || !("http".equalsIgnoreCase(target.getSchemeName())
                || "https".equalsIgnoreCase(target.getSchemeName()))) {
            throw trip(budget, new Refusal(403, "SERVICE may name only http and https endpoints"));
        }
        long timeout = timeoutMillis;   // 0: none
        long cap = maxResponseBytes;
        if (budget != null) {
            budget.countRequest();
            long remaining = budget.remainingMillis();
            if (remaining <= 0) {
                throw budget.trip(new Refusal(504, "the query's execution limit ran out before its next SERVICE request"));
            }
            if (remaining != Long.MAX_VALUE) {
                timeout = timeout == 0 ? remaining : Math.min(timeout, remaining);
            }
            cap = Math.min(cap, budget.remainingBytes());
        }
        long timeoutUsed = timeout;
        java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
        // the request the deadline aborts: the current hop, once redirects are followed
        java.util.concurrent.atomic.AtomicReference<HttpRequest> current = new java.util.concurrent.atomic.AtomicReference<>(request);
        ScheduledFuture<?> deadline = timeout > 0
                ? DEADLINES.schedule(() -> {
                    fired.set(true);
                    if (budget != null) {
                        // recorded here, not only where a read fails: an answer already read is
                        // past the abort's reach, and its check could outlast the deadline
                        // unnoticed (found in co-review of #129)
                        budget.trip(new Refusal(504, "a SERVICE endpoint did not answer within the query's execution limit"));
                    }
                    abort(current.get());
                }, timeout, TimeUnit.MILLISECONDS)
                : NO_DEADLINE;
        if (timeout > 0) {
            BoundedDns.DEADLINE.set(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout));
        }
        HttpHost host = target;
        HttpRequest hop = request;
        hop.setHeader("Accept", Boolean.TRUE.equals(expectTuple) ? ACCEPT_TUPLE : ACCEPT);
        try {
            for (int redirects = 0; ; redirects++) {
                CloseableHttpResponse response;
                try {
                    response = pool().execute(host, hop, context);
                } catch (Refusal | RemoteFailure e) {
                    throw e;
                } catch (IOException e) {
                    throw failure(e, fired, timeoutUsed, budget, host, "could not be reached");
                } catch (Error e) {
                    // Apache shuts its connection manager down on an Error (an OutOfMemoryError,
                    // say); kept, that pool failed every later SERVICE with "Connection pool shut
                    // down" (found in co-review of #123). Let it go so the next request opens a new one.
                    release();
                    throw e;
                }
                boolean keep = false;
                HttpRequest sent = hop;   // the request this response answers; hop moves on with a redirect
                java.io.InputStream opened = null;   // closed below, after any abort: never drained
                try {
                    int status = response.getStatusLine().getStatusCode();
                    org.apache.http.HttpEntity entity = response.getEntity();
                    org.apache.http.Header location = response.getFirstHeader("Location");
                    if (REDIRECTS.contains(status) && location != null && "GET".equalsIgnoreCase(hop.getRequestLine().getMethod())) {
                        java.net.URI from;
                        java.net.URI to;
                        try {
                            from = org.apache.http.client.utils.URIUtils.resolve(java.net.URI.create(host.toURI()),
                                    hop.getRequestLine().getUri());
                            // Apache's resolve, not URI.resolve: a query-only reference ("?query=…")
                            // kept the path there and lost it here (found in co-review of #129)
                            to = org.apache.http.client.utils.URIUtils.resolve(from, location.getValue().trim());
                        } catch (IllegalArgumentException e) {
                            throw new RemoteFailure("the SERVICE endpoint " + host.toURI() + " redirected to an invalid URI");
                        }
                        String refused = refusedRedirect(from, to);
                        if (refused != null) {
                            throw trip(budget, new Refusal(502, refused));
                        }
                        if (redirects + 1 > MAX_REDIRECTS) {
                            throw new RemoteFailure("the SERVICE endpoint " + host.toURI() + " redirected more than "
                                    + MAX_REDIRECTS + " times");
                        }
                        if (budget != null) {
                            budget.countRequest();
                        }
                        org.apache.http.client.methods.HttpGet next = new org.apache.http.client.methods.HttpGet(to);
                        for (org.apache.http.Header h : hop.getAllHeaders()) {
                            if (!h.getName().equalsIgnoreCase("Host")) {
                                next.addHeader(h);
                            }
                        }
                        if (!explicitPortValid(to)) {
                            // Apache read ":-2" as no port and went to 80, a destination the
                            // remote never named (found in co-review of #129)
                            throw new RemoteFailure("the SERVICE endpoint " + host.toURI() + " redirected to <"
                                    + strip(to) + ">, whose port is not a port");
                        }
                        HttpHost nextHost = org.apache.http.client.utils.URIUtils.extractHost(to);
                        if (nextHost != null && (nextHost.getPort() > 65535 || nextHost.getPort() == 0)) {
                            // extractHost took port 65536, and Apache's IllegalArgumentException then
                            // came back as the caller's 400 (found in co-review of #129)
                            throw new RemoteFailure("the SERVICE endpoint " + host.toURI() + " redirected to <"
                                    + strip(to) + ">, whose port is out of range");
                        }
                        if (nextHost == null) {
                            // "Location: http:/broken" has a scheme and no host: a 502, not an NPE
                            throw new RemoteFailure("the SERVICE endpoint " + host.toURI() + " redirected to <"
                                    + strip(to) + ">, which names no host");
                        }
                        host = nextHost;
                        current.set(next);
                        hop = next;
                        // The timer aborts whichever request is current when it fires: one that fired
                        // before this hop was published aborted the last one, and this one would run
                        // unbounded (found in co-review of #129). Checked after publishing, so one
                        // of the two always sees the other.
                        if (fired.get()) {
                            throw timedOut(new IOException("deadline reached between redirects"), timeoutUsed, budget);
                        }
                        continue;   // the redirect's own body is dropped with its connection below
                    }
                    if (status / 100 != 2) {
                        String excerpt = "";
                        if (entity != null) {
                            opened = entity.getContent();
                            excerpt = new String(readCapped(opened, Math.min(512, cap), true, budget),
                                    java.nio.charset.StandardCharsets.UTF_8).strip();
                        }
                        throw new RemoteFailure("the SERVICE endpoint " + host.toURI() + " answered HTTP " + status
                                + (excerpt.isEmpty() ? "" : ": " + excerpt));
                    }
                    if (entity == null || (status != 200 && status != 203)) {
                        // RDF4J takes only 200 and 203 as a result; a 204 went on to RDF4J, which
                        // failed it as a 500 that quoted the outbound query (found in co-review of #129)
                        throw new RemoteFailure("the SERVICE endpoint " + host.toURI() + " answered HTTP " + status
                                + (entity == null ? " with no result" : ", not a result"));
                    }
                    org.apache.http.Header[] types = response.getHeaders("Content-Type");
                    if (refused(types)) {
                        throw new RemoteFailure("the SERVICE endpoint " + host.toURI() + " answered Content-Type "
                                + java.util.Arrays.stream(types).map(h -> "'" + h.getValue() + "'")
                                .collect(java.util.stream.Collectors.joining(", "))
                                + "; this endpoint accepts one of " + ALLOWED + " from SERVICE");
                    }
                    opened = entity.getContent();
                    byte[] body = readCapped(opened, cap, false, budget);
                    String mediaType = types.length == 0 ? "" : types[0].getElements()[0].getName().trim().toLowerCase(java.util.Locale.ROOT);
                    Checked checked = check(mediaType, body, expectTuple);
                    if (fired.get()) {
                        // The deadline passed while the answer was checked: a timeout, as if the
                        // remote itself had been late — before the check's own verdict, which
                        // otherwise answered a 502 for a query the deadline had ended (found in
                        // co-review of #129).
                        throw timedOut(new IOException("deadline reached while checking the answer"), timeoutUsed, budget);
                    }
                    if (checked.why() != null) {
                        throw new RemoteFailure("the SERVICE endpoint " + host.toURI() + " answered a malformed result: " + checked.why());
                    }
                    body = checked.body();
                    if (mediaType.equals("text/boolean")) {
                        // checked whole above, but RDF4J reads 16 characters: sixteen spaces and
                        // "false" passed the check and failed there, which SILENT read as true
                        // (found in co-review of #129). It gets the answer alone.
                        body = new String(body, java.nio.charset.StandardCharsets.UTF_8).strip().toLowerCase(java.util.Locale.ROOT)
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                    }
                    if (entity instanceof org.apache.http.client.entity.DecompressingEntity) {
                        // The decoded end is not the end of what came over the wire (a gzip
                        // trailer, say), so Apache never took the connection back: five such
                        // answers emptied a route's pool and the sixth request waited for one
                        // (found in co-review of #129). Drop the connection; never drain it.
                        abort(sent);
                    }
                    org.apache.http.entity.ByteArrayEntity held = new org.apache.http.entity.ByteArrayEntity(body);
                    held.setContentType(types.length == 0 ? null : types[0]);
                    response.setEntity(held);
                    keep = true;
                    return response;
                } catch (Refusal | RemoteFailure e) {
                    throw e;
                } catch (IOException e) {
                    throw failure(e, fired, timeoutUsed, budget, host, "answer broke off");
                } finally {
                    if (!keep) {
                        // whatever is left unread is dropped with the connection, not drained from it
                        abort(sent);
                    }
                    if (opened != null) {
                        // A decompressing stream holds a native inflater until it is closed; the
                        // entity it came from is replaced or dropped here, so nothing else would
                        // close it (found in co-review of #129). After any abort, so closing it
                        // cannot drain the connection; read to its end, it has nothing left to drain.
                        try {
                            opened.close();
                        } catch (IOException ignored) {
                            // aborted underneath: closed all the same
                        }
                    }
                    if (!keep) {
                        try {
                            response.close();
                        } catch (IOException ignored) {
                            // aborted: nothing more to close
                        }
                    }
                }
            }
        } finally {
            deadline.cancel(false);
            BoundedDns.DEADLINE.remove();
        }
    }

    private static final java.util.Set<Integer> REDIRECTS = java.util.Set.of(301, 302, 303, 307, 308);

    /**
     * Whether the port {@code uri}'s authority spells out, if it spells one out, is 1 to 5 ASCII
     * digits naming 1–65535 (an empty one, "host:", means the default, as RFC 3986 allows). Read
     * from the raw authority: parsed, a negative or malformed port became "none".
     */
    static boolean explicitPortValid(java.net.URI uri) {
        String authority = uri.getRawAuthority();
        if (authority == null) {
            return true;
        }
        String hostPort = authority.substring(authority.lastIndexOf('@') + 1);
        int colon;
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            if (close < 0) {
                return false;
            }
            colon = close + 1 < hostPort.length() ? close + 1 : -1;
            if (colon >= 0 && hostPort.charAt(colon) != ':') {
                return false;
            }
        } else {
            colon = hostPort.lastIndexOf(':');
        }
        if (colon < 0) {
            return true;
        }
        String port = hostPort.substring(colon + 1);
        if (port.isEmpty()) {
            return true;
        }
        if (port.length() > 5) {
            return false;
        }
        for (int i = 0; i < port.length(); i++) {
            if (!isDigit(port.charAt(i))) {
                return false;
            }
        }
        int n = Integer.parseInt(port);
        return n >= 1 && n <= 65535;
    }

    private static void abort(HttpRequest request) {
        if (request instanceof AbstractExecutionAwareRequest a) {
            a.abort();
        }
    }

    private static Refusal trip(Budget budget, Refusal r) {
        return budget == null ? r : budget.trip(r);
    }

    /**
     * What a failed request reports: a timeout when the deadline fired or the cause is one (the
     * name lookup's own deadline can win the race against the abort timer, and its timeout then
     * vanished under SILENT — found in co-review of #129), otherwise a {@link RemoteFailure}, so
     * that every way a remote fails — unreachable, or an answer that breaks off — is a 502.
     */
    static IOException failure(IOException e, java.util.concurrent.atomic.AtomicBoolean fired, long timeoutMillis,
                                       Budget budget, HttpHost host, String what) {
        boolean timeout = fired.get();
        for (Throwable t = e; t != null && !timeout; t = t.getCause()) {
            timeout = t instanceof java.net.SocketTimeoutException || t instanceof org.apache.http.conn.ConnectTimeoutException;
            if (t.getCause() == t) {
                break;
            }
        }
        if (timeout) {
            return timedOut(e, timeoutMillis, budget);
        }
        RemoteFailure f = new RemoteFailure("the SERVICE endpoint " + host.toURI() + " " + what + ": " + e.getMessage());
        f.initCause(e);
        return f;
    }

    /**
     * Why {@code body} is not a SPARQL result in {@code mediaType} of the form the query asked for
     * ({@code expectTuple}: {@code true} SELECT, {@code false} ASK, {@code null} either), or
     * {@code null} when it is one. Checked here, once, so that a malformed answer fails the
     * request itself: left to RDF4J, the parse failed while it iterated, where {@code SERVICE
     * SILENT} dropped the query's own rows instead of keeping them (found in the SERVICE audit
     * after #128). RDF4J's parsers alone are not enough — they took {@code {}}, a head with no
     * results, a binding outside {@code head.vars}, a number for a term's value, content after the
     * result, {@code "boolean": 42}, a boolean answer to a SELECT, and text after
     * {@code false} past its 16th character (found in co-review of #129) — so the shape is checked
     * here in full, and the terms then by RDF4J's parser. The cost is bounded by the byte cap.
     */
    static String invalidResult(String mediaType, byte[] body, Boolean expectTuple) {
        return check(mediaType, body, expectTuple).why();
    }

    /** What {@link #check} found: why the answer is refused, or {@code null} and the bytes RDF4J is to get. */
    record Checked(String why, byte[] body) { }

    /**
     * {@link #invalidResult}, and the bytes to hand RDF4J: a JSON answer {@linkplain #headFirst head
     * first}. The rewrite comes between the shape check (on the bytes as sent, so content after the
     * result is still seen) and RDF4J's parse, which reads the rewritten bytes: RDF4J reading the
     * bindings before the head holds them in a hash set, and distinct literals with colliding
     * hashes made that quadratic — a valid 1 MB answer took 3.3 s to parse, 8 ms head first, and a
     * parse cannot be aborted (found in co-review of #129).
     */
    static Checked check(String mediaType, byte[] body, Boolean expectTuple) {
        try {
            // Decoded strictly before anything reads it: the overlong UTF-8 "C0 AF" passed, and
            // became two U+FFFD in RDF4J's parse or "/" in the head-first rewrite, depending on
            // member order. JSON may also be UTF-16 or UTF-32 (RFC 4627 §3, which SPARQL's JSON
            // results adopt), and taking UTF-8 alone refused a valid UTF-16 answer (both found in
            // co-review of #129): the encoding is told from the first four bytes, and the answer
            // is carried on as UTF-8.
            java.nio.charset.Charset encoding = mediaType.equals("text/boolean")
                    ? java.nio.charset.StandardCharsets.UTF_8 : jsonEncoding(body);
            String decoded = encoding.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(body)).toString();
            if (encoding != java.nio.charset.StandardCharsets.UTF_8) {
                body = decoded.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            if (mediaType.equals("text/boolean")) {
                if (Boolean.TRUE.equals(expectTuple)) {
                    throw new IOException("a boolean answer to a SELECT");
                }
                String text = new String(body, java.nio.charset.StandardCharsets.UTF_8).strip();
                if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) {
                    throw new IOException("not 'true' or 'false'");
                }
                return new Checked(null, body);
            }
            if (!mediaType.equals("application/sparql-results+json") && !mediaType.equals("application/json")) {
                return new Checked("no parser for '" + mediaType + "'", body);
            }
            boolean tuple = jsonShape(body);   // true: results, false: boolean
            if (expectTuple != null && tuple != expectTuple) {
                throw new IOException(tuple ? "rows answering an ASK" : "a boolean answer to a SELECT");
            }
            body = headFirst(body);
            if (tuple) {
                var parser = org.eclipse.rdf4j.query.resultio.QueryResultIO.createTupleParser(
                        org.eclipse.rdf4j.query.resultio.TupleQueryResultFormat.JSON);
                parser.setQueryResultHandler(new org.eclipse.rdf4j.query.AbstractTupleQueryResultHandler() { });
                parser.parseQueryResult(new java.io.ByteArrayInputStream(body));
            } else {
                org.eclipse.rdf4j.query.resultio.QueryResultIO.parseBoolean(new java.io.ByteArrayInputStream(body),
                        org.eclipse.rdf4j.query.resultio.BooleanQueryResultFormat.JSON);
            }
            return new Checked(null, body);
        } catch (Exception | StackOverflowError e) {
            // StackOverflowError: no check here recurses on the input, but a parser that did would
            // otherwise reach the servlet as the caller's "nests too deeply" 400
            String why = String.valueOf(e.getMessage());
            return new Checked(why.length() > 300 ? why.substring(0, 300) + "…" : why, body);
        }
    }

    /**
     * Not {@code triple}: RDF-star results are not taken from SERVICE. RDF4J read a triple term
     * whose value was a string as no value at all, an unbound variable in a 200 (found in co-review
     * of #129), and checking a triple's parts here is more than this endpoint needs.
     */
    private static final java.util.Set<String> TERM_TYPES = java.util.Set.of("uri", "literal", "bnode", "typed-literal");

    /**
     * The shape SPARQL 1.1 Query Results JSON requires: one object, a {@code head} object, and
     * either {@code results.bindings} — then {@code head.vars} an array of strings, every binding
     * an object naming only those variables, every term an object with a {@code type} RDF4J reads
     * (not an RDF-star triple) and a string {@code value}, {@code xml:lang} and
     * {@code datatype} strings — or a JSON boolean {@code boolean}; nothing after it. Returns
     * whether it is a tuple result.
     */
    private static boolean jsonShape(byte[] body) throws IOException {
        try (com.fasterxml.jackson.core.JsonParser p = new com.fasterxml.jackson.core.JsonFactory().createParser(body)) {
            expect(p.nextToken() == com.fasterxml.jackson.core.JsonToken.START_OBJECT, "not a JSON object");
            boolean head = false;
            boolean results = false;
            boolean bool = false;
            java.util.Set<String> vars = null;
            java.util.Set<String> used = new java.util.HashSet<>();
            java.util.Set<String> members = new java.util.HashSet<>();
            while (p.nextToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME) {
                String name = p.currentName();
                // A second "head" after the results let the answer past the head-first rewrite
                // with RDF4J buffering again (found in co-review of #129): a member given twice
                // is refused, as JSON's own advice on duplicate names would have it.
                expect(members.add(name), "'" + name + "' given twice");
                com.fasterxml.jackson.core.JsonToken value = p.nextToken();
                switch (name) {
                    case "head" -> {
                        expect(value == com.fasterxml.jackson.core.JsonToken.START_OBJECT, "'head' is not an object");
                        head = true;
                        boolean varsSeen = false;
                        while (p.nextToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME) {
                            String field = p.currentName();
                            com.fasterxml.jackson.core.JsonToken v = p.nextToken();
                            if (field.equals("link")) {
                                // RDF4J reads link; a malformed one ended its parse early, and the
                                // rows after it were lost (found in co-review of #129)
                                expect(v == com.fasterxml.jackson.core.JsonToken.START_ARRAY, "'head.link' is not an array");
                                while (p.nextToken() != com.fasterxml.jackson.core.JsonToken.END_ARRAY) {
                                    expect(p.currentToken() == com.fasterxml.jackson.core.JsonToken.VALUE_STRING,
                                            "'head.link' holds a non-string");
                                }
                                continue;
                            }
                            if (field.equals("vars")) {
                                expect(!varsSeen, "'head.vars' given twice");
                                varsSeen = true;
                                expect(v == com.fasterxml.jackson.core.JsonToken.START_ARRAY, "'head.vars' is not an array");
                                vars = new java.util.HashSet<>();
                                while (p.nextToken() != com.fasterxml.jackson.core.JsonToken.END_ARRAY) {
                                    expect(p.currentToken() == com.fasterxml.jackson.core.JsonToken.VALUE_STRING,
                                            "'head.vars' holds a non-string");
                                    vars.add(p.getText());
                                }
                            } else {
                                p.skipChildren();
                            }
                        }
                    }
                    case "results" -> {
                        expect(value == com.fasterxml.jackson.core.JsonToken.START_OBJECT, "'results' is not an object");
                        while (p.nextToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME) {
                            String field = p.currentName();
                            com.fasterxml.jackson.core.JsonToken v = p.nextToken();
                            if (field.equals("ordered") || field.equals("distinct")) {
                                // RDF4J reads these older members, and read bindings nested in them
                                // that this check had skipped: an update stored an invalid IRI that
                                // way (found in co-review of #129)
                                expect(v == com.fasterxml.jackson.core.JsonToken.VALUE_TRUE
                                        || v == com.fasterxml.jackson.core.JsonToken.VALUE_FALSE, "'" + field + "' is not true or false");
                                continue;
                            }
                            if (!field.equals("bindings")) {
                                p.skipChildren();
                                continue;
                            }
                            expect(!results, "'results.bindings' given twice");
                            expect(v == com.fasterxml.jackson.core.JsonToken.START_ARRAY, "'bindings' is not an array");
                            results = true;
                            while (p.nextToken() != com.fasterxml.jackson.core.JsonToken.END_ARRAY) {
                                expect(p.currentToken() == com.fasterxml.jackson.core.JsonToken.START_OBJECT, "a binding is not an object");
                                while (p.nextToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME) {
                                    used.add(p.currentName());
                                    expect(p.nextToken() == com.fasterxml.jackson.core.JsonToken.START_OBJECT, "a term is not an object");
                                    term(p);
                                }
                            }
                        }
                        expect(results, "'results' has no 'bindings'");
                    }
                    case "boolean" -> {
                        expect(value == com.fasterxml.jackson.core.JsonToken.VALUE_TRUE
                                || value == com.fasterxml.jackson.core.JsonToken.VALUE_FALSE, "'boolean' is not true or false");
                        bool = true;
                    }
                    default -> p.skipChildren();
                }
            }
            expect(p.currentToken() == com.fasterxml.jackson.core.JsonToken.END_OBJECT && p.nextToken() == null,
                    "content after the result");
            expect(head, "no 'head'");
            expect(results != bool, results ? "both 'results' and 'boolean'" : "neither 'results' nor 'boolean'");
            if (results) {
                expect(vars != null, "no 'head.vars'");
                for (String v : used) {
                    expect(vars.contains(v), "a binding names '" + v + "', which 'head.vars' does not");
                }
            }
            return results;
        }
    }

    private static final String RDF_LANG_STRING = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString";

    /**
     * One RDF term's object, its START_OBJECT already read: a known {@code type}, a string
     * {@code value}, and {@code xml:lang} / {@code datatype} only where they make a term — a
     * language only on a literal, and then with no datatype or {@code rdf:langString}; a datatype
     * only on a literal (required on a {@code typed-literal}); {@code rdf:langString} only with a
     * language; a {@code uri}'s value and a datatype absolute IRIs. RDF4J took the rest and changed them: an {@code rdf:langString} with no language
     * became an {@code xsd:string}, a language beside another datatype lost the datatype (found in
     * co-review of #129).
     */
    private static void term(com.fasterxml.jackson.core.JsonParser p) throws IOException {
        String type = null;
        boolean value = false;
        String lang = null;
        String datatype = null;
        String text = null;
        while (p.nextToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME) {
            String field = p.currentName();
            com.fasterxml.jackson.core.JsonToken v = p.nextToken();
            switch (field) {
                case "type" -> {
                    expect(v == com.fasterxml.jackson.core.JsonToken.VALUE_STRING && TERM_TYPES.contains(p.getText()),
                            "a term's 'type' is not one of " + TERM_TYPES);
                    type = p.getText();
                }
                case "value" -> {
                    expect(v == com.fasterxml.jackson.core.JsonToken.VALUE_STRING, "a term's 'value' is not a string");
                    value = true;
                    text = p.getText();
                    // a JSON escape can name half a surrogate pair, which strict byte decoding
                    // never sees; RDF4J then held a string no UTF-8 can carry (found in
                    // co-review of #129)
                    expect(paired(text), "a term's 'value' holds an unpaired surrogate");
                }
                case "xml:lang", "datatype" -> {
                    expect(v == com.fasterxml.jackson.core.JsonToken.VALUE_STRING && paired(p.getText()),
                            "a term's '" + field + "' is not a well-formed string");
                    if (field.equals("datatype")) {
                        datatype = p.getText();
                    } else {
                        lang = p.getText();
                    }
                }
                default -> p.skipChildren();
            }
        }
        expect(type != null && value, "a term without 'type' and 'value'");
        boolean literal = type.equals("literal") || type.equals("typed-literal");
        expect(literal || (lang == null && datatype == null), "a " + type + " with a language or datatype");
        expect(!type.equals("typed-literal") || datatype != null, "a typed-literal without a datatype");
        expect(lang == null || languageTag(lang), "a malformed language tag");
        expect(lang == null || datatype == null || datatype.equals(RDF_LANG_STRING), "a language beside the datatype " + datatype);
        expect(!RDF_LANG_STRING.equals(datatype) || lang != null, "an rdf:langString without a language");
        // RDF4J's parser takes "http://example.org/a b" as an IRI, and an update stored it (found
        // in co-review of #129): the strict RFC 3987 parse the servlet gives graph IRIs
        expect(!type.equals("uri") || absoluteIri(text), "a uri that is not an absolute IRI");
        expect(datatype == null || absoluteIri(datatype), "a datatype that is not an absolute IRI");
    }

    /**
     * An absolute IRI by RFC 3987's grammar: {@code scheme ":" ihier-part ["?" iquery]
     * ["#" ifragment]}, with its character ranges. Neither library parser matched it:
     * RDF4J's {@code ParsedIRI} refused {@code http://127.0.0.1?x} and took
     * {@code http://[not-an-ip]/a}; {@code java.net.URI} refused U+00A0 in a path and took
     * {@code urn:a[b]} and private-use characters outside the query (both found in co-review of
     * #129). Checked character by character, without recursion.
     */
    static boolean absoluteIri(String iri) {
        int colon = iri.indexOf(':');
        if (colon < 1 || !isAlpha(iri.charAt(0))) {
            return false;
        }
        for (int i = 1; i < colon; i++) {
            char c = iri.charAt(i);
            if (!isAlpha(c) && !isDigit(c) && c != '+' && c != '-' && c != '.') {
                return false;
            }
        }
        String rest = iri.substring(colon + 1);
        int hash = rest.indexOf('#');
        String fragment = hash < 0 ? null : rest.substring(hash + 1);
        if (hash >= 0) {
            rest = rest.substring(0, hash);
        }
        int question = rest.indexOf('?');
        String query = question < 0 ? null : rest.substring(question + 1);
        String hier = question < 0 ? rest : rest.substring(0, question);
        if (fragment != null && !chars(fragment, "/?", false)) {
            return false;
        }
        if (query != null && !chars(query, "/?", true)) {
            return false;
        }
        String path = hier;
        if (hier.startsWith("//")) {
            int slash = hier.indexOf('/', 2);
            String authority = slash < 0 ? hier.substring(2) : hier.substring(2, slash);
            path = slash < 0 ? "" : hier.substring(slash);
            if (!authority(authority)) {
                return false;
            }
        }
        return chars(path, "/", false);
    }

    private static boolean authority(String a) {
        int at = a.lastIndexOf('@');
        if (at >= 0 && !chars(a.substring(0, at), ":", false, false)) {
            return false;
        }
        String hostPort = a.substring(at + 1);
        String host = hostPort;
        String port = null;
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            if (close < 0) {
                return false;
            }
            host = hostPort.substring(0, close + 1);
            String after = hostPort.substring(close + 1);
            if (!after.isEmpty()) {
                if (after.charAt(0) != ':') {
                    return false;
                }
                port = after.substring(1);
            }
            if (!ipLiteral(host.substring(1, host.length() - 1))) {
                return false;
            }
        } else {
            int c = hostPort.lastIndexOf(':');
            if (c >= 0) {
                host = hostPort.substring(0, c);
                port = hostPort.substring(c + 1);
            }
            if (!chars(host, "", false, false)) {   // ireg-name: no ':' '@' '/' '[' ']'
                return false;
            }
        }
        if (port != null) {
            for (int i = 0; i < port.length(); i++) {
                if (!isDigit(port.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** IPv6address or IPvFuture, the brackets' content. IPv6 is parsed as a literal: it has ':', so no name is looked up. */
    private static boolean ipLiteral(String s) {
        if (!s.isEmpty() && (s.charAt(0) == 'v' || s.charAt(0) == 'V')) {
            int dot = s.indexOf('.');
            if (dot < 2 || dot == s.length() - 1) {
                return false;
            }
            for (int i = 1; i < dot; i++) {
                if (!hex(s.charAt(i))) {
                    return false;
                }
            }
            for (int i = dot + 1; i < s.length(); i++) {
                char c = s.charAt(i);
                if (!(isAlpha(c) || isDigit(c) || "-._~!$&'()*+,;=:".indexOf(c) >= 0)) {
                    return false;
                }
            }
            return true;
        }
        return ipv6(s);
    }

    /**
     * RFC 3986's IPv6address, by its grammar: groups of 1 to 4 hex digits, at most one "::", and
     * an optional dotted IPv4 tail standing for two groups. Not {@code InetAddress}: it turned
     * {@code ::ffff:192.0.2.1} into an {@code Inet4Address}, refusing a valid IRI, and took
     * {@code ::192.000.2.1} (found in co-review of #129).
     */
    static boolean ipv6(String s) {
        int gap = s.indexOf("::");
        if (gap >= 0 && s.indexOf("::", gap + 1) >= 0) {
            return false;
        }
        String head = gap < 0 ? s : s.substring(0, gap);
        String tail = gap < 0 ? "" : s.substring(gap + 2);
        int groups = 0;
        String[][] sides = {head.isEmpty() ? new String[0] : head.split(":", -1),
                tail.isEmpty() ? new String[0] : tail.split(":", -1)};
        for (int side = 0; side < 2; side++) {
            String[] parts = sides[side];
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                boolean last = side == (gap < 0 ? 0 : 1) && i == parts.length - 1;
                if (last && part.indexOf('.') >= 0) {
                    if (!ipv4(part)) {
                        return false;
                    }
                    groups += 2;
                } else {
                    if (part.isEmpty() || part.length() > 4) {
                        return false;
                    }
                    for (int k = 0; k < part.length(); k++) {
                        if (!hex(part.charAt(k))) {
                            return false;
                        }
                    }
                    groups++;
                }
            }
        }
        return gap < 0 ? groups == 8 : groups <= 7;
    }

    /** dec-octet "." dec-octet "." dec-octet "." dec-octet, no leading zeros. */
    private static boolean ipv4(String s) {
        String[] octets = s.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String o : octets) {
            if (o.isEmpty() || o.length() > 3 || (o.length() > 1 && o.charAt(0) == '0')) {
                return false;
            }
            for (int i = 0; i < o.length(); i++) {
                if (!isDigit(o.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(o) > 255) {
                return false;
            }
        }
        return true;
    }

    /** An ASCII hex digit: {@code Character.digit} took fullwidth and other Unicode digits (found in co-review of #129). */
    private static boolean hex(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** An ipchar sequence (adds ':' and '@' to {@code extra}); see the overload. */
    private static boolean chars(String s, String extra, boolean privateUse) {
        return chars(s, extra + ":@", privateUse, true);
    }

    /**
     * Every code point of {@code s} an iunreserved, sub-delim, percent-encoding or one of
     * {@code extra}, taken exactly as given ({@code exactExtras} marks this overload, used for
     * userinfo and host, which are not ipchar); private-use code points only where
     * {@code privateUse} (the query).
     */
    private static boolean chars(String s, String extra, boolean privateUse, boolean exactExtras) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (cp == '%') {
                if (i + 2 >= s.length() || !hex(s.charAt(i + 1)) || !hex(s.charAt(i + 2))) {
                    return false;
                }
                i += 3;
                continue;
            }
            boolean ok = (cp < 128 && (isAlpha((char) cp) || isDigit((char) cp) || "-._~!$&'()*+,;=".indexOf(cp) >= 0
                    || extra.indexOf(cp) >= 0))
                    || ucschar(cp) || (privateUse && iprivate(cp));
            if (!ok) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    /** RFC 3987's ucschar. */
    private static boolean ucschar(int c) {
        if ((c >= 0xA0 && c <= 0xD7FF) || (c >= 0xF900 && c <= 0xFDCF) || (c >= 0xFDF0 && c <= 0xFFEF)) {
            return true;
        }
        if ((c >= 0x10000 && c <= 0xDFFFD) || (c >= 0xE1000 && c <= 0xEFFFD)) {
            // each plane's xxFFFE and xxFFFF are not; plane 14 starts at E1000, its tags block
            // E0000–E0FFF excluded (found in co-review of #129)
            return (c & 0xFFFF) <= 0xFFFD;
        }
        return false;
    }

    /** RFC 3987's iprivate. */
    private static boolean iprivate(int c) {
        return (c >= 0xE000 && c <= 0xF8FF) || (c >= 0xF0000 && c <= 0xFFFFD) || (c >= 0x100000 && c <= 0x10FFFD);
    }

    private static boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * RFC 4627 §3: JSON's first two characters are ASCII, so the pattern of zero bytes among the
     * first four tells UTF-32BE, UTF-16BE, UTF-32LE or UTF-16LE from UTF-8.
     */
    static java.nio.charset.Charset jsonEncoding(byte[] b) {
        if (b.length >= 4) {
            boolean z0 = b[0] == 0;
            boolean z1 = b[1] == 0;
            boolean z2 = b[2] == 0;
            boolean z3 = b[3] == 0;
            if (z0 && z1 && z2 && !z3) {
                return java.nio.charset.Charset.forName("UTF-32BE");
            }
            if (!z0 && z1 && z2 && z3) {
                return java.nio.charset.Charset.forName("UTF-32LE");
            }
            if (z0 && !z1 && z2 && !z3) {
                return java.nio.charset.StandardCharsets.UTF_16BE;
            }
            if (!z0 && z1 && !z2 && z3) {
                return java.nio.charset.StandardCharsets.UTF_16LE;
            }
        }
        return java.nio.charset.StandardCharsets.UTF_8;
    }

    /** Whether every surrogate in {@code s} is half of a pair, in order. */
    static boolean paired(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    /** RFC 5646's grandfathered tags, irregular and regular, compared case-insensitively. */
    private static final java.util.Set<String> GRANDFATHERED = java.util.Set.of(
            "en-gb-oed", "i-ami", "i-bnn", "i-default", "i-enochian", "i-hak", "i-klingon", "i-lux", "i-mingo",
            "i-navajo", "i-pwn", "i-tao", "i-tay", "i-tsu", "sgn-be-fr", "sgn-be-nl", "sgn-ch-de",
            "art-lojban", "cel-gaulish", "no-bok", "no-nyn", "zh-guoyu", "zh-hakka", "zh-min", "zh-min-nan", "zh-xiang");

    /**
     * A well-formed BCP 47 language tag (RFC 5646 §2.1), read subtag by subtag: language (2–3
     * letters with up to three 3-letter extlangs, or 4–8 letters), then script (4 letters), region
     * (2 letters or 3 digits), variants (5–8 alphanumerics, or a digit and 3 alphanumerics),
     * extensions (a singleton other than x, then subtags of 2–8), and private use (x, then subtags
     * of 1–8) — or a private-use tag alone, or a grandfathered one. A check of characters and
     * lengths took "a", "en-a" and "en-US-US" (found in co-review of #129), and its regex
     * predecessor recursed per subtag until the stack overflowed.
     */
    static boolean languageTag(String tag) {
        if (tag.isEmpty() || GRANDFATHERED.contains(tag.toLowerCase(java.util.Locale.ROOT))) {
            return !tag.isEmpty();
        }
        String[] t = tag.split("-", -1);
        for (String sub : t) {
            if (sub.isEmpty() || sub.length() > 8 || !alnum(sub)) {
                return false;
            }
        }
        int i = 0;
        if (t[0].equalsIgnoreCase("x")) {
            return privateUse(t, 0);
        }
        // language
        if (!alpha(t[0]) || t[0].length() < 2) {
            return false;
        }
        i = 1;
        if (t[0].length() <= 3) {
            for (int ext = 0; ext < 3 && i < t.length && t[i].length() == 3 && alpha(t[i]); ext++) {
                i++;   // extlang
            }
        }
        if (i < t.length && t[i].length() == 4 && alpha(t[i])) {
            i++;   // script
        }
        if (i < t.length && ((t[i].length() == 2 && alpha(t[i])) || (t[i].length() == 3 && digits(t[i])))) {
            i++;   // region
        }
        while (i < t.length && (t[i].length() >= 5 || (t[i].length() == 4 && isDigit(t[i].charAt(0))))) {
            i++;   // variant: 5–8 alphanumerics, or a digit and three alphanumerics
        }
        while (i < t.length && t[i].length() == 1 && !t[i].equalsIgnoreCase("x")) {
            int start = ++i;   // extension: its singleton, then at least one subtag of 2–8
            while (i < t.length && t[i].length() >= 2) {
                i++;
            }
            if (i == start) {
                return false;
            }
        }
        if (i < t.length && t[i].equalsIgnoreCase("x")) {
            return privateUse(t, i);
        }
        return i == t.length;
    }

    /** "x" at {@code at}, then at least one subtag (1–8 alphanumerics, checked already), to the end. */
    private static boolean privateUse(String[] t, int at) {
        return at + 1 < t.length;
    }

    private static boolean alnum(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!isAlpha(c) && !isDigit(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean alpha(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!isAlpha(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean digits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code body} with its {@code head} member first, the rest as it was. RDF4J 4.3.8 reading
     * {@code results} before {@code head} holds the bindings in a set until the head arrives, so
     * two identical solutions became one, and their order was lost — a COUNT and a join over them
     * changed (found in co-review of #129). The bindings array is copied token by token,
     * untouched. Returns {@code body} itself when the head is already first.
     */
    static byte[] headFirst(byte[] body) throws IOException {
        com.fasterxml.jackson.core.JsonFactory json = new com.fasterxml.jackson.core.JsonFactory();
        try (com.fasterxml.jackson.core.JsonParser p = json.createParser(body)) {
            p.nextToken();
            if (p.nextToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME && p.currentName().equals("head")) {
                return body;
            }
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(body.length + 16);
        try (com.fasterxml.jackson.core.JsonGenerator g = json.createGenerator(out)) {
            g.writeStartObject();
            for (boolean headPass : new boolean[] {true, false}) {
                try (com.fasterxml.jackson.core.JsonParser p = json.createParser(body)) {
                    p.nextToken();   // the outer object
                    while (p.nextToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME) {
                        boolean isHead = p.currentName().equals("head");
                        if (isHead == headPass) {
                            g.writeFieldName(p.currentName());
                            p.nextToken();
                            g.copyCurrentStructure(p);
                        } else {
                            p.nextToken();
                            p.skipChildren();
                        }
                    }
                }
            }
            g.writeEndObject();
        }
        return out.toByteArray();
    }

    private static void expect(boolean ok, String why) throws IOException {
        if (!ok) {
            throw new IOException(why);
        }
    }

    /**
     * Whether {@code request} carries a SELECT ({@code true}) or an ASK ({@code false}), read from
     * the {@code query} parameter of its URL or form body; {@code null} when that cannot be told.
     * RDF4J's {@code Accept} is the same for both, so the query text is what says which result
     * may answer it.
     */
    static Boolean expectedForm(HttpRequest request) {
        try {
            String params = null;
            if (request instanceof org.apache.http.HttpEntityEnclosingRequest enclosing && enclosing.getEntity() != null
                    && enclosing.getEntity().isRepeatable()) {
                params = org.apache.http.util.EntityUtils.toString(enclosing.getEntity(), java.nio.charset.StandardCharsets.UTF_8);
            } else {
                params = java.net.URI.create(request.getRequestLine().getUri()).getRawQuery();
            }
            if (params == null) {
                return null;
            }
            for (org.apache.http.NameValuePair pair : org.apache.http.client.utils.URLEncodedUtils.parse(params,
                    java.nio.charset.StandardCharsets.UTF_8)) {
                if (pair.getName().equals("query") && pair.getValue() != null) {
                    return formOf(pair.getValue());
                }
            }
        } catch (IOException | RuntimeException e) {
            // cannot tell: either form is accepted
        }
        return null;
    }

    /**
     * How many {@code query} parameters {@code request} carries, in its URL and form body
     * together. RDF4J adds its own to whatever the SERVICE IRI already has, so an IRI ending in
     * {@code ?query=ASK{}} sent two, and the form check read the IRI's (found in co-review of
     * #129). More than one is refused before anything is sent.
     */
    static int queryParameters(HttpRequest request) {
        int n = 0;
        try {
            String url = java.net.URI.create(request.getRequestLine().getUri()).getRawQuery();
            if (url != null) {
                n += count(url);
            }
            if (request instanceof org.apache.http.HttpEntityEnclosingRequest enclosing && enclosing.getEntity() != null
                    && enclosing.getEntity().isRepeatable()) {
                n += count(org.apache.http.util.EntityUtils.toString(enclosing.getEntity(), java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            // unreadable: counted as none here; the remote decides
        }
        return n;
    }

    private static int count(String params) {
        int n = 0;
        for (org.apache.http.NameValuePair pair : org.apache.http.client.utils.URLEncodedUtils.parse(params,
                java.nio.charset.StandardCharsets.UTF_8)) {
            if (pair.getName().equals("query")) {
                n++;
            }
        }
        return n;
    }

    /**
     * The form after the prologue — whitespace, comments, BASE and PREFIX declarations — read in
     * one pass: {@code true} SELECT, {@code false} ASK, {@code null} anything else. Not a regex:
     * a repeated group recursed per declaration, and 2,000 PREFIX lines overflowed the stack
     * (found in co-review of #129).
     */
    static Boolean formOf(String q) {
        int i = 0;
        int n = q.length();
        while (i < n) {
            char c = q.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '#') {
                int end = q.indexOf('\n', i);
                if (end < 0) {
                    return null;
                }
                i = end + 1;
            } else if (q.regionMatches(true, i, "BASE", 0, 4) || q.regionMatches(true, i, "PREFIX", 0, 6)) {
                int end = q.indexOf('>', i);   // an IRIREF holds no '>'
                if (end < 0) {
                    return null;
                }
                i = end + 1;
            } else if (keyword(q, i, "SELECT")) {
                return Boolean.TRUE;
            } else if (keyword(q, i, "ASK")) {
                return Boolean.FALSE;
            } else {
                return null;
            }
        }
        return null;
    }

    private static boolean keyword(String q, int at, String word) {
        int end = at + word.length();
        return q.regionMatches(true, at, word, 0, word.length())
                && (end == q.length() || !Character.isLetterOrDigit(q.charAt(end)));
    }

    /**
     * {@code in} read to its end, or to {@code cap} bytes: beyond that, {@link Refusal} (502), or
     * with {@code truncate} the first {@code cap} bytes (for an error answer's excerpt). Every byte
     * read is charged to {@code budget} as it arrives — a read that then fails, or an error answer,
     * spends it as surely as a result does (found in co-review of #129: with a 50-byte budget,
     * error answers of 1 KB each went uncounted).
     *
     * <p>{@code in} is not closed here. Closing an Apache body before its end makes Apache read
     * the rest, to reuse the connection: stopping at a 16 MiB cap then drained a 1 GB answer for
     * 3.5 s (found while fixing the SERVICE audit after #128). The caller aborts the request
     * first, which drops the connection instead; a body read to its end releases it by itself.
     */
    static byte[] readCapped(java.io.InputStream in, long cap, boolean truncate, Budget budget) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (budget != null) {
                budget.addBytes(n);
                if (budget.overspent()) {
                    // spent here, by this answer — an error answer's excerpt included, which
                    // returned with the budget spent and nothing recorded, so SILENT went on to a
                    // 200 and an update committed (found in co-review of #129)
                    throw budget.trip(new Refusal(502, "a SERVICE endpoint answered more than " + budget.maxBytes
                            + " bytes, the most this endpoint takes from SERVICE in one query"));
                }
            }
            if (out.size() + (long) n > cap) {
                if (truncate) {
                    out.write(buf, 0, (int) (cap - out.size()));
                    return out.toByteArray();
                }
                throw trip(budget, new Refusal(502, "a SERVICE endpoint answered more than " + cap
                        + " bytes, the most this endpoint takes from SERVICE in one query"));
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static java.net.SocketTimeoutException timedOut(IOException cause, long timeoutMillis, Budget budget) {
        java.net.SocketTimeoutException e = new java.net.SocketTimeoutException(
                "SERVICE request aborted at its deadline of " + timeoutMillis + " ms");
        e.initCause(cause);
        if (budget != null) {
            budget.trip(new Refusal(504, "a SERVICE endpoint did not answer within the query's execution limit"));
        }
        return e;
    }

    /** A request this endpoint refuses to make or finish, with the status the servlet answers. */
    static final class Refusal extends IOException {
        final int status;

        Refusal(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /** The remote answered, but not with a result: a non-2xx status, or a format refused. The servlet answers 502. */
    static final class RemoteFailure extends IOException {
        RemoteFailure(String message) {
            super(message);
        }
    }

    /**
     * What one query or update may spend on SERVICE in all: its execution limit as one deadline
     * for every request, at most {@code maxRequests} requests, and at most {@code maxBytes} of
     * answers. Opened by the servlet on its own thread, where RDF4J makes the requests, and closed
     * when the query is done; a request made with none open is bounded on its own, as before.
     *
     * <p>A limit that trips is also {@linkplain #tripped() recorded}: under {@code SERVICE SILENT}
     * RDF4J swallows the failure and carries on with fewer rows, and an endpoint limit must not
     * turn into a silently shorter answer. The servlet checks it after evaluation (and before an
     * update commits) and answers the recorded status instead.
     */
    static final class Budget implements AutoCloseable {
        static final ThreadLocal<Budget> CURRENT = new ThreadLocal<>();

        private final long deadlineNanos;   // Long.MAX_VALUE: none
        private final int maxRequests;
        final long maxBytes;
        private final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicLong bytes = new java.util.concurrent.atomic.AtomicLong();
        private volatile Refusal tripped;

        private Budget(int limitSeconds, int maxRequests, long maxBytes) {
            this.deadlineNanos = limitSeconds > 0 ? System.nanoTime() + TimeUnit.SECONDS.toNanos(limitSeconds) : Long.MAX_VALUE;
            this.maxRequests = maxRequests;
            this.maxBytes = maxBytes;
        }

        /** Opens a budget on this thread; close it when the query is done. */
        static Budget open(int limitSeconds, int maxRequests, long maxBytes) {
            Budget b = new Budget(limitSeconds, maxRequests, maxBytes);
            CURRENT.set(b);
            return b;
        }

        /** Counts a request; refused once any limit has tripped, the bytes are spent, or there were enough. */
        void countRequest() throws Refusal {
            Refusal earlier = tripped;
            if (earlier != null) {
                throw earlier;   // SILENT swallowed it; the query does not get to go on asking
            }
            if (bytes.get() >= maxBytes) {
                throw trip(new Refusal(502, "the query's SERVICE answers reached " + maxBytes
                        + " bytes, the most this endpoint takes from SERVICE in one query"));
            }
            if (requests.incrementAndGet() > maxRequests) {
                throw trip(new Refusal(503, "the query needs more than " + maxRequests
                        + " SERVICE requests, the most this endpoint makes for one query"));
            }
        }

        long remainingMillis() {
            return deadlineNanos == Long.MAX_VALUE ? Long.MAX_VALUE
                    : TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        }

        long remainingBytes() {
            return Math.max(0, maxBytes - bytes.get());
        }

        boolean overspent() {
            return bytes.get() > maxBytes;
        }

        void addBytes(long n) {
            bytes.addAndGet(n);
        }

        int requests() {
            return requests.get();
        }

        Refusal trip(Refusal r) {
            if (tripped == null) {
                tripped = r;
            }
            return r;
        }

        /** The first limit this query's SERVICE calls ran into, or {@code null}. */
        Refusal tripped() {
            return tripped;
        }

        @Override
        public void close() {
            CURRENT.remove();
        }
    }

    @Override
    public void close() {
        release();
    }

    @Override
    @Deprecated
    public org.apache.http.params.HttpParams getParams() {
        return pool().getParams();
    }

    @Override
    @Deprecated
    public org.apache.http.conn.ClientConnectionManager getConnectionManager() {
        return pool().getConnectionManager();
    }
}
