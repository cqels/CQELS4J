package org.cqels.playground.probe;

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
import org.eclipse.rdf4j.repository.RepositoryConnection;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.cqels.playground.probe.ProbeSupport.*;

/**
 * Reasoning add-ons: the RETE reasoner ({@code cqels-reasoning-rete}) and the ASP module
 * ({@code cqels-asp}), including the shape of the facade's ASP registration API.
 *
 * <p>{@link CapabilityProbe} runs these checks and names the documentation each one guards.
 */
final class ReasoningProbes {

    private ReasoningProbes() {
    }

    /**
     * The examples README states the warm parse-cache ASP backend is not exposed by the
     * {@code registerAspQuery} convenience overloads. If the facade grows one that accepts an
     * {@code AspSolverBackend}, that note is stale — checked reflectively, since the claim is
     * about the shape of the API rather than about behaviour.
     */
    static boolean registerAspQueryTakesBackend() {
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
    static boolean reasonerSeesAtomicElements() throws Exception {
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
    static boolean reasonerSeesBackgroundGraph() throws Exception {
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
     * The README claims the warm parse-cache backend IS reachable through the facade, via the
     * 5-arg {@code AspContinuousQuery} constructor and the generic {@code registerQuery}. That is a
     * documented, runnable snippet, so it is asserted here rather than trusted.
     */
    static boolean warmBackendReachable() {
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
}
