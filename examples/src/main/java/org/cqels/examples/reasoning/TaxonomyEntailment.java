package org.cqels.examples.reasoning;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.examples.Fleet;
import org.cqels.reasoning.config.RDFSProfile;
import org.cqels.reasoning.engine.ReactiveReteAdapter;

/**
 * Example — MULTI-HOP RDFS taxonomy + sub-property entailment over the stream
 * (cqels-reasoning-rete).
 *
 * <p>The scenario: the depot's registry taxonomy is two levels deep —
 * {@code ex:ElectricBus rdfs:subClassOf ex:DepotVehicle} and
 * {@code ex:DepotVehicle rdfs:subClassOf vsso:Vehicle}. When {@code EV-3K8} registers as an
 * {@code ex:ElectricBus} (the BOTTOM class), a query that only asks for the TOP class
 * {@code vsso:Vehicle} still finds it: <em>rdfs11</em> first derives
 * {@code ex:ElectricBus rdfs:subClassOf vsso:Vehicle} from the axiom chain, then <em>rdfs9</em>
 * lifts the instance across both hops.
 *
 * <p>The property layer works the same way: {@code ex:reportsSoC rdfs:subPropertyOf
 * ex:reportsTelemetry}, so a raw state-of-charge reading pushed with the SPECIFIC property
 * matches a query written against the GENERIC {@code ex:reportsTelemetry} (<em>rdfs7</em>).
 *
 * <p>Entailment is axiom-driven, not name-driven: a contractor shuttle typed with a class that
 * has no {@code rdfs:subClassOf} link into the taxonomy never shows up in the top-class query.
 *
 * <p>Add-on dependency: {@code org.cqels:cqels-reasoning-rete}.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.reasoning.TaxonomyEntailment}
 */
public class TaxonomyEntailment {

    private static final String ELECTRIC_BUS = Fleet.EX + "ElectricBus";
    private static final String DEPOT_VEHICLE = Fleet.EX + "DepotVehicle";
    private static final String CONTRACTOR_SHUTTLE = Fleet.EX + "ContractorShuttle";
    private static final String REPORTS_SOC = Fleet.EX + "reportsSoC";
    private static final String REPORTS_TELEMETRY = Fleet.EX + "reportsTelemetry";
    private static final String RDFS_SUBPROPERTYOF =
            "http://www.w3.org/2000/01/rdf-schema#subPropertyOf";

    public static void main(String[] args) throws InterruptedException {
        // RDFS profile = rdfs2/3/5/7/9/11 with recursive inference on, so entailments
        // cascade (subclass transitivity feeds type propagation).
        ReactiveReteAdapter reasoner = new ReactiveReteAdapter(RDFSProfile.INSTANCE.createConfig());

        try (CQELSEngine engine = CQELSEngine.builder()
                .id("taxonomy-entailment")
                .withMemoryStore()
                .addStreamProcessor(reasoner::apply)
                .build()) {

            DataStream registry = engine.createStream("Registry");

            // Query 1 asks ONLY for the TOP class — instances are asserted two levels below it.
            String topClassQuery = Fleet.PREFIXES + """
                    REGISTER QUERY TopClassVehicles AS
                    SELECT ?vehicle
                    FROM STREAM Registry [NOW]
                    WHERE { STREAM Registry { ?vehicle a vsso:Vehicle . } }
                    """;
            engine.registerCqelsQuery(topClassQuery, row ->
                    System.out.println("  [rdfs11+rdfs9] entailed vsso:Vehicle -> " + row));

            // Query 2 asks for the SUPER property — data arrives on the specific sub-property.
            String telemetryQuery = Fleet.PREFIXES + """
                    REGISTER QUERY TelemetryReporters AS
                    SELECT ?vehicle ?value
                    FROM STREAM Registry [NOW]
                    WHERE { STREAM Registry { ?vehicle ex:reportsTelemetry ?value . } }
                    """;
            engine.registerCqelsQuery(telemetryQuery, row ->
                    System.out.println("  [rdfs7] entailed ex:reportsTelemetry -> " + row));

            engine.start();
            System.out.println("Engine started. Queries ask for vsso:Vehicle (top class) and"
                    + " ex:reportsTelemetry (super property) only.\n");

            // 1) Schema axioms — a two-hop class chain plus one sub-property axiom.
            System.out.println("push schema: ex:ElectricBus rdfs:subClassOf ex:DepotVehicle");
            registry.pushTriple(ELECTRIC_BUS, Fleet.RDFS_SUBCLASSOF, DEPOT_VEHICLE);
            System.out.println("push schema: ex:DepotVehicle rdfs:subClassOf vsso:Vehicle");
            registry.pushTriple(DEPOT_VEHICLE, Fleet.RDFS_SUBCLASSOF, Fleet.VEHICLE_CLASS);
            System.out.println("push schema: ex:reportsSoC rdfs:subPropertyOf ex:reportsTelemetry");
            registry.pushTriple(REPORTS_SOC, RDFS_SUBPROPERTYOF, REPORTS_TELEMETRY);
            Thread.sleep(400);

            // 2) Instance asserted at the BOTTOM of the chain -> top-class query fires.
            System.out.println("\npush instance: EV-3K8 a ex:ElectricBus  (bottom class, 2 hops down)");
            registry.pushTriple(Fleet.EV2, Fleet.RDF_TYPE, ELECTRIC_BUS);
            Thread.sleep(600);

            // 3) Data on the SPECIFIC property -> super-property query fires.
            System.out.println("\npush data: EV-3K8 ex:reportsSoC 54.0  (specific sub-property)");
            registry.push(Fleet.EV2, REPORTS_SOC, 54.0);
            Thread.sleep(600);

            // 4) Negative case: a class with NO subclass axiom into the taxonomy stays silent.
            System.out.println("\npush instance: EV-9TZ a ex:ContractorShuttle  (no axiom -> must stay quiet)");
            registry.pushTriple(Fleet.EV3, Fleet.RDF_TYPE, CONTRACTOR_SHUTTLE);
            Thread.sleep(600);
            System.out.println("  (no entailment: ex:ContractorShuttle has no rdfs:subClassOf link"
                    + " to vsso:Vehicle)");
        }
        System.out.println("\nDone.");
    }
}
