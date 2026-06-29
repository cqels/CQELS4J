package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

/**
 * Example — CypherQL: continuous property-graph queries.
 *
 * <p>CQELS also speaks Cypher. The same streaming model applies, but you match property-graph
 * patterns instead of triple patterns; RDF maps naturally — {@code rdf:type} becomes a node label.
 * Here {@code MATCH (o:Observation) RETURN o} continuously reports the telemetry observations flowing
 * through the fleet stream (each {@code sosa:Observation} is one labelled node).
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.CypherGraphQuery}
 */
public class CypherGraphQuery {

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("cypher-graph-query")
                .withMemoryStore()
                .build()) {

            DataStream telemetry = engine.createStream("Telemetry");

            String cypher = """
                    FROM STREAM Telemetry [NOW]
                    MATCH (o:Observation)
                    RETURN o
                    """;
            engine.registerCypherQuery(cypher, row ->
                    System.out.println("  observation -> " + row));

            engine.start();
            System.out.println("Engine started. Cypher MATCH (o:Observation) over the telemetry stream.\n");

            String[][] fleet = {
                    {Fleet.SENSOR_EV1, Fleet.EV1}, {Fleet.SENSOR_EV2, Fleet.EV2}, {Fleet.SENSOR_EV3, Fleet.EV3}};
            for (String[] ev : fleet) {
                System.out.println("push: observation from " + ev[1].substring(Fleet.EX.length()));
                Fleet.pushObservation(telemetry, ev[0], ev[1], Fleet.SPEED, 55.0);
                Thread.sleep(250);
            }
            Thread.sleep(800);
        }
        System.out.println("\nDone.");
    }
}
