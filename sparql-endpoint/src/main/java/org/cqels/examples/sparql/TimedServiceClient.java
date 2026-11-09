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
 * {@code SERVICE} resolver: every request has an <em>absolute</em> deadline, and the connection
 * pool can be let go and comes back when used again.
 *
 * <p><strong>Why an absolute deadline, not only timeouts.</strong> Apache's connect and socket
 * timeouts bound the wait for each next byte. A remote that sends one byte of its response
 * headers every 200 ms never trips them, and held a query slot for 6.4 s under a 1 s limit
 * (found in co-review of #123). So a request still running at the deadline is aborted from a
 * timer, which ends a read in progress, headers included. The timer is cancelled when the
 * response is closed; one that fires on a request already finished does nothing.
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
     * refused.
     */
    static final String ACCEPT = "application/sparql-results+json, application/json;q=0.9, "
            + "text/tab-separated-values;q=0.7, text/csv;q=0.6, text/boolean;q=0.5";

    /** The only media types a SERVICE answer may declare; see {@link #ACCEPT}. */
    private static final java.util.Set<String> ALLOWED = java.util.Set.of("application/sparql-results+json",
            "application/json", "text/tab-separated-values", "text/csv", "text/boolean");

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
        this(timeoutMillis, org.apache.http.impl.conn.SystemDefaultDnsResolver.INSTANCE);
    }

    /** {@code dns} is the resolver the deadline is put around; tests pass one that stalls. */
    TimedServiceClient(int timeoutMillis, org.apache.http.conn.DnsResolver dns) {
        this.timeoutMillis = timeoutMillis;
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
            try {
                return lookup.get(timeoutMillis, TimeUnit.MILLISECONDS);
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

    private synchronized CloseableHttpClient pool() {
        if (pool == null) {
            pool = HttpClientBuilder.create()
                    .useSystemProperties()
                    .setDnsResolver(dns)
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

    @Override
    protected CloseableHttpResponse doExecute(HttpHost target, HttpRequest request, HttpContext context)
            throws IOException {
        // With no execution limit (timeoutMillis 0) there is no deadline, but the format rules
        // below still hold: skipping the whole client for a 0 limit let an XML answer through
        // (found in co-review of #123).
        java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
        ScheduledFuture<?> deadline = timeoutMillis > 0 && request instanceof AbstractExecutionAwareRequest abortable
                ? DEADLINES.schedule(() -> {
                    fired.set(true);
                    abortable.abort();
                }, timeoutMillis, TimeUnit.MILLISECONDS)
                : NO_DEADLINE;
        request.setHeader("Accept", ACCEPT);
        CloseableHttpResponse response;
        try {
            response = pool().execute(target, request, context);
        } catch (IOException e) {
            deadline.cancel(false);
            throw fired.get() ? timedOut(e) : e;
        } catch (RuntimeException e) {
            deadline.cancel(false);
            throw e;
        }
        org.apache.http.Header[] types = response.getHeaders("Content-Type");
        if (refused(types)) {
            deadline.cancel(false);
            response.close();
            throw new IOException("the SERVICE endpoint answered Content-Type " + java.util.Arrays.stream(types)
                    .map(h -> "'" + h.getValue() + "'").collect(java.util.stream.Collectors.joining(", "))
                    + "; this endpoint accepts one of " + ALLOWED + " from SERVICE");
        }
        if (response.getEntity() == null) {
            // Nothing left to read, and RDF4J does not close such a response (a 204, say), so
            // neither disarming path below would run: the timer kept the request for the whole
            // deadline (found in co-review of #123).
            deadline.cancel(false);
            return response;
        }
        // The body is read after execute returns, so the deadline stays armed until the
        // response is closed, and a read it cuts short reports a timeout too: an abort
        // otherwise surfaces as a bare "Socket closed", indistinguishable from any failure.
        return (CloseableHttpResponse) Proxy.newProxyInstance(CloseableHttpResponse.class.getClassLoader(),
                new Class<?>[] {CloseableHttpResponse.class}, (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(response, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause() instanceof IOException io && fired.get() ? timedOut(io) : e.getCause();
                    } finally {
                        // disarmed only once closing has finished: closing may still read
                        if (method.getName().equals("close") && method.getParameterCount() == 0) {
                            deadline.cancel(false);
                        }
                    }
                    if (method.getName().equals("getEntity") && result instanceof org.apache.http.HttpEntity entity) {
                        return new org.apache.http.entity.HttpEntityWrapper(entity) {
                            @Override
                            public java.io.InputStream getContent() throws IOException {
                                // the deadline is also disarmed once the body is read to its
                                // end or closed: RDF4J does not always close the response
                                return new java.io.FilterInputStream(super.getContent()) {
                                    private boolean ended;

                                    @Override
                                    public int read() throws IOException {
                                        try {
                                            int c = super.read();
                                            if (c < 0) {
                                                ended = true;
                                                deadline.cancel(false);
                                            }
                                            return c;
                                        } catch (IOException e) {
                                            throw fired.get() ? timedOut(e) : e;
                                        }
                                    }

                                    @Override
                                    public int read(byte[] b, int off, int len) throws IOException {
                                        try {
                                            int n = super.read(b, off, len);
                                            if (n < 0) {
                                                ended = true;
                                                deadline.cancel(false);
                                            }
                                            return n;
                                        } catch (IOException e) {
                                            throw fired.get() ? timedOut(e) : e;
                                        }
                                    }

                                    /**
                                     * Closing a body not read to its end makes Apache read the rest,
                                     * to reuse the connection — and a remote trickling that rest held
                                     * the slot through it, the deadline already disarmed (found in
                                     * co-review of #123). So an unfinished body aborts the request,
                                     * which drops the connection instead, and the deadline stays
                                     * armed until closing is done.
                                     */
                                    @Override
                                    public void close() throws IOException {
                                        try {
                                            if (!ended && request instanceof AbstractExecutionAwareRequest a) {
                                                a.abort();
                                            }
                                            super.close();
                                        } catch (IOException e) {
                                            if (!ended) {
                                                return;   // the abort we asked for: nothing more to close
                                            }
                                            throw e;
                                        } finally {
                                            deadline.cancel(false);
                                        }
                                    }
                                };
                            }
                        };
                    }
                    return result;
                });
    }

    private java.net.SocketTimeoutException timedOut(IOException cause) {
        java.net.SocketTimeoutException e = new java.net.SocketTimeoutException(
                "SERVICE request aborted at its deadline of " + timeoutMillis + " ms");
        e.initCause(cause);
        return e;
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
