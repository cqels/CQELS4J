package org.cqels.playground.sparql;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counters for {@code /metrics}, in the Prometheus text format. Before the audit after #121 the
 * endpoint counted nothing, so an operator could not tell a wave of 401s or a full set of query
 * slots from a quiet afternoon.
 *
 * <p>Counts only — no query text, no client address, no token — which is why {@code /metrics}
 * sits beside {@code /health} outside the token, as a scraper expects.
 *
 * <p>Statuses are counted by {@link #requestLog}, at the end of each request as Jetty sent it, so
 * a request the host, origin or token filter refuses, Jetty's own error answers and the
 * {@code 503}s of a stop in progress are all counted. Aborts are counted by {@link #handler},
 * from the request's completion. A servlet filter did both at first and missed the stop's
 * {@code 503}s, counted an exception before anything was sent as an abort, and never saw a
 * response that failed in its final write, after the filter had returned (all found in
 * co-review of #128).
 */
public final class EndpointMetrics {

    private final Map<Integer, LongAdder> byStatus = new ConcurrentHashMap<>();
    private final LongAdder aborted = new LongAdder();

    /**
     * Counts each request to {@code path} by the status Jetty sent, whatever answered it. An
     * aborted response is counted under the status it had started with, and in {@link #aborted()}.
     */
    public org.eclipse.jetty.server.RequestLog requestLog(String path) {
        return (request, response) -> {
            if (path.equals(request.getHttpURI().getDecodedPath())) {
                byStatus.computeIfAbsent(response.getStatus(), k -> new LongAdder()).increment();
            }
        };
    }

    /**
     * Counts responses to {@code path} that failed after they had started: the servlet throws to
     * abort a committed response (see its {@code fail()}), or the client went away mid-body, its
     * final write included. Either way the status line already sent is not what the client got.
     * A failure before anything was sent is not an abort; Jetty answers it, and
     * {@link #requestLog} counts that answer. Wraps the handler that serves the path.
     */
    public org.eclipse.jetty.server.Handler.Wrapper handler(String path) {
        return new org.eclipse.jetty.server.Handler.Wrapper() {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response,
                                  org.eclipse.jetty.util.Callback callback) throws Exception {
                if (!path.equals(request.getHttpURI().getDecodedPath())) {
                    return super.handle(request, response, callback);
                }
                return super.handle(request, response, new org.eclipse.jetty.util.Callback() {
                    @Override
                    public void succeeded() {
                        callback.succeeded();
                    }

                    @Override
                    public void failed(Throwable cause) {
                        if (response.isCommitted()) {
                            aborted.increment();
                        }
                        callback.failed(cause);
                    }

                    @Override
                    public InvocationType getInvocationType() {
                        return callback.getInvocationType();
                    }
                });
            }
        };
    }

    /** Requests answered with {@code status} so far. */
    public long answered(int status) {
        LongAdder a = byStatus.get(status);
        return a == null ? 0 : a.sum();
    }

    /** Requests whose response was aborted after it had started. */
    public long aborted() {
        return aborted.sum();
    }

    /**
     * The exposition: requests by status, aborted responses, the servlet's busy rejections and its
     * slots, and whether the engine runs.
     */
    String render(SparqlProtocolServlet servlet, boolean engineRunning) {
        StringBuilder out = new StringBuilder();
        out.append("# HELP cqels_sparql_requests_total Requests to the SPARQL path, by the status sent.\n")
                .append("# TYPE cqels_sparql_requests_total counter\n");
        byStatus.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
                out.append("cqels_sparql_requests_total{code=\"").append(e.getKey()).append("\"} ")
                        .append(e.getValue().sum()).append('\n'));
        out.append("# HELP cqels_sparql_aborted_total Responses aborted after they had started (the execution limit mid-stream, or a client gone).\n")
                .append("# TYPE cqels_sparql_aborted_total counter\n")
                .append("cqels_sparql_aborted_total ").append(aborted.sum()).append('\n');
        out.append("# HELP cqels_sparql_busy_total Requests turned away because every query slot was taken.\n")
                .append("# TYPE cqels_sparql_busy_total counter\n")
                .append("cqels_sparql_busy_total ").append(servlet.busyRejections()).append('\n');
        out.append("# HELP cqels_sparql_slots_in_use Query slots held now.\n")
                .append("# TYPE cqels_sparql_slots_in_use gauge\n")
                .append("cqels_sparql_slots_in_use ").append(servlet.slotsInUse()).append('\n');
        out.append("# HELP cqels_sparql_slots_limit Query slots in all (CQELS_SPARQL_MAX_CONCURRENT_QUERIES).\n")
                .append("# TYPE cqels_sparql_slots_limit gauge\n")
                .append("cqels_sparql_slots_limit ").append(servlet.slotLimit()).append('\n');
        out.append("# HELP cqels_sparql_engine_running Whether the CQELS engine is running (1) or not (0).\n")
                .append("# TYPE cqels_sparql_engine_running gauge\n")
                .append("cqels_sparql_engine_running ").append(engineRunning ? 1 : 0).append('\n');
        return out.toString();
    }
}
