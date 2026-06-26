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
 * <p>The IBS-TH2 sensor types subclass {@code sosa:Sensor}
 * ({@code sensor:IBS-TH2-Plus-T rdfs:subClassOf sosa:Sensor}). After declaring that and registering
 * {@code tank1-T a sensor:IBS-TH2-Plus-T}, the <em>rdfs9</em> rule infers
 * {@code tank1-T a sosa:Sensor} — so a query asking only for {@code sosa:Sensor} instances finds it.
 *
 * <p>Add-on dependency: {@code org.cqels:cqels-reasoning-rete}.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.RdfsReasoning}
 */
public class RdfsReasoning {

    public static void main(String[] args) throws InterruptedException {
        ReactiveReteAdapter reasoner = new ReactiveReteAdapter(RDFSProfile.INSTANCE.createConfig());

        try (CQELSEngine engine = CQELSEngine.builder()
                .id("rdfs-reasoning")
                .withMemoryStore()
                .addStreamProcessor(reasoner::apply)
                .build()) {

            DataStream registry = engine.createStream("Registry");

            String query = Brewery.PREFIXES + """
                    PREFIX sensor: <http://example.org/sensor/>
                    REGISTER QUERY Sensors AS
                    SELECT ?sensor
                    FROM STREAM Registry [NOW]
                    WHERE { STREAM Registry { ?sensor a sosa:Sensor . } }
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  inferred sosa:Sensor -> " + row));

            engine.start();
            System.out.println("Engine started. Schema: sensor:IBS-TH2-Plus-T rdfs:subClassOf sosa:Sensor.\n");

            // 1) schema axiom — lands in the reasoner's working memory
            System.out.println("push schema: IBS-TH2-Plus-T rdfs:subClassOf sosa:Sensor");
            registry.pushTriple(Brewery.IBS_TH2_T, Brewery.RDFS_SUBCLASSOF, Brewery.SENSOR_CLASS);
            Thread.sleep(300);

            // 2) instance — fires rdfs9 -> infers tank1-T a sosa:Sensor
            System.out.println("push instance: tank1-T a IBS-TH2-Plus-T");
            registry.pushTriple(Brewery.SENSOR_T1, Brewery.RDF_TYPE, Brewery.IBS_TH2_T);
            Thread.sleep(600);
        }
        System.out.println("\nDone.");
    }
}
