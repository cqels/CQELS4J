package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.reasoning.config.RDFSProfile;
import org.cqels.reasoning.config.ReasoningConfig;
import org.cqels.reasoning.engine.ReactiveReteAdapter;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;

/**
 * Example 14 — RDFS reasoning over a stream (cqels-reasoning-rete).
 *
 * <p>CQELS can run incremental RDFS/OWL inference inline with stream processing. A RETE
 * reasoner is attached to the engine as a {@code StreamProcessor}; as triples arrive it
 * fires entailment rules and injects the inferred triples back into the stream, where
 * ordinary CQELS-QL queries can match them.
 *
 * <p>Here we declare {@code ex:Sensor rdfs:subClassOf ex:Device}, then push
 * {@code ex:sensor1 a ex:Sensor}. The RDFS <em>rdfs9</em> rule infers
 * {@code ex:sensor1 a ex:Device} — and the query, which only asks for {@code ex:Device}
 * instances, emits {@code sensor1} purely from the inferred triple.
 *
 * <p>Add-on dependency: {@code org.cqels:cqels-reasoning-rete}.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.RdfsReasoning}
 */
public class RdfsReasoning {

    private static final String EX = "http://example.org/";

    public static void main(String[] args) throws InterruptedException {
        // RDFS entailment rules, compiled into a reactive RETE stream processor.
        ReasoningConfig config = RDFSProfile.INSTANCE.createConfig();
        ReactiveReteAdapter reasoner = new ReactiveReteAdapter(config);

        try (CQELSEngine engine = CQELSEngine.builder()
                .id("rdfs-reasoning")
                .withMemoryStore()
                .addStreamProcessor(reasoner::apply)
                .build()) {

            DataStream data = engine.createStream("SensorData");

            // The query asks ONLY for ex:Device instances — none are pushed directly.
            String query = """
                    PREFIX ex: <http://example.org/>
                    PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                    REGISTER QUERY Devices AS
                    SELECT ?device
                    FROM STREAM SensorData [NOW]
                    WHERE { STREAM SensorData { ?device rdf:type ex:Device . } }
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  inferred Device -> " + row));

            engine.start();
            System.out.println("Engine started. Schema: ex:Sensor rdfs:subClassOf ex:Device.\n");

            // 1) schema triple — lands in the reasoner's working memory
            System.out.println("push schema: ex:Sensor rdfs:subClassOf ex:Device");
            data.pushTriple(EX + "Sensor", RDFS.SUBCLASSOF.stringValue(), EX + "Device");
            Thread.sleep(300);

            // 2) instance triple — fires rdfs9 -> infers ex:sensor1 rdf:type ex:Device
            System.out.println("push instance: ex:sensor1 rdf:type ex:Sensor");
            data.pushTriple(EX + "sensor1", RDF.TYPE.stringValue(), EX + "Sensor");
            Thread.sleep(600);
        }
        System.out.println("\nDone.");
    }
}
