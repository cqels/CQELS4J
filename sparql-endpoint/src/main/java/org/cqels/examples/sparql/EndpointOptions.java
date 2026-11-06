package org.cqels.examples.sparql;

import java.util.List;

/**
 * What the servlet enforces, independent of how it is hosted. The launcher fills this from
 * {@code CQELS_SPARQL_*} environment variables; a program mounting the servlet in its own Jetty
 * builds it directly.
 *
 * @param path                 the servlet path, e.g. {@code /sparql}
 * @param maxExecutionSeconds  passed to {@code Query.setMaxExecutionTime}; {@code 0} = unlimited.
 *                             A query that exceeds it answers 503. <strong>Best effort:</strong>
 *                             RDF4J checks the limit at operator boundaries, and a query that is
 *                             inside an operator with no such boundary runs to completion (see
 *                             the README's measurements) — which is why the slot cap below exists
 * @param maxConcurrentQueries queries and updates evaluating at once; beyond it the request is
 *                             answered 503 with {@code Retry-After} at once instead of queuing.
 *                             Bounds the damage of queries the limit cannot stop
 * @param maxRows              SELECT rows beyond this are dropped and the response carries
 *                             {@code X-CQELS-Truncated: true}; {@code 0} = unlimited. When set, up
 *                             to {@code maxRows + 1} rows are buffered so the header can be
 *                             decided before the body starts — the cap bounds that memory
 * @param updateEnabled        whether SPARQL Update is accepted at all; off by default because
 *                             an update rewrites the graph the engine joins its streams against
 * @param authToken            bearer token, or {@code null} for no authentication
 * @param allowedOrigins       browser origins allowed to call the endpoint, or {@code *}; they get
 *                             CORS headers. Enforced by {@link AccessFilters.Origins}, which
 *                             refuses any other {@code Origin} with 403 — the servlet itself does not
 * @param maxRequestBytes      bodies larger than this answer 413 before being parsed
 */
public record EndpointOptions(
        String path,
        int maxExecutionSeconds,
        int maxConcurrentQueries,
        int maxRows,
        boolean updateEnabled,
        String authToken,
        List<String> allowedOrigins,
        long maxRequestBytes) {

    public static final String DEFAULT_PATH = "/sparql";
    public static final int DEFAULT_MAX_EXECUTION_SECONDS = 30;   // the MCP server's client-query default
    public static final int DEFAULT_MAX_CONCURRENT_QUERIES = 16;
    public static final long DEFAULT_MAX_REQUEST_BYTES = 1L << 20;

    public EndpointOptions {
        if (path == null || !path.startsWith("/") || path.length() < 2) {
            throw new IllegalArgumentException("path must start with '/' and name a resource: " + path);
        }
        if (maxExecutionSeconds < 0 || maxRows < 0 || maxRequestBytes <= 0 || maxConcurrentQueries <= 0) {
            throw new IllegalArgumentException(
                    "limits must be non-negative (request bytes and concurrent queries positive)");
        }
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        authToken = authToken == null || authToken.isBlank() ? null : authToken;
    }

    /** Loopback-friendly defaults: {@code /sparql}, 30 s, 16 slots, unlimited rows, read-only, no token, no CORS, 1 MiB. */
    public static EndpointOptions defaults() {
        return new EndpointOptions(DEFAULT_PATH, DEFAULT_MAX_EXECUTION_SECONDS, DEFAULT_MAX_CONCURRENT_QUERIES, 0,
                false, null, List.of(), DEFAULT_MAX_REQUEST_BYTES);
    }

    public EndpointOptions withPath(String p) {
        return new EndpointOptions(p, maxExecutionSeconds, maxConcurrentQueries, maxRows, updateEnabled, authToken,
                allowedOrigins, maxRequestBytes);
    }

    public EndpointOptions withMaxExecutionSeconds(int s) {
        return new EndpointOptions(path, s, maxConcurrentQueries, maxRows, updateEnabled, authToken, allowedOrigins,
                maxRequestBytes);
    }

    public EndpointOptions withMaxConcurrentQueries(int n) {
        return new EndpointOptions(path, maxExecutionSeconds, n, maxRows, updateEnabled, authToken, allowedOrigins,
                maxRequestBytes);
    }

    public EndpointOptions withMaxRows(int n) {
        return new EndpointOptions(path, maxExecutionSeconds, maxConcurrentQueries, n, updateEnabled, authToken,
                allowedOrigins, maxRequestBytes);
    }

    public EndpointOptions withUpdateEnabled(boolean b) {
        return new EndpointOptions(path, maxExecutionSeconds, maxConcurrentQueries, maxRows, b, authToken,
                allowedOrigins, maxRequestBytes);
    }

    public EndpointOptions withAuthToken(String t) {
        return new EndpointOptions(path, maxExecutionSeconds, maxConcurrentQueries, maxRows, updateEnabled, t,
                allowedOrigins, maxRequestBytes);
    }

    public EndpointOptions withAllowedOrigins(List<String> o) {
        return new EndpointOptions(path, maxExecutionSeconds, maxConcurrentQueries, maxRows, updateEnabled, authToken,
                o, maxRequestBytes);
    }

    public EndpointOptions withMaxRequestBytes(long b) {
        return new EndpointOptions(path, maxExecutionSeconds, maxConcurrentQueries, maxRows, updateEnabled, authToken,
                allowedOrigins, b);
    }
}
