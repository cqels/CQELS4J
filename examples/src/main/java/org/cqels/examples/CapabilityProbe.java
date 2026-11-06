package org.cqels.examples;

import org.cqels.asp.config.AspStreamSolveConfig;
import org.cqels.asp.integration.AspFactMapper;
import org.cqels.asp.query.AspContinuousQuery;
import org.cqels.asp.solver.AspSolverBackend;
import org.cqels.asp.solver.WarmParseCacheAspSolverBackend;
import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.engine.cep.PatternMatch;
import org.cqels.reasoning.Rule;
import org.cqels.reasoning.RuleCondition;
import org.cqels.reasoning.RuleConsequent;
import org.cqels.reasoning.RuleSet;
import org.cqels.reasoning.TriplePattern;
import org.cqels.reasoning.TripleTemplate;
import org.cqels.reasoning.config.ReasoningConfig;
import org.cqels.reasoning.engine.ReactiveReteAdapter;
import org.cqels.stream.StreamElement;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Behaviour-truth probe — asserts that what this repository <em>says</em> about the engine is still
 * what the engine <em>does</em>.
 *
 * <p><strong>Why this exists.</strong> `scripts/ci/version-truth-gate.sh` was written because a claim
 * in the prose can go stale without anything failing to compile. Capability claims are the same
 * hazard one category over. The examples, `examples/README.md`, `CQELS-QL_SPEC.md` §6/§9 and
 * `CDSP_MAPPING.md` all carry statements of the form "X does not work on 2.0.0-alpha.22, work around
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
        knownBug("     xsd:duration in MAX is compared as text (MAX of PT9S, PT10S is PT9S)",
                durationMax(), "PT9S PT9S / PT10S PT9S", "PT9S PT10S / PT10S PT10S",
                "CQELS-QL_SPEC.md §9 gap table, CDSP_MAPPING.md §4, GETTING_STARTED.md limitations table");
        knownBug("     AVG of a group with no bound argument is null, not 0",
                avgOfUnboundGroup(), "null", "0",
                "CQELS-QL_SPEC.md §9 Aggregates, GETTING_STARTED.md limitations table");
        knownBug("     an aggregate whose argument nothing binds reports no row, not COUNT 0",
                countOfNeverBoundArgument(), "no row", "0",
                "CQELS-QL_SPEC.md §9 Aggregates, GETTING_STARTED.md limitations table");
        knownBug("     GROUP BY merges \"cat\"@en and \"cat\"@fr into one group",
                groupsOverLanguageTaggedKey(), "1 group", "2 groups",
                "CQELS-QL_SPEC.md §9 Aggregates, GETTING_STARTED.md limitations table");
        knownBug("     a static UNION drops every row when its join variable is not projected",
                unionWithUnprojectedJoinVariable(), "0 rows", "1 row",
                "CQELS-QL_SPEC.md §9 UNION entry, GETTING_STARTED.md limitations table");
        caveat("#58 reasoner cannot match the background graph",
                reasonerSeesBackgroundGraph(),
                "SkosConceptRollup.java, CDSP_MAPPING.md §5, GETTING_STARTED.md limitations table");
        caveat("     registerAspQuery has no backend-accepting overload",
                registerAspQueryTakesBackend(),
                "examples/README.md note under 'Reasoning & validation'");

        System.out.println("\n-- capabilities (documented as WORKING; the examples depend on these) --");
        capability("#54 static pattern eliminates a non-matching row (fixed)",
                staticPatternEliminates());
        capability("#55 prefixed name inside FILTER resolves (fixed)",
                pnameInFilterResolves());
        capability("property paths run in a stream-only STREAM block and are refused by name elsewhere",
                propertyPathRoute());
        capability("#65 sameTerm() evaluates (fixed)",
                sameTermEvaluates());
        capability("#57 reasoner sees multi-statement (atomic) elements (fixed)",
                reasonerSeesAtomicElements());
        capability("#67 reverse-edge guard admits a match, rejects an unmatchable element (fixed)",
                reverseEdgeGuardAdmits());
        capability("#67 [NOW] static pattern does not admit a missing stream conjunct (fixed)",
                nowStaticPatternEnforcesConjunction());
        capability("#82 FILTER compares xsd:duration by value (fixed in alpha.22)",
                durationFilterIsByValue());
        capability("     a literal join key answers on the composed route (fixed in alpha.22)",
                literalJoinKeyAdmits());
        capability("#83 a FILTER sees a BIND target beside a static pattern (fixed in alpha.22)",
                filterSeesBindTargetBesideStaticPattern());
        capability("#85 a FILTER sees an OPTIONAL variable on a stream-only query (fixed in alpha.22)",
                filterSeesOptionalVariableOnStreamOnlyQuery());
        capability("#74 a CEP SEQ variable that is never a subject is refused (fixed in alpha.22)",
                seqVariableMustBeASubject());
        capability("     FROM <graph> scopes a static lookup on a one-stream query (fixed in alpha.22)",
                fromGraphScopesStaticLookup());
        capability("     REPLACE() is evaluated (fixed in alpha.22)", replaceEvaluates());
        capability("stream-static enrichment binds static variables", staticEnrichmentBinds());
        capability("FILTER/BIND builtins: ABS, CONCAT, IRI, SUBSTR, IF", builtinsWork());
        capability("atomic multi-statement element joins in a query", atomicElementJoins());
        capability("GROUP BY with aggregates", groupByWorks());
        capability("single-pattern [RANGE] aggregates at window close",
                singlePatternRangeAggregatesAtWindowClose());
        capability("two-pattern [RANGE] aggregate reports on every arrival (rolling)",
                twoPatternRangeAggregateRolls());
        capability("CypherQL: FROM STREAM before MATCH registers; MATCH first is a syntax error",
                cypherClauseOrder());
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

    /**
     * A behaviour documented as broken in one EXACT way. {@code observed} is compared with the
     * documented broken result and with the result a fix would give; anything else — no result at
     * all, an error, a third value — is a divergence too, because the docs then describe neither
     * what happens nor what a fix looks like. A boolean caveat cannot tell those apart: it reads
     * "no row" as "still broken" (codex, review of #116).
     */
    private static void knownBug(String name, String observed, String asDocumented, String fixed, String docs) {
        checks++;
        if (asDocumented.equals(observed)) {
            System.out.println("  still open " + name);
        } else if (fixed.equals(observed)) {
            System.out.println("  NOW FIXED  " + name);
            DIVERGENCES.add("NOW FIXED — " + name + "\n      remove the caveat and its workaround from: " + docs);
        } else {
            System.out.println("  CHANGED    " + name);
            DIVERGENCES.add("CHANGED — " + name + "\n      observed " + observed + "; documented as "
                    + asDocumented + ", fixed would be " + fixed + ". Re-measure and edit: " + docs);
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

    /** #54 (fixed): a static pattern with no matching data must eliminate the row. */
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

    /** #55 (fixed): a prefixed name as a FILTER constant must resolve. */
    private static boolean pnameInFilterResolves() throws Exception {
        // A non-matching value must be filtered out, or an ignored FILTER would pass (codex, #116).
        List<Object> rows = run(null, """
            PREFIX p: <%s>
            SELECT ?o ?x FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o p:of ?x . } FILTER(?x = p:a) }
            """.formatted(C),
                s -> {
                    s.pushTriple(C + "o1", C + "of", C + "a");
                    s.pushTriple(C + "o2", C + "of", C + "b");
                });
        return exactRows(rows, List.of(row("o", iri("o1"), "x", iri("a"))));
    }

    /**
     * Property paths, since 2.0.0-alpha.22: EXECUTED on one route, REFUSED by name on every other.
     * CQELS-QL_SPEC.md §9 states both halves, so the check asserts both.
     *
     * <p>Executed: inside the one {@code STREAM} block of a single-stream {@code [RANGE]} query with
     * no static graph pattern. The hierarchy is {@code leaf -> mid -> root}, pushed as two elements;
     * {@code ?x broader+ root} must bind BOTH {@code mid} (one hop) and {@code leaf} (two), and a
     * path to a target that does not exist must bind nothing. The oracle lives entirely inside the
     * stream block on purpose: an earlier version put the path in the static graph, where #54 used
     * to make a non-matching pattern survive (codex, round 2). Binding only {@code mid} is the old
     * #56 silent mis-evaluation, so it fails the check.
     *
     * <p>Refused: under {@code [NOW]}, and beside a static graph pattern. Each refusal must name the
     * path ("path" in the message): a refusal for some unrelated reason must not read as the
     * documented one. Before alpha.22 every path was refused, which this check reports as a
     * regression — the docs now promise the executed route.
     */
    private static boolean propertyPathRoute() throws Exception {
        String path = "?x <" + C + "broader>+ ";
        String now = pathRefusal("SELECT ?x FROM STREAM S [NOW]\n"
                + "WHERE { STREAM S { " + path + "<" + C + "root> . } }\n");
        String besideStatic = pathRefusal("SELECT ?x ?l FROM STREAM S [RANGE 5s]\n"
                + "WHERE { STREAM S { " + path + "?r . } ?r <" + C + "label> ?l . }\n");
        boolean refusedByName = now != null && now.toLowerCase().contains("path")
                && besideStatic != null && besideStatic.toLowerCase().contains("path");

        try (CQELSEngine engine = CQELSEngine.builder().id("probe-path").withMemoryStore().build()) {
            DataStream stream = engine.createStream("S");
            List<Object> toRoot = new CopyOnWriteArrayList<>();
            List<Object> toBogus = new CopyOnWriteArrayList<>();
            try {
                engine.registerCqelsQuery(withRegister("SELECT ?x FROM STREAM S [RANGE 5s]\n"
                        + "WHERE { STREAM S { " + path + "<" + C + "root> . } }\n"), toRoot::add);
                engine.registerCqelsQuery(withRegister("SELECT ?x FROM STREAM S [RANGE 5s]\n"
                        + "WHERE { STREAM S { " + path + "<" + C + "nope> . } }\n"), toBogus::add);
            } catch (Exception e) {
                DIVERGENCES.add("REFUSED — a '+' path inside a stream-only [RANGE] STREAM block was "
                        + "refused at registration (" + e.getMessage() + ")\n      CQELS-QL_SPEC.md §9 "
                        + "says this route executes paths");
                return false;
            }
            engine.start();
            stream.pushTriple(C + "leaf", C + "broader", C + "mid");
            Thread.sleep(200);
            stream.pushTriple(C + "mid", C + "broader", C + "root");
            Thread.sleep(1500);
            String got = toRoot.toString();
            boolean executes = exactRows(toRoot, List.of(row("x", iri("mid")), row("x", iri("leaf"))))
                    && toBogus.isEmpty();
            if (!executes) {
                DIVERGENCES.add("MIS-EVALUATED — '+' over leaf -> mid -> root returned " + got
                        + " (bogus target: " + toBogus + ")\n      expected mid and leaf, and nothing "
                        + "for the bogus target; this is the silent-mangling case from #56");
            }
            if (!refusedByName) {
                DIVERGENCES.add("NOT REFUSED BY NAME — [NOW]: " + now + "; beside a static pattern: "
                        + besideStatic + "\n      CQELS-QL_SPEC.md §9 says both are refused at "
                        + "registration with a message naming the path");
            }
            return executes && refusedByName;
        }
    }

    /** Registers a CQELS-QL query; returns null if it registers, or the refusal's message. */
    private static String pathRefusal(String body) throws Exception {
        try (CQELSEngine engine = CQELSEngine.builder().id("probe-path-refusal-" + QUERY_SEQ.incrementAndGet())
                .withMemoryStore().build()) {
            engine.createStream("S");
            try {
                engine.registerCqelsQuery(withRegister(body), row -> { });
                return null;
            } catch (Exception e) {
                return String.valueOf(e.getMessage());
            }
        }
    }

    /**
     * REPLACE (SPARQL 1.1 §17.4.3.14) must evaluate; fixed in alpha.22 (before, the variable stayed
     * unbound). The value is asserted, not just its presence: {@code STR(?x)} alone would also bind
     * {@code ?r} (codex, review of #116). The second case is the nested segment-id cleanup that
     * CDSP_MAPPING.md now says ports directly.
     */
    private static boolean replaceEvaluates() throws Exception {
        String single = firstValue(run(null, """
            SELECT ?r FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sof> ?x . } BIND(REPLACE(STR(?x), "^.*#", "") AS ?r) }
            """.formatted(C),
                s -> s.pushTriple(C + "o1", C + "of", C + "a")), "r");
        String nested = firstValue(run(null, """
            SELECT ?r FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sof> ?x . }
                    BIND(REPLACE(REPLACE(STR(?x), "^.*#", ""), "[^a-zA-Z0-9]", "") AS ?r) }
            """.formatted(C),
                s -> s.pushTriple(C + "o1", C + "of", C + "a-b_c")), "r");
        return "a".equals(single) && "abc".equals(nested);
    }

    /** The label (or string form) of {@code var} in the first row, or null. */
    private static String firstValue(List<Object> rows, String var) {
        if (rows.isEmpty()) {
            return null;
        }
        Object v = ((Map<?, ?>) rows.get(0)).get(var);
        return v == null ? null : (v instanceof Literal l ? l.getLabel() : String.valueOf(v));
    }

    /** sameTerm (SPARQL 1.1 §17.4.1.1) must evaluate, even over one variable with itself. */
    private static boolean sameTermEvaluates() throws Exception {
        // A positive alone is a tautology: FILTER(sameTerm(?x, ?x)) also passes on an engine that
        // treats an unknown function as "true", or ignores the filter entirely (codex, review of
        // #66). What this guards is SPARQL *term identity*, so it needs the two distinctions that
        // separate sameTerm from a loose comparison — a different IRI, and an IRI versus a string
        // literal with the same lexical form. Both verified against alpha.20 before being asserted.
        List<Object> same = run(null, """
            SELECT ?x FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sof> ?x . } FILTER(sameTerm(?x, ?x)) }
            """.formatted(C),
                s -> s.pushTriple(C + "o1", C + "of", C + "a"));

        List<Object> otherIri = run(null, """
            SELECT ?x FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sof> ?x . } FILTER(sameTerm(?x, <%szzz>)) }
            """.formatted(C, C),
                s -> s.pushTriple(C + "o1", C + "of", C + "a"));

        // ?x is the IRI <...#a>; ?lit is the plain string "...#a". STR() cannot tell them apart;
        // sameTerm must.
        List<Object> iriVsLiteral = run(null, """
            SELECT ?x FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sof> ?x . ?o <%slit> ?lit . } FILTER(sameTerm(?x, ?lit)) }
            """.formatted(C, C),
                s -> {
                    IRI o = VF.createIRI(C + "o1");
                    s.push(List.of(
                            VF.createStatement(o, VF.createIRI(C + "of"), VF.createIRI(C + "a")),
                            VF.createStatement(o, VF.createIRI(C + "lit"), VF.createLiteral(C + "a"))));
                });

        return !same.isEmpty() && otherIri.isEmpty() && iriVsLiteral.isEmpty();
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
     * #57 (fixed): the rule network must see statements pushed as ONE atomic multi-statement
     * element, the same way it sees single-statement pushes.
     *
     * <p>The previous version of this probe leaned on {@code atomicElementJoins()} — an ordinary
     * query over an atomic element, with no reasoner attached at all. That result is independent of
     * #57, so an upstream fix would have left the demos' single-statement workaround (and its
     * Javadocs) stale while this job stayed green (codex, review of #62). This attaches a reasoner
     * and pushes through it, which is the comparison that actually matters. Now that #57 is fixed,
     * {@code SkosConceptRollup} pushes atomically like every other demo — see
     * {@code S2dm.pushConceptSignal}.
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
            // "<o1> of <mid>" and the query above fires — which is what #57 being fixed means.
            // The rule-relevant statement is deliberately NOT first. With it in position 1, an
            // engine that only ever processed an element's opening statement would pass this check
            // while still skipping the rest (codex, review of #66). Verified on alpha.20 that the
            // reasoner derives from either position, so asserting the harder one costs nothing and
            // encodes the evidence.
            IRI o1 = VF.createIRI(C + "o1");
            stream.push(List.of(
                    VF.createStatement(o1, VF.createIRI(C + "note"), VF.createLiteral("frame")),
                    VF.createStatement(o1, VF.createIRI(C + "note2"), VF.createLiteral("filler")),
                    VF.createStatement(o1, of, VF.createIRI(C + "leaf"))));
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
                DIVERGENCES.add("REGRESSED — single-statement pushes no longer reach the rule "
                        + "network either\n      (the pre-#57-fix workaround); #57's verdict below "
                        + "is not meaningful");
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

    /**
     * #67, fixed since 2.0.0-alpha.21: a static guard that reaches the join key by a REVERSE edge —
     * {@code ex:coll ex:member ?f}, with {@code ?f} bound by the stream in object position — must
     * admit a matching element and must not admit one that cannot match the stream block.
     *
     * <p>Before 2.0.0-alpha.21 it did neither reliably, in two opposite ways depending on shape: it
     * <em>eliminated</em> rows that fully matched (this exact {@code [TRIPLES 1]} two-pattern shape),
     * and elsewhere it <em>fabricated</em> rows from the static data alone when the pushed element
     * matched no stream pattern at all. Issue #67 has the history.
     *
     * <p>This was a caveat until the alpha.21 bump, and the body below is unchanged from that
     * version: the same two phases that reported "still open" on alpha.20 report correct behaviour
     * on alpha.21. That is the point of flipping a caveat rather than rewriting it — the assertion
     * that changed its verdict is the evidence.
     *
     * <p>Two phases are required, not one. An earlier version returned {@code !rows.isEmpty()},
     * which would have read a FABRICATED row as the bug being fixed. A fixed engine must emit on the
     * matching element ({@code admitsMatch}) and stay silent on the unmatchable one
     * ({@code fabricates}). The forward-typed guard runs alongside as a control in both phases.
     *
     * <p>A 162-case sweep over six windows, both guard directions, four push kinds, six static
     * shapes and four projections was correct on alpha.21 and wrong in 24 cases on alpha.20 —
     * see {@code CQELS-QL_SPEC.md} §6. This check pins one cell of it; the next check pins the
     * {@code [NOW]} half, which was the more serious.
     */
    private static boolean reverseEdgeGuardAdmits() throws Exception {
        // Phase 1 — a MATCHING element. A fixed engine emits the row here.
        boolean admitsMatch = !reverseGuardRun(true).isEmpty();
        // Phase 2 — an element that CANNOT match the stream block. A fixed engine emits nothing;
        // this engine fabricates a row from the static data alone (issue #67 §A). Without this
        // phase the check could not tell "fixed" from "got worse": the old version returned
        // !reverse.isEmpty(), so a fabricated row would have been reported as NOW FIXED and the
        // caveat removed from the docs — precisely backwards.
        boolean fabricates = !reverseGuardRun(false).isEmpty();
        // Phase 3 — an element that DOES match the stream block, keyed on b, which is not a member
        // of the collection. Only the static guard can reject it; phase 2 never reaches the guard,
        // so without this phase an engine that ignored the guard would pass (codex, review of #116).
        boolean guardRejects = reverseGuardRun(null).isEmpty();
        return admitsMatch && !fabricates && guardRejects;
    }

    /**
     * One run of the #67 comparison. Pushes a matching two-statement element ({@code true}), one
     * that cannot match the stream block at all ({@code false}), or one that matches the stream
     * block keyed on a non-member {@code b} ({@code null}), and returns what the reverse-guarded
     * query emitted.
     * The forward-typed guard runs alongside as a control: on the matching push it must admit, and
     * on the non-matching push it must stay silent. If either fails, the reverse verdict is not
     * measuring what it claims to and a divergence says so.
     */
    private static List<Object> reverseGuardRun(Boolean mode) throws Exception {
        boolean matching = Boolean.TRUE.equals(mode);
        boolean nonMember = mode == null;
        try (CQELSEngine engine = CQELSEngine.builder().id("probe-67").withMemoryStore().build()) {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                // Both directions asserted, exactly as the s2dm exporter emits them.
                conn.add(VF.createIRI(C + "a"), VF.createIRI(Fleet.RDF_TYPE), VF.createIRI(C + "Field"));
                conn.add(VF.createIRI(C + "coll"), VF.createIRI(C + "member"), VF.createIRI(C + "a"));
            }
            DataStream stream = engine.createStream("S");
            List<Object> reverse = new CopyOnWriteArrayList<>();
            List<Object> forward = new CopyOnWriteArrayList<>();
            // Two patterns in the STREAM block — the shape that triggers it. Each query projects
            // exactly what its own BGP binds; getting that wrong is how a shape difference gets
            // mistaken for a guard difference.
            String block = "?o <" + C + "of> ?f . ?o <" + C + "v> ?val .";
            engine.registerCqelsQuery(withRegister("SELECT ?f ?val FROM STREAM S [TRIPLES 1]\n"
                    + "WHERE { STREAM S { " + block + " } <" + C + "coll> <" + C + "member> ?f . }\n"),
                    reverse::add);
            engine.registerCqelsQuery(withRegister("SELECT ?f ?val FROM STREAM S [TRIPLES 1]\n"
                    + "WHERE { STREAM S { " + block + " } ?f <" + Fleet.RDF_TYPE + "> <" + C + "Field> . }\n"),
                    forward::add);
            engine.start();
            IRI o = VF.createIRI(C + "o1");
            if (matching || nonMember) {
                stream.push(List.of(
                        VF.createStatement(o, VF.createIRI(C + "of"), VF.createIRI(C + (nonMember ? "b" : "a"))),
                        VF.createStatement(o, VF.createIRI(C + "v"), VF.createLiteral(1.0))));
            } else {
                stream.push(VF.createStatement(o, VF.createIRI(C + "unrelated"), VF.createIRI(C + "x")));
            }
            Thread.sleep(1200);
            if (matching && forward.isEmpty()) {
                DIVERGENCES.add("REGRESSED — the forward-typed guard no longer admits a matching element\n"
                        + "      the #67 comparison is meaningless in this run; investigate before "
                        + "trusting its verdict");
            } else if (!matching && !forward.isEmpty()) {
                // b is not typed Field either, so the forward guard must reject the non-member too.
                DIVERGENCES.add("REGRESSED — the forward-typed guard now fabricates too\n"
                        + "      it emitted for an element that cannot match the stream block; the "
                        + "#67 control is no longer sound");
            }
            return reverse;
        }
    }

    /**
     * The <em>exact</em> shape probed below — one stream pattern, one {@code COUNT}, no
     * {@code FILTER}, {@code HAVING}, static join or {@code OPTIONAL}, over {@code [RANGE 3s]} —
     * reports on epoch-aligned <em>tumbling buckets</em>: the closed bucket's aggregate is emitted
     * when an element arrives whose event time falls past the boundary.
     * {@code DataStream.complete()} flushes it too, so a subsequent arrival is not the only
     * trigger; wall-clock time alone is not one at all.
     *
     * <p>Deliberately stated as one measured shape rather than a rule. Neighbouring shapes differ in
     * <em>which elements are aggregated</em>, not merely in when results appear, and the boundary
     * between them is not derivable from pattern and aggregate counts: adding {@code HAVING}, a
     * static join or {@code OPTIONAL} switches this same query to a rolling window, while
     * {@code FILTER} and {@code GROUP BY} do not. {@code CQELS-QL_SPEC.md} §9 carries the measured
     * table; this check pins one row of it.
     *
     * <p>Two earlier versions of this check were wrong, which is why it looks the way it does:
     * <ul>
     *   <li>The first pushed three elements into a {@code [RANGE 10s]} window, waited 1.5 s, saw
     *       nothing and reported the combination BROKEN — a claim that reached §9 and an upstream
     *       issue before a reviewer refuted it with one late push. The window had not closed.</li>
     *   <li>The second pushed on wall-clock timing and asserted the exact count. Three pushes take
     *       ~11 ms, but if they straddle a bucket boundary the engine correctly emits mid-burst and
     *       the check fails for a reason unrelated to the engine. It failed 2 runs in 10 under
     *       probe load while passing 8/8 in isolation.</li>
     * </ul>
     * Hence <strong>timestamped</strong> pushes: event time, not the scheduler, decides which
     * bucket each element lands in, so the expected count is exact and the result deterministic.
     */
    /**
     * True when the row binds {@code ?n} to exactly 3.
     *
     * <p>Parsed rather than substring-matched: {@code contains("n=3")} also accepts {@code n=30},
     * which a reviewer demonstrated by mutating the delivered count. The value is required to run to
     * a delimiter, so {@code 3.5} and {@code 3E1} are rejected as well — {@code COUNT} returns an
     * integral value today, and this should start failing rather than quietly passing if that
     * changes.
     */
    private static boolean countIsThree(Object row) {
        return java.util.regex.Pattern.compile("\\bn=3(?![\\d.eE+-])")
                .matcher(String.valueOf(row)).find();
    }

    private static boolean singlePatternRangeAggregatesAtWindowClose() throws Exception {
        try (CQELSEngine engine = CQELSEngine.builder().id("probe-range-agg").withMemoryStore().build()) {
            DataStream stream = engine.createStream("S");
            List<Object> rows = new CopyOnWriteArrayList<>();
            engine.registerCqelsQuery(withRegister(
                    "SELECT (COUNT(?val) AS ?n) FROM STREAM S [RANGE 3s]\n"
                    + "WHERE { STREAM S { ?o <" + C + "v> ?val . } }\n"), rows::add);
            engine.start();
            // All three inside the bucket [0, 3000).
            for (int i = 1; i <= 3; i++) {
                stream.push(List.of(VF.createStatement(VF.createIRI(C + "o" + i),
                        VF.createIRI(C + "v"), VF.createLiteral((double) i))), 100L * i);
                Thread.sleep(60);
            }
            // Checked immediately before the trigger, leaving no unobserved gap in which an
            // autonomous emission could occur and be mistaken for a triggered one. A per-arrival
            // route would already have produced rows here.
            boolean quietBeforeTrigger = rows.isEmpty();
            stream.push(List.of(VF.createStatement(VF.createIRI(C + "o4"),
                    VF.createIRI(C + "v"), VF.createLiteral(4.0))), 3100L);
            for (int i = 0; i < 40 && rows.isEmpty(); i++) {
                Thread.sleep(100);
            }
            // n=3 exactly: the closed bucket holds the three timestamped elements and not the
            // trigger. Asserting the value, not just non-emptiness, rejects a raw or mis-scoped
            // result that happens to arrive at the right moment. Matched as a NUMBER: an earlier
            // version used String.contains("n=3"), which a reviewer showed also accepts n=30.
            return quietBeforeTrigger && rows.stream().anyMatch(CapabilityProbe::countIsThree);
        }
    }


    /**
     * #67's {@code [NOW]} half, fixed since 2.0.0-alpha.21. Two stream patterns plus a <em>satisfied</em>
     * static pattern, and an element carrying only the FIRST stream statement: the second stream
     * pattern has no match, so the correct answer is no row.
     *
     * <p>Before 2.0.0-alpha.21 the engine emitted one anyway, with the unmatched variable simply
     * absent from the binding. It did not depend on the guard's direction — any satisfied static
     * pattern with a determinable subject triggered it — and removing the static pattern made the
     * same query correct. Of the two #67 failures this one was the worse: a consumer cannot tell a
     * fabricated row from a real one.
     *
     * <p>The full element is the control. If it stops being admitted, the static pattern has started
     * eliminating instead, and "no row on the partial element" would no longer mean what it claims.
     */
    private static boolean nowStaticPatternEnforcesConjunction() throws Exception {
        Seeder seeded = engine -> {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                conn.add(VF.createIRI(C + "allowed"), VF.createIRI(Fleet.RDF_TYPE), VF.createIRI(C + "Field"));
            }
        };
        String query = """
            SELECT ?f ?val FROM STREAM S [NOW]
            WHERE { STREAM S { ?o <%1$sof> ?f . ?o <%1$sv> ?val . } ?f <%2$s> <%1$sField> . }
            """.formatted(C, Fleet.RDF_TYPE);
        IRI o = VF.createIRI(C + "o1");
        List<Object> full = run(seeded, query, s -> s.push(List.of(
                VF.createStatement(o, VF.createIRI(C + "of"), VF.createIRI(C + "allowed")),
                VF.createStatement(o, VF.createIRI(C + "v"), VF.createLiteral(1.0)))));
        List<Object> partial = run(seeded, query,
                s -> s.push(VF.createStatement(o, VF.createIRI(C + "of"), VF.createIRI(C + "allowed"))));
        if (full.isEmpty()) {
            DIVERGENCES.add("REGRESSED — a complete element under [NOW] plus a satisfied static pattern "
                    + "no longer matches\n      the #67 [NOW] check's control failed; its verdict on the "
                    + "partial element is meaningless until that is understood");
        }
        return !full.isEmpty() && partial.isEmpty();
    }

    /**
     * #82, fixed in 2.0.0-alpha.22: a {@code FILTER} compares {@code xsd:duration} values by value.
     * Through alpha.21 every result was consistent with comparing the literals' text:
     *
     * <pre>
     *   "PT2S"  &gt; "PT1S"    matched        (true as durations, true as text)
     *   "PT10S" &gt; "PT9S"    did NOT match  (true as durations, false as text)
     *   "PT60S" = "PT1M"    did NOT match  (equal durations, different text)
     * </pre>
     *
     * <p>All three must match now, and their negations must NOT: a check made only of comparisons
     * that should hold also passes on an engine that ignores the {@code FILTER} (codex, review of
     * #116). The first pair is the control: {@code PT2S > PT1S} held as text too, so it is the
     * row that would catch a type error. A row-bound duration from the stream is checked as well,
     * against a constant, both ways.
     */
    private static boolean durationFilterIsByValue() throws Exception {
        String d = "^^<%1$sduration>";
        boolean holds = durationFilterMatches("\"PT2S\"" + d + " > \"PT1S\"" + d)
                && durationFilterMatches("\"PT10S\"" + d + " > \"PT9S\"" + d)
                && durationFilterMatches("\"PT60S\"" + d + " = \"PT1M\"" + d);
        boolean rejects = !durationFilterMatches("\"PT1S\"" + d + " > \"PT2S\"" + d)
                && !durationFilterMatches("\"PT9S\"" + d + " > \"PT10S\"" + d)
                && !durationFilterMatches("\"PT60S\"" + d + " != \"PT1M\"" + d);
        IRI duration = VF.createIRI("http://www.w3.org/2001/XMLSchema#duration");
        Set<String> rowBound = subjects(run(null, """
            SELECT ?o FROM STREAM S [NOW]
            WHERE { STREAM S { ?o <%1$sd> ?d . } FILTER(?d > "PT9S"^^<http://www.w3.org/2001/XMLSchema#duration>) }
            """.formatted(C), s -> {
                s.push(VF.createStatement(VF.createIRI(C + "o1"), VF.createIRI(C + "d"),
                        VF.createLiteral("PT10S", duration)));
                s.push(VF.createStatement(VF.createIRI(C + "o2"), VF.createIRI(C + "d"),
                        VF.createLiteral("PT8S", duration)));
            }));
        return holds && rejects && rowBound.equals(Set.of("o1"));
    }

    /**
     * Upstream lists it as a known issue of 2.0.0-alpha.22: {@code ORDER BY}, {@code MIN} and
     * {@code MAX} still compare durations as text, although a {@code FILTER} now compares them by
     * value. Measured: {@code MAX} over {@code PT9S} then {@code PT10S} under {@code [TRIPLES 3]}
     * reports {@code PT9S}.
     *
     * <p>The control is {@code MAX} over two durations whose text and value orders agree
     * ({@code PT1S}, {@code PT2S}): it must report {@code PT2S}, or the aggregate itself is failing.
     *
     * <p>Every arrival's report is read, in both input orders, so a lost final evaluation cannot
     * leave the first arrival's value standing in for the two-element answer (codex, review of
     * #116). Returns the reports as {@code "PT9S PT9S / PT10S PT9S"} — {@code PT9S,PT10S} then
     * {@code PT10S,PT9S}: that string as documented (text order), {@code "PT9S PT10S / PT10S PT10S"}
     * once fixed.
     */
    private static String durationMax() throws Exception {
        String agreeing = maxReports("PT1S", "PT2S");
        if (!"PT1S PT2S".equals(agreeing)) {
            DIVERGENCES.add("REGRESSED — the control (MAX of PT1S, PT2S) reported " + agreeing
                    + ", not PT1S PT2S\n      MAX over durations is failing outright; the text-order "
                    + "verdict is meaningless");
        }
        return maxReports("PT9S", "PT10S") + " / " + maxReports("PT10S", "PT9S");
    }

    /** Pushes two {@code xsd:duration} values and returns every {@code ?m} MAX reported, space-separated. */
    private static String maxReports(String first, String second) throws Exception {
        IRI duration = VF.createIRI("http://www.w3.org/2001/XMLSchema#duration");
        List<Object> rows = run(null, """
            SELECT (MAX(?d) AS ?m) FROM STREAM S [TRIPLES 3]
            WHERE { STREAM S { ?o <%sd> ?d . } }
            """.formatted(C), s -> {
                s.push(VF.createStatement(VF.createIRI(C + "o1"), VF.createIRI(C + "d"),
                        VF.createLiteral(first, duration)), 1000L);
                Thread.sleep(100);
                s.push(VF.createStatement(VF.createIRI(C + "o2"), VF.createIRI(C + "d"),
                        VF.createLiteral(second, duration)), 1100L);
            });
        List<String> reports = new ArrayList<>();
        rows.forEach(row -> reports.add(text(((Map<?, ?>) row).get("m"))));
        return reports.isEmpty() ? "no row" : String.join(" ", reports);
    }

    /**
     * Upstream lists it as a known issue of 2.0.0-alpha.22: {@code AVG} of a group in which no row
     * binds the argument is null, where SPARQL 1.1 defines {@code Avg} of an empty multiset as 0.
     * Measured: one element whose {@code OPTIONAL} finds no label in the store, grouped by subject under
     * {@code [TRIPLES 2]}, reports {@code a=null}.
     *
     * <p>The control stores the label, so the {@code OPTIONAL} binds the argument: {@code AVG} must
     * then report the label's value, or the aggregate route itself is failing.
     *
     * <p>Returns the last {@code ?a} for the unbound group: {@code null} as documented, {@code 0}
     * once fixed, {@code no row} if nothing was reported.
     */
    private static String avgOfUnboundGroup() throws Exception {
        String query = """
            SELECT ?o (AVG(?l) AS ?a) FROM STREAM S [TRIPLES 2]
            WHERE { STREAM S { ?o <%1$sv> ?x . } OPTIONAL { ?o <%1$slabel> ?l } } GROUP BY ?o
            """.formatted(C);
        Pusher one = s -> s.push(List.of(VF.createStatement(VF.createIRI(C + "o1"), VF.createIRI(C + "v"),
                VF.createLiteral(1))), 1000L);
        // The OPTIONAL reads the store, as in the #85 check: the control seeds the label there.
        Seeder labelled = engine -> {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                conn.add(VF.createIRI(C + "o1"), VF.createIRI(C + "label"), VF.createLiteral(8));
            }
        };
        List<Object> control = run(labelled, query, one);
        if (!exactRows(control, List.of(row("o", iri("o1"), "a", num(8))))) {
            DIVERGENCES.add("REGRESSED — the control (AVG over a bound label 8) reported " + control
                    + "\n      the aggregate route itself is failing; the empty-group verdict is meaningless");
        }
        // Exactly the one group row: an extra or empty row must not read as the documented null
        // (codex, round 3 of #116). The alias must be present, bound to null.
        List<Object> rows = run(null, query, one);
        if (rows.size() == 1 && ((Map<?, ?>) rows.get(0)).containsKey("a")
                && exactRows(rows, List.of(row("o", iri("o1"), "a", UNBOUND)))) {
            return "null";
        }
        if (exactRows(rows, List.of(row("o", iri("o1"), "a", num(0))))) {
            return "0";
        }
        return rows.isEmpty() ? "no row" : String.valueOf(rows);
    }

    /**
     * Upstream lists it as a known issue of 2.0.0-alpha.22: on the windowed hash join a solution whose
     * projection is empty is dropped before aggregation, so an aggregate whose argument no solution
     * binds reports no row instead of {@code COUNT} 0. Measured: {@code COUNT(?missing)}, with
     * {@code ?missing} in no pattern, over one matching element under {@code [TRIPLES 3]} reports
     * nothing.
     *
     * <p>The control is {@code COUNT(*)} over the same element: it must report 1, or the aggregate
     * route itself is failing.
     *
     * <p>Returns {@code no row} as documented, {@code 0} once fixed, or what was reported.
     */
    private static String countOfNeverBoundArgument() throws Exception {
        Pusher one = s -> s.push(VF.createStatement(VF.createIRI(C + "o1"), VF.createIRI(C + "v"),
                VF.createLiteral(1)), 1000L);
        List<Object> control = run(null, """
            SELECT (COUNT(*) AS ?n) FROM STREAM S [TRIPLES 3] WHERE { STREAM S { ?o <%sv> ?x . } }
            """.formatted(C), one);
        if (!exactRows(control, List.of(row("n", num(1))))) {
            DIVERGENCES.add("REGRESSED — the control (COUNT(*) over one element) reported " + control
                    + "\n      the aggregate route itself is failing; the unbound-argument verdict is meaningless");
        }
        List<Object> rows = run(null, """
            SELECT (COUNT(?missing) AS ?n) FROM STREAM S [TRIPLES 3] WHERE { STREAM S { ?o <%sv> ?x . } }
            """.formatted(C), one);
        if (rows.isEmpty()) {
            return "no row";
        }
        return exactRows(rows, List.of(row("n", num(0)))) ? "0" : String.valueOf(rows);
    }

    /**
     * Upstream lists it as a known issue of 2.0.0-alpha.22: a {@code GROUP BY} key holds a literal's
     * label without its language tag or datatype. Measured: {@code "cat"@en} then {@code "cat"@fr}
     * under {@code [TRIPLES 3]}, grouped by the literal, end in one group of 2.
     *
     * <p>The control is the documented workaround — {@code BIND(LANG(?l) AS ?lang)} and
     * {@code GROUP BY ?l ?lang} — which must report both tags, or grouping itself is failing.
     *
     * <p>Both outputs are read <em>per arrival</em>: each arrival's rows are collected before the
     * next element is pushed, and compared exactly. The documented result is {@code {cat,1}} on the
     * first arrival and {@code {cat,2}} on the second; a fix reports {@code {cat,1}}, then both tags
     * at 1. The workaround control must be {@code en:1}, then {@code en:1} and {@code fr:1}. One
     * flattened multiset accepted reversed or batched histories (codex, round 3 of #116).
     *
     * <p>Returns {@code 1 group} as documented, {@code 2 groups} once fixed, or the rows.
     */
    private static String groupsOverLanguageTaggedKey() throws Exception {
        List<Pusher> twoTags = List.of(
                s -> s.push(VF.createStatement(iri("o1"), iri("l"), VF.createLiteral("cat", "en")), 1000L),
                s -> s.push(VF.createStatement(iri("o2"), iri("l"), VF.createLiteral("cat", "fr")), 1100L));
        List<List<Object>> control = runPerArrival("""
            SELECT ?l ?lang (COUNT(*) AS ?n) FROM STREAM S [TRIPLES 3]
            WHERE { STREAM S { ?o <%sl> ?l . } BIND(LANG(?l) AS ?lang) } GROUP BY ?l ?lang
            """.formatted(C), twoTags);
        if (!(exactRows(control.get(0), List.of(row("l", "cat", "lang", "en", "n", num(1))))
                && exactRows(control.get(1), List.of(row("l", "cat", "lang", "en", "n", num(1)),
                        row("l", "cat", "lang", "fr", "n", num(1)))))) {
            DIVERGENCES.add("REGRESSED — the documented workaround (GROUP BY ?l ?lang) reported " + control
                    + "\n      fix the workaround in GETTING_STARTED.md before trusting the verdict");
        }
        List<List<Object>> rows = runPerArrival("""
            SELECT ?l (COUNT(*) AS ?n) FROM STREAM S [TRIPLES 3]
            WHERE { STREAM S { ?o <%sl> ?l . } } GROUP BY ?l
            """.formatted(C), twoTags);
        boolean first = exactRows(rows.get(0), List.of(row("l", "cat", "n", num(1))));
        if (first && exactRows(rows.get(1), List.of(row("l", "cat", "n", num(2))))) {
            return "1 group";
        }
        if (first && exactRows(rows.get(1), List.of(row("l", "cat", "n", num(1)), row("l", "cat", "n", num(1))))) {
            return "2 groups";
        }
        return String.valueOf(rows);
    }

    /**
     * Upstream lists it as a known issue of 2.0.0-alpha.22: a static {@code UNION} joined to a stream
     * pattern drops every row on the flat paths when the variable joining it is not projected.
     * Measured under {@code [NOW]}: {@code SELECT ?r ?city} answers nothing; the control, the same
     * query also projecting {@code ?p}, answers the one row.
     *
     * <p>Returns {@code 0 rows} as documented, {@code 1 row} once fixed, or the count.
     */
    private static String unionWithUnprojectedJoinVariable() throws Exception {
        Seeder seeded = engine -> {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                conn.add(VF.createIRI(C + "p1"), VF.createIRI(C + "city"), VF.createLiteral("Berlin"));
            }
        };
        String shape = """
            SELECT %2$s FROM STREAM S [NOW]
            WHERE { STREAM S { ?r <%1$sperson> ?p . }
                    { ?p <%1$scity> ?city } UNION { ?p <%1$stown> ?city } }
            """;
        Pusher request = s -> s.push(VF.createStatement(VF.createIRI(C + "r1"), VF.createIRI(C + "person"),
                VF.createIRI(C + "p1")));
        List<Object> control = run(seeded, shape.formatted(C, "?r ?p ?city"), request);
        if (!exactRows(control, List.of(row("r", iri("r1"), "p", iri("p1"), "city", "Berlin")))) {
            DIVERGENCES.add("REGRESSED — the control (UNION with ?p projected) returned " + control
                    + ", not one row\n      the static UNION itself is failing; the projection verdict is meaningless");
        }
        int n = run(seeded, shape.formatted(C, "?r ?city"), request).size();
        return n == 0 ? "0 rows" : n == 1 ? "1 row" : n + " rows";
    }

    private static boolean durationFilterMatches(String filter) throws Exception {
        String xsd = "http://www.w3.org/2001/XMLSchema#";
        List<Object> rows = run(null, """
            SELECT ?o FROM STREAM S [NOW]
            WHERE { STREAM S { ?o <%sv> ?x . } FILTER(%s) }
            """.formatted(C, filter.formatted(xsd)),
                s -> s.push(VF.createStatement(VF.createIRI(C + "o1"), VF.createIRI(C + "v"),
                        VF.createLiteral(1.0))));
        return !rows.isEmpty();
    }

    /**
     * Fixed in 2.0.0-alpha.22, outside the 162-case sweep that showed #67 fixed: on the composed
     * windowed route (a multi-pattern {@code STREAM} block under an accumulating window), a stream
     * solution whose join key binds a LITERAL used to drop its row on alpha.21. The identical query
     * keyed on an IRI answers — that is the control, and if it stops answering the comparison means
     * nothing.
     *
     * <p>Returns true when the literal-keyed row carries {@code ?item = item1}, and a literal key
     * with no static match ({@code "ZZ"}) answers nothing. A non-empty result alone would also pass
     * if the static join were skipped (codex, review of #116).
     */
    private static boolean literalJoinKeyAdmits() throws Exception {
        Seeder seeded = engine -> {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                conn.add(VF.createIRI(C + "item1"), VF.createIRI(C + "itemCode"), VF.createLiteral("A1"));
                conn.add(VF.createIRI(C + "item1"), VF.createIRI(C + "itemCodeIri"), VF.createIRI(C + "A1"));
            }
        };
        IRI o = VF.createIRI(C + "o1");
        List<Object> literal = run(seeded, """
            SELECT ?o ?item FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%1$scode> ?k . ?o <%1$stag> ?t . } ?item <%1$sitemCode> ?k . }
            """.formatted(C), s -> s.push(List.of(
                VF.createStatement(o, VF.createIRI(C + "code"), VF.createLiteral("A1")),
                VF.createStatement(o, VF.createIRI(C + "tag"), VF.createLiteral("t")))));
        List<Object> unmatched = run(seeded, """
            SELECT ?o ?item FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%1$scode> ?k . ?o <%1$stag> ?t . } ?item <%1$sitemCode> ?k . }
            """.formatted(C), s -> s.push(List.of(
                VF.createStatement(o, VF.createIRI(C + "code"), VF.createLiteral("ZZ")),
                VF.createStatement(o, VF.createIRI(C + "tag"), VF.createLiteral("t")))));
        List<Object> iri = run(seeded, """
            SELECT ?o ?item FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%1$scodeIri> ?k . ?o <%1$stag> ?t . } ?item <%1$sitemCodeIri> ?k . }
            """.formatted(C), s -> s.push(List.of(
                VF.createStatement(o, VF.createIRI(C + "codeIri"), VF.createIRI(C + "A1")),
                VF.createStatement(o, VF.createIRI(C + "tag"), VF.createLiteral("t")))));
        if (iri.isEmpty()) {
            DIVERGENCES.add("REGRESSED — the IRI-keyed control of the literal-join-key check no longer "
                    + "answers\n      the composed route now drops IRI-keyed rows too; the literal "
                    + "verdict is meaningless until that is understood");
        }
        return exactRows(literal, List.of(row("o", iri("o1"), "item", iri("item1")))) && unmatched.isEmpty();
    }

    /**
     * #83, fixed in 2.0.0-alpha.22: on a query with a static graph pattern, a {@code FILTER} over a
     * {@code BIND} target must see the target, as SPARQL 1.1 requires. On alpha.21 the
     * {@code FILTER} ran before the {@code BIND} and saw the variable unbound, so a comparison on it
     * dropped every row. Checking the dropping direction is enough: a regression of the order flips
     * both. The query deliberately has no {@code OPTIONAL} / {@code UNION} block: on alpha.21 a
     * {@code BIND} written above the first of those already ran first, which would hide a
     * regression.
     *
     * <p>The control filters on the variable the {@code BIND} reads instead, which answers; if it
     * stops answering, the static lookup itself has broken and this verdict means nothing.
     *
     * <p>Two subjects arrive: {@code o1} with {@code ?x} 4 ({@code ?y} 8) and {@code o2} with 2
     * ({@code ?y} 4). {@code FILTER(?y > 5)} must keep exactly {@code o1}: a check that only looks
     * for a surviving row also passes when the {@code FILTER} is ignored (codex, review of #116).
     */
    private static boolean filterSeesBindTargetBesideStaticPattern() throws Exception {
        Seeder seeded = engine -> {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                conn.add(VF.createIRI(C + "o1"), VF.createIRI(C + "kind"), VF.createIRI(C + "K"));
                conn.add(VF.createIRI(C + "o2"), VF.createIRI(C + "kind"), VF.createIRI(C + "K"));
            }
        };
        Pusher four = s -> {
            s.push(VF.createStatement(VF.createIRI(C + "o1"), VF.createIRI(C + "v"), VF.createLiteral(4.0)));
            s.push(VF.createStatement(VF.createIRI(C + "o2"), VF.createIRI(C + "v"), VF.createLiteral(2.0)));
        };
        String shape = """
            SELECT ?o ?y FROM STREAM S [NOW]
            WHERE { STREAM S { ?o <%1$sv> ?x . } ?o <%1$skind> <%1$sK> . BIND(?x * 2 AS ?y) FILTER(%2$s) }
            """;
        List<Object> onBindTarget = run(seeded, shape.formatted(C, "?y > 5"), four);
        Set<String> onInput = subjects(run(seeded, shape.formatted(C, "?x > 2.5"), four));
        if (!onInput.equals(Set.of("o1"))) {
            DIVERGENCES.add("REGRESSED — the control (a FILTER on the BIND's input) no longer answers\n"
                    + "      the static lookup itself is failing; the BIND/FILTER verdict is meaningless");
        }
        return exactRows(onBindTarget, List.of(row("o", iri("o1"), "y", num(8))));
    }

    /**
     * #85, fixed in 2.0.0-alpha.22: on a stream-only query, a {@code FILTER} over a variable an
     * {@code OPTIONAL} binds must run after the {@code OPTIONAL}. On alpha.20 and alpha.21 it ran
     * first and saw the variable unbound: {@code FILTER(BOUND(?l))} dropped a row whose {@code ?l}
     * the {@code OPTIONAL} binds, and {@code FILTER(!BOUND(?l))} admitted it, carrying the bound
     * value.
     *
     * <p>Two subjects arrive: {@code o1}, which the {@code OPTIONAL} labels, and {@code o2}, which it
     * does not. The control is the query with no {@code FILTER}: it must return {@code o1} with
     * {@code ?l} bound and {@code o2} without, or the {@code OPTIONAL} itself has broken and the
     * verdict means nothing. The verdict needs both directions right — {@code BOUND} keeps only
     * {@code o1}, {@code !BOUND} keeps only {@code o2} — so a query that loses its {@code FILTER}
     * altogether does not read as a fix.
     *
     * <p>Returns true when both filters answer as SPARQL requires.
     */
    private static boolean filterSeesOptionalVariableOnStreamOnlyQuery() throws Exception {
        Seeder seeded = engine -> {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                conn.add(VF.createIRI(C + "o1"), VF.createIRI(C + "label"), VF.createLiteral(8));
            }
        };
        Pusher both = s -> {
            s.push(VF.createStatement(VF.createIRI(C + "o1"), VF.createIRI(C + "v"), VF.createLiteral(3)));
            s.push(VF.createStatement(VF.createIRI(C + "o2"), VF.createIRI(C + "v"), VF.createLiteral(4)));
        };
        String shape = """
            SELECT ?o ?l FROM STREAM S [NOW]
            WHERE { STREAM S { ?o <%1$sv> ?x . } OPTIONAL { ?o <%1$slabel> ?l } %2$s }
            """;
        Set<String> unfiltered = subjects(run(seeded, shape.formatted(C, ""), both));
        Set<String> bound = subjects(run(seeded, shape.formatted(C, "FILTER(BOUND(?l))"), both));
        Set<String> unbound = subjects(run(seeded, shape.formatted(C, "FILTER(!BOUND(?l))"), both));
        if (!unfiltered.equals(Set.of("o1+l", "o2"))) {
            DIVERGENCES.add("REGRESSED — the control (the OPTIONAL with no FILTER) returned " + unfiltered
                    + ", not [o1+l, o2]\n      the stream-only OPTIONAL itself is failing; the "
                    + "OPTIONAL/FILTER verdict is meaningless");
        }
        boolean boundRight = bound.equals(Set.of("o1+l"));
        boolean unboundRight = unbound.equals(Set.of("o2"));
        if (boundRight != unboundRight) {
            DIVERGENCES.add("PARTIAL — #85 holds in one direction only: FILTER(BOUND(?l)) returned "
                    + bound + ", FILTER(!BOUND(?l)) returned " + unbound);
        }
        return boundRight && unboundRight;
    }

    /** Each row as its subject's local name, suffixed {@code +l} when {@code ?l} is bound. */
    private static Set<String> subjects(List<Object> rows) {
        Set<String> out = new TreeSet<>();
        for (Object row : rows) {
            Map<?, ?> bindings = (Map<?, ?>) row;
            String o = String.valueOf(bindings.get("o"));
            out.add(o.substring(o.lastIndexOf('#') + 1) + (bindings.get("l") == null ? "" : "+l"));
        }
        return out;
    }

    /**
     * #74, fixed in 2.0.0-alpha.22: a {@code SEQ} argument must be the subject of a triple pattern
     * (CQELS-QL_SPEC.md §7), and one that is not is refused at registration. On alpha.21 it
     * registered and matched any element. Both measured forms are checked — the variable mentioned
     * nowhere else, and the variable used only as an object — each with an alert followed by an
     * unrelated element, in case a regression lets one register again.
     *
     * <p>The control makes the second argument a subject and pushes two alerts: it must match. If it
     * stops matching, CEP itself has broken and this verdict means nothing.
     *
     * <p>Returns true when both forms are refused at registration, which is what §7 documents; a
     * form that registers fails the check even if it matches nothing, and one form refused while
     * the other is not is reported as PARTIAL.
     */
    private static boolean seqVariableMustBeASubject() throws Exception {
        String alert = "<" + C + "hasAlert> <" + C + "Overheat>";
        Statement unrelated = VF.createStatement(VF.createIRI(C + "x1"), VF.createIRI(C + "unrelated"),
                VF.createIRI(C + "y"));
        List<Object> unmentioned = cep("?e1 " + alert + " . FILTER(SEQ(?e1 ; ?zz))", unrelated);
        List<Object> objectOnly = cep("?e1 " + alert + " . ?x <" + C + "hasAlert> ?e2 . "
                + "FILTER(SEQ(?e1 ; ?e2))", unrelated);
        List<Object> control = cep("?e1 " + alert + " . ?e2 " + alert + " . FILTER(SEQ(?e1 ; ?e2))",
                VF.createStatement(VF.createIRI(C + "m2"), VF.createIRI(C + "hasAlert"), VF.createIRI(C + "Overheat")));
        if (control == null || control.isEmpty()) {
            DIVERGENCES.add("REGRESSED — the control (two alerts, both SEQ arguments subjects) no longer matches\n"
                    + "      CEP itself is failing; the #74 verdict is meaningless");
        }
        boolean unmentionedFixed = unmentioned == null;
        boolean objectOnlyFixed = objectOnly == null;
        if (unmentionedFixed != objectOnlyFixed) {
            DIVERGENCES.add("PARTIAL — #74 holds for one form only: unmentioned variable "
                    + (unmentionedFixed ? "refused" : "registers, matches " + unmentioned)
                    + ", object-only variable "
                    + (objectOnlyFixed ? "refused" : "registers, matches " + objectOnly));
        }
        return unmentionedFixed && objectOnlyFixed;
    }

    /**
     * Registers {@code SELECT ?e1 … [RANGE 30s] WHERE { body }} as a CEP query, pushes an alert at
     * 1 s and {@code second} at 2 s, and returns the matches, or null when registration is refused.
     */
    private static List<Object> cep(String body, Statement second) throws Exception {
        try (CQELSEngine engine = CQELSEngine.builder().id("probe-" + System.nanoTime()).withMemoryStore().build()) {
            DataStream stream = engine.createStream("S");
            List<Object> matches = new CopyOnWriteArrayList<>();
            try {
                engine.registerCepQuery(withRegister("SELECT ?e1 FROM STREAM S [RANGE 30s]\nWHERE { " + body + " }\n"),
                        (PatternMatch<StreamElement> m) -> matches.add(m.getEvents().size()));
            } catch (Exception refused) {
                // Only the documented refusal counts as one; anything else (an unrelated failure)
                // must not read as the #74 validation (codex, review of #116).
                if (String.valueOf(refused.getMessage()).contains("is not the subject of a stream triple pattern")) {
                    return null;
                }
                DIVERGENCES.add("UNEXPECTED REFUSAL — a CEP query was refused for another reason: "
                        + refused.getMessage() + "\n      the #74 check cannot tell what it measured");
                return List.of();
            }
            engine.start();
            stream.push(VF.createStatement(VF.createIRI(C + "m1"), VF.createIRI(C + "hasAlert"),
                    VF.createIRI(C + "Overheat")), 1000L);
            Thread.sleep(100);
            stream.push(second, 2000L);
            Thread.sleep(1500);
            return matches;
        }
    }

    /**
     * Fixed in 2.0.0-alpha.22: on a query over one stream, {@code FROM <g>} scopes the static lookup.
     * On alpha.21 a static pattern matched a triple stored only in another named graph, or in the
     * default graph, exactly as if the clause were absent. (A two-stream {@code [RANGE]} join was
     * already scoped, so this check stays on one stream.) Raised in #79.
     *
     * <p>The control stores the triple in the declared graph, where it must match; if it stops
     * matching, the static lookup itself has broken.
     *
     * <p>Returns true when neither the other named graph nor the default graph is matched; a
     * regression for only one of them is reported as PARTIAL.
     */
    private static boolean fromGraphScopesStaticLookup() throws Exception {
        String where = """
            SELECT ?city FROM STREAM S [NOW] FROM <%1$sgraph>
            WHERE { STREAM S { ?req <%1$spersonIri> ?person . } ?person <%1$scity> ?city . }
            """.formatted(C);
        Pusher request = s -> s.push(VF.createStatement(VF.createIRI(C + "req1"),
                VF.createIRI(C + "personIri"), VF.createIRI(C + "alice")));
        boolean otherScoped = run(inGraph("other"), where, request).isEmpty();
        boolean defaultScoped = run(inGraph(null), where, request).isEmpty();
        if (run(inGraph("graph"), where, request).isEmpty()) {
            DIVERGENCES.add("REGRESSED — the control (data in the declared graph) no longer answers\n"
                    + "      the static lookup itself is failing; the FROM <graph> verdict is meaningless");
        }
        if (otherScoped != defaultScoped) {
            DIVERGENCES.add("PARTIAL — FROM <graph> scopes the lookup against "
                    + (otherScoped ? "another named graph" : "the default graph") + " only");
        }
        return otherScoped && defaultScoped;
    }

    /** Seeds {@code alice city "Berlin"} into the named graph {@code graph}, or the default graph if null. */
    private static Seeder inGraph(String graph) {
        return engine -> {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                if (graph == null) {
                    conn.add(VF.createIRI(C + "alice"), VF.createIRI(C + "city"), VF.createLiteral("Berlin"));
                } else {
                    conn.add(VF.createIRI(C + "alice"), VF.createIRI(C + "city"), VF.createLiteral("Berlin"),
                            VF.createIRI(C + graph));
                }
            }
        };
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

    /**
     * CQELS-QL_SPEC.md §9 lists "two stream patterns" as a rolling {@code [RANGE]} aggregate, and
     * the WindowedAggregation example depends on it: a running count on every arrival over the last
     * 3 s of event time, not a bucket reported when it closes. Raised in #77.
     *
     * <p>Three elements, timestamped inside one 3 s bucket, each satisfying both patterns. Each
     * arrival must report the running count — {@code n=1}, then {@code n=2}, then {@code n=3} — before
     * the next element is pushed, which rejects close-only and batched reporting. A fourth element at
     * 10 s must then report {@code n=1}, which rejects a window that never evicts.
     */
    private static boolean twoPatternRangeAggregateRolls() throws Exception {
        try (CQELSEngine engine = CQELSEngine.builder().id("probe-range-roll").withMemoryStore().build()) {
            DataStream stream = engine.createStream("S");
            List<Object> rows = new CopyOnWriteArrayList<>();
            engine.registerCqelsQuery(withRegister(
                    "SELECT (COUNT(*) AS ?n) FROM STREAM S [RANGE 3s]\n"
                    + "WHERE { STREAM S { ?o <" + C + "v> ?val . ?o <" + C + "w> ?w . } }\n"), rows::add);
            engine.start();
            long[] timestamps = {100L, 200L, 300L, 10_000L};
            int[] expected = {1, 2, 3, 1};
            for (int k = 0; k < timestamps.length; k++) {
                int before = rows.size();
                stream.push(List.of(
                        VF.createStatement(VF.createIRI(C + "o" + k), VF.createIRI(C + "v"), VF.createLiteral(k)),
                        VF.createStatement(VF.createIRI(C + "o" + k), VF.createIRI(C + "w"), VF.createLiteral(k))),
                        timestamps[k]);
                for (int i = 0; i < 30 && rows.size() == before; i++) {
                    Thread.sleep(100);
                }
                if (rows.size() == before || !countIs(rows.get(rows.size() - 1), expected[k])) {
                    return false;
                }
            }
            return true;
        }
    }

    /** True when the row binds {@code ?n} to exactly {@code n} (parsed as in {@link #countIsThree}). */
    private static boolean countIs(Object row, int n) {
        return java.util.regex.Pattern.compile("\\bn=" + n + "(?![\\d.eE+-])")
                .matcher(String.valueOf(row)).find();
    }

    /**
     * CQELS-QL_SPEC.md §10 states the CypherQL clause order: {@code FROM STREAM} first, and a query
     * starting with {@code MATCH} is a syntax error (the spec's own example had it the wrong way
     * round until #76). Both halves are checked, so the sentence goes stale in either direction.
     */
    private static boolean cypherClauseOrder() throws Exception {
        String match = "MATCH (req:Request)-[:FOR]->(p:Person)-[:LOCATED_IN]->(c:City)\n";
        String from = "FROM STREAM ProfileRequests [NOW]\n";
        String ret = "RETURN p.firstName, p.lastName, c.name";
        return cypherRefusal(from + match + ret) == null
                && String.valueOf(cypherRefusal(match + from + ret)).contains("Syntax error");
    }

    /** Registers the CypherQL query; returns null if it registers, or the refusal's message. */
    private static String cypherRefusal(String query) throws Exception {
        try (CQELSEngine engine = CQELSEngine.builder().id("probe-" + System.nanoTime()).withMemoryStore().build()) {
            engine.createStream("ProfileRequests");
            try {
                engine.registerCypherQuery(query, row -> { });
                return null;
            } catch (Exception refused) {
                return String.valueOf(refused.getMessage());
            }
        }
    }

    private static boolean staticEnrichmentBinds() throws Exception {
        List<Object> rows = run(engine -> {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                conn.add(VF.createIRI(C + "a"), VF.createIRI(C + "label"), VF.createLiteral("Alpha"));
            }
        }, """
            SELECT ?o ?label FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sof> ?x . } ?x <%slabel> ?label . }
            """.formatted(C, C),
                s -> s.pushTriple(C + "o1", C + "of", C + "a"));
        return exactRows(rows, List.of(row("o", iri("o1"), "label", "Alpha")));
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
        // Compared as exact values per binding: substring matching accepted d=150.01 (codex, #116).
        return exactRows(rows, List.of(row(
                "d", num(150.0),                // ABS(250 - 100)
                "c", "x250.0",                  // CONCAT("x", STR(250.0))
                "i", iri("250.0"),              // IRI(...) must bind an IRI, not a string
                "u", "25",                      // SUBSTR("250.0", 1, 2)
                "f", "hi")));                   // IF(250 > 10, "hi", "lo")
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
        return exactRows(rows, List.of(row("v", num(1.0), "w", num(2.0))));
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
                    // so the bucket needs a later element to close it. Event time decides the
                    // bucket: the first three at 100-300 ms land in [0, 1000), and the fourth at
                    // 1500 ms closes it. Wall-clock pacing used to let the scheduler split the
                    // bucket and fail the check with a correct engine (codex, review of #116).
                    s.push(st("o1", "group", "g1"), 100L);
                    s.push(st("o2", "group", "g1"), 200L);
                    s.push(st("o3", "group", "g2"), 300L);
                    Thread.sleep(200);
                    s.push(st("o4", "group", "g2"), 1500L);
                });
        // Exactly the closed bucket: {g1, 2} and {g2, 1}, nothing else. Substring matching across
        // rows passed on {g1,n=1}+{g2,n=2} and on tenfold counts (codex, rounds 2 and 3 of #66;
        // review of #116).
        return exactRows(rows, List.of(row("g", iri("g1"), "n", num(2)), row("g", iri("g2"), "n", num(1))));
    }

    private static Statement st(String s, String p, String o) {
        return VF.createStatement(VF.createIRI(C + s), VF.createIRI(C + p), VF.createIRI(C + o));
    }

    // ---- exact-row oracle ------------------------------------------------------------------

    /**
     * A binding as text: a literal's label, an IRI's string, anything else's {@code toString}. For
     * messages and for the duration reports; checks compare with {@link #sameValue}, never with
     * substrings of a row's {@code toString} (substring matching accepted {@code 150.01} for
     * {@code 150.0} and {@code AlphaWRONG} for {@code Alpha}; codex, review of #116).
     */
    private static String text(Object value) {
        return value == null ? "null" : (value instanceof Literal l ? l.getLabel() : String.valueOf(value));
    }

    /** An expected number: compared numerically, and only against a number or numeric literal. */
    private record Num(double value) { }

    /** An expected unbound variable: absent from the row, or bound to null. */
    private static final Object UNBOUND = new Object();

    private static IRI iri(String local) {
        return VF.createIRI(C + local);
    }

    private static Num num(double value) {
        return new Num(value);
    }

    /** One expected row: alternating variable names and typed values ({@link IRI}, {@link Num}, String, {@link #UNBOUND}). */
    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /**
     * True when {@code actual} is the expected value AND of the expected kind: an IRI only matches an
     * IRI, a string only a non-IRI with the same exact lexical form ({@code "025"} is not
     * {@code "25"}), a number only a number. Spelling alone accepted a string where the engine
     * should bind an IRI (codex, round 3 of #116).
     */
    private static boolean sameValue(Object actual, Object expected) {
        if (expected == UNBOUND) {
            return actual == null;
        }
        if (actual == null) {
            return false;
        }
        if (expected instanceof IRI want) {
            return actual instanceof IRI got && got.equals(want);
        }
        if (expected instanceof Num want) {
            if (actual instanceof Number n) {
                return n.doubleValue() == want.value();
            }
            if (actual instanceof Literal l) {
                try {
                    return l.doubleValue() == want.value();
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            return false;
        }
        if (actual instanceof IRI || actual instanceof Number) {
            return false;
        }
        return text(actual).equals(expected);
    }

    /**
     * True when the rows are exactly the expected ones, as a multiset: the same number of rows, and
     * each expected row matched by a distinct actual row that binds every expected variable to the
     * expected value. Variables an expected row does not name are not inspected.
     */
    private static boolean exactRows(List<Object> rows, List<Map<String, Object>> expected) {
        if (rows.size() != expected.size()) {
            return false;
        }
        List<Object> left = new ArrayList<>(rows);
        for (Map<String, Object> want : expected) {
            Object hit = null;
            for (Object candidate : left) {
                Map<?, ?> got = (Map<?, ?>) candidate;
                if (want.entrySet().stream().allMatch(e -> (e.getValue() == UNBOUND || got.containsKey(e.getKey()))
                        && sameValue(got.get(e.getKey()), e.getValue()))) {
                    hit = candidate;
                    break;
                }
            }
            if (hit == null) {
                return false;
            }
            left.remove(hit);
        }
        return true;
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
    /**
     * Like {@link #run}, but pushes one element per {@link Pusher} and returns the rows each arrival
     * produced, collected before the next element is pushed — so a check can tell one arrival's
     * output from the next one's.
     */
    private static List<List<Object>> runPerArrival(String where, List<Pusher> arrivals) throws Exception {
        List<Object> rows = new CopyOnWriteArrayList<>();
        List<List<Object>> perArrival = new ArrayList<>();
        try (CQELSEngine engine = CQELSEngine.builder().id("probe-" + System.nanoTime()).withMemoryStore().build()) {
            DataStream stream = engine.createStream("S");
            engine.registerCqelsQuery(withRegister(where), rows::add);
            engine.start();
            for (Pusher arrival : arrivals) {
                int before = rows.size();
                arrival.push(stream);
                Thread.sleep(1500);
                perArrival.add(new ArrayList<>(rows.subList(before, rows.size())));
            }
        }
        return perArrival;
    }

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
