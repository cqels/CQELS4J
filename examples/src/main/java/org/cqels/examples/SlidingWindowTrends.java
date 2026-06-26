package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

import java.util.Random;

/**
 * Example 3 — sliding windows for moving trends.
 *
 * <p>A {@code [SLIDE 4s STEP 2s]} window keeps the trailing 4 seconds of temperature observations
 * and re-emits every 2 seconds, so consecutive results overlap — handy for moving averages. Here:
 * per-sensor moving average / min / max tank temperature.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.SlidingWindowTrends}
 */
public class SlidingWindowTrends {

    private static final Random RANDOM = new Random(3);

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("sliding-window-trends")
                .withMemoryStore()
                .build()) {

            DataStream fermentation = engine.createStream("Fermentation");

            String query = Brewery.PREFIXES + """
                    REGISTER QUERY TempTrends AS
                    SELECT ?sensor (AVG(?v) AS ?avg) (MIN(?v) AS ?min) (MAX(?v) AS ?max)
                    FROM STREAM Fermentation [SLIDE 4s STEP 2s]
                    WHERE {
                      STREAM Fermentation {
                        ?obs sosa:madeBySensor ?sensor .
                        ?obs sosa:hasSimpleResult ?v .
                      }
                    }
                    GROUP BY ?sensor
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  trailing-4s trend -> " + row));

            engine.start();
            System.out.println("Engine started. Moving avg/min/max tank temperature, re-emitted every 2s.\n");

            String[][] s = {
                    {Brewery.SENSOR_T1, Brewery.TANK1},
                    {Brewery.SENSOR_T2, Brewery.TANK2}};
            for (int i = 0; i < 24; i++) {
                String[] sensor = s[RANDOM.nextInt(s.length)];
                Brewery.pushObservation(fermentation, sensor[0], sensor[1],
                        Brewery.TEMPERATURE, 18 + RANDOM.nextDouble() * 12);
                Thread.sleep(300);
            }
            Thread.sleep(1000);
        }
        System.out.println("\nDone.");
    }
}
