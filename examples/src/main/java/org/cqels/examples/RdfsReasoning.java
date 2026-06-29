package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.reasoning.config.RDFSProfile;
import org.cqels.reasoning.engine.ReactiveReteAdapter;

/**
 * Example — RDFS reasoning over the stream (cqels-reasoning-rete).
 *
 * <p>CQELS can run incremental RDFS/OWL inference inline with stream processing. A RETE reasoner is
 * attached as a {@code StreamProcessor}; as triples arrive it fires entailment rules and injects the
 * inferred triples back into the stream for ordinary queries to match.
 *
 * <p>The fleet taxonomy specialises {@code vsso:Vehicle}: {@code ex:DepotVehicle rdfs:subClassOf
 * vsso:Vehicle}. After declaring that and registering {@code ex:vehicle/EV-7Q2 a ex:DepotVehicle}, the
 * <em>rdfs9</em> rule infers {@code ex:vehicle/EV-7Q2 a vsso:Vehicle} — so a query asking only for
 * {@code vsso:Vehicle} instances finds it.
 *
 * <p>Add-on dependency: {@code org.cqels:cqels-reasoning-rete}.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.RdfsReasoning}
 */
public class RdfsReasoning {

    private static final String DEPOT_VEHICLE = Fleet.EX + "DepotVehicle";

    public static void main(String[] args) throws InterruptedException {
        ReactiveReteAdapter reasoner = new ReactiveReteAdapter(RDFSProfile.INSTANCE.createConfig());

        try (CQELSEngine engine = CQELSEngine.builder()
                .id("rdfs-reasoning")
                .withMemoryStore()
                .addStreamProcessor(reasoner::apply)
                .build()) {

            DataStream registry = engine.createStream("Registry");

            String query = Fleet.PREFIXES + """
                    REGISTER QUERY Vehicles AS
                    SELECT ?vehicle
                    FROM STREAM Registry [NOW]
                    WHERE { STREAM Registry { ?vehicle a vsso:Vehicle . } }
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  inferred vsso:Vehicle -> " + row));

            engine.start();
            System.out.println("Engine started. Schema: ex:DepotVehicle rdfs:subClassOf vsso:Vehicle.\n");

            // 1) schema axiom — lands in the reasoner's working memory
            System.out.println("push schema: ex:DepotVehicle rdfs:subClassOf vsso:Vehicle");
            registry.pushTriple(DEPOT_VEHICLE, Fleet.RDFS_SUBCLASSOF, Fleet.VEHICLE_CLASS);
            Thread.sleep(300);

            // 2) instance — fires rdfs9 -> infers EV-7Q2 a vsso:Vehicle
            System.out.println("push instance: EV-7Q2 a ex:DepotVehicle");
            registry.pushTriple(Fleet.EV1, Fleet.RDF_TYPE, DEPOT_VEHICLE);
            Thread.sleep(600);
        }
        System.out.println("\nDone.");
    }
}
