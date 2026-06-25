package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

import java.util.Random;

/**
 * Example 5 — W3C SOSA/SSN sensor observations.
 *
 * <p>Real RDF sensor streams are usually modelled with the W3C
 * <a href="https://www.w3.org/TR/vocab-ssn/">SOSA/SSN</a> vocabulary: each
 * {@code sosa:Observation} links the sensor that produced it ({@code sosa:madeBySensor})
 * to a result ({@code sosa:hasSimpleResult}). This example consumes such a stream and
 * computes, per sensor, the average and peak result over a 4-second window.
 *
 * <p>It also shows a <strong>multi-pattern graph join inside the window</strong>: the two
 * triples that make up each observation ({@code madeBySensor} and {@code hasSimpleResult})
 * are joined on the shared {@code ?obs} subject before grouping.
 *
 * <p>Key ideas:
 * <ul>
 *   <li>Standard SOSA/SSN terms, so the same query works over any conformant RDF stream.</li>
 *   <li>Two stream triple patterns joined on {@code ?obs}, then {@code GROUP BY ?sensor}.</li>
 *   <li>Push an IRI-valued object with {@code pushTriple(...)} and a numeric literal with
 *       {@code push(..., double)}.</li>
 * </ul>
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.SosaObservations}
 */
public class SosaObservations {

    private static final String SOSA = "http://www.w3.org/ns/sosa/";
    private static final Random RANDOM = new Random(11);

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("sosa-observations")
                .withMemoryStore()
                .build()) {

            DataStream observations = engine.createStream("Observations");

            String query = """
                    PREFIX sosa: <http://www.w3.org/ns/sosa/>
                    REGISTER QUERY AvgResultPerSensor AS
                    SELECT ?sensor (AVG(?value) AS ?avg) (MAX(?value) AS ?peak) (COUNT(*) AS ?n)
                    FROM STREAM Observations [RANGE 4s]
                    WHERE {
                      STREAM Observations {
                        ?obs sosa:madeBySensor ?sensor .
                        ?obs sosa:hasSimpleResult ?value .
                      }
                    }
                    GROUP BY ?sensor
                    """;

            engine.registerCqelsQuery(query, row ->
                    System.out.println("  window result -> " + row));

            engine.start();
            System.out.println("Engine started. SOSA observations aggregated per sensor every 4s.\n");

            for (int i = 0; i < 40; i++) {
                String obs = "http://example.org/obs/" + i;
                String sensor = SOSA + "sensor/" + (i % 3);
                double value = 18 + RANDOM.nextDouble() * 20; // 18..38

                // One observation = two triples sharing the same ?obs subject.
                observations.pushTriple(obs, SOSA + "madeBySensor", sensor);   // object is an IRI
                observations.push(obs, SOSA + "hasSimpleResult", value);       // object is a numeric literal

                Thread.sleep(200);
            }

            Thread.sleep(1000);
        }
        System.out.println("\nDone.");
    }
}
