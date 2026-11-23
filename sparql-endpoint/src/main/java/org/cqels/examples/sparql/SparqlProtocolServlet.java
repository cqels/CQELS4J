package org.cqels.examples.sparql;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.rdf4j.common.lang.FileFormat;
import org.eclipse.rdf4j.common.net.ParsedIRI;
import org.eclipse.rdf4j.common.lang.service.FileFormatServiceRegistry;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
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
import org.eclipse.rdf4j.query.algebra.Load;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.sparql.BaseDeclProcessor;
import org.eclipse.rdf4j.query.parser.sparql.PrefixDeclProcessor;
import org.eclipse.rdf4j.query.parser.sparql.ast.ASTGraphGraphPattern;
import org.eclipse.rdf4j.query.parser.sparql.ast.ASTIRI;
import org.eclipse.rdf4j.query.parser.sparql.ast.ASTQueryContainer;
import org.eclipse.rdf4j.query.parser.sparql.ast.Node;
import org.eclipse.rdf4j.query.parser.sparql.ast.ParseException;
import org.eclipse.rdf4j.query.parser.sparql.ast.SyntaxTreeBuilder;
import org.eclipse.rdf4j.query.parser.sparql.ast.TokenMgrError;
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
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.RDFWriterRegistry;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * The SPARQL 1.1 Protocol over whatever {@link Repository} the supplier returns — for this
 * project, the CQELS engine's background graph ({@code engine.getRepository()}).
 *
 * <p><strong>What this is and is not.</strong> It is RDF4J's protocol <em>library</em> layer
 * (result-writer registries, Rio, the query API) behind a plain {@code jakarta.servlet}. It is
 * not RDF4J Server: no repository management, no transactions, no Workbench. The only code that
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
 * nothing is buffered unless {@link EndpointOptions#maxRows()} asks for a bounded buffer, with
 * one exception: JSON-LD and NDJSON-LD, whose RDF4J writers hold statements before writing, are
 * always buffered within {@link EndpointOptions#maxBufferedBytes()}: beyond it they are refused
 * (503) when {@code maxRows} is {@code 0}, and truncated like any buffered result
 * ({@code X-CQELS-Truncated}) when a row cap is set.
 *
 * <p>Protocol mapping (SPARQL 1.1 Protocol §2):
 * <pre>
 *   GET  (no query string)                                 service description (SD §2)
 *   GET  ?query=…                                         query via GET
 *   POST application/x-www-form-urlencoded  query=|update= query / update via URL-encoded POST
 *   POST application/sparql-query                         query via direct POST
 *   POST application/sparql-update                        update via direct POST
 *   default-graph-uri / named-graph-uri                   query dataset
 *   using-graph-uri / using-named-graph-uri               update dataset
 * </pre>
 * The base IRI for relative IRIs (§2.3) is the request URL. Status codes: 400 malformed query,
 * bad graph IRI, update dataset parameters together with USING / WITH (§2.2.3), {@code query} or
 * {@code update} given twice (or in the URL of a direct POST), bytes not valid in the declared
 * charset, or a request nested too deeply to parse; 403 update while disabled, update by a
 * request {@link AccessFilters#READ_ONLY} marks, SERVICE / LOAD while federation is
 * disabled ({@link #reachesOut}), or naming a non-HTTP IRI while it is enabled
 * ({@link #nonHttpTarget}); 405, with {@code Allow}, update via GET or a method other than GET,
 * HEAD, POST and OPTIONS; 406 no acceptable result format; 408 body not
 * received within {@code maxUploadSeconds}; 413 body over the limit; 415 unknown POST content
 * type or charset; 500 evaluation failure or the heap exhausted; 503 execution time exceeded or
 * every query slot busy (then with {@code Retry-After}); 504 a SERVICE or LOAD target did not
 * answer in time. A failure after the response is committed — the execution limit tripping
 * mid-stream — aborts the response instead.
 *
 * <p>Every answer to GET and POST carries {@code Vary: Accept}; the launcher adds it in front of
 * the access filters too, for their refusals.
 *
 * <p>A request takes its query slot before its body is read, so a slow upload is bounded by
 * the slots like a slow query, not by the container's request threads.
 * Origins and tokens are the filters' business ({@link AccessFilters}), not this class's.
 *
 * <p><strong>The metadata graph.</strong> {@link #METADATA_GRAPH} is reserved. A query whose
 * dataset ({@code FROM} / {@code FROM NAMED}, or {@code default-graph-uri} /
 * {@code named-graph-uri}) names it is answered from a fresh snapshot the constructor's
 * {@code metadata} supplier builds, in a memory store of its own, under the same slots and
 * execution limit as any query — never from the repository, so the snapshot cannot reach the
 * default graph other queries (the engine's included) see. Hence the rules: that graph is the
 * only graph such a dataset may name (400 otherwise: the two cannot be joined); naming it in
 * {@code GRAPH} without naming it in the dataset is a 400 that says so rather than an empty
 * answer; with no supplier configured, naming it is a 403. An update that leaves any triple in
 * that graph in the repository is rolled back with a 403, whatever syntax put it there.
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

    /** The reserved graph a query names to read the {@code metadata} snapshot; see the class comment. */
    public static final IRI METADATA_GRAPH = VF.createIRI("cqels://endpoint/metadata");

    private final transient Supplier<Repository> repository;
    private final EndpointOptions options;
    private final transient Semaphore slots;
    /**
     * Ends uploads that outlive {@code maxUploadSeconds}; see {@link #readBody}. Made in
     * {@link #init()} and shut down in {@link #destroy()}: Jetty reuses the servlet instance
     * across a stop and start, and an executor made once in the constructor stayed shut down
     * after the first stop, failing every POST (found in co-review of #123).
     */
    private transient volatile java.util.concurrent.ScheduledExecutorService uploadWatch;
    /** Fetches LOAD sources; built on first use. See {@link #executeLoad}. */
    private transient volatile java.net.http.HttpClient loadClient;
    /**
     * Which lifetime of this servlet instance a request belongs to. Each request records it on
     * arrival ({@link #GENERATION}); {@link #destroy()} advances it under {@link #commitGate}'s write
     * lock, and an update commits only if its request's generation is still the current one.
     *
     * <p>Why: Jetty cuts off a request still running when its drain ends but does not stop its
     * thread, so an update held up until then committed afterwards, for a client long since told
     * the connection was gone (found in co-review of #128). A flag that {@code destroy()} set and
     * {@code init()} cleared was not enough: Jetty reuses the instance across a restart, and the
     * restart re-armed the old request's commit (found in the next round).
     */
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
    private static final String GENERATION = SparqlProtocolServlet.class.getName() + ".generation";
    private final transient java.util.concurrent.locks.ReadWriteLock commitGate =
            new java.util.concurrent.locks.ReentrantReadWriteLock();
    private final transient Supplier<? extends Collection<Statement>> metadata;
    /** Requests turned away because every slot was taken; for {@code /metrics}. */
    private final java.util.concurrent.atomic.LongAdder busyRejections = new java.util.concurrent.atomic.LongAdder();
    /** When the last "every slot taken" warning was logged (nanoTime), so a flood logs once a minute. */
    private final java.util.concurrent.atomic.AtomicLong lastBusyWarning = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean warnedBusy;

    /** The methods this resource answers, for {@code Allow} (RFC 9110 §10.2.1 requires it on a 405). */
    static final String ALLOW = "GET, HEAD, POST, OPTIONS";

    /** Without a metadata graph: a query naming {@link #METADATA_GRAPH} is refused with 403. */
    public SparqlProtocolServlet(Supplier<Repository> repository, EndpointOptions options) {
        this(repository, options, null);
    }

    /**
     * @param metadata builds the statements of {@link #METADATA_GRAPH} afresh for each query that
     *                 names it, or {@code null} for none. Statements are added in that graph
     *                 whatever context they carry
     */
    public SparqlProtocolServlet(Supplier<Repository> repository, EndpointOptions options,
                                 Supplier<? extends Collection<Statement>> metadata) {
        this.repository = repository;
        this.options = options;
        this.slots = new Semaphore(options.maxConcurrentQueries());
        this.metadata = metadata;
    }

    @Override
    public void init() {
        var watch = new java.util.concurrent.ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "sparql-upload-deadline");
            t.setDaemon(true);
            return t;
        });
        // A cancelled deadline leaves the queue at once; by default it stayed for the whole
        // deadline, so 100 finished uploads under a 24 h limit kept 100 tasks (found in
        // co-review of #123).
        watch.setRemoveOnCancelPolicy(true);
        uploadWatch = watch;
    }

    /** Upload deadlines armed and neither fired nor cancelled; for tests. */
    int armedUploadDeadlines() {
        var watch = uploadWatch;
        return watch instanceof java.util.concurrent.ScheduledThreadPoolExecutor pool ? pool.getQueue().size() : 0;
    }

    @Override
    public void destroy() {
        commitGate.writeLock().lock();   // waits for a commit in progress; none of this lifetime follows
        try {
            generation.incrementAndGet();
        } finally {
            commitGate.writeLock().unlock();
        }
        java.util.concurrent.ScheduledExecutorService watch = uploadWatch;
        uploadWatch = null;
        if (watch != null) {
            watch.shutdownNow();
        }
        // The LOAD client's selector thread and kept-alive connections outlived a Jetty stop
        // (found in co-review of #123). Dropped here and remade on the next LOAD; closed outright
        // where the runtime can (HttpClient is AutoCloseable from JDK 21), otherwise left to the
        // collector, which ends its thread once nothing holds it.
        java.net.http.HttpClient client = loadClient;
        loadClient = null;
        if (client instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.debug("closing the LOAD client: {}", e.toString());
            }
        }
        super.destroy();
    }

    /** Whether a LOAD client is held; for tests. */
    boolean loadClientHeld() {
        return loadClient != null;
    }

    /** Query slots free now: {@code /ready} answers 503 at none. */
    public int availableSlots() {
        return slots.availablePermits();
    }

    /** Query slots held now. */
    public int slotsInUse() {
        return options.maxConcurrentQueries() - slots.availablePermits();
    }

    /** Query slots in all. */
    public int slotLimit() {
        return options.maxConcurrentQueries();
    }

    /** Requests turned away so far because every slot was taken. */
    public long busyRejections() {
        return busyRejections.sum();
    }

    /**
     * Only GET (and HEAD), POST and OPTIONS. Anything else was left to {@code HttpServlet}, whose
     * 405 carried no {@code Allow} (found in the audit after #121) and whose TRACE would echo the
     * request's headers back. The 405 answering an update sent by GET carries it too.
     */
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp)
            throws jakarta.servlet.ServletException, IOException {
        req.setAttribute(GENERATION, generation.get());
        switch (req.getMethod()) {
            case "GET", "HEAD", "POST", "OPTIONS" -> super.service(req, resp);
            default -> methodNotAllowed(resp, "method " + req.getMethod() + " is not supported; use GET or POST "
                    + "(SPARQL 1.1 Protocol; the Graph Store Protocol is not served here)");
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        // HttpServlet's own lists TRACE, which service() refuses
        resp.setHeader("Allow", ALLOW);
    }

    private static void methodNotAllowed(HttpServletResponse resp, String message) throws IOException {
        fail(resp, HttpServletResponse.SC_METHOD_NOT_ALLOWED, message);
        resp.setHeader("Allow", ALLOW);   // after fail(): fail() resets the response first
    }

    /**
     * The one value of {@code name}, or {@code null}; 400 ({@code IllegalArgumentException}) when it
     * is given more than once. Taking the first silently answered {@code ?query=ASK{}&query=SELEKT}
     * with 200, and let a URL parameter override the form body (found in the audit after #121):
     * whatever sits between the client and the endpoint could then make the request say one
     * thing to it and another to anything that read the last value.
     */
    private static String single(Params params, String name) {
        List<String> values = params.all(name);
        if (values.size() > 1) {
            throw new IllegalArgumentException("'" + name + "' is given " + values.size() + " times; send it once");
        }
        return values.isEmpty() ? null : values.get(0);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        varyOnAccept(resp);
        Params params;
        String query;
        try {
            params = Params.parse(req.getQueryString());
            // counted first: a repeated parameter is a 400 whatever else is wrong (found in
            // co-review of #128: ?update=…&update=… was a 405)
            String update = single(params, "update");
            query = single(params, "query");
            if (update != null) {
                // §2.2: update requests are POST only. Refusing here rather than ignoring the
                // parameter keeps a misconfigured client from believing its update ran.
                methodNotAllowed(resp, "SPARQL Update is not accepted via GET; POST it");
                return;
            }
        } catch (IllegalArgumentException e) {
            fail(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return;
        }
        if (query == null) {
            if (req.getQueryString() == null || req.getQueryString().isEmpty()) {
                describeService(req, resp);
                return;
            }
            fail(resp, HttpServletResponse.SC_BAD_REQUEST, "missing 'query' parameter");
            return;
        }
        inSlot(resp, () -> evaluateQuery(req, resp, params, query));
    }

    static final String SD = "http://www.w3.org/ns/sparql-service-description#";

    /**
     * The SPARQL 1.1 Service Description (§2: a GET of the endpoint with no query string), in
     * any RDF format {@code Accept} names, Turtle by default. It says what a client or a
     * federation engine needs to decide how to talk to this endpoint, and nothing about the data:
     * no graph names from the store and no counts, which would cost a scan per request and tell
     * an anonymous reader what the read token guards — the same access filters run before this
     * as before a query. It takes no query slot: it reads only this servlet's own options.
     */
    private void describeService(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        List<String> acceptFields = Collections.list(req.getHeaders("Accept"));
        String accept = acceptFields.isEmpty() ? null : String.join(", ", acceptFields);
        Optional<Negotiated<RDFFormat>> fmt = negotiate(accept, RDFWriterRegistry.getInstance(), RDFFormat.TURTLE);
        if (fmt.isEmpty()) {
            notAcceptable(resp, accept);
            return;
        }
        org.eclipse.rdf4j.model.Model description = serviceDescription(req.getRequestURL().toString());
        try (OutputStream out = begin(resp, fmt.get())) {
            org.eclipse.rdf4j.rio.Rio.write(description, out, fmt.get().format());
        }
    }

    /**
     * What {@link #describeService} serves for the endpoint at {@code endpoint}: the languages
     * (SPARQL 1.1 Update only while updates are enabled), the result formats the writers here can
     * produce (those with a W3C format IRI), {@code sd:BasicFederatedQuery} only while federation is
     * enabled (and the repository's client has the endpoint's limits, without which SERVICE is a
     * 503), and the metadata graph among the available graphs only when this servlet serves it.
     */
    org.eclipse.rdf4j.model.Model serviceDescription(String endpoint) {
        org.eclipse.rdf4j.model.Model m = new org.eclipse.rdf4j.model.impl.LinkedHashModel();
        m.setNamespace("sd", SD);
        m.setNamespace("formats", "http://www.w3.org/ns/formats/");
        org.eclipse.rdf4j.model.BNode service = VF.createBNode();
        m.add(service, org.eclipse.rdf4j.model.vocabulary.RDF.TYPE, VF.createIRI(SD, "Service"));
        m.add(service, VF.createIRI(SD, "endpoint"), VF.createIRI(endpoint));
        m.add(service, VF.createIRI(SD, "supportedLanguage"), VF.createIRI(SD, "SPARQL11Query"));
        if (options.updateEnabled()) {
            m.add(service, VF.createIRI(SD, "supportedLanguage"), VF.createIRI(SD, "SPARQL11Update"));
        }
        // Not sd:UnionDefaultGraph: it means the union of the named graphs, and RDF4J's default
        // graph also holds the triples in no named graph (found in co-review of #130)
        if (options.federationEnabled() && serviceRuns()) {
            m.add(service, VF.createIRI(SD, "feature"), VF.createIRI(SD, "BasicFederatedQuery"));
        }
        java.util.Set<String> formats = new java.util.TreeSet<>();
        for (FileFormatServiceRegistry<? extends FileFormat, ?> registry : List.of(
                TupleQueryResultWriterRegistry.getInstance(), BooleanQueryResultWriterRegistry.getInstance(),
                RDFWriterRegistry.getInstance())) {
            for (FileFormat f : registry.getKeys()) {
                IRI standard = f instanceof RDFFormat r ? r.getStandardURI()
                        : f instanceof QueryResultFormat q ? q.getStandardURI() : null;
                if (standard != null) {
                    formats.add(standard.stringValue());
                }
            }
        }
        for (String f : formats) {
            m.add(service, VF.createIRI(SD, "resultFormat"), VF.createIRI(f));
        }
        if (metadata != null) {
            org.eclipse.rdf4j.model.BNode graphs = VF.createBNode();
            org.eclipse.rdf4j.model.BNode named = VF.createBNode();
            org.eclipse.rdf4j.model.BNode graph = VF.createBNode();
            m.add(service, VF.createIRI(SD, "availableGraphs"), graphs);
            m.add(graphs, org.eclipse.rdf4j.model.vocabulary.RDF.TYPE, VF.createIRI(SD, "GraphCollection"));
            m.add(graphs, VF.createIRI(SD, "namedGraph"), named);
            m.add(named, org.eclipse.rdf4j.model.vocabulary.RDF.TYPE, VF.createIRI(SD, "NamedGraph"));
            m.add(named, VF.createIRI(SD, "name"), METADATA_GRAPH);
            m.add(named, VF.createIRI(SD, "graph"), graph);
            m.add(graph, org.eclipse.rdf4j.model.vocabulary.RDF.TYPE, VF.createIRI(SD, "Graph"));
        }
        return m;
    }

    /**
     * Whether a SERVICE would run rather than be answered 503: the repository's client has the
     * endpoint's limits ({@link #refusedOutbound}). A description advertising federation that every
     * SERVICE then refused would send a federation engine to an endpoint that cannot serve it.
     */
    private boolean serviceRuns() {
        try {
            return SparqlEndpointLauncher.outboundLimited(repository.get());
        } catch (RuntimeException e) {
            return false;   // no repository yet: nothing runs
        }
    }

    /**
     * The same GET URL answers JSON or CSV depending on {@code Accept}; without saying so a cache
     * between client and endpoint could hand one client the other's format (found in the audit
     * after #121). Added first, so every answer carries it, errors included ({@link #fail} keeps it).
     */
    private static void varyOnAccept(HttpServletResponse resp) {
        // the launcher's first filter may have added it already, for the filters' own refusals
        for (String v : resp.getHeaders("Vary")) {
            for (String field : v.split(",")) {
                if (field.trim().equalsIgnoreCase("Accept")) {
                    return;
                }
            }
        }
        resp.addHeader("Vary", "Accept");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        varyOnAccept(resp);
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
        // The slot is taken BEFORE the body is read. Reading first let a client that trickles
        // its body hold a request thread the slots never counted: with every thread so held,
        // neither the immediate 503 nor /health answered (found in the audit after #121). Now
        // such a client holds a slot, the rest are turned away at once, and maxUploadSeconds
        // hands the slot back.
        inSlot(resp, () -> dispatchPost(req, resp, type));
    }

    private void dispatchPost(HttpServletRequest req, HttpServletResponse resp, String type) throws IOException {
        // The body is read here, bounded, for every content type — forms included. Leaving a
        // form to the container's getParameter() would apply the container's own form limit
        // instead of maxRequestBytes (and a chunked form has no Content-Length for the check
        // above to see), so a servlet mounted in someone else's Jetty would not enforce it.
        String body = readBody(req, resp);
        if (body == null) {
            return;   // readBody already answered 413 or 415
        }
        Params params;
        String update;
        String query;
        try {
            params = Params.parse(req.getQueryString());
            if (type.equals("application/x-www-form-urlencoded")) {
                // Percent-escapes in the body decode in the body's declared charset (UTF-8
                // when none is given, as the URL encoding standard says); the query string
                // is always UTF-8.
                params = params.and(Params.parse(body, bodyCharset(req)));
            } else if (!params.all("query").isEmpty() || !params.all("update").isEmpty()) {
                // a direct POST's body IS the operation (§2.1.3, §2.2.2); a second one in the URL
                // left the request saying two things
                throw new IllegalArgumentException("a direct POST carries its operation in the body; drop the "
                        + "'query' / 'update' parameter from the URL");
            }
            // counted across the URL and the form body together
            update = single(params, "update");
            query = single(params, "query");
        } catch (IllegalArgumentException e) {
            fail(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return;
        }
        switch (type) {
            case "application/sparql-query" -> evaluateQuery(req, resp, params, body);
            case "application/sparql-update" -> handleUpdate(req, resp, params, body);
            default -> {
                if (update != null && query != null) {
                    fail(resp, HttpServletResponse.SC_BAD_REQUEST, "send either 'query' or 'update', not both");
                } else if (update != null) {
                    handleUpdate(req, resp, params, update);
                } else if (query != null) {
                    evaluateQuery(req, resp, params, query);
                } else {
                    fail(resp, HttpServletResponse.SC_BAD_REQUEST, "missing 'query' or 'update' parameter");
                }
            }
        }
    }

    /** What runs inside a slot; it answers on {@code resp} itself. */
    private interface SlotWork {
        void run() throws IOException;
    }

    /**
     * Runs {@code work} holding a query slot, or answers 503 at once when none is free.
     *
     * <p>Two JVM errors are answered here rather than left to the container, whose HTML error
     * page names the server's version and whose log gets a stack trace per request (both found
     * in the audit after #121):
     * <ul>
     *   <li>{@code StackOverflowError}: a 4 KB query of 2,000 nested parentheses overflows
     *       RDF4J's recursive-descent parser, far inside the request-size limit. That is the
     *       request's shape, so a 400. The stack unwinds fully before it is caught; nothing
     *       shared is left half-done, and an update's transaction rolls back in its own
     *       {@code finally}.</li>
     *   <li>{@code OutOfMemoryError}: RDF4J builds {@code GROUP BY} groups, sorts and computed
     *       strings in the heap, and nothing a servlet can do bounds that — the endpoint shares
     *       the engine's JVM (see the README's <em>Memory</em>). Answering keeps the client from
     *       retrying blind, and the log line says what happened. The request's own allocations
     *       are unreachable by then, which is what makes answering possible at all.</li>
     * </ul>
     */
    private void inSlot(HttpServletResponse resp, SlotWork work) throws IOException {
        if (!slots.tryAcquire()) {
            busy(resp);
            return;
        }
        try {
            work.run();
        } catch (StackOverflowError e) {
            fail(resp, HttpServletResponse.SC_BAD_REQUEST, "the request nests too deeply to be parsed or evaluated");
        } catch (OutOfMemoryError e) {
            log.error("a request ran out of heap; the engine shares this JVM: {}", e.toString());
            fail(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "the request ran out of server memory");
        } finally {
            slots.release();
        }
    }

    // ---- query -------------------------------------------------------------------------

    private void evaluateQuery(HttpServletRequest req, HttpServletResponse resp, Params params, String requested)
            throws IOException {
        String queryString = withoutRepeatedPrefixes(requested);
        Repository target = null;
        boolean snapshot = false;
        try {
            // §2.3: with no BASE in the request, the service defines the base IRI. The request
            // URL is the obvious one, and without any base a relative IRI that the SPARQL
            // grammar allows would be rejected as malformed.
            String base = baseIri(req);
            ParsedQuery parsed;
            try {
                // Parsed before any connection is opened: which repository answers depends on
                // the dataset the query names (the metadata graph is not in the repository).
                parsed = QueryParserUtil.parseQuery(QueryLanguage.SPARQL, queryString, base);
            } catch (MalformedQueryException e) {
                fail(resp, HttpServletResponse.SC_BAD_REQUEST, "malformed query: " + e.getMessage());
                return;
            }
            if (refusedOutbound(resp, List.of(parsed.getTupleExpr()))) {
                return;
            }
            Dataset dataset = dataset(params, "default-graph-uri", "named-graph-uri");
            // The protocol parameters replace the query's own FROM / FROM NAMED (§2.1.4), and
            // setDataset below makes RDF4J do exactly that, so they decide the routing too.
            Dataset effective = dataset != null ? dataset : parsed.getDataset();
            if (names(effective, METADATA_GRAPH) || inGraphClause(queryString, base, METADATA_GRAPH)) {
                if (metadata == null) {
                    fail(resp, HttpServletResponse.SC_FORBIDDEN, "the metadata graph <" + METADATA_GRAPH
                            + "> is not enabled on this endpoint (CQELS_SPARQL_METADATA_ENABLED=true enables it)");
                    return;
                }
                if (!names(effective, METADATA_GRAPH)) {
                    fail(resp, HttpServletResponse.SC_BAD_REQUEST, "the metadata graph <" + METADATA_GRAPH
                            + "> is visible only to a query whose dataset names it: add FROM NAMED <"
                            + METADATA_GRAPH + "> (or FROM, or the named-graph-uri / default-graph-uri parameter)");
                    return;
                }
                if (!namesOnly(effective, METADATA_GRAPH)) {
                    fail(resp, HttpServletResponse.SC_BAD_REQUEST, "the metadata graph <" + METADATA_GRAPH
                            + "> is a snapshot kept apart from the store, so a dataset that names it cannot "
                            + "name other graphs too; query it on its own");
                    return;
                }
                target = snapshotRepository();
                snapshot = true;
            } else {
                target = repository.get();
            }
            evaluate(req, resp, target, queryString, base, dataset);
        } catch (IllegalArgumentException e) {
            fail(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (RuntimeException e) {
            if (!resp.isCommitted()) {
                log.warn("unexpected failure answering a query", e);
            }
            fail(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal error: " + e.getMessage());
        } finally {
            if (snapshot && target != null) {
                target.shutDown();
            }
        }
    }

    /** A memory store holding one snapshot of the metadata graph; the caller shuts it down. */
    private Repository snapshotRepository() {
        SailRepository repo = new SailRepository(new MemoryStore());
        // SERVICE in a metadata query must meet the same deadline as anywhere else: the
        // snapshot would otherwise make its own resolver, with RDF4J's untimed client (found in
        // co-review of #123). The store does not shut down a resolver it was given.
        if (repository.get() instanceof SailRepository main) {
            repo.setFederatedServiceResolver(main.getFederatedServiceResolver());
        }
        repo.init();
        try (RepositoryConnection c = repo.getConnection()) {
            for (Statement st : metadata.get()) {
                c.add(st.getSubject(), st.getPredicate(), st.getObject(), METADATA_GRAPH);
            }
        } catch (RuntimeException e) {
            repo.shutDown();
            throw e;
        }
        return repo;
    }

    private void evaluate(HttpServletRequest req, HttpServletResponse resp, Repository target, String queryString,
                          String base, Dataset dataset) throws IOException {
        long started = System.nanoTime();
        try (TimedServiceClient.Budget budget = openBudget();
             RepositoryConnection conn = target.getConnection()) {
            Query query;
            try {
                query = conn.prepareQuery(QueryLanguage.SPARQL, queryString, base);
            } catch (MalformedQueryException e) {
                fail(resp, HttpServletResponse.SC_BAD_REQUEST, "malformed query: " + e.getMessage());
                return;
            }
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
                writeStatements(resp, gq, fmt.get());
            } else if (query instanceof BooleanQuery bq) {
                Optional<Negotiated<QueryResultFormat>> fmt = negotiate(accept,
                        BooleanQueryResultWriterRegistry.getInstance(), (QueryResultFormat) BooleanQueryResultFormat.JSON);
                if (fmt.isEmpty()) {
                    notAcceptable(resp, accept);
                    return;
                }
                boolean answer = bq.evaluate();   // evaluate BEFORE committing, so a timeout can still be a 503
                if (serviceLimitTripped(resp)) {
                    return;
                }
                BooleanQueryResultWriter writer = BooleanQueryResultWriterRegistry.getInstance()
                        .get(fmt.get().format()).orElseThrow().getWriter(begin(resp, fmt.get()));
                writer.handleBoolean(answer);
            } else {
                fail(resp, HttpServletResponse.SC_BAD_REQUEST, "unsupported query form");
            }
            // A streamed result has been written by now: a limit SILENT swallowed aborts it
            // (see fail()) rather than let it end as a complete-looking 200.
            serviceLimitTripped(resp);
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
            if (outboundTimedOut(e)) {
                fail(resp, HttpServletResponse.SC_GATEWAY_TIMEOUT, "a SERVICE endpoint did not answer in time");
                return;
            }
            if (serviceFailed(resp, e)) {
                return;
            }
            if (pastLimit(started)) {
                fail(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "query exceeded the execution limit of " + options.maxExecutionSeconds() + " s");
                return;
            }
            log.warn("query evaluation failed: {}", e.getMessage());
            fail(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "query evaluation failed: " + e.getMessage());
        } catch (RuntimeException e) {
            // Rio and the result writers also wrap a client that went away in a handler
            // exception; fail() turns any of these into an aborted response once committed.
            if (outboundTimedOut(e)) {
                fail(resp, HttpServletResponse.SC_GATEWAY_TIMEOUT, "a SERVICE endpoint did not answer in time");
                return;
            }
            if (serviceFailed(resp, e)) {
                return;
            }
            if (pastLimit(started)) {
                fail(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "query exceeded the execution limit of " + options.maxExecutionSeconds() + " s");
                return;
            }
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
        // Bounded buffer: the truncation header must be decided before the body starts, so the
        // result is held — up to maxRows rows and maxBufferedBytes of them, whichever comes first.
        List<String> names;
        List<BindingSet> rows = new ArrayList<>();
        boolean truncated = false;
        try (TupleQueryResult result = tq.evaluate()) {
            names = result.getBindingNames();
            long bytes = 0;
            while (result.hasNext()) {
                if (rows.size() >= options.maxRows()) {
                    truncated = true;
                    break;
                }
                BindingSet row = result.next();
                // a row costs something even with nothing bound: 65,536 empty rows passed a
                // 1 KB cap as zero bytes (found in co-review of #123)
                long size = 64;
                for (var binding : row) {
                    size += 32 + estimate(binding.getValue());
                }
                if (bytes + size > options.maxBufferedBytes()) {
                    truncated = true;
                    break;
                }
                bytes += size;
                rows.add(row);
            }
        }
        if (serviceLimitTripped(resp)) {
            return;
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

    /**
     * CONSTRUCT and DESCRIBE under the same caps as SELECT: streamed when {@code maxRows} is
     * {@code 0}, otherwise held up to {@code maxRows} statements and {@code maxBufferedBytes}
     * and truncated the same way. Before the audit after #121 a graph result streamed whatever
     * the row cap said, so the cap bounded SELECT only.
     */
    private void writeStatements(HttpServletResponse resp, org.eclipse.rdf4j.query.GraphQuery gq,
                                 Negotiated<RDFFormat> fmt) throws IOException {
        var factory = RDFWriterRegistry.getInstance().get(fmt.format()).orElseThrow();
        // RDF4J's JSON-LD writer collects the whole graph in a model before it writes a byte, so
        // "streamed" meant "held in full": 1,500 100 KB literals ran a 128 MB heap out of memory as
        // JSON-LD and streamed fine as N-Triples (found in co-review of #123). Such a format is
        // always buffered here, within maxBufferedBytes; with no row cap configured, a result
        // beyond that is refused rather than silently cut short.
        //
        // NDJSON-LD's writer groups a fixed 1,024 statements of any size, which no setting changes
        // (it ran a 128 MB heap out of memory; found in co-review of #123), so it is held the same
        // way. Turtle and TriG — Turtle is the CONSTRUCT default — buffer while pretty-printing or
        // inlining blank nodes, both of which RDF4J turns on, so a streamed result turns them off:
        // grouping by subject is lost, and memory stays flat.
        boolean writerHoldsAll = fmt.format().equals(RDFFormat.JSONLD) || fmt.format().equals(RDFFormat.NDJSONLD);
        if (options.maxRows() == 0 && !writerHoldsAll) {
            RDFWriter writer = factory.getWriter(begin(resp, fmt));
            writer.getWriterConfig().set(org.eclipse.rdf4j.rio.helpers.BasicWriterSettings.PRETTY_PRINT, false);
            writer.getWriterConfig().set(org.eclipse.rdf4j.rio.helpers.BasicWriterSettings.INLINE_BLANK_NODES, false);
            gq.evaluate(writer);
            return;
        }
        long rowCap = options.maxRows() == 0 ? Long.MAX_VALUE : options.maxRows();
        List<Statement> statements = new ArrayList<>();
        Map<String, String> namespaces;
        boolean truncated = false;
        boolean tooBig = false;
        try (org.eclipse.rdf4j.query.GraphQueryResult result = gq.evaluate()) {
            long bytes = 0;
            while (result.hasNext()) {
                if (statements.size() >= rowCap) {
                    truncated = true;
                    break;
                }
                Statement st = result.next();
                long size = 64 + estimate(st.getSubject()) + estimate(st.getPredicate()) + estimate(st.getObject())
                        + estimate(st.getContext());
                if (bytes + size > options.maxBufferedBytes()) {
                    truncated = true;
                    tooBig = options.maxRows() == 0;
                    break;
                }
                bytes += size;
                statements.add(st);
            }
            namespaces = result.getNamespaces();
        }
        if (serviceLimitTripped(resp)) {
            return;
        }
        if (tooBig) {
            fail(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "the result exceeds " + options.maxBufferedBytes()
                    + " bytes, the most this endpoint holds to write " + fmt.mediaType()
                    + "; ask for Turtle or N-Triples, which stream");
            return;
        }
        if (truncated) {
            resp.setHeader(TRUNCATED_HEADER, "true");
        }
        RDFWriter writer = factory.getWriter(begin(resp, fmt));
        writer.startRDF();
        namespaces.forEach(writer::handleNamespace);
        for (Statement st : statements) {
            writer.handleStatement(st);
        }
        writer.endRDF();
    }

    /**
     * Roughly what holding {@code v} costs: its characters (two bytes each) plus a fixed
     * overhead for the object and, for a literal, its datatype and language. An estimate, not an
     * account — it only has to keep a buffer within the same order as {@code maxBufferedBytes}.
     */
    static long estimate(org.eclipse.rdf4j.model.Value v) {
        if (v == null) {
            return 0;
        }
        long size = 48 + 2L * v.stringValue().length();
        if (v instanceof org.eclipse.rdf4j.model.Literal l) {
            // the datatype IRI too: STRDT with a 10,000-character datatype passed as 66 bytes
            // (found in co-review of #123)
            size += 2L * l.getLanguage().map(String::length).orElse(0) + 48 + 2L * l.getDatatype().stringValue().length();
        }
        return size;
    }

    // ---- update ------------------------------------------------------------------------

    private void handleUpdate(HttpServletRequest req, HttpServletResponse resp, Params params, String updateString)
            throws IOException {
        if (!options.updateEnabled()) {
            fail(resp, HttpServletResponse.SC_FORBIDDEN,
                    "SPARQL Update is disabled on this endpoint (CQELS_SPARQL_UPDATE_ENABLED=true enables it)");
            return;
        }
        if (Boolean.TRUE.equals(req.getAttribute(AccessFilters.READ_ONLY))) {
            fail(resp, HttpServletResponse.SC_FORBIDDEN, "this request may only read: an update needs the update "
                    + "token (CQELS_SPARQL_UPDATE_TOKEN)");
            resp.setHeader("WWW-Authenticate", "Bearer error=\"insufficient_scope\"");   // RFC 6750 §3.1
            return;
        }
        executeUpdate(req, resp, params, updateString);
    }

    private void executeUpdate(HttpServletRequest req, HttpServletResponse resp, Params params, String requested)
            throws IOException {
        String updateString = withoutRepeatedPrefixes(requested);
        try (TimedServiceClient.Budget budget = openBudget();
             RepositoryConnection conn = repository.get().getConnection()) {
            String base = baseIri(req);
            Dataset dataset = dataset(params, "using-graph-uri", "using-named-graph-uri");
            Update update;
            try {
                ParsedUpdate parsed = QueryParserUtil.parseUpdate(QueryLanguage.SPARQL, updateString, base);
                if (refusedOutbound(resp, parsed.getUpdateExprs())) {
                    return;
                }
                if (dataset != null && namesItsOwnDataset(parsed)) {
                    // §2.2.3: protocol dataset parameters together with USING, USING NAMED or
                    // WITH in the request are an error. Executing anyway is not harmless —
                    // RDF4J lets WITH win, so the update silently rewrites a different graph
                    // from the one the parameters named.
                    fail(resp, HttpServletResponse.SC_BAD_REQUEST, "using-graph-uri / using-named-graph-uri cannot "
                            + "be combined with USING, USING NAMED or WITH in the update (SPARQL 1.1 Protocol §2.2.3)");
                    return;
                }
                Optional<Load> load = parsed.getUpdateExprs().stream()
                        .filter(Load.class::isInstance).map(Load.class::cast).findFirst();
                if (load.isPresent()) {
                    if (parsed.getUpdateExprs().size() != 1) {
                        fail(resp, HttpServletResponse.SC_BAD_REQUEST, "send LOAD as a request of its own: "
                                + "this endpoint performs LOAD itself and cannot mix it with other operations");
                        return;
                    }
                    executeLoad(req, resp, conn, load.get());
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
            // The reserved metadata graph is checked by its effect, not by reading the request:
            // INSERT DATA keeps its triples as an unparsed text block in RDF4J's algebra, where a
            // prefixed name (x:metadata) would hide the IRI from any check of the parse. Inside
            // one transaction, an update that leaves anything in that graph is undone before
            // anything — the engine included — can see it. So is one that finds data already
            // there and does not remove it; an update that empties the graph is accepted.
            if (commitUnlessReserved(req, resp, conn, update::execute)) {
                resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
            }
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
            if (outboundTimedOut(e)) {
                fail(resp, HttpServletResponse.SC_GATEWAY_TIMEOUT, "a SERVICE or LOAD target did not answer in time");
                return;
            }
            if (serviceFailed(resp, e)) {
                return;
            }
            log.warn("update failed: {}", e.toString());
            fail(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "update failed: " + e.getMessage());
        }
    }

    /**
     * Runs {@code work} in one transaction and commits it, unless it left a triple in
     * {@link #METADATA_GRAPH}: then it is rolled back and answered 403. Returns whether it
     * committed. Rolled back on every other way out too — and when the servlet has been
     * destroyed since {@code req} arrived (503): see {@link #generation}.
     */
    private boolean commitUnlessReserved(HttpServletRequest req, HttpServletResponse resp, RepositoryConnection conn,
                                         Runnable work)
            throws IOException {
        conn.begin();
        try {
            work.run();
            // a SERVICE limit that SILENT swallowed would otherwise commit a partial update
            TimedServiceClient.Budget budget = TimedServiceClient.Budget.CURRENT.get();
            if (budget != null && budget.tripped() != null) {
                conn.rollback();
                fail(resp, budget.tripped().status, budget.tripped().getMessage() + "; nothing was changed");
                return false;
            }
            if (conn.hasStatement(null, null, null, false, METADATA_GRAPH)) {
                conn.rollback();
                fail(resp, HttpServletResponse.SC_FORBIDDEN, "the graph <" + METADATA_GRAPH + "> is reserved "
                        + "for the endpoint's metadata snapshot; an update may not leave triples in it");
                return false;
            }
            // The engine sees the change on its next element: it listens to its own Sail for
            // lookup-cache invalidation (measured on 2.0.0-alpha.22, even under FROM <g> CACHE 5m).
            commitGate.readLock().lock();
            try {
                if (!Long.valueOf(generation.get()).equals(req.getAttribute(GENERATION))) {
                    conn.rollback();
                    fail(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                            "the endpoint stopped before the update could commit; nothing was changed");
                    return false;
                }
                conn.commit();
                return true;
            } finally {
                commitGate.readLock().unlock();
            }
        } finally {
            if (conn.isActive()) {
                conn.rollback();
            }
        }
    }

    /**
     * {@code LOAD}, performed here rather than by RDF4J. Its {@code RDFLoader} follows redirects by
     * hand to any scheme ({@code file:} included: an http URL redirecting to a local file read it
     * into the store) and its connection has no deadline, and neither can be fixed from outside
     * except JVM-wide — which a co-review of #123 showed fails silently in a JVM that has already
     * opened an HTTP connection. Java's {@code HttpClient} instead follows redirects only to
     * {@code http}/{@code https} (never from https to http), and the whole fetch is bounded by
     * the execution limit (504) and the body by {@code maxBufferedBytes}.
     *
     * <p>The price: a request that holds {@code LOAD} must hold nothing else (400), since RDF4J
     * cannot run the other operations of one request around a load done elsewhere. {@code SILENT}
     * keeps its meaning: a failed fetch or parse changes nothing and answers 204.
     */
    private void executeLoad(HttpServletRequest req, HttpServletResponse resp, RepositoryConnection conn, Load load)
            throws IOException {
        String source = load.getSource().getValue().stringValue();
        org.eclipse.rdf4j.model.Resource graph = load.getGraph() == null ? null
                : (org.eclipse.rdf4j.model.Resource) load.getGraph().getValue();
        // One deadline for everything the LOAD fetches: the source and any JSON-LD context.
        long deadline = System.nanoTime() + options.maxExecutionSeconds() * 1_000_000_000L;
        String timedOut = "LOAD <" + source + "> did not finish within " + options.maxExecutionSeconds() + " s";
        java.net.http.HttpResponse<byte[]> fetched;
        try {
            fetched = fetch(source, RDF_ACCEPT, deadline);
        } catch (java.util.concurrent.TimeoutException e) {
            loadFailed(resp, load, HttpServletResponse.SC_GATEWAY_TIMEOUT, timedOut);
            return;
        } catch (IOException | IllegalArgumentException e) {
            loadFailed(resp, load, HttpServletResponse.SC_BAD_GATEWAY, "LOAD <" + source + "> failed: " + e.getMessage());
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            loadFailed(resp, load, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "LOAD <" + source + "> was interrupted");
            return;
        }
        if (fetched.statusCode() / 100 != 2) {
            loadFailed(resp, load, HttpServletResponse.SC_BAD_GATEWAY,
                    "LOAD <" + source + "> answered HTTP " + fetched.statusCode());
            return;
        }
        byte[] body;
        try {
            body = decoded(fetched);
        } catch (IOException e) {
            loadFailed(resp, load, HttpServletResponse.SC_BAD_GATEWAY, "LOAD <" + source + ">: " + e.getMessage());
            return;
        }
        String type = fetched.headers().firstValue("Content-Type").map(SparqlProtocolServlet::mediaType).orElse("");
        List<Part> parts;
        try {
            parts = unpack(fetched.uri().getPath(), type, body);
        } catch (IOException e) {
            loadFailed(resp, load, HttpServletResponse.SC_BAD_GATEWAY, "LOAD <" + source + ">: " + e.getMessage());
            return;
        }
        List<RDFFormat> formats = new ArrayList<>();
        for (Part part : parts) {
            if (part.format().isEmpty()) {
                loadFailed(resp, load, HttpServletResponse.SC_BAD_GATEWAY, "LOAD <" + source + ">: no RDF format for "
                        + "'" + part.name() + "'" + (type.isEmpty() ? "" : " (Content-Type '" + type + "')"));
                return;
            }
            formats.add(part.format().get());
        }
        ContextLoader contexts = new ContextLoader(deadline);
        boolean committed;
        try {
            committed = commitUnlessReserved(req, resp, conn, () -> {
                org.eclipse.rdf4j.repository.util.RDFInserter inserter = new org.eclipse.rdf4j.repository.util.RDFInserter(conn);
                if (graph != null) {
                    inserter.enforceContext(graph);
                }
                for (int i = 0; i < parts.size(); i++) {
                    org.eclipse.rdf4j.rio.RDFParser parser = org.eclipse.rdf4j.rio.Rio.createParser(formats.get(i));
                    restrict(parser.getParserConfig(), contexts);
                    parser.setRDFHandler(inserter);
                    try {
                        parser.parse(new java.io.ByteArrayInputStream(parts.get(i).bytes()), fetched.uri().toString());
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
            });
        } catch (org.eclipse.rdf4j.rio.RDFParseException | org.eclipse.rdf4j.rio.RDFHandlerException
                 | java.io.UncheckedIOException e) {
            // UncheckedIOException: the parser's own I/O failures, such as an XML encoding the
            // runtime does not know, which escaped LOAD SILENT as a 500 (found in co-review of #123)
            if (contexts.timedOut) {
                loadFailed(resp, load, HttpServletResponse.SC_GATEWAY_TIMEOUT, timedOut);
            } else {
                loadFailed(resp, load, HttpServletResponse.SC_BAD_GATEWAY, "LOAD <" + source + ">: " + e.getMessage());
            }
            return;
        }
        if (committed) {
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
        }
    }

    private static final String RDF_ACCEPT = String.join(", ", RDFFormat.getAcceptParams(
            org.eclipse.rdf4j.rio.RDFParserRegistry.getInstance().getKeys(), false, null));

    /**
     * What a LOAD's parser may reach beyond the bytes it was given — nothing but what
     * {@link #fetch} allows. Found in co-review of #123: a JSON-LD document's {@code @context}
     * URL is fetched by jsonld-java's own loader, which read {@code file:///…} and waited past
     * the deadline. RDF/XML and TriX get no doctype and no external entities, so an entity
     * naming a local file cannot pull it in either.
     */
    private static void restrict(org.eclipse.rdf4j.rio.ParserConfig config, ContextLoader contexts) {
        config.set(org.eclipse.rdf4j.rio.helpers.JSONLDSettings.DOCUMENT_LOADER, contexts);
        config.set(org.eclipse.rdf4j.rio.helpers.XMLParserSettings.SECURE_PROCESSING, true);
        config.set(org.eclipse.rdf4j.rio.helpers.XMLParserSettings.DISALLOW_DOCTYPE_DECL, true);
        config.set(org.eclipse.rdf4j.rio.helpers.XMLParserSettings.LOAD_EXTERNAL_DTD, false);
        config.set(org.eclipse.rdf4j.rio.helpers.XMLParserSettings.EXTERNAL_GENERAL_ENTITIES, false);
        config.set(org.eclipse.rdf4j.rio.helpers.XMLParserSettings.EXTERNAL_PARAMETER_ENTITIES, false);
    }

    /** Remote JSON-LD contexts, fetched like the LOAD source: http(s) only, capped, within the same deadline. */
    private final class ContextLoader extends com.github.jsonldjava.core.DocumentLoader {
        private final long deadline;
        volatile boolean timedOut;

        ContextLoader(long deadline) {
            this.deadline = deadline;
        }

        @Override
        public com.github.jsonldjava.core.RemoteDocument loadDocument(String url)
                throws com.github.jsonldjava.core.JsonLdError {
            var refused = com.github.jsonldjava.core.JsonLdError.Error.LOADING_REMOTE_CONTEXT_FAILED;
            String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                throw new com.github.jsonldjava.core.JsonLdError(refused, "only http and https contexts: " + url);
            }
            try {
                var fetched = fetch(url, "application/ld+json, application/json;q=0.9", deadline);
                if (fetched.statusCode() / 100 != 2) {
                    throw new com.github.jsonldjava.core.JsonLdError(refused, url + " answered HTTP " + fetched.statusCode());
                }
                // A context page that is not JSON may point at its JSON-LD with
                // Link: <…>; rel="alternate"; type="application/ld+json" (JSON-LD 1.1 §9.4.4),
                // which jsonld-java's own loader followed (found in co-review of #123). Followed
                // once, through the same fetch.
                String type = fetched.headers().firstValue("Content-Type").map(SparqlProtocolServlet::mediaType).orElse("");
                if (!type.endsWith("json")) {
                    Optional<String> alternate = fetched.headers().allValues("Link").stream()
                            .flatMap(v -> java.util.Arrays.stream(v.split(",")))
                            .map(String::trim)
                            .filter(l -> l.matches("(?i)<[^>]+>.*;\\s*rel=\"?alternate\"?.*")
                                    && l.toLowerCase(Locale.ROOT).contains("application/ld+json"))
                            .map(l -> l.substring(1, l.indexOf('>')))
                            .findFirst();
                    if (alternate.isPresent()) {
                        String next = fetched.uri().resolve(alternate.get()).toString();
                        String lowerNext = next.toLowerCase(Locale.ROOT);
                        if (!lowerNext.startsWith("http://") && !lowerNext.startsWith("https://")) {
                            throw new com.github.jsonldjava.core.JsonLdError(refused, "only http and https contexts: " + next);
                        }
                        fetched = fetch(next, "application/ld+json, application/json;q=0.9", deadline);
                        if (fetched.statusCode() / 100 != 2) {
                            throw new com.github.jsonldjava.core.JsonLdError(refused, next + " answered HTTP " + fetched.statusCode());
                        }
                    }
                }
                return new com.github.jsonldjava.core.RemoteDocument(url,
                        com.github.jsonldjava.utils.JsonUtils.fromInputStream(new java.io.ByteArrayInputStream(decoded(fetched))));
            } catch (java.util.concurrent.TimeoutException e) {
                timedOut = true;
                throw new com.github.jsonldjava.core.JsonLdError(refused, url + " did not answer in time");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new com.github.jsonldjava.core.JsonLdError(refused, url + ": interrupted");
            } catch (IOException | IllegalArgumentException e) {
                throw new com.github.jsonldjava.core.JsonLdError(refused, url + ": " + e.getMessage());
            }
        }
    }

    /** One RDF document of a LOAD source: its name, its format (if any could be told), its bytes. */
    record Part(String name, Optional<RDFFormat> format, byte[] bytes) { }

    /**
     * A LOAD source as RDF4J's own loader read it: gzip unpacked and zip archives opened, one part
     * per file — told apart by their bytes (the gzip and zip signatures), not by name or media
     * type, and unpacked again if what came out is packed too. The servlet's first LOAD parsed a
     * {@code .ttl.gz} file as Turtle; its second, going by name, unpacked a {@code .ttl.gz} that
     * the server's {@code Content-Encoding: gzip} had already undone, and sent a gzipped file
     * inside a zip straight to the parser (both found in co-review of #123). A part's format
     * comes from its media type when it is the source itself, else from its name, a
     * {@code .gz} / {@code .zip} suffix dropped. Unpacked bytes count against
     * {@code maxBufferedBytes}, all parts together, and packing nests at most four deep.
     */
    private List<Part> unpack(String path, String type, byte[] body) throws IOException {
        List<Part> parts = new ArrayList<>();
        long[] budget = {options.maxBufferedBytes()};
        unpack(path == null ? "" : path, type, body, budget, 0, parts);
        if (parts.isEmpty()) {
            throw new IOException("the archive holds no files");
        }
        return parts;
    }

    private static void unpack(String name, String type, byte[] bytes, long[] budget, int depth, List<Part> into)
            throws IOException {
        unpack(name, type, Optional.empty(), bytes, budget, depth, into);
    }

    /**
     * {@code inherited} is the format the enclosing source was known to have: a gzipped body
     * labelled {@code text/turtle} with no extension is Turtle once unpacked, and a zip entry
     * named {@code part} in {@code archive.ttl.zip} is Turtle too — as RDF4J's loader read them
     * (found in co-review of #123). A media type travels through gzip, which packs one
     * document; not into a zip, whose entries name themselves.
     */
    private static void unpack(String name, String type, Optional<RDFFormat> inherited, byte[] bytes, long[] budget,
                               int depth, List<Part> into) throws IOException {
        boolean packedType = type.equals("application/gzip") || type.equals("application/x-gzip")
                || type.equals("application/zip") || type.equals("application/octet-stream");
        String plainType = packedType ? "" : type;
        Optional<RDFFormat> here = org.eclipse.rdf4j.rio.Rio.getParserFormatForMIMEType(plainType)
                .or(() -> org.eclipse.rdf4j.rio.Rio.getParserFormatForFileName(unpackedName(name)))
                .or(() -> inherited);
        boolean gzip = bytes.length >= 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b;
        boolean zip = bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
        if (!gzip && !zip) {
            into.add(new Part(name, here, bytes));
            return;
        }
        if (depth >= 4) {
            throw new IOException("packed more than four deep");
        }
        if (gzip) {
            try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
                unpack(unpackedName(name), plainType, here, readCapped(in, budget), budget, depth + 1, into);
            }
            return;
        }
        try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                // A directory entry is read too, against the same budget: skipped, the zip reader
                // still inflated whatever it held, outside any count — 2 MiB under a 4 KiB budget
                // (found in co-review of #123).
                byte[] entryBytes = readCapped(in, budget);
                if (!entry.isDirectory()) {
                    unpack(entry.getName(), "", here, entryBytes, budget, depth + 1, into);
                }
            }
        }
    }

    /** {@code data.ttl.gz} → {@code data.ttl}: the name the format is read from. */
    private static String unpackedName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".gz") || lower.endsWith(".zip")
                ? name.substring(0, name.lastIndexOf('.')) : name;
    }

    /** Reads {@code in} to its end, failing once more than {@code budget[0]} bytes have come, and debits them. */
    private static byte[] readCapped(InputStream in, long[] budget) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n > budget[0]) {
                throw new IOException("the unpacked body exceeds the buffer budget");
            }
            budget[0] -= n;
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void loadFailed(HttpServletResponse resp, Load load, int status, String message) throws IOException {
        if (load.isSilent()) {
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);   // SILENT: the failure changes nothing
            return;
        }
        fail(resp, status, message);
    }

    /**
     * GETs {@code url} by {@code deadline} (a {@link System#nanoTime} instant, ignored when the
     * execution limit is 0), following redirects only to http(s), holding at most
     * {@code maxBufferedBytes} of body.
     */
    private java.net.http.HttpResponse<byte[]> fetch(String url, String accept, long deadline)
            throws IOException, InterruptedException, java.util.concurrent.TimeoutException {
        java.net.http.HttpClient client = loadClient;
        if (client == null) {
            java.net.http.HttpClient.Builder b = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL);
            if (options.maxExecutionSeconds() > 0) {
                b.connectTimeout(java.time.Duration.ofSeconds(options.maxExecutionSeconds()));
            }
            client = b.build();
            loadClient = client;
        }
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .header("Accept", accept).GET().build();
        long cap = options.maxBufferedBytes();
        boolean timed = options.maxExecutionSeconds() > 0;
        long left = deadline - System.nanoTime();
        if (timed && left <= 0) {
            throw new java.util.concurrent.TimeoutException();
        }
        var pending = client.sendAsync(request, info -> new CappedBody(cap));
        try {
            return timed ? pending.get(left, java.util.concurrent.TimeUnit.NANOSECONDS) : pending.get();
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof IOException io ? io : new IOException(String.valueOf(cause), cause);
        } catch (java.util.concurrent.TimeoutException | InterruptedException e) {
            pending.cancel(true);
            throw e;
        }
    }

    /**
     * The body with its {@code Content-Encoding} undone ({@code gzip}, {@code deflate}), held to
     * {@code maxBufferedBytes} after decoding as well. RDF4J's own LOAD decoded a gzip-encoded
     * source; passed straight to the parser it failed on the gzip magic byte (found in
     * co-review of #123). Any other encoding is refused.
     */
    private byte[] decoded(java.net.http.HttpResponse<byte[]> fetched) throws IOException {
        String encoding = fetched.headers().firstValue("Content-Encoding").orElse("identity").trim().toLowerCase(Locale.ROOT);
        InputStream in;
        switch (encoding) {
            case "identity", "" -> {
                return fetched.body();
            }
            case "gzip", "x-gzip" -> in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(fetched.body()));
            case "deflate" -> in = new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(fetched.body()));
            default -> throw new IOException("unsupported Content-Encoding '" + encoding + "'");
        }
        long cap = options.maxBufferedBytes();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (out.size() + (long) n > cap) {
                    throw new IOException("the decoded body exceeds " + cap + " bytes");
                }
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
    }

    /** A response body held in memory up to {@code cap} bytes; beyond it the fetch fails. */
    private static final class CappedBody implements java.net.http.HttpResponse.BodySubscriber<byte[]> {
        private final long cap;
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        private final java.util.concurrent.CompletableFuture<byte[]> result = new java.util.concurrent.CompletableFuture<>();
        private java.util.concurrent.Flow.Subscription subscription;

        CappedBody(long cap) {
            this.cap = cap;
        }

        @Override
        public java.util.concurrent.CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<java.nio.ByteBuffer> items) {
            for (java.nio.ByteBuffer b : items) {
                if (out.size() + (long) b.remaining() > cap) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("the body exceeds " + cap + " bytes"));
                    return;
                }
                byte[] chunk = new byte[b.remaining()];
                b.get(chunk);
                out.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable t) {
            result.completeExceptionally(t);
        }

        @Override
        public void onComplete() {
            result.complete(out.toByteArray());
        }
    }

    /**
     * Whether the execution limit has run out since {@code started}. A failure past it is
     * answered as the limit, whatever RDF4J happened to throw: when its own timer and a
     * SERVICE deadline fire together, the iteration they both close can surface as a bare
     * {@code NoSuchElementException} (found in co-review of #123), which said "internal error"
     * about a query that had simply run out of time.
     */
    private boolean pastLimit(long started) {
        return options.maxExecutionSeconds() > 0
                && System.nanoTime() - started >= options.maxExecutionSeconds() * 1_000_000_000L;
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
    private static boolean namesItsOwnDataset(ParsedUpdate parsed) {
        return parsed.getDatasetMapping().values().stream().anyMatch(Objects::nonNull);
    }

    /**
     * Whether evaluating {@code node} would make this server send a request of its own:
     * {@code SERVICE} anywhere in a query or an update's {@code WHERE}, or {@code LOAD}.
     *
     * <p>Why this is refused by default rather than left to the origin filter: a cross-site
     * {@code <img src="http://127.0.0.1:8089/sparql?query=…">} is a GET that carries no
     * {@code Origin} header, and with {@code SERVICE <http://attacker/>} in it the server
     * itself posts bindings from the graph to the attacker — found reviewing #105, where the
     * remote service received the seeded drivers' names in a {@code VALUES} clause. CORS
     * cannot help; the data never passes through the browser. The engine owns the repository,
     * so its service resolver cannot be swapped out here; the parsed algebra is checked instead.
     */
    static boolean reachesOut(QueryModelNode node) {
        boolean[] found = {false};
        node.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(Service service) {
                found[0] = true;
            }

            @Override
            public void meet(Load load) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /**
     * The first {@code LOAD} source or constant {@code SERVICE} IRI in {@code node} whose scheme
     * is not {@code http} or {@code https}, or {@code null}.
     *
     * <p>Why, with federation on: RDF4J's {@code LOAD} opens any URL Java can, so
     * {@code LOAD <file:///…/x.ttl>} read an RDF file off the server's disk into the store, where
     * the next query returned it (found in the audit after #121). Federation is "this server
     * talks to other servers", not "this server's files". A {@code SERVICE ?var} resolved at run
     * time is not checked here; RDF4J's {@code SERVICE} client speaks HTTP only, so it cannot
     * open a file. A {@code LOAD} redirect is followed only to {@code http}/{@code https}:
     * the servlet fetches the source itself ({@link #executeLoad}).
     */
    static String nonHttpTarget(QueryModelNode node) {
        String[] found = {null};
        node.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(Service service) {
                if (found[0] == null && service.getServiceRef().hasValue()) {
                    check(service.getServiceRef().getValue().stringValue());
                }
                super.meet(service);
            }

            @Override
            public void meet(Load load) {
                if (found[0] == null) {
                    check(load.getSource().getValue().stringValue());
                }
            }

            private void check(String iri) {
                String lower = iri.toLowerCase(Locale.ROOT);
                if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                    found[0] = iri;
                }
            }
        });
        return found[0];
    }

    /** Answers 403 and returns true when federation is off and {@code nodes} reach out, or reach a non-HTTP IRI. */
    private boolean refusedOutbound(HttpServletResponse resp, List<? extends QueryModelNode> nodes) throws IOException {
        for (QueryModelNode node : nodes) {
            if (!options.federationEnabled() && reachesOut(node)) {
                federationDisabled(resp);
                return true;
            }
            String bad = options.federationEnabled() ? nonHttpTarget(node) : null;
            if (bad != null) {
                fail(resp, HttpServletResponse.SC_FORBIDDEN, "SERVICE and LOAD may name only http and https IRIs, "
                        + "not <" + bad + ">");
                return true;
            }
            // SERVICE goes through the repository's own client, which only
            // SparqlEndpointLauncher.limitOutboundRequests gives a deadline and the refusal of
            // XML answers. A program mounting the servlet without it — or a resolver that
            // ignored it — would run SERVICE without either, so SERVICE waits for it.
            if (options.federationEnabled() && serviceInOrdering(node)) {
                fail(resp, HttpServletResponse.SC_BAD_REQUEST, "SERVICE inside an ORDER BY expression is not accepted "
                        + "here: RDF4J sorts in parallel, and SERVICE calls made by the sort's worker threads would escape "
                        + "this query's SERVICE limits. Bind the value first (BIND or a subquery), then order by the variable");
                return true;
            }
            if (options.federationEnabled() && containsService(node)
                    && !SparqlEndpointLauncher.outboundLimited(repository.get())) {
                fail(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "SERVICE is unavailable: the repository's SERVICE "
                        + "client has not been given this endpoint's limits (SparqlEndpointLauncher.limitOutboundRequests)");
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a SERVICE sits inside an ORDER BY key ({@code EXISTS { SERVICE … }} in one).
     * RDF4J's {@code OrderIterator} sorts with {@code Arrays.parallelSort}, so the comparator —
     * which evaluates those keys — runs on common-pool threads, where the query's
     * {@code TimedServiceClient.Budget} is not: 9,000 rows made 19 SERVICE requests under a limit
     * of 1 (found in co-review of #129). Refused rather than half-bounded.
     */
    static boolean serviceInOrdering(QueryModelNode node) {
        boolean[] found = {false};
        node.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(org.eclipse.rdf4j.query.algebra.Order order) {
                for (org.eclipse.rdf4j.query.algebra.OrderElem elem : order.getElements()) {
                    if (containsService(elem.getExpr())) {
                        found[0] = true;
                    }
                }
                super.meet(order);
            }
        });
        return found[0];
    }

    private static boolean containsService(QueryModelNode node) {
        boolean[] found = {false};
        node.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(Service service) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /**
     * Whether {@code e} or anything it wraps is a socket read or connect timing out: a
     * {@code SERVICE} endpoint that did not answer within the deadline
     * {@link SparqlEndpointLauncher#limitOutboundRequests} gives its client.
     */
    /** The query's SERVICE budget: one deadline, request count and byte total for all its SERVICE calls. */
    private TimedServiceClient.Budget openBudget() {
        return TimedServiceClient.Budget.open(options.maxExecutionSeconds(), options.maxServiceRequests(),
                options.maxServiceBytes());
    }

    /**
     * Answers the SERVICE limit this query ran into, if one tripped though evaluation went on —
     * which {@code SERVICE SILENT} makes it do, with fewer rows — and returns whether it did.
     */
    private static boolean serviceLimitTripped(HttpServletResponse resp) throws IOException {
        TimedServiceClient.Budget budget = TimedServiceClient.Budget.CURRENT.get();
        TimedServiceClient.Refusal limit = budget == null ? null : budget.tripped();
        if (limit == null) {
            return false;
        }
        fail(resp, limit.status, limit.getMessage());
        return true;
    }

    /**
     * Answers a SERVICE call that failed, if {@code e} is one, and returns whether it was: the
     * status a {@link TimedServiceClient.Refusal} carries (403 a non-http endpoint, 502 an answer
     * too large, 503 too many requests, 504 the query's deadline), and 502 for a remote that
     * failed or could not be reached. Every such failure was a 500 "query evaluation failed"
     * before the SERVICE audit after #128, which said the fault was here, not upstream.
     */
    private static boolean serviceFailed(HttpServletResponse resp, Throwable e) throws IOException {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TimedServiceClient.Refusal r) {
                fail(resp, r.status, r.getMessage());
                return true;
            }
            if (t instanceof TimedServiceClient.RemoteFailure) {
                fail(resp, HttpServletResponse.SC_BAD_GATEWAY, t.getMessage());
                return true;
            }
            if (t instanceof java.net.ConnectException || t instanceof java.net.NoRouteToHostException
                    || t instanceof java.net.UnknownHostException || t instanceof org.apache.http.NoHttpResponseException
                    || t instanceof javax.net.ssl.SSLException || t instanceof org.apache.http.client.ClientProtocolException) {
                Throwable reason = t.getCause() != null && t.getCause().getMessage() != null ? t.getCause() : t;
                fail(resp, HttpServletResponse.SC_BAD_GATEWAY, "a SERVICE endpoint could not be reached: " + reason.getMessage());
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static boolean outboundTimedOut(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.SocketTimeoutException   // TimedServiceClient's deadline reports as one
                    || t instanceof org.apache.http.conn.ConnectTimeoutException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** Whether {@code d} names {@code g} as a default or a named graph. */
    static boolean names(Dataset d, IRI g) {
        return d != null && (d.getDefaultGraphs().contains(g) || d.getNamedGraphs().contains(g));
    }

    /** Whether every graph {@code d} names is {@code g}. */
    static boolean namesOnly(Dataset d, IRI g) {
        Set<IRI> all = new java.util.HashSet<>(d.getDefaultGraphs());
        all.addAll(d.getNamedGraphs());
        return all.equals(Set.of(g));
    }

    /**
     * Whether a {@code GRAPH <g> { … }} clause anywhere in the query names {@code g}, after its
     * prefixes and base are resolved — {@code m:metadata} and {@code <metadata>} under a matching
     * {@code BASE} count. Read from RDF4J's syntax tree rather than the algebra: the algebra
     * keeps the graph only on statement patterns, so a clause that reads no triples
     * ({@code GRAPH <g> { BIND(1 AS ?x) }}, {@code GRAPH <g> { }}, a {@code VALUES} block) leaves
     * no trace there — found in co-review of #121, where such a query answered 200 from the
     * store instead of the 400 below. Called only after the query has parsed.
     */
    static boolean inGraphClause(String query, String base, IRI g) {
        try {
            ASTQueryContainer tree = SyntaxTreeBuilder.parseQuery(query);
            // The same two steps RDF4J's own parser takes before building the algebra.
            BaseDeclProcessor.process(tree, base);
            PrefixDeclProcessor.process(tree, Map.of());
            return graphClauseNames(tree, g.stringValue());
        } catch (ParseException | TokenMgrError | MalformedQueryException e) {
            return false;   // unreachable for a query that parsed; the parse already answered for it
        }
    }

    private static boolean graphClauseNames(Node node, String iri) {
        if (node instanceof ASTGraphGraphPattern && node.jjtGetNumChildren() > 0
                && node.jjtGetChild(0) instanceof ASTIRI name && iri.equals(name.getValue())) {
            return true;
        }
        for (int i = 0; i < node.jjtGetNumChildren(); i++) {
            if (graphClauseNames(node.jjtGetChild(i), iri)) {
                return true;
            }
        }
        return false;
    }

    private static void federationDisabled(HttpServletResponse resp) throws IOException {
        fail(resp, HttpServletResponse.SC_FORBIDDEN, "SERVICE and LOAD are disabled on this endpoint: they make the "
                + "server send requests of its own (CQELS_SPARQL_FEDERATION_ENABLED=true enables them)");
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
        // RDF4J's writers flush as they go — the CSV writer right after its header row — and a
        // flush commits the response: a SERVICE limit or a timeout a few rows later could then only
        // abort it, never answer its status (found in the SERVICE audit after #128). Their flushes
        // are ignored here; Jetty sends when its own buffer fills and when the response ends, so a
        // failure within the first buffer is still a clean status.
        OutputStream out = resp.getOutputStream();
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                out.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void flush() {
                // see above
            }

            @Override
            public void close() throws IOException {
                out.close();
            }
        };
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
        // Split outside quoted strings: a parameter value may be a quoted-string holding ',' or ';'
        // (RFC 9110 §5.6.4), and a plain split broke text/csv;x="a,b" into two bogus ranges, the
        // second of them able to match (found in the audit after #121).
        for (String part : splitOutsideQuotes(accept, ',')) {
            List<String> pieces = splitOutsideQuotes(part, ';');
            String type = pieces.get(0).trim().toLowerCase(Locale.ROOT);
            if (type.isEmpty()) {
                continue;
            }
            double q = 1.0;
            Map<String, String> params = new LinkedHashMap<>();
            for (int i = 1; i < pieces.size(); i++) {
                String p = pieces.get(i).trim();
                int eq = p.indexOf('=');
                String name = (eq < 0 ? p : p.substring(0, eq)).trim().toLowerCase(Locale.ROOT);   // names are case-insensitive
                String value = eq < 0 ? "" : unquote(p.substring(eq + 1).trim());
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

    /** {@code s} split at {@code sep} where it is not inside a quoted-string; empty pieces kept. */
    static List<String> splitOutsideQuotes(String s, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted && c == '\\' && i + 1 < s.length()) {
                cur.append(c).append(s.charAt(++i));   // quoted-pair: the next character is literal
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
            } else if (c == sep && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    /** A parameter value: a quoted-string loses its quotes and its quoted-pair backslashes; a token is as is. */
    static String unquote(String v) {
        if (v.length() < 2 || v.charAt(0) != '"' || v.charAt(v.length() - 1) != '"') {
            return v;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < v.length() - 1; i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length() - 1) {
                c = v.charAt(++i);
            }
            out.append(c);
        }
        return out.toString();
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

    /**
     * {@code text} with every {@code PREFIX} declaration of its leading prologue blanked out that
     * a later one in the same prologue declares again, so the last declaration of a prefix wins.
     *
     * <p><strong>A deliberate leniency.</strong> SPARQL 1.1 Query §19.5 says a prefix "may not be
     * re-declared in the same query", and RDF4J refuses it ("Multiple prefix declarations for
     * prefix"); Jena and Virtuoso accept it, the last declaration winning. rdflib's
     * {@code SPARQLStore} prepends some 30 namespaces to every query, so a query declaring
     * {@code skos:} itself was a 400 here and a 200 there (cqels/CQELS4J#115). This endpoint sides
     * with Jena for that case.
     *
     * <p><strong>Only what it reads with certainty.</strong> Only the leading prologue is read,
     * and only while it follows the grammar exactly: whitespace as SPARQL defines it (space, tab,
     * CR, LF), {@code #} comments ending at CR or LF (§19.4), {@code BASE} and {@code PREFIX} in
     * any ASCII case, prefix names of ASCII PN_PREFIX characters, and IRIREFs without the
     * characters production [139] excludes. At the first token that is anything else — the query's
     * form, or a declaration it cannot read so — the scan stops, and what it has not read is left
     * to RDF4J as it was; so it never turns text RDF4J would refuse as malformed into text it
     * accepts (found in the Opus co-review of #135: a comment ended by a bare CR ran the scan into
     * a string literal and blanked part of it; Unicode spaces, a dotless ı and {@code { }} inside
     * an IRI were read as if valid).
     *
     * <p>Removing an earlier declaration changes no other meaning: every use of a prefix comes
     * after the whole prologue, where the last declaration is the one in force, and a {@code BASE}
     * between them resolves the kept declaration's IRI as before. A blanked declaration becomes
     * spaces, its line breaks kept, so the line and column RDF4J reports for a later error are
     * those of the request. In an update, only the prologue before the first operation is read.
     * One pass, linear in the text: it runs inside a query slot, before any execution limit.
     */
    static String withoutRepeatedPrefixes(String text) {
        int n = text.length();
        List<int[]> spans = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (sparqlSpace(c)) {
                i++;
            } else if (c == '#') {
                int end = i + 1;
                while (end < n && text.charAt(end) != '\n' && text.charAt(end) != '\r') {
                    end++;
                }
                if (end >= n) {
                    break;
                }
                i = end;
            } else if (prologueKeyword(text, i, "base")) {
                int end = iriRef(text, i + 4);
                if (end < 0) {
                    break;
                }
                i = end;
            } else if (prologueKeyword(text, i, "prefix")) {
                int j = i + 6;
                while (j < n && sparqlSpace(text.charAt(j))) {
                    j++;
                }
                int colon = text.indexOf(':', j);
                if (colon < 0 || !pnPrefix(text, j, colon)) {
                    break;
                }
                int end = iriRef(text, colon + 1);
                if (end < 0) {
                    break;
                }
                names.add(text.substring(j, colon));
                spans.add(new int[] {i, end});
                i = end;
            } else {
                break;
            }
        }
        // one pass: the last declaration of each name, then every other one blanked (a list
        // searched per declaration took 4.4 s for a 1 MB body of declarations — Opus co-review of #135)
        Map<String, Integer> last = new java.util.HashMap<>();
        for (int k = 0; k < names.size(); k++) {
            last.put(names.get(k), k);
        }
        if (last.size() == names.size()) {
            return text;
        }
        char[] out = text.toCharArray();
        for (int k = 0; k < names.size(); k++) {
            if (last.get(names.get(k)) != k) {
                for (int p = spans.get(k)[0]; p < spans.get(k)[1]; p++) {
                    if (out[p] != '\n' && out[p] != '\r') {
                        out[p] = ' ';
                    }
                }
            }
        }
        return new String(out);
    }

    /** SPARQL's whitespace (§19.8, WS): space, tab, CR, LF — nothing else Java calls whitespace. */
    private static boolean sparqlSpace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    /**
     * Whether {@code lower} (ASCII lowercase letters) starts at {@code at} in any ASCII case and
     * is followed by SPARQL whitespace. Not {@code regionMatches(true, …)}: that folds U+0131 (ı)
     * to {@code I}, and read {@code PREFıX} as the keyword.
     */
    private static boolean prologueKeyword(String text, int at, String lower) {
        int end = at + lower.length();
        if (end >= text.length()) {
            return false;
        }
        for (int k = 0; k < lower.length(); k++) {
            char c = text.charAt(at + k);
            if (c > 0x7F || (c | 0x20) != lower.charAt(k)) {
                return false;
            }
        }
        return sparqlSpace(text.charAt(end));
    }

    /**
     * Whether {@code text[from, to)} is a prefix name this scan reads with certainty: empty (the
     * default prefix), or PN_PREFIX in ASCII — a letter, then letters, digits, '_', '-' or '.',
     * not ending in '.'. A name outside that stops the scan; RDF4J judges it.
     */
    private static boolean pnPrefix(String text, int from, int to) {
        if (from == to) {
            return true;
        }
        if (!asciiLetter(text.charAt(from)) || text.charAt(to - 1) == '.') {
            return false;
        }
        for (int k = from + 1; k < to; k++) {
            char c = text.charAt(k);
            if (!(asciiLetter(c) || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.')) {
                return false;
            }
        }
        return true;
    }

    private static boolean asciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * The index just past an IRIREF that follows {@code from} after SPARQL whitespace only, or
     * {@code -1} when there is none to read with certainty: production [139] excludes
     * {@code <>"{}|^`\} and everything up to U+0020 from it.
     */
    private static int iriRef(String text, int from) {
        int n = text.length();
        int i = from;
        while (i < n && sparqlSpace(text.charAt(i))) {
            i++;
        }
        if (i >= n || text.charAt(i) != '<') {
            return -1;
        }
        for (int j = i + 1; j < n; j++) {
            char c = text.charAt(j);
            if (c == '>') {
                return j + 1;
            }
            if (c <= ' ' || "<\"{}|^`\\".indexOf(c) >= 0) {
                return -1;
            }
        }
        return -1;
    }

    /** The service-defined base IRI of §2.3: the request URL, without its query string. */
    private static String baseIri(HttpServletRequest req) {
        return req.getRequestURL().toString();
    }

    /**
     * Reads a POST body within the size and time limits. Answers 413 beyond the size, 408 past
     * {@code maxUploadSeconds}, or 415 for a charset this runtime does not know, and returns
     * {@code null} in each case.
     *
     * <p>The deadline cannot be checked only between reads: a read blocks until body bytes
     * arrive, and a client can keep a chunked body's framing busy — a chunk extension sent a
     * character at a time — without ever delivering one, holding the slot for as long as it
     * likes while the traffic keeps the idle timeout away (found in co-review of #123). So a
     * watchdog interrupts the reading thread at the deadline; the container's blocking read
     * gives up with an {@code InterruptedIOException}. The interrupt is delivered and cleared
     * under one lock, so it can never land after the body is read, on the query that follows.
     */
    private String readBody(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long max = options.maxRequestBytes();
        // Never 0: Math.min(max + 1, …) overflowed for max = Long.MAX_VALUE into an empty buffer,
        // which read every body as empty (found in the audit after #121).
        byte[] buf = new byte[(int) Math.min(max, 64 * 1024) + 1];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        Thread reader = Thread.currentThread();
        Object lock = new Object();
        boolean[] state = {false, false};   // {reading done, deadline passed}
        java.util.concurrent.ScheduledExecutorService watcher = uploadWatch;   // null only before init()
        java.util.concurrent.ScheduledFuture<?> watch = options.maxUploadSeconds() == 0 || watcher == null ? null
                : watcher.schedule(() -> {
                    synchronized (lock) {
                        if (!state[0]) {
                            state[1] = true;
                            reader.interrupt();
                        }
                    }
                }, options.maxUploadSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        boolean tooLarge = false;
        try (InputStream in = req.getInputStream()) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                if (out.size() > max) {
                    tooLarge = true;
                    break;
                }
            }
        } catch (IOException e) {
            synchronized (lock) {
                if (!state[1]) {
                    throw e;   // a real I/O failure, not the deadline
                }
            }
        } finally {
            if (watch != null) {
                watch.cancel(false);
            }
            synchronized (lock) {
                state[0] = true;
                if (state[1]) {
                    Thread.interrupted();   // the watchdog's interrupt: consumed here, never seen later
                }
            }
        }
        if (state[1]) {
            fail(resp, HttpServletResponse.SC_REQUEST_TIMEOUT,
                    "request body took longer than " + options.maxUploadSeconds() + " s to arrive");
            return null;
        }
        if (tooLarge) {
            fail(resp, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "request body exceeds " + max + " bytes");
            return null;
        }
        Charset cs;
        try {
            cs = bodyCharset(req);
        } catch (IllegalArgumentException e) {
            fail(resp, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                    "unsupported charset '" + req.getCharacterEncoding() + "'");
            return null;
        }
        try {
            return strictly(out.toByteArray(), cs);
        } catch (java.nio.charset.CharacterCodingException e) {
            fail(resp, HttpServletResponse.SC_BAD_REQUEST, "the request body is not valid " + cs.name());
            return null;
        }
    }

    /**
     * Decodes or fails. {@code new String(bytes, cs)} replaces a malformed sequence with U+FFFD, so
     * an update in broken UTF-8 stored altered literals and answered 204 (found in the audit after
     * #121); a client is better told its bytes were not what it declared.
     */
    static String strictly(byte[] bytes, Charset cs) throws java.nio.charset.CharacterCodingException {
        return cs.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
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
        busyRejections.increment();
        // Logged, but at most once a minute: a client retrying in a loop would otherwise fill the
        // log with the very condition it is told about. Before the audit after #121 nothing was.
        long now = System.nanoTime();
        long last = lastBusyWarning.get();
        if ((!warnedBusy || now - last >= 60_000_000_000L) && lastBusyWarning.compareAndSet(last, now)) {
            warnedBusy = true;
            log.warn("every query slot ({}) is taken; turning requests away with 503 ({} so far)",
                    options.maxConcurrentQueries(), busyRejections.sum());
        }
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
     * {@code application/x-www-form-urlencoded}, UTF-8 unless the form body declares otherwise,
     * and decoded strictly: bytes that are not valid in that charset are a 400, not U+FFFD.
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
            try {
                return strictly(bytes.toByteArray(), cs);
            } catch (java.nio.charset.CharacterCodingException e) {
                throw new IllegalArgumentException("request parameters are not valid " + cs.name() + " once percent-decoded");
            }
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
