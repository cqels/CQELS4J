package org.cqels.examples.sparql;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.rdf4j.common.lang.FileFormat;
import org.eclipse.rdf4j.common.net.ParsedIRI;
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
import org.eclipse.rdf4j.query.impl.SimpleDataset;
import org.eclipse.rdf4j.query.parser.ParsedUpdate;
import org.eclipse.rdf4j.query.parser.QueryParserUtil;
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
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 * The base IRI for relative IRIs (§2.3) is the request URL. Status codes: 400 malformed query,
 * bad graph IRI, or update dataset parameters together with USING / WITH (§2.2.3); 403 update
 * while disabled; 405 update via GET; 406 no acceptable result format; 413 body over the limit;
 * 415 unknown POST content type or charset; 500 evaluation failure; 503 execution time exceeded
 * or every query slot busy (then with {@code Retry-After}). A failure after the response is
 * committed — the execution limit tripping mid-stream — aborts the response instead.
 * Origins and tokens are the filters' business ({@link AccessFilters}), not this class's.
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
        Params params;
        try {
            params = Params.parse(req.getQueryString());
        } catch (IllegalArgumentException e) {
            fail(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return;
        }
        if (params.first("update") != null) {
            // §2.2: update requests are POST only. Refusing here rather than ignoring the
            // parameter keeps a misconfigured client from believing its update ran.
            fail(resp, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "SPARQL Update is not accepted via GET");
            return;
        }
        String query = params.first("query");
        if (query == null) {
            fail(resp, HttpServletResponse.SC_BAD_REQUEST, "missing 'query' parameter");
            return;
        }
        handleQuery(req, resp, params, query);
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
        if (!type.equals("application/x-www-form-urlencoded") && !type.equals("application/sparql-query")
                && !type.equals("application/sparql-update")) {
            fail(resp, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                    "unsupported Content-Type '" + type + "'; use application/x-www-form-urlencoded, "
                            + "application/sparql-query or application/sparql-update");
            return;
        }
        // The body is read here, bounded, for every content type — forms included. Leaving a
        // form to the container's getParameter() would apply the container's own form limit
        // instead of maxRequestBytes (and a chunked form has no Content-Length for the check
        // above to see), so a servlet mounted in someone else's Jetty would not enforce it.
        String body = readBody(req, resp);
        if (body == null) {
            return;   // readBody already answered 413 or 415
        }
        Params params;
        try {
            params = Params.parse(req.getQueryString());
            if (type.equals("application/x-www-form-urlencoded")) {
                // Percent-escapes in the body decode in the body's declared charset (UTF-8
                // when none is given, as the URL encoding standard says); the query string
                // is always UTF-8.
                params = params.and(Params.parse(body, bodyCharset(req)));
            }
        } catch (IllegalArgumentException e) {
            fail(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return;
        }
        switch (type) {
            case "application/sparql-query" -> handleQuery(req, resp, params, body);
            case "application/sparql-update" -> handleUpdate(req, resp, params, body);
            default -> {
                String update = params.first("update");
                String query = params.first("query");
                if (update != null && query != null) {
                    fail(resp, HttpServletResponse.SC_BAD_REQUEST, "send either 'query' or 'update', not both");
                } else if (update != null) {
                    handleUpdate(req, resp, params, update);
                } else if (query != null) {
                    handleQuery(req, resp, params, query);
                } else {
                    fail(resp, HttpServletResponse.SC_BAD_REQUEST, "missing 'query' or 'update' parameter");
                }
            }
        }
    }

    // ---- query -------------------------------------------------------------------------

    private void handleQuery(HttpServletRequest req, HttpServletResponse resp, Params params, String queryString)
            throws IOException {
        if (!slots.tryAcquire()) {
            busy(resp);
            return;
        }
        try {
            evaluateQuery(req, resp, params, queryString);
        } finally {
            slots.release();
        }
    }

    private void evaluateQuery(HttpServletRequest req, HttpServletResponse resp, Params params, String queryString)
            throws IOException {
        try (RepositoryConnection conn = repository.get().getConnection()) {
            Query query;
            try {
                // §2.3: with no BASE in the request, the service defines the base IRI. The
                // request URL is the obvious one, and without any base a relative IRI that the
                // SPARQL grammar allows would be rejected as malformed.
                query = conn.prepareQuery(QueryLanguage.SPARQL, queryString, baseIri(req));
            } catch (MalformedQueryException e) {
                fail(resp, HttpServletResponse.SC_BAD_REQUEST, "malformed query: " + e.getMessage());
                return;
            }
            Dataset dataset = dataset(params, "default-graph-uri", "named-graph-uri");
            if (dataset != null) {
                query.setDataset(dataset);
            }
            if (options.maxExecutionSeconds() > 0) {
                query.setMaxExecutionTime(options.maxExecutionSeconds());
            }
            // A list header may arrive as several fields; RFC 9110 §5.2 makes them one list.
            List<String> acceptFields = Collections.list(req.getHeaders("Accept"));
            String accept = acceptFields.isEmpty() ? null : String.join(", ", acceptFields);

            // The tuple and boolean registries are keyed by the shared QueryResultFormat type,
            // hence the widened defaults here.
            if (query instanceof TupleQuery tq) {
                Optional<Negotiated<QueryResultFormat>> fmt = negotiate(accept,
                        TupleQueryResultWriterRegistry.getInstance(), (QueryResultFormat) TupleQueryResultFormat.JSON);
                if (fmt.isEmpty()) {
                    notAcceptable(resp, accept);
                    return;
                }
                writeTuples(resp, tq, fmt.get());
            } else if (query instanceof GraphQuery gq) {
                Optional<Negotiated<RDFFormat>> fmt = negotiate(accept, RDFWriterRegistry.getInstance(), RDFFormat.TURTLE);
                if (fmt.isEmpty()) {
                    notAcceptable(resp, accept);
                    return;
                }
                RDFWriter writer = RDFWriterRegistry.getInstance().get(fmt.get().format()).orElseThrow()
                        .getWriter(begin(resp, fmt.get()));
                gq.evaluate(writer);
            } else if (query instanceof BooleanQuery bq) {
                Optional<Negotiated<QueryResultFormat>> fmt = negotiate(accept,
                        BooleanQueryResultWriterRegistry.getInstance(), (QueryResultFormat) BooleanQueryResultFormat.JSON);
                if (fmt.isEmpty()) {
                    notAcceptable(resp, accept);
                    return;
                }
                boolean answer = bq.evaluate();   // evaluate BEFORE committing, so a timeout can still be a 503
                BooleanQueryResultWriter writer = BooleanQueryResultWriterRegistry.getInstance()
                        .get(fmt.get().format()).orElseThrow().getWriter(begin(resp, fmt.get()));
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
            if (timedOut(e)) {
                fail(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "query exceeded the execution limit of " + options.maxExecutionSeconds() + " s");
                return;
            }
            log.warn("query evaluation failed: {}", e.getMessage());
            fail(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "query evaluation failed: " + e.getMessage());
        } catch (RuntimeException e) {
            // Rio and the result writers also wrap a client that went away in a handler
            // exception; fail() turns any of these into an aborted response once committed.
            if (!resp.isCommitted()) {
                log.warn("unexpected failure answering a query", e);
            }
            fail(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal error: " + e.getMessage());
        }
    }

    private void writeTuples(HttpServletResponse resp, TupleQuery tq, Negotiated<QueryResultFormat> fmt)
            throws IOException {
        var factory = TupleQueryResultWriterRegistry.getInstance().get(fmt.format()).orElseThrow();
        if (options.maxRows() == 0) {
            TupleQueryResultWriter writer = factory.getWriter(begin(resp, fmt));
            // Streams. A timeout before the first buffer is flushed is still a clean 503; one
            // after it aborts the response (see fail()), never a short 200.
            tq.evaluate(writer);
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

    private void handleUpdate(HttpServletRequest req, HttpServletResponse resp, Params params, String updateString)
            throws IOException {
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
            executeUpdate(req, resp, params, updateString);
        } finally {
            slots.release();
        }
    }

    private void executeUpdate(HttpServletRequest req, HttpServletResponse resp, Params params, String updateString)
            throws IOException {
        try (RepositoryConnection conn = repository.get().getConnection()) {
            String base = baseIri(req);
            Dataset dataset = dataset(params, "using-graph-uri", "using-named-graph-uri");
            Update update;
            try {
                if (dataset != null && namesItsOwnDataset(updateString, base)) {
                    // §2.2.3: protocol dataset parameters together with USING, USING NAMED or
                    // WITH in the request are an error. Executing anyway is not harmless —
                    // RDF4J lets WITH win, so the update silently rewrites a different graph
                    // from the one the parameters named.
                    fail(resp, HttpServletResponse.SC_BAD_REQUEST, "using-graph-uri / using-named-graph-uri cannot "
                            + "be combined with USING, USING NAMED or WITH in the update (SPARQL 1.1 Protocol §2.2.3)");
                    return;
                }
                update = conn.prepareUpdate(QueryLanguage.SPARQL, updateString, base);
            } catch (MalformedQueryException e) {
                fail(resp, HttpServletResponse.SC_BAD_REQUEST, "malformed update: " + e.getMessage());
                return;
            }
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
        } catch (RuntimeException e) {
            // RDF4J reports an update's timeout as UpdateExecutionException wrapping a
            // SailException wrapping the QueryInterruptedException (measured on 4.3.8), so the
            // catch above never sees it; the cause chain decides.
            if (timedOut(e)) {
                fail(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "update exceeded the execution limit of " + options.maxExecutionSeconds() + " s");
                return;
            }
            log.warn("update failed: {}", e.toString());
            fail(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "update failed: " + e.getMessage());
        }
    }

    /** Whether {@code e} or anything it wraps is RDF4J's execution-limit interruption. */
    private static boolean timedOut(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof QueryInterruptedException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /**
     * Whether any operation in the request carries USING, USING NAMED or WITH. RDF4J's parser
     * records each of those as a per-operation dataset (measured on 4.3.8: {@code WITH <g>}
     * becomes {@code DELETE FROM / INSERT INTO / FROM <g>}, {@code USING <g>} becomes
     * {@code FROM <g>}); operations without them map to {@code null}.
     */
    private static boolean namesItsOwnDataset(String updateString, String base) {
        ParsedUpdate parsed = QueryParserUtil.parseUpdate(QueryLanguage.SPARQL, updateString, base);
        return parsed.getDatasetMapping().values().stream().anyMatch(Objects::nonNull);
    }

    // ---- helpers -----------------------------------------------------------------------

    /** Starts the response for a successful result: status, media type, charset, and the body stream. */
    private static OutputStream begin(HttpServletResponse resp, Negotiated<?> negotiated) throws IOException {
        FileFormat format = negotiated.format();
        String type = negotiated.mediaType();
        if (format.hasCharset()) {
            Charset cs = format.getCharset() == null ? StandardCharsets.UTF_8 : format.getCharset();
            type += "; charset=" + cs.name();
        }
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType(type);
        return resp.getOutputStream();
    }

    /** A negotiated writer format and the media type to label the response with. */
    record Negotiated<F extends FileFormat>(F format, String mediaType) { }

    /**
     * Content negotiation against one of RDF4J's writer registries (RFC 9110 §12.5.1). Every
     * media type a format is registered under (JSON results are both
     * {@code application/sparql-results+json} and {@code application/json}) is scored on its
     * own: it takes the quality of the <em>most specific</em> range matching it — exact over
     * {@code type/*} over {@code *&#47;*} — so {@code json;q=0, *&#47;*} excludes that type
     * instead of letting the wildcard bring it back, while an alias the client did accept still
     * counts. The best-scoring media type wins (higher q, then more specific match, then the
     * range listed first, then the default format and its primary type), and the response is
     * labelled with it. A missing header selects the default. Empty means nothing is
     * acceptable: 406.
     */
    static <F extends FileFormat, S> Optional<Negotiated<F>> negotiate(String accept,
                                                                       FileFormatServiceRegistry<F, S> registry,
                                                                       F dflt) {
        if (accept == null || accept.isBlank()) {
            return Optional.of(new Negotiated<>(dflt, dflt.getDefaultMIMEType()));
        }
        // A range's media parameters (those before q) narrow it further (RFC 9110 §12.5.1):
        // text/csv;charset=UTF-8 is more specific than text/csv, and matches only a
        // representation with those parameters. The only parameter these writers have is the
        // charset of a text format, so any other parameter matches nothing here.
        record Range(String type, Map<String, String> params, double q, int order) {
            int specificity() {
                return type.equals("*/*") ? 1 : type.endsWith("/*") ? 2 : params.isEmpty() ? 3 : 4;
            }

            boolean matches(String mime, Charset charset) {
                boolean typeMatches = switch (type.equals("*/*") ? 1 : type.endsWith("/*") ? 2 : 3) {
                    case 1 -> true;
                    case 2 -> mime.startsWith(type.substring(0, type.length() - 1));
                    default -> mime.equals(type);
                };
                if (!typeMatches) {
                    return false;
                }
                for (Map.Entry<String, String> p : params.entrySet()) {
                    if (!p.getKey().equals("charset") || charset == null
                            || !charset.name().equalsIgnoreCase(p.getValue())) {
                        return false;
                    }
                }
                return true;
            }

            boolean beats(Range other) {
                return q > other.q || (q == other.q && (specificity() > other.specificity()
                        || (specificity() == other.specificity() && order < other.order)));
            }
        }
        List<Range> ranges = new ArrayList<>();
        int order = 0;
        for (String part : accept.split(",")) {
            String[] pieces = part.trim().split(";");
            String type = pieces[0].trim().toLowerCase(Locale.ROOT);
            if (type.isEmpty()) {
                continue;
            }
            double q = 1.0;
            Map<String, String> params = new LinkedHashMap<>();
            for (int i = 1; i < pieces.length; i++) {
                String p = pieces[i].trim();
                int eq = p.indexOf('=');
                String name = (eq < 0 ? p : p.substring(0, eq)).trim().toLowerCase(Locale.ROOT);   // names are case-insensitive
                String value = eq < 0 ? "" : p.substring(eq + 1).trim().replace("\"", "");
                if (name.equals("q")) {
                    try {
                        q = Double.parseDouble(value);
                    } catch (NumberFormatException ignored) {
                        q = 0;
                    }
                    break;   // what follows q is accept-ext, not media-type parameters
                }
                if (!name.isEmpty()) {
                    params.put(name, value);
                }
            }
            ranges.add(new Range(type, params, q, order++));   // q=0 is kept: it is an exclusion
        }
        List<F> candidates = new ArrayList<>();
        candidates.add(dflt);
        for (F f : registry.getKeys()) {
            if (!f.equals(dflt)) {
                candidates.add(f);
            }
        }
        Negotiated<F> best = null;
        Range bestRange = null;
        for (F f : candidates) {
            Charset charset = f.hasCharset() ? (f.getCharset() == null ? StandardCharsets.UTF_8 : f.getCharset()) : null;
            for (String mime : f.getMIMETypes()) {   // the format's primary type first
                String m = mime.toLowerCase(Locale.ROOT);
                Range match = null;   // the most specific range for this type, earliest on a tie
                for (Range r : ranges) {
                    if (r.matches(m, charset) && (match == null || r.specificity() > match.specificity()
                            || (r.specificity() == match.specificity() && r.order() < match.order()))) {
                        match = r;
                    }
                }
                if (match != null && match.q() > 0 && (bestRange == null || match.beats(bestRange))) {
                    best = new Negotiated<>(f, mime);
                    bestRange = match;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Builds the protocol dataset from repeated graph parameters; {@code null} when none were sent. */
    private static Dataset dataset(Params params, String defaultParam, String namedParam) {
        List<String> defaults = params.all(defaultParam);
        List<String> named = params.all(namedParam);
        if (defaults.isEmpty() && named.isEmpty()) {
            return null;
        }
        SimpleDataset ds = new SimpleDataset();
        for (String g : defaults) {
            ds.addDefaultGraph(iri(defaultParam, g));
        }
        for (String g : named) {
            ds.addNamedGraph(iri(namedParam, g));
        }
        return ds;
    }

    /**
     * Strict RFC 3987 parsing. {@code SimpleValueFactory.createIRI} only looks for a colon, and
     * {@code ParsedIRI.create} is lenient too (both accept {@code http://bad iri}); the
     * constructor is the strict one.
     */
    private static IRI iri(String param, String value) {
        try {
            if (new ParsedIRI(value).isAbsolute()) {
                return VF.createIRI(value);
            }
        } catch (URISyntaxException e) {
            // fall through to the same answer
        }
        throw new IllegalArgumentException("'" + param + "' is not an absolute IRI: " + value);
    }

    /** The service-defined base IRI of §2.3: the request URL, without its query string. */
    private static String baseIri(HttpServletRequest req) {
        return req.getRequestURL().toString();
    }

    /**
     * Reads a POST body within the size limit. Answers 413 beyond it, or 415 for a charset this
     * runtime does not know, and returns {@code null} in both cases.
     */
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
        Charset cs;
        try {
            cs = bodyCharset(req);
        } catch (IllegalArgumentException e) {
            fail(resp, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                    "unsupported charset '" + req.getCharacterEncoding() + "'");
            return null;
        }
        return out.toString(cs);
    }

    /** The body's declared charset, UTF-8 when none; {@code IllegalArgumentException} for an unknown one. */
    private static Charset bodyCharset(HttpServletRequest req) {
        String enc = req.getCharacterEncoding();
        return enc == null ? StandardCharsets.UTF_8 : Charset.forName(enc);
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

    /**
     * Answers an error. Before the response is committed: a plain-text body with the status,
     * keeping the CORS headers the origin filter set so a browser client can read the error
     * ({@code reset()} alone would drop them along with everything else).
     *
     * <p>After commit the status line is gone, and returning quietly would let the container
     * finish the response normally — a client would then take a stream cut short by the
     * execution limit for a complete {@code 200}. Measured with a 6-way cross product over the
     * fleet seed and a 1 s limit: curl received 565 k CSV rows and exited 0. Throwing instead
     * makes the container abort the response (curl: exit 18, "transfer closed with outstanding
     * read data"), so the client sees a broken transfer — the only signal the protocol leaves.
     */
    private static void fail(HttpServletResponse resp, int status, String message) throws IOException {
        if (resp.isCommitted()) {
            throw new IOException("aborting a committed response (" + status + "): " + message);
        }
        Map<String, List<String>> keep = new LinkedHashMap<>();
        for (String h : resp.getHeaderNames()) {
            if (h.regionMatches(true, 0, "Access-Control-", 0, 15) || h.equalsIgnoreCase("Vary")) {
                keep.put(h, new ArrayList<>(resp.getHeaders(h)));
            }
        }
        resp.reset();
        keep.forEach((h, values) -> values.forEach(v -> resp.addHeader(h, v)));
        resp.setStatus(status);
        resp.setContentType("text/plain; charset=UTF-8");
        resp.getWriter().write(message + "\n");
    }

    /**
     * Request parameters, parsed here rather than through {@code getParameter()} so the form
     * body goes through {@link #readBody}'s limit in any container. Names and values are
     * {@code application/x-www-form-urlencoded}, UTF-8.
     */
    record Params(Map<String, List<String>> map) {
        static Params parse(String encoded) {
            return parse(encoded, StandardCharsets.UTF_8);
        }

        static Params parse(String encoded, Charset cs) {
            Map<String, List<String>> m = new LinkedHashMap<>();
            if (encoded != null && !encoded.isEmpty()) {
                for (String pair : encoded.split("&")) {
                    if (pair.isEmpty()) {
                        continue;
                    }
                    int eq = pair.indexOf('=');
                    String name = decode(eq < 0 ? pair : pair.substring(0, eq), cs);
                    String value = eq < 0 ? "" : decode(pair.substring(eq + 1), cs);
                    m.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
                }
            }
            return new Params(m);
        }

        /**
         * Percent-decodes to bytes first and decodes those bytes once, in {@code cs}.
         * {@code URLDecoder} decodes each run of escapes on its own, which breaks a multibyte
         * character whose trailing byte is a plain ASCII letter — Shift_JIS テ is {@code %83e}.
         */
        private static String decode(String s, Charset cs) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '+') {
                    bytes.write(' ');
                } else if (c == '%') {
                    int hi = i + 2 < s.length() ? Character.digit(s.charAt(i + 1), 16) : -1;
                    int lo = hi < 0 ? -1 : Character.digit(s.charAt(i + 2), 16);
                    if (lo < 0) {
                        throw new IllegalArgumentException("malformed percent-encoding in request parameters");
                    }
                    bytes.write((hi << 4) | lo);
                    i += 2;
                } else {
                    int end = Character.isHighSurrogate(c) && i + 1 < s.length() ? i + 2 : i + 1;
                    bytes.writeBytes(s.substring(i, end).getBytes(cs));
                    i = end - 1;
                }
            }
            return bytes.toString(cs);
        }

        Params and(Params other) {
            Map<String, List<String>> m = new LinkedHashMap<>();
            map.forEach((k, v) -> m.computeIfAbsent(k, x -> new ArrayList<>()).addAll(v));
            other.map.forEach((k, v) -> m.computeIfAbsent(k, x -> new ArrayList<>()).addAll(v));
            return new Params(m);
        }

        String first(String name) {
            List<String> v = map.get(name);
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        List<String> all(String name) {
            return map.getOrDefault(name, List.of());
        }
    }
}
