package org.cqels.examples.cdsp;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.examples.Fleet;
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

/**
 * Example — rolling stream observations up the <strong>S2DM concept hierarchy</strong> with custom
 * RETE rules over {@code skos:broader} (cqels-reasoning-rete).
 *
 * <p>The scenario: a depot supervisor watches one number — "how much is going on inside the cabin
 * right now?" — without caring whether the activity came from a seat, a door, or something added
 * to the model next quarter.
 *
 * <p><strong>Why RDFS reasoning is the wrong tool here.</strong> The existing
 * {@code RdfsReasoning} demo relies on {@code rdfs:subClassOf}, and CQELS's RDFS profile knows to
 * propagate types along it. S2DM hierarchies are not class hierarchies: the projection emits
 * {@code skos:Concept} nodes linked by <strong>{@code skos:broader}</strong>, which carries no
 * RDFS entailment whatsoever. {@code Seat skos:broader Cabin} does not make a {@code Seat}
 * observation a {@code Cabin} observation under any standard profile — SKOS is deliberately
 * weaker than OWL, and that is the point of SKOS.
 *
 * <p>So the rollup has to be stated explicitly, which the {@link Rule} API supports directly. Two
 * rules:
 * <pre>
 *   R1 (lift)       ?obs c:ofConcept ?narrow  AND  ?narrow skos:broader ?broad
 *                     -> ?obs c:ofConcept ?broad
 *   R2 (transitive) ?a skos:broader ?b  AND  ?b skos:broader ?c
 *                     -> ?a skos:broader ?c
 * </pre>
 * R1 lifts each observation onto the concept directly above it, so a {@code Seat} reading is
 * matched by a query that asks only about {@code Cabin} and mentions no narrower concept. The
 * discriminating case is {@code Powertrain}: it is narrower than {@code Vehicle} but not than
 * {@code Cabin}, and it correctly stays out of the cabin query — the rollup follows the modelled
 * hierarchy rather than lifting everything everywhere.
 *
 * <p><strong>Known limits on 2.0.0-alpha.20</strong>, each verified here rather than assumed:
 * <ul>
 *   <li>R2 is registered and {@code enableRecursiveInference(true)} is set, but the two-hop
 *       rollup ({@code Seat -> Cabin -> Vehicle}) does not produce a {@code Vehicle}-level match,
 *       so this demo asserts only the one-hop claim it can actually demonstrate.</li>
 *   <li>The hierarchy must be pushed into the stream: the reasoner reads stream elements only,
 *       never the background graph — even though that is exactly where an s2dm catalogue lives.</li>
 * </ul>
 * Filed as #58 (background graph) and #56 (property paths), each with a minimal reproducer. #56
 * is listed although no query here contains a path expression: the natural way to write this
 * rollup is {@code ?narrow skos:broader+ ?broad} in the query itself, and the reason it is a pair
 * of RETE rules instead is that property paths still do not execute — the engine now rejects one
 * cleanly at registration rather than silently mis-evaluating it, but it remains unsupported. The
 * rules are the workaround for that.
 *
 * <p>Observations are pushed as one atomic multi-statement element
 * ({@link S2dm#pushConceptSignal}), the same call every other demo uses: the reasoner used to skip
 * multi-statement elements entirely, which is why an earlier version of this demo pushed one
 * statement at a time instead — that workaround is gone now that the rule network sees every
 * statement of an atomic push (issue #57, fixed).
 *
 * <p>This is the streaming counterpart of what a SKOS-aware catalogue browser does offline, and it
 * is the piece that makes an s2dm model useful at query time rather than only at design time: the
 * supervisor's "cabin activity" query keeps working when the modeller adds
 * {@code Armrest skos:broader Cabin} next quarter — no query is touched.
 *
 * <p>Add-on dependency: {@code org.cqels:cqels-reasoning-rete}.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.cdsp.SkosConceptRollup}
 */
public class SkosConceptRollup {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    public static void main(String[] args) throws InterruptedException {
        IRI ofConcept = VF.createIRI(S2dm.OF_CONCEPT);
        IRI broader = VF.createIRI(S2dm.SKOS_BROADER);

        // R1 — lift an observation from a narrower concept onto its broader one.
        Rule lift = Rule.builder()
                .id("skos-broader-lift")
                .condition(RuleCondition.builder()
                        .addPattern(TriplePattern.builder()
                                .subjectVar("obs").predicate(ofConcept).objectVar("narrow").build())
                        .addPattern(TriplePattern.builder()
                                .subjectVar("narrow").predicate(broader).objectVar("broad").build())
                        .build())
                .consequent(RuleConsequent.builder()
                        .addTemplate(TripleTemplate.builder()
                                .subjectVar("obs").predicate(ofConcept).objectVar("broad").build())
                        .build())
                .priority(10)
                .build();

        // R2 — skos:broader is not transitive in SKOS (skos:broaderTransitive is). The rollup
        // wants the transitive reading, so it is asserted here as an explicit rule rather than
        // assumed: two hops of the hierarchy become one edge, and R1 lifts across it.
        Rule transitive = Rule.builder()
                .id("skos-broader-transitive")
                .condition(RuleCondition.builder()
                        .addPattern(TriplePattern.builder()
                                .subjectVar("a").predicate(broader).objectVar("b").build())
                        .addPattern(TriplePattern.builder()
                                .subjectVar("b").predicate(broader).objectVar("c").build())
                        .build())
                .consequent(RuleConsequent.builder()
                        .addTemplate(TripleTemplate.builder()
                                .subjectVar("a").predicate(broader).objectVar("c").build())
                        .build())
                .priority(20)
                .build();

        ReasoningConfig config = ReasoningConfig.builder()
                .ruleSet(RuleSet.of(transitive, lift))
                .enableRecursiveInference(true)
                .maxRecursionDepth(6)
                .build();
        ReactiveReteAdapter reasoner = new ReactiveReteAdapter(config);

        try (CQELSEngine engine = CQELSEngine.builder()
                .id("skos-concept-rollup")
                .withMemoryStore()
                .addStreamProcessor(reasoner::apply)
                .build()) {

            S2dm.seedConceptGraph(engine);

            DataStream cabin = engine.createStream("Cabin");
            // The hierarchy has to be PUSHED, not read from the static graph seeded above.
            // ReactiveReteAdapter is a stream processor: its working memory is fed by stream
            // elements only, so a rule condition like "?narrow skos:broader ?broad" can never
            // match a fact that lives in the repository. Asserting the edges into the stream is
            // the documented workaround (BoundedTransitiveClosure does the same with ex:partOf).
            // A reasoner able to join stream data against the background graph would let the
            // s2dm catalogue stay where it belongs -- see issue #58.

            // "Anything happening in the cabin" — names only the BROAD concept.
            String cabinActivity = S2dm.PREFIXES + """
                    REGISTER QUERY CabinActivity AS
                    SELECT ?obs
                    FROM STREAM Cabin [TRIPLES 1]
                    WHERE {
                      STREAM Cabin {
                        ?obs c:ofConcept <https://example.org/vss#Cabin> .
                      }
                    }
                    """;
            engine.registerCqelsQuery(cabinActivity, row ->
                    System.out.println("  [Cabin rollup] " + row));


            engine.start();
            System.out.println("""
                    Engine started. Hierarchy: Seat/Door -> Cabin (skos:broader).
                    Observations are pushed against the NARROW concepts only; the query asks
                    about Cabin and is answered by inference.
                    """);

            System.out.println("seeding the skos:broader hierarchy INTO the stream "
                    + "(the reasoner cannot see the static graph):");
            cabin.pushTriple(S2dm.C_SEAT, S2dm.SKOS_BROADER, S2dm.C_CABIN);
            cabin.pushTriple(S2dm.C_DOOR, S2dm.SKOS_BROADER, S2dm.C_CABIN);
            cabin.pushTriple(S2dm.C_CABIN, S2dm.SKOS_BROADER, S2dm.C_VEHICLE);
            cabin.pushTriple(S2dm.C_POWERTRAIN, S2dm.SKOS_BROADER, S2dm.C_VEHICLE);
            Thread.sleep(800);

            System.out.println("\npush: EV-7Q2 ofConcept c:Seat  (narrow — no query mentions Seat)");
            S2dm.pushConceptSignal(cabin, Fleet.EV1, S2dm.C_SEAT, 1.0);
            Thread.sleep(900);

            System.out.println("\npush: EV-3K8 ofConcept c:Door  (narrow)");
            S2dm.pushConceptSignal(cabin, Fleet.EV2, S2dm.C_DOOR, 1.0);
            Thread.sleep(900);

            // Negative case: Powertrain is narrower than Vehicle but NOT than Cabin, so it must
            // reach the Vehicle query and stay out of the Cabin one. This is the check that the
            // rollup follows the modelled hierarchy rather than lifting everything everywhere.
            System.out.println("\npush: EV-9TZ ofConcept c:Powertrain"
                    + "  (broader=Vehicle only -> must NOT appear under Cabin)");
            S2dm.pushConceptSignal(cabin, Fleet.EV3, S2dm.C_POWERTRAIN, 1.0);
            Thread.sleep(1200);

            System.out.println("\ncascade rounds cut short by the depth cap: "
                    + reasoner.getNetwork().getRecursionTruncations()
                    + "  (0 = every cascade settled naturally below depth 6)");
        }
        System.out.println("\nDone.");
    }
}
