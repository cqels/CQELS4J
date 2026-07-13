package org.cqels.examples.reasoning;

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
 * Example — recursive (transitive-closure) inference with an explicit depth bound
 * (cqels-reasoning-rete).
 *
 * <p>The scenario: the depot's site model is a containment hierarchy four levels deep —
 * charging bay 12 is {@code ex:partOf} hall A, hall A is part of the north depot, and the depot
 * is part of the metro region. Only the DIRECT edges are asserted; a single custom rule
 * <pre>  ?a ex:partOf ?b  AND  ?b ex:partOf ?c   ->   ?a ex:partOf ?c</pre>
 * plus {@code enableRecursiveInference(true)} lets the RETE network cascade: each inferred edge
 * is re-fed through the network, so the long-range fact
 * {@code site:bay12 ex:partOf site:regionMetro} (two inference hops) is derived and matched by
 * an ordinary continuous query that never mentions the intermediate sites.
 *
 * <p>{@code maxRecursionDepth} bounds how deep one cascade may re-feed. Ordinary cycles do not
 * spin even without it — the network suppresses statements already in working memory, so a
 * finite cyclic {@code partOf} graph reaches its fixpoint naturally. What the cap adds is a
 * hard ceiling on per-element cascade work (a closure deeper than the cap gets truncated). The
 * engine reports whether any cascade was cut short — this demo prints that counter (0 here:
 * the chain settles well below the bound).
 *
 * <p>Add-on dependency: {@code org.cqels:cqels-reasoning-rete}.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.reasoning.BoundedTransitiveClosure}
 */
public class BoundedTransitiveClosure {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private static final String PART_OF = Fleet.EX + "partOf";
    private static final String SITE = Fleet.EX + "site/";
    private static final String BAY_12 = SITE + "bay12";
    private static final String HALL_A = SITE + "hallA";
    private static final String DEPOT_NORTH = SITE + "depotNorth";
    private static final String REGION_METRO = SITE + "regionMetro";
    // An unrelated containment edge on the other side of town (negative case).
    private static final String KIOSK = SITE + "cityWestKiosk";
    private static final String CITY_WEST_HUB = SITE + "cityWestHub";

    public static void main(String[] args) throws InterruptedException {
        // One transitive rule: ?a partOf ?b AND ?b partOf ?c -> ?a partOf ?c.
        IRI partOf = VF.createIRI(PART_OF);
        Rule transitive = Rule.builder()
                .id("partOf-transitive")
                .condition(RuleCondition.builder()
                        .addPattern(TriplePattern.builder()
                                .subjectVar("a").predicate(partOf).objectVar("b").build())
                        .addPattern(TriplePattern.builder()
                                .subjectVar("b").predicate(partOf).objectVar("c").build())
                        .build())
                .consequent(RuleConsequent.builder()
                        .addTemplate(TripleTemplate.builder()
                                .subjectVar("a").predicate(partOf).objectVar("c").build())
                        .build())
                .priority(10)
                .build();

        // Recursive inference ON, with an explicit depth cap: inferred edges are re-fed
        // through the network, but never more than 4 cascade rounds per input element —
        // a hard ceiling on cascade work (dedup already stops ordinary cycles).
        ReasoningConfig config = ReasoningConfig.builder()
                .ruleSet(RuleSet.of(transitive))
                .enableRecursiveInference(true)
                .maxRecursionDepth(4)
                .build();
        ReactiveReteAdapter reasoner = new ReactiveReteAdapter(config);

        try (CQELSEngine engine = CQELSEngine.builder()
                .id("bounded-transitive-closure")
                .withMemoryStore()
                .addStreamProcessor(reasoner::apply)
                .build()) {

            DataStream sites = engine.createStream("Sites");

            // The query only asks "what is (transitively) inside the metro region?" —
            // it never mentions hall A or the depot.
            String query = Fleet.PREFIXES
                    + "PREFIX site: <" + SITE + ">\n"
                    + """
                    REGISTER QUERY RegionAssets AS
                    SELECT ?asset
                    FROM STREAM Sites [NOW]
                    WHERE { STREAM Sites { ?asset ex:partOf site:regionMetro . } }
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  contained in site:regionMetro -> " + row));

            engine.start();
            System.out.println("Engine started. Rule: ex:partOf is transitive"
                    + " (recursive inference, depth cap 4).\n");

            System.out.println("push edge: site:bay12 ex:partOf site:hallA");
            sites.pushTriple(BAY_12, PART_OF, HALL_A);
            Thread.sleep(300);

            System.out.println("push edge: site:hallA ex:partOf site:depotNorth");
            sites.pushTriple(HALL_A, PART_OF, DEPOT_NORTH);
            Thread.sleep(300);

            // The closing edge: the cascade now derives depot-, hall- AND bay-level
            // containment in the region. site:bay12 -> site:regionMetro is the
            // long-range fact (2 inference hops beyond anything asserted).
            System.out.println("push edge: site:depotNorth ex:partOf site:regionMetro"
                    + "  (closes the 4-level chain)");
            sites.pushTriple(DEPOT_NORTH, PART_OF, REGION_METRO);
            Thread.sleep(800);

            // Negative case: an unrelated containment edge across town — no path to the
            // region, so the query must stay quiet.
            System.out.println("\npush edge: site:cityWestKiosk ex:partOf site:cityWestHub"
                    + "  (no path to the region -> must stay quiet)");
            sites.pushTriple(KIOSK, PART_OF, CITY_WEST_HUB);
            Thread.sleep(600);
            System.out.println("  (no containment derived: cityWestHub is not part of regionMetro)");

            System.out.println("\ncascade rounds cut short by the depth cap: "
                    + reasoner.getNetwork().getRecursionTruncations()
                    + "  (0 = every cascade settled naturally below depth 4)");
        }
        System.out.println("\nDone.");
    }
}
