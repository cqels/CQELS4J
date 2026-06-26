package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

import java.util.Random;

/**
 * Example — W3C SOSA/SSN observations with a multi-pattern stream join.
 *
 * <p>Shows the full {@code sosa:Observation} shape used across these demos: an observation links a
 * sensor, an observed property, a result, and a feature of interest (the tank). This query joins
 * {@code sosa:hasFeatureOfInterest} + {@code sosa:observedProperty} + {@code sosa:hasSimpleResult}
 * to report the average <em>temperature per tank</em> each window — over standard
 * [SOSA/SSN](https://www.w3.org/TR/vocab-ssn/) vocabulary.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.SosaObservations}
 */
public class SosaObservations {

    private static final Random RANDOM = new Random(11);

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("sosa-observations")
                .withMemoryStore()
                .build()) {

            DataStream observations = engine.createStream("Observations");

            String query = Brewery.PREFIXES + """
                    REGISTER QUERY AvgTempPerTank AS
                    SELECT ?tank (AVG(?v) AS ?avgTemp) (COUNT(*) AS ?n)
                    FROM STREAM Observations [RANGE 3s]
                    WHERE {
                      STREAM Observations {
                        ?obs sosa:hasFeatureOfInterest ?tank .
                        ?obs sosa:observedProperty qk:Temperature .
                        ?obs sosa:hasSimpleResult ?v .
                      }
                    }
                    GROUP BY ?tank
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  per-tank temp -> " + row));

            engine.start();
            System.out.println("Engine started. Average temperature per fermentation tank each 3s.\n");

            String[][] s = {
                    {Brewery.SENSOR_T1, Brewery.TANK1},
                    {Brewery.SENSOR_T2, Brewery.TANK2},
                    {Brewery.SENSOR_T3, Brewery.TANK3}};
            for (int i = 0; i < 30; i++) {
                String[] sensor = s[RANDOM.nextInt(s.length)];
                // temperature observation (and an occasional humidity reading, which this query ignores)
                Brewery.pushObservation(observations, sensor[0], sensor[1],
                        Brewery.TEMPERATURE, 18 + RANDOM.nextDouble() * 12);
                if (RANDOM.nextInt(3) == 0) {
                    Brewery.pushObservation(observations, sensor[0], sensor[1],
                            Brewery.REL_HUMIDITY, 40 + RANDOM.nextDouble() * 20);
                }
                Thread.sleep(150);
            }
            Thread.sleep(1000);
        }
        System.out.println("\nDone.");
    }
}
