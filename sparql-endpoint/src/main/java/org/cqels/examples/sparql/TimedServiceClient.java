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
     * What a SERVICE endpoint is asked for. Not XML: RDF4J parses SPARQL XML results with its
     * default XML settings, under which an external entity in the response read a local file
     * into the results (found in co-review of #123), and its session offers no way to change
     * them. A response that is XML all the same is refused.
     */
    static final String ACCEPT = "application/sparql-results+json, application/json;q=0.9, "
            + "application/x-binary-rdf-results-table;q=0.8, text/tab-separated-values;q=0.7, text/csv;q=0.6, "
            + "text/boolean;q=0.5";

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

    TimedServiceClient(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    private synchronized CloseableHttpClient pool() {
        if (pool == null) {
            pool = HttpClientBuilder.create()
                    .useSystemProperties()
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
        org.apache.http.Header type = response.getFirstHeader("Content-Type");
        if (type != null && type.getValue().toLowerCase(java.util.Locale.ROOT).contains("xml")) {
            deadline.cancel(false);
            response.close();
            throw new IOException("the SERVICE endpoint answered " + type.getValue() + "; this endpoint accepts "
                    + "SPARQL JSON, binary, TSV or CSV results from SERVICE, not XML");
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
