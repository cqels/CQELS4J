package org.cqels.examples.sparql;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.rdf4j.common.lang.FileFormat;
import org.eclipse.rdf4j.common.lang.service.FileFormatServiceRegistry;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.BooleanQuery;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.GraphQuery;
import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.query.Query;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.QueryInterruptedException;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.Update;
import org.eclipse.rdf4j.query.UpdateExecutionException;
import org.eclipse.rdf4j.query.impl.SimpleDataset;
import org.eclipse.rdf4j.query.resultio.BooleanQueryResultFormat;
import org.eclipse.rdf4j.query.resultio.BooleanQueryResultWriter;
import org.eclipse.rdf4j.query.resultio.BooleanQueryResultWriterRegistry;
import org.eclipse.rdf4j.query.resultio.QueryResultFormat;
import org.eclipse.rdf4j.query.resultio.TupleQueryResultFormat;
import org.eclipse.rdf4j.query.resultio.TupleQueryResultWriter;
import org.eclipse.rdf4j.query.resultio.TupleQueryResultWriterRegistry;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.RDFWriterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * The SPARQL 1.1 Protocol over whatever {@link Repository} the supplier returns — for this
 * project, the CQELS engine's background graph ({@code engine.getRepository()}).
 *
 * <p><strong>What this is and is not.</strong> It is RDF4J's protocol <em>library</em> layer
 * (result-writer registries, Rio, the query API) behind a plain {@code jakarta.servlet}. It is
 * not RDF4J Server: no repository management, no transactions, no Workbench. Those are store
 * features; the README names RDF4J Server as the deployment for them. The only code that
 * duplicates RDF4J is this adapter from HTTP to the query API, which RDF4J ships only as a
 * Spring MVC controller on {@code javax.servlet} (4.3.8) and which therefore cannot be mounted
 * in the {@code jakarta} Jetty that {@code cqels-mcp} embeds.
 *
 * <p><strong>Why it takes a {@code Supplier<Repository>} and not a store path.</strong> A
 * NativeStore holds a directory lock; the endpoint must share the engine's repository object,
 * never open a second store on the same path. One connection is opened per request and closed
 * in {@code finally}; the memory and native stores both allow concurrent readers beside the
 * engine's own static lookups.
 *
 * <p><strong>Blocking on the request thread is deliberate.</strong> RDF4J evaluation is
 * blocking end to end (iterator results, {@code OutputStream} writers); a reactive front end
 * would only move the same blocking work to another pool. Results stream to the response —
 * nothing is buffered unless {@link EndpointOptions#maxRows()} asks for a bounded buffer.
 *
 * <p>Protocol mapping (SPARQL 1.1 Protocol §2):
 * <pre>
 *   GET  ?query=…                                         query via GET
 *   POST application/x-www-form-urlencoded  query=|update= query / update via URL-encoded POST
 *   POST application/sparql-query                         query via direct POST
 *   POST application/sparql-update                        update via direct POST
 *   default-graph-uri / named-graph-uri                   query dataset
 *   using-graph-uri / using-named-graph-uri               update dataset
 * </pre>
 * Status codes: 400 malformed query or bad graph IRI, 403 update while disabled, 405 update via
 * GET, 406 no acceptable result format, 413 body over the limit, 415 unknown POST content type,
 * 500 evaluation failure, 503 execution time exceeded or every query slot busy (then with
 * {@code Retry-After}).
 *
 * <p><strong>Why there are query slots.</strong> {@code setMaxExecutionTime} is checked by RDF4J
 * at operator boundaries. Measured on 4.3.8's memory store with a 1-second limit over 300
 * triples: a streaming 4-way cross product and an {@code ORDER BY} over it stop within 1.1 s, a
 * {@code COUNT} over a 3-way cross product stops after 3 s, and a {@code COUNT} over the 4-way
 * cross product does not stop at all — not for the limit, not when the connection is closed from
 * another thread, not when its thread is interrupted. Such a query holds its thread until it
 * finishes. The slots bound how many can; the rest are told to retry, and {@code /health}
 * stays reachable because it needs no slot.
 */
public final class SparqlProtocolServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(SparqlProtocolServlet.class);
    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    static final String TRUNCATED_HEADER = "X-CQELS-Truncated";

    private final transient Supplier<Repository> repository;
    private final EndpointOptions options;
    private final transient Semaphore slots;

    public SparqlProtocolServlet(Supplier<Repository> repository, EndpointOptions options) {
        this.repository = repository;
        this.options = options;
        this.slots = new Semaphore(options.maxConcurrentQueries());
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (req.getParameter("update") != null) {
            // §2.2: update requests are POST only. Refusing here rather than ignoring the
            // parameter keeps a misconfigured client from believing its update ran.
            fail(resp, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "SPARQL Update is not accepted via GET");
            return;
        }
        String query = req.getParameter("query");
        if (query == null) {
            fail(resp, HttpServletResponse.SC_BAD_REQUEST, "missing 'query' parameter");
            return;
        }
        handleQuery(req, resp, query);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long declared = req.getContentLengthLong();
        if (declared > options.maxRequestBytes()) {
            fail(resp, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    "request body exceeds " + options.maxRequestBytes() + " bytes");
            return;
        }
        String type = mediaType(req.getContentType());
        switch (type) {
            case "application/x-www-form-urlencoded" -> {
                // Jetty parses the form body; the size check above ran before it did.
                String update = req.getParameter("update");
                String query = req.getParameter("query");
                if (update != null && query != null) {
                    fail(resp, HttpServletResponse.SC_BAD_REQUEST, "send either 'query' or 'update', not both");
                } else if (update != null) {
                    handleUpdate(req, resp, update);
                } else if (query != null) {
                    handleQuery(req, resp, query);
                } else {
                    fail(resp, HttpServletResponse.SC_BAD_REQUEST, "missing 'query' or 'update' parameter");
                }
            }
            case "application/sparql-query" -> handleQuery(req, resp, readBody(req, resp));
            case "application/sparql-update" -> handleUpdate(req, resp, readBody(req, resp));
            default -> fail(resp, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                    "unsupported Content-Type '" + type + "'; use application/x-www-form-urlencoded, "
                            + "application/sparql-query or application/sparql-update");
        }
    }

    // ---- query -------------------------------------------------------------------------

    private void handleQuery(HttpServletRequest req, HttpServletResponse resp, String queryString)
            throws IOException {
        if (queryString == null) {
            return;   // readBody already answered 413
        }
        if (!slots.tryAcquire()) {
            busy(resp);
            return;
        }
        try {
            evaluateQuery(req, resp, queryString);
        } finally {
            slots.release();
        }
    }

    private void evaluateQuery(HttpServletRequest req, HttpServletResponse resp, String queryString)
            throws IOException {
        try (RepositoryConnection conn = repository.get().getConnection()) {
            Query query;
            try {
                query = conn.prepareQuery(QueryLanguage.SPARQL, queryString);
            } catch (MalformedQueryException e) {
                fail(resp, HttpServletResponse.SC_BAD_REQUEST, "malformed query: " + e.getMessage());
                return;
            }
            Dataset dataset = dataset(req, "default-graph-uri", "named-graph-uri");
            if (dataset != null) {
                query.setDataset(dataset);
            }
            if (options.maxExecutionSeconds() > 0) {
                query.setMaxExecutionTime(options.maxExecutionSeconds());
            }
            String accept = req.getHeader("Accept");

            // The tuple and boolean registries are keyed by the shared QueryResultFormat type,
            // hence the widened defaults here.
            if (query instanceof TupleQuery tq) {
                Optional<QueryResultFormat> fmt = negotiate(accept, TupleQueryResultWriterRegistry.getInstance(),
                        (QueryResultFormat) TupleQueryResultFormat.JSON);
                if (fmt.isEmpty()) {
                    notAcceptable(resp, accept);
                    return;
                }
                writeTuples(resp, tq, fmt.get());
            } else if (query instanceof GraphQuery gq) {
                Optional<RDFFormat> fmt = negotiate(accept, RDFWriterRegistry.getInstance(), RDFFormat.TURTLE);
                if (fmt.isEmpty()) {
                    notAcceptable(resp, accept);
                    return;
                }
                RDFWriter writer = RDFWriterRegistry.getInstance().get(fmt.get()).orElseThrow()
                        .getWriter(begin(resp, fmt.get()));
                gq.evaluate(writer);
            } else if (query instanceof BooleanQuery bq) {
                Optional<QueryResultFormat> fmt = negotiate(accept, BooleanQueryResultWriterRegistry.getInstance(),
                        (QueryResultFormat) BooleanQueryResultFormat.JSON);
                if (fmt.isEmpty()) {
                    notAcceptable(resp, accept);
                    return;
                }
                boolean answer = bq.evaluate();   // evaluate BEFORE committing, so a timeout can still be a 503
                BooleanQueryResultWriter writer = BooleanQueryResultWriterRegistry.getInstance().get(fmt.get())
                        .orElseThrow().getWriter(begin(resp, fmt.get()));
                writer.handleBoolean(answer);
            } else {
                fail(resp, HttpServletResponse.SC_BAD_REQUEST, "unsupported query form");
            }
        } catch (IllegalArgumentException e) {
            fail(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (QueryInterruptedException e) {
            fail(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "query exceeded the execution limit of " + options.maxExecutionSeconds() + " s");
        } catch (QueryEvaluationException e) {
            log.warn("query evaluation failed: {}", e.getMessage());
            fail(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "query evaluation failed: " + e.getMessage());
        } catch (RuntimeException e) {
            // Rio and the result writers wrap a client that went away in a handler exception;
            // once the response is committed there is nobody left to tell.
            if (resp.isCommitted()) {
                log.debug("response aborted mid-stream: {}", e.toString());
            } else {
                log.warn("unexpected failure answering a query", e);
                fail(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal error: " + e.getMessage());
            }
        }
    }

    private void writeTuples(HttpServletResponse resp, TupleQuery tq, QueryResultFormat fmt) throws IOException {
        var factory = TupleQueryResultWriterRegistry.getInstance().get(fmt).orElseThrow();
        if (options.maxRows() == 0) {
            TupleQueryResultWriter writer = factory.getWriter(begin(resp, fmt));
            tq.evaluate(writer);   // streams; a timeout before the first row is still a clean 503
            return;
        }
        // Bounded buffer: the truncation header must be decided before the body starts, and
        // maxRows is exactly the bound on what is held. Evaluation stops at maxRows + 1.
        List<String> names;
        List<BindingSet> rows = new ArrayList<>();
        boolean truncated = false;
        try (TupleQueryResult result = tq.evaluate()) {
            names = result.getBindingNames();
            while (result.hasNext()) {
                if (rows.size() >= options.maxRows()) {
                    truncated = true;
                    break;
                }
                rows.add(result.next());
            }
        }
        if (truncated) {
            resp.setHeader(TRUNCATED_HEADER, "true");
        }
        TupleQueryResultWriter writer = factory.getWriter(begin(resp, fmt));
        writer.startQueryResult(names);
        for (BindingSet row : rows) {
            writer.handleSolution(row);
        }
        writer.endQueryResult();
    }

    // ---- update ------------------------------------------------------------------------

    private void handleUpdate(HttpServletRequest req, HttpServletResponse resp, String updateString)
            throws IOException {
        if (updateString == null) {
            return;   // readBody already answered 413
        }
        if (!options.updateEnabled()) {
            fail(resp, HttpServletResponse.SC_FORBIDDEN,
                    "SPARQL Update is disabled on this endpoint (CQELS_SPARQL_UPDATE_ENABLED=true enables it)");
            return;
        }
        if (!slots.tryAcquire()) {
            busy(resp);
            return;
        }
        try {
            executeUpdate(req, resp, updateString);
        } finally {
            slots.release();
        }
    }

    private void executeUpdate(HttpServletRequest req, HttpServletResponse resp, String updateString)
            throws IOException {
        try (RepositoryConnection conn = repository.get().getConnection()) {
            Update update;
            try {
                update = conn.prepareUpdate(QueryLanguage.SPARQL, updateString);
            } catch (MalformedQueryException e) {
                fail(resp, HttpServletResponse.SC_BAD_REQUEST, "malformed update: " + e.getMessage());
                return;
            }
            Dataset dataset = dataset(req, "using-graph-uri", "using-named-graph-uri");
            if (dataset != null) {
                update.setDataset(dataset);
            }
            if (options.maxExecutionSeconds() > 0) {
                update.setMaxExecutionTime(options.maxExecutionSeconds());
            }
            // The engine sees the change on its next element: it listens to its own Sail for
            // lookup-cache invalidation (measured on 2.0.0-alpha.21, even under FROM <g> CACHE 5m).
            update.execute();
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
        } catch (IllegalArgumentException e) {
            fail(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (QueryInterruptedException e) {
            fail(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "update exceeded the execution limit of " + options.maxExecutionSeconds() + " s");
        } catch (UpdateExecutionException e) {
            log.warn("update failed: {}", e.getMessage());
            fail(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "update failed: " + e.getMessage());
        }
    }

    // ---- helpers -----------------------------------------------------------------------

    /** Starts the response for a successful result: status, media type, charset, and the body stream. */
    private static OutputStream begin(HttpServletResponse resp, FileFormat format) throws IOException {
        String type = format.getDefaultMIMEType();
        if (format.hasCharset()) {
            Charset cs = format.getCharset() == null ? StandardCharsets.UTF_8 : format.getCharset();
            type += "; charset=" + cs.name();
        }
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType(type);
        return resp.getOutputStream();
    }

    /**
     * Content negotiation against one of RDF4J's writer registries. Media ranges are honoured in
     * {@code q} order; {@code *&#47;*} and a missing header select the default; {@code type/*}
     * selects the first registered format of that type. Empty means nothing matched: 406.
     */
    static <F extends FileFormat, S> Optional<F> negotiate(String accept, FileFormatServiceRegistry<F, S> registry,
                                                           F dflt) {
        if (accept == null || accept.isBlank()) {
            return Optional.of(dflt);
        }
        record Range(String type, double q, int order) { }
        List<Range> ranges = new ArrayList<>();
        int order = 0;
        for (String part : accept.split(",")) {
            String[] pieces = part.trim().split(";");
            String type = pieces[0].trim().toLowerCase(Locale.ROOT);
            if (type.isEmpty()) {
                continue;
            }
            double q = 1.0;
            for (int i = 1; i < pieces.length; i++) {
                String p = pieces[i].trim();
                if (p.startsWith("q=")) {
                    try {
                        q = Double.parseDouble(p.substring(2));
                    } catch (NumberFormatException ignored) {
                        q = 0;
                    }
                }
            }
            if (q > 0) {
                ranges.add(new Range(type, q, order++));
            }
        }
        ranges.sort(Comparator.comparingDouble(Range::q).reversed().thenComparingInt(Range::order));
        for (Range r : ranges) {
            if (r.type().equals("*/*")) {
                return Optional.of(dflt);
            }
            if (r.type().endsWith("/*")) {
                String primary = r.type().substring(0, r.type().length() - 1);
                if (dflt.getDefaultMIMEType().startsWith(primary)) {
                    return Optional.of(dflt);
                }
                for (F f : registry.getKeys()) {
                    if (f.getDefaultMIMEType().startsWith(primary)) {
                        return Optional.of(f);
                    }
                }
                continue;
            }
            Optional<F> exact = registry.getFileFormatForMIMEType(r.type());
            if (exact.isPresent()) {
                return exact;
            }
        }
        return Optional.empty();
    }

    /** Builds the protocol dataset from repeated graph parameters; {@code null} when none were sent. */
    private static Dataset dataset(HttpServletRequest req, String defaultParam, String namedParam) {
        String[] defaults = req.getParameterValues(defaultParam);
        String[] named = req.getParameterValues(namedParam);
        if ((defaults == null || defaults.length == 0) && (named == null || named.length == 0)) {
            return null;
        }
        SimpleDataset ds = new SimpleDataset();
        if (defaults != null) {
            for (String g : defaults) {
                ds.addDefaultGraph(iri(defaultParam, g));
            }
        }
        if (named != null) {
            for (String g : named) {
                ds.addNamedGraph(iri(namedParam, g));
            }
        }
        return ds;
    }

    private static IRI iri(String param, String value) {
        try {
            return VF.createIRI(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + param + "' is not an absolute IRI: " + value);
        }
    }

    /** Reads a direct-POST body within the size limit; answers 413 and returns {@code null} beyond it. */
    private String readBody(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long max = options.maxRequestBytes();
        byte[] buf = new byte[(int) Math.min(max + 1, 64 * 1024)];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (InputStream in = req.getInputStream()) {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > max) {
                    fail(resp, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                            "request body exceeds " + max + " bytes");
                    return null;
                }
            }
        }
        String enc = req.getCharacterEncoding();
        Charset cs = enc == null ? StandardCharsets.UTF_8 : Charset.forName(enc);
        return out.toString(cs);
    }

    private static String mediaType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semi = contentType.indexOf(';');
        return (semi < 0 ? contentType : contentType.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
    }

    /** Every slot is taken: answer at once rather than queue behind queries that may never return. */
    private void busy(HttpServletResponse resp) throws IOException {
        fail(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "too many concurrent queries (limit "
                + options.maxConcurrentQueries() + "); retry shortly");
        resp.setHeader("Retry-After", "1");   // after fail(): fail() resets the response first
    }

    private static void notAcceptable(HttpServletResponse resp, String accept) throws IOException {
        fail(resp, HttpServletResponse.SC_NOT_ACCEPTABLE, "no result format matches Accept: " + accept);
    }

    private static void fail(HttpServletResponse resp, int status, String message) throws IOException {
        if (resp.isCommitted()) {
            log.debug("cannot report {} after commit: {}", status, message);
            return;
        }
        resp.reset();
        resp.setStatus(status);
        resp.setContentType("text/plain; charset=UTF-8");
        resp.getWriter().write(message + "\n");
    }
}
