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
     * JSON and TSV keep every term as it was.
     */
    static final String ACCEPT = "application/sparql-results+json, application/json;q=0.9, "
            + "text/tab-separated-values;q=0.7, text/boolean;q=0.5";

    /** The only media types a SERVICE answer may declare; see {@link #ACCEPT}. */
    private static final java.util.Set<String> ALLOWED = java.util.Set.of("application/sparql-results+json",
            "application/json", "text/tab-separated-values", "text/boolean");

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
        this.dns = timeoutMillis > 0 ? new BoundedDns(dns, timeoutMillis) : dns;
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

    /**
     * Apache's own redirect rules (GET and HEAD follow 301, 302, 303, 307 and 308), but only to
     * http or https, and never from https down to http — the rules the servlet's LOAD keeps. Apache's
     * default followed an https SERVICE to plain http, and, through a JVM proxy, to other schemes
     * (found in the SERVICE audit after #128).
     */
    static final org.apache.http.impl.client.DefaultRedirectStrategy HTTP_ONLY_REDIRECTS =
            new org.apache.http.impl.client.DefaultRedirectStrategy() {
                @Override
                protected java.net.URI createLocationURI(String location) throws org.apache.http.ProtocolException {
                    java.net.URI uri = super.createLocationURI(location);
                    String scheme = uri.getScheme();
                    if (scheme != null && !"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                        throw new org.apache.http.ProtocolException("a SERVICE endpoint redirected to <" + strip(uri)
                                + ">: only http and https are followed");
                    }
                    return uri;
                }

                @Override
                public java.net.URI getLocationURI(HttpRequest request, org.apache.http.HttpResponse response,
                                                   HttpContext context) throws org.apache.http.ProtocolException {
                    java.net.URI next = super.getLocationURI(request, response, context);
                    HttpHost from = org.apache.http.client.protocol.HttpClientContext.adapt(context).getTargetHost();
                    if (from != null && "https".equalsIgnoreCase(from.getSchemeName())
                            && "http".equalsIgnoreCase(next.getScheme())) {
                        throw new org.apache.http.ProtocolException("a SERVICE endpoint redirected from https to <"
                                + strip(next) + ">: a redirect may not drop to plain http");
                    }
                    return next;
                }
            };

    private synchronized CloseableHttpClient pool() {
        if (pool == null) {
            pool = HttpClientBuilder.create()
                    .useSystemProperties()
                    .setDnsResolver(dns)
                    .setRedirectStrategy(HTTP_ONLY_REDIRECTS)
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
     * One SERVICE request, read to its end before RDF4J sees it.
     *
     * <p><strong>Why the body is read here, whole.</strong> RDF4J's {@code SERVICE SILENT} keeps the
     * query's own rows when a remote fails before answering, but drops every one of them when the
     * answer breaks off midway: the failure then surfaces while RDF4J iterates, where its silent
     * iteration ends the results instead (found in the SERVICE audit after #128: a cut-off body
     * answered 200 with no rows, and an update answered 204 having written nothing). Read here,
     * a body that breaks off fails the request itself, which SILENT handles as the standard says.
     * Reading it here also bounds it: at most {@code maxResponseBytes}, after Apache has undone
     * any gzip or deflate, so neither an endless answer nor a compressed one can grow the heap.
     *
     * <p><strong>Status before format.</strong> A remote's 403 or 503 is answered in plain text,
     * and checking the media type first reported "answered Content-Type text/plain" instead of the
     * remote's own reason (found in the same audit). A non-2xx answer now fails as
     * {@link RemoteFailure}, quoting the start of its body; the servlet answers 502.
     *
     * <p><strong>The query's budget.</strong> Within a {@link Budget} — the servlet opens one per
     * query and update — every request also counts against the query's own deadline, request
     * count and bytes, so the query's execution limit bounds its SERVICE calls as a whole rather
     * than each of them: 20 requests of 1.5 s each ran a query for 30 s under a 2 s limit (found
     * in the same audit).
     */
    @Override
    protected CloseableHttpResponse doExecute(HttpHost target, HttpRequest request, HttpContext context)
            throws IOException {
        if (target == null || !("http".equalsIgnoreCase(target.getSchemeName())
                || "https".equalsIgnoreCase(target.getSchemeName()))) {
            throw new Refusal(403, "SERVICE may name only http and https endpoints");
        }
        Budget budget = Budget.CURRENT.get();
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
        if (timeout > 0) {
            BoundedDns.DEADLINE.set(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout));
        }
        ScheduledFuture<?> deadline = timeout > 0 && request instanceof AbstractExecutionAwareRequest abortable
                ? DEADLINES.schedule(() -> {
                    fired.set(true);
                    abortable.abort();
                }, timeout, TimeUnit.MILLISECONDS)
                : NO_DEADLINE;
        request.setHeader("Accept", ACCEPT);
        CloseableHttpResponse response;
        try {
            response = pool().execute(target, request, context);
        } catch (IOException e) {
            deadline.cancel(false);
            throw fired.get() ? timedOut(e, timeoutUsed, budget) : e;
        } catch (RuntimeException e) {
            deadline.cancel(false);
            throw e;
        } catch (Error e) {
            // Apache shuts its connection manager down on an Error (an OutOfMemoryError, say);
            // kept, that pool failed every later SERVICE with "Connection pool shut down" (found
            // in co-review of #123). Let it go so the next request opens a new one.
            deadline.cancel(false);
            release();
            throw e;
        } finally {
            BoundedDns.DEADLINE.remove();
        }
        boolean done = false;
        try {
            int status = response.getStatusLine().getStatusCode();
            org.apache.http.HttpEntity entity = response.getEntity();
            if (status / 100 != 2) {
                String excerpt = entity == null ? "" : new String(readCapped(entity.getContent(), 512, true),
                        java.nio.charset.StandardCharsets.UTF_8).strip();
                throw new RemoteFailure("the SERVICE endpoint " + target.toURI() + " answered HTTP " + status
                        + (excerpt.isEmpty() ? "" : ": " + excerpt));
            }
            if (entity == null) {
                done = true;
                return response;   // a 204, say: nothing to read
            }
            org.apache.http.Header[] types = response.getHeaders("Content-Type");
            if (refused(types)) {
                throw new RemoteFailure("the SERVICE endpoint " + target.toURI() + " answered Content-Type "
                        + java.util.Arrays.stream(types).map(h -> "'" + h.getValue() + "'")
                        .collect(java.util.stream.Collectors.joining(", "))
                        + "; this endpoint accepts one of " + ALLOWED + " from SERVICE");
            }
            byte[] body;
            try {
                body = readCapped(entity.getContent(), cap, false);
            } catch (Refusal tooBig) {
                throw budget == null ? tooBig : budget.trip(tooBig);
            }
            if (budget != null) {
                budget.addBytes(body.length);
            }
            String mediaType = types.length == 0 ? "" : types[0].getElements()[0].getName().trim().toLowerCase(java.util.Locale.ROOT);
            String invalid = invalidResult(mediaType, body);
            if (invalid != null) {
                throw new RemoteFailure("the SERVICE endpoint " + target.toURI() + " answered a malformed result: " + invalid);
            }
            org.apache.http.entity.ByteArrayEntity held = new org.apache.http.entity.ByteArrayEntity(body);
            held.setContentType(entity.getContentType());
            response.setEntity(held);
            done = true;
            return response;
        } catch (IOException e) {
            throw fired.get() ? timedOut(e, timeoutUsed, budget) : e;
        } finally {
            deadline.cancel(false);
            if (!done) {
                // whatever is left unread is dropped with the connection, not drained from it
                if (request instanceof AbstractExecutionAwareRequest a) {
                    a.abort();
                }
                try {
                    response.close();
                } catch (IOException ignored) {
                    // aborted: nothing more to close
                }
            }
        }
    }

    /**
     * Why {@code body} is not a SPARQL result in {@code mediaType}, or {@code null} when it is one —
     * a tuple result, or for JSON and {@code text/boolean} a boolean one. Parsed here, once, so
     * that a malformed answer fails the request itself: left to RDF4J, the parse failed while it
     * iterated, where {@code SERVICE SILENT} dropped the query's own rows instead of keeping them
     * (found in the SERVICE audit after #128, with a remote whose JSON broke off inside a
     * complete HTTP body). RDF4J parses the same bytes again; the cost is bounded by the byte cap.
     */
    static String invalidResult(String mediaType, byte[] body) {
        org.eclipse.rdf4j.query.resultio.QueryResultFormat tuple = switch (mediaType) {
            case "application/sparql-results+json", "application/json" -> org.eclipse.rdf4j.query.resultio.TupleQueryResultFormat.JSON;
            case "text/tab-separated-values" -> org.eclipse.rdf4j.query.resultio.TupleQueryResultFormat.TSV;
            default -> null;
        };
        org.eclipse.rdf4j.query.resultio.QueryResultFormat bool = switch (mediaType) {
            case "application/sparql-results+json", "application/json" -> org.eclipse.rdf4j.query.resultio.BooleanQueryResultFormat.JSON;
            case "text/boolean" -> org.eclipse.rdf4j.query.resultio.BooleanQueryResultFormat.TEXT;
            default -> null;
        };
        String why = "no parser for '" + mediaType + "'";
        if (tuple != null) {
            try {
                var parser = org.eclipse.rdf4j.query.resultio.QueryResultIO.createTupleParser(tuple);
                parser.setQueryResultHandler(new org.eclipse.rdf4j.query.AbstractTupleQueryResultHandler() { });
                parser.parseQueryResult(new java.io.ByteArrayInputStream(body));
                return null;
            } catch (Exception e) {
                why = String.valueOf(e.getMessage());
            }
        }
        if (bool != null) {
            try {
                org.eclipse.rdf4j.query.resultio.QueryResultIO.parseBoolean(new java.io.ByteArrayInputStream(body), bool);
                return null;
            } catch (Exception e) {
                if (tuple == null) {
                    why = String.valueOf(e.getMessage());
                }
            }
        }
        return why.length() > 300 ? why.substring(0, 300) + "…" : why;
    }

    /**
     * {@code in} read to its end, or to {@code cap} bytes: beyond that, {@link Refusal} (502), or
     * with {@code truncate} the first {@code cap} bytes (for an error answer's excerpt).
     *
     * <p>{@code in} is not closed here. Closing an Apache body before its end makes Apache read
     * the rest, to reuse the connection: stopping at a 16 MiB cap then drained a 1 GB answer for
     * 3.5 s (found while fixing the SERVICE audit after #128). The caller aborts the request
     * first, which drops the connection instead; a body read to its end releases it by itself.
     */
    static byte[] readCapped(java.io.InputStream in, long cap, boolean truncate) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (out.size() + (long) n > cap) {
                if (truncate) {
                    out.write(buf, 0, (int) (cap - out.size()));
                    return out.toByteArray();
                }
                throw new Refusal(502, "a SERVICE endpoint answered more than " + cap
                        + " bytes, the most this endpoint takes from SERVICE in one query");
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
        private final long maxBytes;
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

        void countRequest() throws Refusal {
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
