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
 * @param maxRows              SELECT rows, or CONSTRUCT / DESCRIBE statements, beyond this are
 *                             dropped and the response carries {@code X-CQELS-Truncated: true};
 *                             {@code 0} = unlimited and streamed. When set, the result is buffered
 *                             so the header can be decided before the body starts
 * @param maxBufferedBytes     what such a buffer may hold, estimated from the values' lengths;
 *                             reaching it truncates the same way. A row cap alone bounds rows, not
 *                             memory: one computed literal can be megabytes (found in the audit
 *                             after #121, an 8 MB row under {@code maxRows=1}). Not used for
 *                             results when {@code maxRows} is {@code 0}, since nothing is then
 *                             buffered. Also the most a {@code LOAD} source's body may be
 * @param maxUploadSeconds     a POST body must arrive within this; {@code 0} = no deadline. A body
 *                             is read while holding a query slot, so a client that trickles one
 *                             holds that slot, and this is what gives it back. Checked as bytes
 *                             arrive: a connection that stalls completely is ended by the
 *                             container's idle timeout instead (Jetty's default is 30 s)
 * @param updateEnabled        whether SPARQL Update is accepted at all; off by default because
 *                             an update rewrites the graph the engine joins its streams against
 * @param federationEnabled    whether {@code SERVICE} (queries and updates) and {@code LOAD} are
 *                             accepted. Off by default: both make this <em>server</em> send
 *                             requests — with bindings from the graph — to any URL the request
 *                             names, which no browser-side protection can see or stop. Even when
 *                             on, only {@code http} and {@code https} IRIs are accepted; the
 *                             servlet performs {@code LOAD} itself within {@code maxExecutionSeconds},
 *                             and {@code SparqlEndpointLauncher.limitOutboundRequests} gives
 *                             {@code SERVICE} the same deadline
 * @param metadataEnabled      whether the launcher serves {@link SparqlProtocolServlet#METADATA_GRAPH},
 *                             a snapshot of the engine's streams and status ({@link EngineMetadata}).
 *                             Read by {@code SparqlEndpointLauncher.startServer}, which then hands
 *                             the servlet {@code EngineMetadata.of(engine)}; a program constructing
 *                             the servlet itself passes that supplier (or none) directly. Off by
 *                             default: stream attributes are whatever the program that created the
 *                             stream put there, and this endpoint cannot know whether that is fit
 *                             to publish
 * @param authToken            bearer token, or {@code null} for no authentication
 * @param allowedOrigins       browser origins allowed to call the endpoint, or {@code *}; they get
 *                             CORS headers. Enforced by {@link AccessFilters.Origins}, which
 *                             refuses any other {@code Origin} with 403 — the servlet itself does not
 * @param allowedHosts         host names a request's {@code Host} may carry in addition to the
 *                             loopback names and the bind host, or {@code *} for any. Enforced by
 *                             {@link AccessFilters.Hosts} whenever no token is set (or this is
 *                             given explicitly) — the defence against DNS rebinding
 * @param maxRequestBytes      bodies larger than this answer 413 before being parsed
 * @param allowUnauthenticatedRemote  whether {@code SparqlEndpointLauncher.startServer} may bind a
 *                             non-loopback host with no {@code authToken}. Off by default, and the
 *                             refusal is in {@code startServer} itself, not only in the launcher's
 *                             {@code main}: a program embedding the endpoint through
 *                             {@code startServer} skipped it before (found in the audit after #121)
 */
public record EndpointOptions(
        String path,
        int maxExecutionSeconds,
        int maxConcurrentQueries,
        int maxRows,
        long maxBufferedBytes,
        int maxUploadSeconds,
        boolean updateEnabled,
        boolean federationEnabled,
        boolean metadataEnabled,
        String authToken,
        List<String> allowedOrigins,
        List<String> allowedHosts,
        long maxRequestBytes,
        boolean allowUnauthenticatedRemote) {

    public static final String DEFAULT_PATH = "/sparql";
    public static final int DEFAULT_MAX_EXECUTION_SECONDS = 30;   // the MCP server's client-query default
    public static final int DEFAULT_MAX_CONCURRENT_QUERIES = 16;
    public static final long DEFAULT_MAX_REQUEST_BYTES = 1L << 20;
    public static final long DEFAULT_MAX_BUFFERED_BYTES = 32L << 20;
    public static final int DEFAULT_MAX_UPLOAD_SECONDS = 30;

    public EndpointOptions {
        if (path == null || !path.startsWith("/") || path.length() < 2) {
            throw new IllegalArgumentException("path must start with '/' and name a resource: " + path);
        }
        if (maxExecutionSeconds < 0 || maxRows < 0 || maxUploadSeconds < 0 || maxRequestBytes <= 0
                || maxConcurrentQueries <= 0 || maxBufferedBytes <= 0) {
            throw new IllegalArgumentException("limits must be non-negative (request bytes, buffered bytes and "
                    + "concurrent queries positive)");
        }
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        authToken = authToken == null || authToken.isBlank() ? null : authToken;
    }

    /**
     * Loopback-friendly defaults: {@code /sparql}, 30 s, 16 slots, unlimited rows (32 MiB of them
     * when a row cap buffers), 30 s to upload a body, read-only, no federation, no metadata graph,
     * no token, no browser origins, 1 MiB, and no unauthenticated non-loopback bind.
     */
    public static EndpointOptions defaults() {
        return new EndpointOptions(DEFAULT_PATH, DEFAULT_MAX_EXECUTION_SECONDS, DEFAULT_MAX_CONCURRENT_QUERIES, 0,
                DEFAULT_MAX_BUFFERED_BYTES, DEFAULT_MAX_UPLOAD_SECONDS, false, false, false, null, List.of(), List.of(),
                DEFAULT_MAX_REQUEST_BYTES, false);
    }

    public EndpointOptions withPath(String v) {
        return new EndpointOptions(v, maxExecutionSeconds, maxConcurrentQueries, maxRows, maxBufferedBytes, maxUploadSeconds, updateEnabled, federationEnabled, metadataEnabled, authToken, allowedOrigins, allowedHosts, maxRequestBytes, allowUnauthenticatedRemote);
    }

    public EndpointOptions withMaxExecutionSeconds(int v) {
        return new EndpointOptions(path, v, maxConcurrentQueries, maxRows, maxBufferedBytes, maxUploadSeconds, updateEnabled, federationEnabled, metadataEnabled, authToken, allowedOrigins, allowedHosts, maxRequestBytes, allowUnauthenticatedRemote);
    }

    public EndpointOptions withMaxConcurrentQueries(int v) {
        return new EndpointOptions(path, maxExecutionSeconds, v, maxRows, maxBufferedBytes, maxUploadSeconds, updateEnabled, federationEnabled, metadataEnabled, authToken, allowedOrigins, allowedHosts, maxRequestBytes, allowUnauthenticatedRemote);
    }

    public EndpointOptions withMaxRows(int v) {
        return new EndpointOptions(path, maxExecutionSeconds, maxConcurrentQueries, v, maxBufferedBytes, maxUploadSeconds, updateEnabled, federationEnabled, metadataEnabled, authToken, allowedOrigins, allowedHosts, maxRequestBytes, allowUnauthenticatedRemote);
    }

    public EndpointOptions withMaxBufferedBytes(long v) {
        return new EndpointOptions(path, maxExecutionSeconds, maxConcurrentQueries, maxRows, v, maxUploadSeconds, updateEnabled, federationEnabled, metadataEnabled, authToken, allowedOrigins, allowedHosts, maxRequestBytes, allowUnauthenticatedRemote);
    }

    public EndpointOptions withMaxUploadSeconds(int v) {
        return new EndpointOptions(path, maxExecutionSeconds, maxConcurrentQueries, maxRows, maxBufferedBytes, v, updateEnabled, federationEnabled, metadataEnabled, authToken, allowedOrigins, allowedHosts, maxRequestBytes, allowUnauthenticatedRemote);
    }

    public EndpointOptions withUpdateEnabled(boolean v) {
        return new EndpointOptions(path, maxExecutionSeconds, maxConcurrentQueries, maxRows, maxBufferedBytes, maxUploadSeconds, v, federationEnabled, metadataEnabled, authToken, allowedOrigins, allowedHosts, maxRequestBytes, allowUnauthenticatedRemote);
    }

    public EndpointOptions withFederationEnabled(boolean v) {
        return new EndpointOptions(path, maxExecutionSeconds, maxConcurrentQueries, maxRows, maxBufferedBytes, maxUploadSeconds, updateEnabled, v, metadataEnabled, authToken, allowedOrigins, allowedHosts, maxRequestBytes, allowUnauthenticatedRemote);
    }

    public EndpointOptions withMetadataEnabled(boolean v) {
        return new EndpointOptions(path, maxExecutionSeconds, maxConcurrentQueries, maxRows, maxBufferedBytes, maxUploadSeconds, updateEnabled, federationEnabled, v, authToken, allowedOrigins, allowedHosts, maxRequestBytes, allowUnauthenticatedRemote);
    }

    public EndpointOptions withAuthToken(String v) {
        return new EndpointOptions(path, maxExecutionSeconds, maxConcurrentQueries, maxRows, maxBufferedBytes, maxUploadSeconds, updateEnabled, federationEnabled, metadataEnabled, v, allowedOrigins, allowedHosts, maxRequestBytes, allowUnauthenticatedRemote);
    }

    public EndpointOptions withAllowedOrigins(List<String> v) {
        return new EndpointOptions(path, maxExecutionSeconds, maxConcurrentQueries, maxRows, maxBufferedBytes, maxUploadSeconds, updateEnabled, federationEnabled, metadataEnabled, authToken, v, allowedHosts, maxRequestBytes, allowUnauthenticatedRemote);
    }

    public EndpointOptions withAllowedHosts(List<String> v) {
        return new EndpointOptions(path, maxExecutionSeconds, maxConcurrentQueries, maxRows, maxBufferedBytes, maxUploadSeconds, updateEnabled, federationEnabled, metadataEnabled, authToken, allowedOrigins, v, maxRequestBytes, allowUnauthenticatedRemote);
    }

    public EndpointOptions withMaxRequestBytes(long v) {
        return new EndpointOptions(path, maxExecutionSeconds, maxConcurrentQueries, maxRows, maxBufferedBytes, maxUploadSeconds, updateEnabled, federationEnabled, metadataEnabled, authToken, allowedOrigins, allowedHosts, v, allowUnauthenticatedRemote);
    }

    public EndpointOptions withAllowUnauthenticatedRemote(boolean v) {
        return new EndpointOptions(path, maxExecutionSeconds, maxConcurrentQueries, maxRows, maxBufferedBytes, maxUploadSeconds, updateEnabled, federationEnabled, metadataEnabled, authToken, allowedOrigins, allowedHosts, maxRequestBytes, v);
    }
}
