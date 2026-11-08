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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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

    /** One daemon timer for every instance: it only calls {@code abort()}. */
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sparql-service-deadline");
        t.setDaemon(true);
        return t;
    });

    private final int timeoutMillis;
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
        if (!(request instanceof AbstractExecutionAwareRequest abortable)) {
            return pool().execute(target, request, context);
        }
        java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
        ScheduledFuture<?> deadline = DEADLINES.schedule(() -> {
            fired.set(true);
            abortable.abort();
        }, timeoutMillis, TimeUnit.MILLISECONDS);
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
        // The body is read after execute returns, so the deadline stays armed until the
        // response is closed, and a read it cuts short reports a timeout too: an abort
        // otherwise surfaces as a bare "Socket closed", indistinguishable from any failure.
        return (CloseableHttpResponse) Proxy.newProxyInstance(CloseableHttpResponse.class.getClassLoader(),
                new Class<?>[] {CloseableHttpResponse.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close") && method.getParameterCount() == 0) {
                        deadline.cancel(false);
                    }
                    Object result;
                    try {
                        result = method.invoke(response, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause() instanceof IOException io && fired.get() ? timedOut(io) : e.getCause();
                    }
                    if (method.getName().equals("getEntity") && result instanceof org.apache.http.HttpEntity entity) {
                        return new org.apache.http.entity.HttpEntityWrapper(entity) {
                            @Override
                            public java.io.InputStream getContent() throws IOException {
                                return new java.io.FilterInputStream(super.getContent()) {
                                    @Override
                                    public int read() throws IOException {
                                        try {
                                            return super.read();
                                        } catch (IOException e) {
                                            throw fired.get() ? timedOut(e) : e;
                                        }
                                    }

                                    @Override
                                    public int read(byte[] b, int off, int len) throws IOException {
                                        try {
                                            return super.read(b, off, len);
                                        } catch (IOException e) {
                                            throw fired.get() ? timedOut(e) : e;
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
