package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

/**
 * Example — CypherQL: continuous property-graph queries.
 *
 * <p>CQELS also speaks Cypher. The same streaming model applies, but you match property-graph
 * patterns instead of triple patterns; RDF maps naturally — {@code rdf:type} becomes a node label.
 * Here {@code MATCH (o:Observation) RETURN o} continuously reports the observations flowing through
 * the brewery stream (each {@code sosa:Observation} is one labelled node).
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.CypherGraphQuery}
 */
public class CypherGraphQuery {

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("cypher-graph-query")
                .withMemoryStore()
                .build()) {

            DataStream observations = engine.createStream("Observations");

            String cypher = """
                    FROM STREAM Observations [NOW]
                    MATCH (o:Observation)
                    RETURN o
                    """;
            engine.registerCypherQuery(cypher, row ->
                    System.out.println("  observation -> " + row));

            engine.start();
            System.out.println("Engine started. Cypher MATCH (o:Observation) over the stream.\n");

            String[][] s = {
                    {Brewery.SENSOR_T1, Brewery.TANK1},
                    {Brewery.SENSOR_T2, Brewery.TANK2},
                    {Brewery.SENSOR_T3, Brewery.TANK3}};
            for (String[] sensor : s) {
                System.out.println("push: observation from " + sensor[0].substring(Brewery.EX.length()));
                Brewery.pushObservation(observations, sensor[0], sensor[1], Brewery.TEMPERATURE, 22.5);
                Thread.sleep(250);
            }
            Thread.sleep(800);
        }
        System.out.println("\nDone.");
    }
}
