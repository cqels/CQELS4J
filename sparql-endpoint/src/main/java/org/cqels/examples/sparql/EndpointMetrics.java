package org.cqels.examples.sparql;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counters for {@code /metrics}, in the Prometheus text format. Before the audit after #121 the
 * endpoint counted nothing, so an operator could not tell a wave of 401s or a full set of query
 * slots from a quiet afternoon.
 *
 * <p>Counts only — no query text, no client address, no token — which is why {@code /metrics}
 * sits beside {@code /health} outside the token, as a scraper expects. {@link #filter()} is
 * installed in front of every other filter on the servlet's path, so a request the host, origin
 * or token filter refuses is counted with its status too.
 */
public final class EndpointMetrics {

    private final Map<Integer, LongAdder> byStatus = new ConcurrentHashMap<>();
    private final LongAdder aborted = new LongAdder();

    /** Counts each request on the path it is installed on, by the status it was answered with. */
    public Filter filter() {
        return new Filter() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                    throws IOException, ServletException {
                try {
                    chain.doFilter(request, response);
                } catch (IOException | ServletException | RuntimeException | Error e) {
                    // the servlet throws to abort a committed response (see its fail()); the
                    // status line already sent says 200, which is not what the client got
                    aborted.increment();
                    throw e;
                }
                byStatus.computeIfAbsent(((HttpServletResponse) response).getStatus(), k -> new LongAdder()).increment();
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
        out.append("# HELP cqels_sparql_requests_total Requests to the SPARQL path, by response status.\n")
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
