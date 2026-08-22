package org.cqels.examples;

import org.cqels.asp.config.AspStreamSolveConfig;
import org.cqels.asp.integration.AspFactMapper;
import org.cqels.asp.query.AspContinuousQuery;
import org.cqels.asp.solver.AspSolverBackend;
import org.cqels.asp.solver.WarmParseCacheAspSolverBackend;
import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.reasoning.Rule;
import org.cqels.reasoning.RuleCondition;
import org.cqels.reasoning.RuleConsequent;
import org.cqels.reasoning.RuleSet;
import org.cqels.reasoning.TriplePattern;
import org.cqels.reasoning.TripleTemplate;
import org.cqels.reasoning.config.ReasoningConfig;
import org.cqels.reasoning.engine.ReactiveReteAdapter;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Behaviour-truth probe — asserts that what this repository <em>says</em> about the engine is still
 * what the engine <em>does</em>.
 *
 * <p><strong>Why this exists.</strong> `scripts/ci/version-truth-gate.sh` was written because a claim
 * in the prose can go stale without anything failing to compile. Capability claims are the same
 * hazard one category over. The examples, `examples/README.md`, `CQELS-QL_SPEC.md` §6/§9 and
 * `CDSP_MAPPING.md` all carry statements of the form "X does not work on 2.0.0-alpha.18, work around
 * it like this". Every one of those becomes <em>false</em> the day the engine is fixed, and nothing
 * would notice: the workarounds keep working, so the build stays green while the documentation
 * quietly starts lying — and readers keep applying workarounds they no longer need.
 *
 * <p>So this probe checks in <strong>both directions</strong>:
 * <ul>
 *   <li><strong>Caveats</strong> — documented as broken. If one starts working, that is a
 *       <em>documentation defect</em>: the prose and the workarounds must be removed. The probe
 *       fails and names the files to edit.</li>
 *   <li><strong>Capabilities</strong> — documented as working, and relied on by the examples. If one
 *       breaks, that is a regression in a new engine release.</li>
 * </ul>
 *
 * <p>Not every claim is about query evaluation. The last pair of checks covers <em>API shape</em>:
 * that the warm parse-cache ASP backend is reachable through the facade's generic
 * {@code registerQuery} (asserted by registering one and seeing it solve), and that
 * {@code registerAspQuery} still has no backend-accepting overload (checked reflectively, since that
 * is a statement about the API's shape rather than about behaviour). Both underwrite the note beside
 * the reasoning demos in {@code examples/README.md}; if the facade grows such an overload, that note
 * needs revisiting and this probe says so.
 *
 * <p>Either way a failure means "reality and the documentation have diverged", which is exactly the
 * class of defect this repository exists to avoid. Run it whenever the pin moves — `RELEASING.md`
 * step 4 — and nightly via `.github/workflows/version-truth.yml`.
 *
 * <p>Exit status: <strong>0</strong> if documentation and reality agree, <strong>non-zero</strong>
 * (an exception, so Maven fails the build) if they diverge.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.CapabilityProbe}
 */
public class CapabilityProbe {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final String C = "https://example.org/probe#";
    private static final java.util.concurrent.atomic.AtomicLong QUERY_SEQ =
            new java.util.concurrent.atomic.AtomicLong();
    private static final List<String> DIVERGENCES = new ArrayList<>();
    private static int checks = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("Capability probe — does the engine still behave as this repo documents?\n");

        System.out.println("-- caveats (documented as BROKEN; a pass here means the docs are stale) --");
        caveat("#54 static pattern fails to eliminate a non-matching row",
                staticPatternEliminates(),
                "CQELS-QL_SPEC.md §6 'Known defect', S2dmConceptCatalog.java, CDSP_MAPPING.md §5");
        caveat("#55 prefixed name inside FILTER is not resolved",
                pnameInFilterResolves(),
                "CQELS-QL_SPEC.md §9 gap table, S2dmInstanceZones.java, CDSP_MAPPING.md §5");
        caveat("#56 property path registers despite a parse error",
                propertyPathRejected(),
                "CQELS-QL_SPEC.md §9 'Property paths', CDSP_MAPPING.md §5");
        caveat("     REPLACE() is not evaluated",
                replaceEvaluates(),
                "CQELS-QL_SPEC.md §9 gap table, CDSP_MAPPING.md §4");
        caveat("     sameTerm() is not evaluated",
                sameTermEvaluates(),
                "CQELS-QL_SPEC.md §9 gap table (no issue of its own; recorded on #55)");
        caveat("#57 reasoner skips multi-statement (atomic) elements",
                reasonerSeesAtomicElements(),
                "S2dm.pushConceptSignalUnbatched, SkosConceptRollup.java, GETTING_STARTED.md table");
        caveat("#58 reasoner cannot match the background graph",
                reasonerSeesBackgroundGraph(),
                "SkosConceptRollup.java, CDSP_MAPPING.md §5, GETTING_STARTED.md limitations table");
        caveat("     registerAspQuery has no backend-accepting overload",
                registerAspQueryTakesBackend(),
                "examples/README.md note under 'Reasoning & validation'");

        System.out.println("\n-- capabilities (documented as WORKING; the examples depend on these) --");
        capability("stream-static enrichment binds static variables", staticEnrichmentBinds());
        capability("FILTER/BIND builtins: ABS, CONCAT, IRI, SUBSTR, IF", builtinsWork());
        capability("atomic multi-statement element joins in a query", atomicElementJoins());
        capability("GROUP BY with aggregates", groupByWorks());
        capability("warm parse-cache ASP backend reachable via registerQuery", warmBackendReachable());

        System.out.println("\n" + "-".repeat(72));
        if (DIVERGENCES.isEmpty()) {
            System.out.println("OK: " + checks + " checks — documentation and engine behaviour agree.");
            return;
        }
        System.out.println("DIVERGENCE: " + DIVERGENCES.size() + " of " + checks
                + " checks disagree with the documentation.\n");
        DIVERGENCES.forEach(d -> System.out.println("  " + d));
        throw new IllegalStateException(
                DIVERGENCES.size() + " capability claim(s) in this repository are no longer true. "
                        + "Update the files named above, then re-run.");
    }

    /** A behaviour documented as broken. {@code works == true} means the docs are now stale. */
    private static void caveat(String name, boolean works, String docs) {
        checks++;
        if (works) {
            System.out.println("  NOW FIXED  " + name);
            DIVERGENCES.add("NOW FIXED — " + name + "\n      remove the caveat and its workaround from: " + docs);
        } else {
            System.out.println("  still open " + name);
        }
    }

    /** A behaviour documented as working. {@code works == false} is a regression. */
    private static void capability(String name, boolean works) {
        checks++;
        if (works) {
            System.out.println("  ok         " + name);
        } else {
            System.out.println("  REGRESSED  " + name);
            DIVERGENCES.add("REGRESSED — " + name + "\n      the examples relying on this no longer work");
        }
    }

    // ---- caveat probes -------------------------------------------------------------------

    /** #54: a static pattern with no matching data must eliminate the row. Today it does not. */
    private static boolean staticPatternEliminates() throws Exception {
        List<Object> rows = run(engine -> {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                conn.add(VF.createIRI(C + "a"), VF.createIRI(C + "broader"), VF.createIRI(C + "b"));
            }
        }, """
            SELECT ?x FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sof> ?x . }
                    ?x <%sbroader> <%sNoSuchThing> . }
            """.formatted(C, C, C),
                s -> s.pushTriple(C + "o1", C + "of", C + "a"));
        return rows.isEmpty();   // eliminated == fixed
    }

    /** #55: a prefixed name as a FILTER constant must resolve. Today it does not. */
    private static boolean pnameInFilterResolves() throws Exception {
        List<Object> rows = run(null, """
            PREFIX p: <%s>
            SELECT ?x FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o p:of ?x . } FILTER(?x = p:a) }
            """.formatted(C),
                s -> s.pushTriple(C + "o1", C + "of", C + "a"));
        return !rows.isEmpty();
    }

    /**
     * #56: a property path must either be REJECTED at registration or EXECUTED correctly.
     *
     * <p>The oracle lives entirely INSIDE the stream block, which matters. An earlier version put
     * both path patterns in the static graph; #54 makes a non-matching static pattern survive, so
     * the "bogus target matched nothing" half was contaminated and the check could not have
     * distinguished a real path fix (codex, round 2). Stream patterns are not affected by #54.
     *
     * <p>The hierarchy is {@code leaf -> mid -> root}. With working paths, {@code ?x broader+ root}
     * binds BOTH {@code leaf} (two hops) and {@code mid} (one). Today the {@code +} is dropped and
     * only {@code mid} matches, which is precisely the discrimination this needs.
     */
    private static boolean propertyPathRejected() throws Exception {
        try (CQELSEngine engine = CQELSEngine.builder().id("probe-path").withMemoryStore().build()) {
            DataStream stream = engine.createStream("S");
            List<Object> toRoot = new CopyOnWriteArrayList<>();
            List<Object> toBogus = new CopyOnWriteArrayList<>();
            try {
                engine.registerCqelsQuery(withRegister(
                        "SELECT ?x FROM STREAM S [RANGE 5s]\n"
                        + "WHERE { STREAM S { ?x <" + C + "broader>+ <" + C + "root> . } }\n"), toRoot::add);
                engine.registerCqelsQuery(withRegister(
                        "SELECT ?x FROM STREAM S [RANGE 5s]\n"
                        + "WHERE { STREAM S { ?x <" + C + "broader>+ <" + C + "nope> . } }\n"), toBogus::add);
            } catch (Exception e) {
                String msg = String.valueOf(e.getMessage()).toLowerCase();
                // Only a PATH-specific rejection counts; an unrelated failure must not read as a fix.
                // A future engine rejecting paths with some other wording reads as "still open",
                // which fails safe: it can never report green when it should not.
                return msg.contains("path") || msg.contains("'+'") || msg.contains("extraneous");
            }
            engine.start();
            stream.pushTriple(C + "leaf", C + "broader", C + "mid");
            Thread.sleep(200);
            stream.pushTriple(C + "mid", C + "broader", C + "root");
            Thread.sleep(1500);
            boolean twoHopWorks = toRoot.toString().contains(C + "leaf");
            boolean bogusClean = toBogus.isEmpty();
            return twoHopWorks && bogusClean;
        }
    }

    /** REPLACE (SPARQL 1.1 §17.4.3.14) must bind. Today the variable stays unbound. */
    private static boolean replaceEvaluates() throws Exception {
        List<Object> rows = run(null, """
            SELECT ?r FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sof> ?x . } BIND(REPLACE(STR(?x), "^.*#", "") AS ?r) }
            """.formatted(C),
                s -> s.pushTriple(C + "o1", C + "of", C + "a"));
        return rows.stream().anyMatch(r -> r.toString().contains("r="));
    }

    /** sameTerm (SPARQL 1.1 §17.4.1.1) must evaluate, even over one variable with itself. */
    private static boolean sameTermEvaluates() throws Exception {
        List<Object> rows = run(null, """
            SELECT ?x FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sof> ?x . } FILTER(sameTerm(?x, ?x)) }
            """.formatted(C),
                s -> s.pushTriple(C + "o1", C + "of", C + "a"));
        return !rows.isEmpty();
    }

    /**
     * The examples README states the warm parse-cache ASP backend is not exposed by the
     * {@code registerAspQuery} convenience overloads. If the facade grows one that accepts an
     * {@code AspSolverBackend}, that note is stale — checked reflectively, since the claim is
     * about the shape of the API rather than about behaviour.
     */
    private static boolean registerAspQueryTakesBackend() {
        for (java.lang.reflect.Method m : CQELSEngine.class.getMethods()) {
            if (!m.getName().equals("registerAspQuery")) {
                continue;
            }
            for (Class<?> p : m.getParameterTypes()) {
                if (org.cqels.asp.solver.AspSolverBackend.class.isAssignableFrom(p)) {
                    return true;   // an overload now takes a backend => the note is stale
                }
            }
        }
        return false;
    }

    /**
     * #57: the rule network must see statements pushed as ONE atomic multi-statement element, the
     * same way it sees single-statement pushes.
     *
     * <p>The previous version of this probe leaned on {@code atomicElementJoins()} — an ordinary
     * query over an atomic element, with no reasoner attached at all. That result is independent of
     * #57, so an upstream fix would have left {@code S2dm.pushConceptSignalUnbatched} and its
     * Javadocs stale while this job stayed green (codex, review of #62). This attaches a reasoner
     * and pushes through it, which is the comparison that actually matters.
     */
    private static boolean reasonerSeesAtomicElements() throws Exception {
        IRI of = VF.createIRI(C + "of");
        IRI broader = VF.createIRI(C + "broader");
        Rule lift = Rule.builder().id("probe-lift")
                .condition(RuleCondition.builder()
                        .addPattern(TriplePattern.builder()
                                .subjectVar("o").predicate(of).objectVar("narrow").build())
                        .addPattern(TriplePattern.builder()
                                .subjectVar("narrow").predicate(broader).objectVar("broad").build())
                        .build())
                .consequent(RuleConsequent.builder()
                        .addTemplate(TripleTemplate.builder()
                                .subjectVar("o").predicate(of).objectVar("broad").build())
                        .build())
                .priority(10).build();
        ReasoningConfig cfg = ReasoningConfig.builder()
                .ruleSet(RuleSet.of(lift)).enableRecursiveInference(true).maxRecursionDepth(4).build();

        try (CQELSEngine engine = CQELSEngine.builder()
                .id("probe-57").withMemoryStore()
                .addStreamProcessor(new ReactiveReteAdapter(cfg)::apply).build()) {
            DataStream stream = engine.createStream("S");
            List<Object> inferred = new CopyOnWriteArrayList<>();
            engine.registerCqelsQuery(withRegister(
                    "SELECT ?o FROM STREAM S [TRIPLES 1]\n"
                    + "WHERE { STREAM S { ?o <" + C + "of> <" + C + "mid> . } }\n"),
                    r -> { if (String.valueOf(r).contains(C + "o1")) { inferred.add(r); } });
            engine.start();
            stream.pushTriple(C + "leaf", C + "broader", C + "mid");   // the hierarchy edge
            Thread.sleep(300);
            // The observation, pushed ATOMICALLY. If the reasoner sees it, the lift rule derives
            // "<o1> of <mid>" and the query above fires. Today it does not.
            IRI o1 = VF.createIRI(C + "o1");
            stream.push(List.of(
                    VF.createStatement(o1, of, VF.createIRI(C + "leaf")),
                    VF.createStatement(o1, VF.createIRI(C + "note"), VF.createLiteral("frame"))));
            Thread.sleep(1200);

            // Positive control. The documented workaround is to push one statement at a time; if
            // THAT stopped working, the check above would also be empty and we would report "#57
            // still open" while the real news is a regression in the workaround (codex, round 2).
            //
            // It must NOT reuse `inferred`: clearing and re-reading the same list lets a late
            // result for the ATOMIC o1 land after the clear and be counted as proof that the
            // unbatched o2 worked (codex, round 3). A separate list keyed to o2 cannot confuse
            // the two, because each row carries its own subject.
            List<Object> unbatched = new CopyOnWriteArrayList<>();
            engine.registerCqelsQuery(withRegister(
                    "SELECT ?o FROM STREAM S [TRIPLES 1]\n"
                    + "WHERE { STREAM S { ?o <" + C + "of> <" + C + "mid> . } }\n"),
                    r -> { if (String.valueOf(r).contains(C + "o2")) { unbatched.add(r); } });
            stream.pushTriple(C + "o2", C + "of", C + "leaf");
            Thread.sleep(1200);
            boolean unbatchedSeen = !unbatched.isEmpty();

            // Read the atomic result AFTER the control's wait, not before it. Snapshotting it
            // earlier discarded any row that arrived during that extra second — so an engine that
            // had actually fixed #57 but derived slowly would still have been reported "still
            // open", the exact false-negative this check exists to avoid (codex, round 4). The
            // list is filtered on the o1 subject, so the later o2 push cannot contaminate it.
            boolean atomicSeen = !inferred.isEmpty();
            if (!unbatchedSeen) {
                DIVERGENCES.add("REGRESSED — the #57 workaround itself (single-statement pushes no "
                        + "longer reach the rule network)\n      S2dm.pushConceptSignalUnbatched is "
                        + "now broken too; #57's verdict below is not meaningful");
            }
            return atomicSeen;
        }
    }

    /**
     * #58: rule conditions cannot match the background graph — only stream elements.
     *
     * <p>Seeds the hierarchy edge into the REPOSITORY (where an s2dm catalogue naturally lives),
     * pushes only the observation, and asks whether the lift rule fires. It does not today, which
     * is why {@code SkosConceptRollup} pushes its hierarchy onto the stream.
     *
     * <p>Added because #64's limitations table claims the probe asserts every row, and #58 was the
     * one row nothing covered (codex, round 2).
     */
    private static boolean reasonerSeesBackgroundGraph() throws Exception {
        IRI of = VF.createIRI(C + "of");
        IRI broader = VF.createIRI(C + "broader");
        Rule lift = Rule.builder().id("probe-bg")
                .condition(RuleCondition.builder()
                        .addPattern(TriplePattern.builder()
                                .subjectVar("o").predicate(of).objectVar("narrow").build())
                        .addPattern(TriplePattern.builder()
                                .subjectVar("narrow").predicate(broader).objectVar("broad").build())
                        .build())
                .consequent(RuleConsequent.builder()
                        .addTemplate(TripleTemplate.builder()
                                .subjectVar("o").predicate(of).objectVar("broad").build())
                        .build())
                .priority(10).build();
        ReasoningConfig cfg = ReasoningConfig.builder()
                .ruleSet(RuleSet.of(lift)).enableRecursiveInference(true).maxRecursionDepth(4).build();
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("probe-58").withMemoryStore()
                .addStreamProcessor(new ReactiveReteAdapter(cfg)::apply).build()) {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                conn.add(VF.createIRI(C + "leaf"), broader, VF.createIRI(C + "mid"));
            }
            DataStream stream = engine.createStream("S");
            List<Object> inferred = new CopyOnWriteArrayList<>();
            engine.registerCqelsQuery(withRegister(
                    "SELECT ?o FROM STREAM S [TRIPLES 1]\n"
                    + "WHERE { STREAM S { ?o <" + C + "of> <" + C + "mid> . } }\n"), inferred::add);
            engine.start();
            stream.pushTriple(C + "o1", C + "of", C + "leaf");   // hierarchy is in the STORE only
            Thread.sleep(1200);
            return !inferred.isEmpty();
        }
    }

    // ---- capability probes ---------------------------------------------------------------

    /**
     * The README claims the warm parse-cache backend IS reachable through the facade, via the
     * 5-arg {@code AspContinuousQuery} constructor and the generic {@code registerQuery}. That is a
     * documented, runnable snippet, so it is asserted here rather than trusted.
     */
    private static boolean warmBackendReachable() {
        String foi = "http://www.w3.org/ns/sosa/hasFeatureOfInterest";
        // The fact mapper emits rdf/3, never obs/1, so a rule written over obs(...) can never be
        // satisfied — this program is the shape AspReasoning uses, and it does derive.
        String program =
                "convoy(V1, V2) :- rdf(O1, iri(\"" + foi + "\"), V1),\n"
                + "                  rdf(O2, iri(\"" + foi + "\"), V2),\n"
                + "                  V1 != V2.\n";
        // Deriving convoy proves a solve happened; it does NOT prove the backend we supplied was
        // the one that ran, because the DEFAULT backend derives the same atom (codex, round 3).
        // Wrapping it is what closes that: if AspContinuousQuery ever ignored the 5-arg backend
        // argument, `invoked` stays false and this check fails even though convoy still appears.
        InvocationRecordingBackend backend =
                new InvocationRecordingBackend(new WarmParseCacheAspSolverBackend());
        try (CQELSEngine engine = CQELSEngine.builder().id("probe-warm").withMemoryStore().build()) {
            DataStream stream = engine.createStream("default");
            List<Object> derived = new CopyOnWriteArrayList<>();
            AspContinuousQuery q = new AspContinuousQuery(
                    "ProbeWarm", program,
                    AspStreamSolveConfig.builder().build(),
                    new AspFactMapper(), backend);
            engine.registerQuery(q, r -> {
                if (String.valueOf(r.getAtoms()).contains("convoy(")) {
                    derived.add(r);
                }
            });
            engine.start();
            stream.pushTriple(C + "o1", foi, C + "vehicleA");
            Thread.sleep(400);
            stream.pushTriple(C + "o2", foi, C + "vehicleB");   // two distinct vehicles -> convoy
            Thread.sleep(1500);
            return backend.invoked && !derived.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** Records whether the backend it wraps was actually asked to solve. */
    private static final class InvocationRecordingBackend implements AspSolverBackend {
        private final AspSolverBackend delegate;
        private volatile boolean invoked;

        private InvocationRecordingBackend(AspSolverBackend delegate) {
            this.delegate = delegate;
        }

        @Override
        public org.cqels.asp.solver.AspSolveResult solve(String program, List<String> facts, long limit) {
            invoked = true;
            return delegate.solve(program, facts, limit);
        }
    }

    private static boolean staticEnrichmentBinds() throws Exception {
        List<Object> rows = run(engine -> {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                conn.add(VF.createIRI(C + "a"), VF.createIRI(C + "label"), VF.createLiteral("Alpha"));
            }
        }, """
            SELECT ?label FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sof> ?x . } ?x <%slabel> ?label . }
            """.formatted(C, C),
                s -> s.pushTriple(C + "o1", C + "of", C + "a"));
        return rows.stream().anyMatch(r -> r.toString().contains("Alpha"));
    }

    private static boolean builtinsWork() throws Exception {
        List<Object> rows = run(null, """
            SELECT ?d ?c ?i ?u ?f FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sval> ?v . }
                    BIND(ABS(?v - 100.0) AS ?d)
                    BIND(CONCAT("x", STR(?v)) AS ?c)
                    BIND(IRI(CONCAT("%s", STR(?v))) AS ?i)
                    BIND(SUBSTR(STR(?v), 1, 2) AS ?u)
                    BIND(IF(?v > 10, "hi", "lo") AS ?f) }
            """.formatted(C, C),
                s -> s.push(C + "o1", C + "val", 250.0));
        // Assert the VALUES, not merely that the bindings exist: a wrong ABS or SUBSTR would
        // otherwise pass, and this check exists to catch behavioural regressions.
        String r = rows.toString();
        return r.contains("d=150.0")            // ABS(250 - 100)
                && r.contains("c=x250.0")       // CONCAT("x", STR(250.0))
                && r.contains("i=" + C + "250.0")
                && r.contains("u=25")           // SUBSTR("250.0", 1, 2)
                && r.contains("f=hi");          // IF(250 > 10, "hi", "lo")
    }

    private static boolean atomicElementJoins() throws Exception {
        List<Object> rows = run(null, """
            SELECT ?v ?w FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sval> ?v . ?o <%sother> ?w . } }
            """.formatted(C, C),
                s -> {
                    IRI o = VF.createIRI(C + "o1");
                    s.push(List.of(
                            VF.createStatement(o, VF.createIRI(C + "val"), VF.createLiteral(1.0)),
                            VF.createStatement(o, VF.createIRI(C + "other"), VF.createLiteral(2.0))));
                });
        String r = rows.toString();
        return r.contains("v=") && r.contains("w=");
    }

    private static boolean groupByWorks() throws Exception {
        List<Object> rows = run(null, """
            SELECT ?g (COUNT(*) AS ?n)
            FROM STREAM S [RANGE 1s]
            WHERE { STREAM S { ?o <%sgroup> ?g . } }
            GROUP BY ?g
            """.formatted(C),
                s -> {
                    // A windowed aggregate is evaluated when an element ARRIVES, not on a timer,
                    // so the window needs a later element to close over the earlier ones. Pushing
                    // all three at once would leave the aggregate unemitted and look like a
                    // regression -- which is exactly the false alarm this pacing avoids.
                    s.pushTriple(C + "o1", C + "group", C + "g1");
                    s.pushTriple(C + "o2", C + "group", C + "g1");
                    s.pushTriple(C + "o3", C + "group", C + "g2");
                    Thread.sleep(1500);
                    s.pushTriple(C + "o4", C + "group", C + "g2");
                });
        // Require the group/count ASSOCIATION within a single row, and require BOTH expected
        // groups. Checking substrings across rows passes on {g1,n=1}+{g2,n=2}; checking only g1
        // passes on an engine that silently drops every second group (codex, rounds 2 and 3).
        boolean g1 = false;
        boolean g2 = false;
        for (Object row : rows) {
            String one = row.toString();
            if (one.contains("g=" + C + "g1") && one.contains("n=2")) {
                g1 = true;
            }
            if (one.contains("g=" + C + "g2") && one.contains("n=1")) {
                g2 = true;
            }
        }
        return g1 && g2;
    }

    // ---- harness -------------------------------------------------------------------------

    private interface Seeder { void seed(CQELSEngine engine); }
    private interface Pusher { void push(DataStream stream) throws Exception; }

    /**
     * Insert {@code REGISTER QUERY … AS} after any leading {@code PREFIX} lines — the prologue must
     * precede the REGISTER clause, so it cannot simply be prepended.
     *
     * <p>The name is made unique per call. A check that registers two queries in one engine used
     * to give both the same name; the second never took effect, its result list stayed empty,
     * and the check read that as success — reporting #56 FIXED when nothing had changed.
     */
    private static String withRegister(String query) {
        StringBuilder prologue = new StringBuilder();
        StringBuilder body = new StringBuilder();
        boolean inPrologue = true;
        for (String line : query.stripLeading().split("\n")) {
            if (inPrologue && line.stripLeading().toUpperCase().startsWith("PREFIX ")) {
                prologue.append(line).append('\n');
            } else {
                inPrologue = false;
                body.append(line).append('\n');
            }
        }
        return prologue + "REGISTER QUERY Probe" + QUERY_SEQ.incrementAndGet() + " AS\n" + body;
    }

    /** One query, one fresh engine, so checks cannot contaminate each other. */
    private static List<Object> run(Seeder seeder, String where, Pusher pusher) throws Exception {
        List<Object> rows = new CopyOnWriteArrayList<>();
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("probe-" + System.nanoTime())
                .withMemoryStore()
                .build()) {
            if (seeder != null) {
                seeder.seed(engine);
            }
            DataStream stream = engine.createStream("S");
            engine.registerCqelsQuery(withRegister(where), rows::add);
            engine.start();
            pusher.push(stream);
            Thread.sleep(2500);
        }
        return rows;
    }
}
