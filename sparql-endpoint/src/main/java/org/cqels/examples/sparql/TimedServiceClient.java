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
                    abort(current.get());
                }, timeout, TimeUnit.MILLISECONDS)
                : NO_DEADLINE;
        if (timeout > 0) {
            BoundedDns.DEADLINE.set(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout));
        }
        HttpHost host = target;
        HttpRequest hop = request;
        Boolean expectTuple = expectedForm(request);   // null: could not tell
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
                        HttpHost nextHost = org.apache.http.client.utils.URIUtils.extractHost(to);
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
                        String excerpt = entity == null ? "" : new String(readCapped(entity.getContent(), Math.min(512, cap),
                                true, budget), java.nio.charset.StandardCharsets.UTF_8).strip();
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
                    byte[] body = readCapped(entity.getContent(), cap, false, budget);
                    String mediaType = types.length == 0 ? "" : types[0].getElements()[0].getName().trim().toLowerCase(java.util.Locale.ROOT);
                    String invalid = invalidResult(mediaType, body, expectTuple);
                    if (invalid != null) {
                        throw new RemoteFailure("the SERVICE endpoint " + host.toURI() + " answered a malformed result: " + invalid);
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
        try {
            if (mediaType.equals("text/boolean")) {
                if (Boolean.TRUE.equals(expectTuple)) {
                    throw new IOException("a boolean answer to a SELECT");
                }
                String text = new String(body, java.nio.charset.StandardCharsets.UTF_8).strip();
                if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) {
                    throw new IOException("not 'true' or 'false'");
                }
                return null;
            }
            if (!mediaType.equals("application/sparql-results+json") && !mediaType.equals("application/json")) {
                return "no parser for '" + mediaType + "'";
            }
            boolean tuple = jsonShape(body);   // true: results, false: boolean
            if (expectTuple != null && tuple != expectTuple) {
                throw new IOException(tuple ? "rows answering an ASK" : "a boolean answer to a SELECT");
            }
            if (tuple) {
                var parser = org.eclipse.rdf4j.query.resultio.QueryResultIO.createTupleParser(
                        org.eclipse.rdf4j.query.resultio.TupleQueryResultFormat.JSON);
                parser.setQueryResultHandler(new org.eclipse.rdf4j.query.AbstractTupleQueryResultHandler() { });
                parser.parseQueryResult(new java.io.ByteArrayInputStream(body));
            } else {
                org.eclipse.rdf4j.query.resultio.QueryResultIO.parseBoolean(new java.io.ByteArrayInputStream(body),
                        org.eclipse.rdf4j.query.resultio.BooleanQueryResultFormat.JSON);
            }
            return null;
        } catch (Exception e) {
            String why = String.valueOf(e.getMessage());
            return why.length() > 300 ? why.substring(0, 300) + "…" : why;
        }
    }

    private static final java.util.Set<String> TERM_TYPES = java.util.Set.of("uri", "literal", "bnode", "typed-literal", "triple");

    /**
     * The shape SPARQL 1.1 Query Results JSON requires: one object, a {@code head} object, and
     * either {@code results.bindings} — then {@code head.vars} an array of strings, every binding
     * an object naming only those variables, every term an object with a {@code type} RDF4J reads
     * and a string {@code value} (an object for an RDF-star triple), {@code xml:lang} and
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
            while (p.nextToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME) {
                String name = p.currentName();
                com.fasterxml.jackson.core.JsonToken value = p.nextToken();
                switch (name) {
                    case "head" -> {
                        expect(value == com.fasterxml.jackson.core.JsonToken.START_OBJECT, "'head' is not an object");
                        head = true;
                        while (p.nextToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME) {
                            String field = p.currentName();
                            com.fasterxml.jackson.core.JsonToken v = p.nextToken();
                            if (field.equals("vars")) {
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
                            if (!field.equals("bindings")) {
                                p.skipChildren();
                                continue;
                            }
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

    /** One RDF term's object, its START_OBJECT already read. */
    private static void term(com.fasterxml.jackson.core.JsonParser p) throws IOException {
        String type = null;
        boolean value = false;
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
                    expect(v == com.fasterxml.jackson.core.JsonToken.VALUE_STRING
                            || v == com.fasterxml.jackson.core.JsonToken.START_OBJECT, "a term's 'value' is not a string");
                    value = true;
                    p.skipChildren();
                }
                case "xml:lang", "datatype" -> expect(v == com.fasterxml.jackson.core.JsonToken.VALUE_STRING,
                        "a term's '" + field + "' is not a string");
                default -> p.skipChildren();
            }
        }
        expect(type != null && value, "a term without 'type' and 'value'");
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
                    java.util.regex.Matcher m = QUERY_FORM.matcher(pair.getValue());
                    return m.find() ? Boolean.valueOf(m.group(1).equalsIgnoreCase("SELECT")) : null;
                }
            }
        } catch (IOException | RuntimeException e) {
            // cannot tell: either form is accepted
        }
        return null;
    }

    /** The query form after the prologue (BASE and PREFIX declarations, comments). */
    private static final java.util.regex.Pattern QUERY_FORM = java.util.regex.Pattern.compile(
            "\\A(?:\\s+|#[^\\n]*\\n|BASE\\s*<[^>]*>|PREFIX\\s+[^:\\s]*:\\s*<[^>]*>)*(SELECT|ASK)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

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
