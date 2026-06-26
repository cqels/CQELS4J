package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

import java.util.Random;

/**
 * Example 2 — tumbling windowed aggregation.
 *
 * <p>Over each 3-second window, compute per-sensor statistics of the brewery temperature
 * observations: average, peak, and count. A {@code [RANGE 3s]} tumbling window joins each
 * observation's {@code sosa:madeBySensor} and {@code sosa:hasSimpleResult} and a {@code GROUP BY}
 * aggregates per sensor.
 *
 * <p>Note: aggregates apply only with an explicit {@code GROUP BY} (see {@code CQELS-QL_SPEC.md}).
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.WindowedAggregation}
 */
public class WindowedAggregation {

    private static final Random RANDOM = new Random(7);

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("windowed-aggregation")
                .withMemoryStore()
                .build()) {

            DataStream fermentation = engine.createStream("Fermentation");

            String query = Brewery.PREFIXES + """
                    REGISTER QUERY PerSensorStats AS
                    SELECT ?sensor (AVG(?v) AS ?avgTemp) (MAX(?v) AS ?peak) (COUNT(*) AS ?n)
                    FROM STREAM Fermentation [RANGE 3s]
                    WHERE {
                      STREAM Fermentation {
                        ?obs sosa:madeBySensor ?sensor .
                        ?obs sosa:observedProperty qk:Temperature .
                        ?obs sosa:hasSimpleResult ?v .
                      }
                    }
                    GROUP BY ?sensor
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  window stats -> " + row));

            engine.start();
            System.out.println("Engine started. Per-sensor avg/peak/count temperature each 3s.\n");

            String[][] sensors = {
                    {Brewery.SENSOR_T1, Brewery.TANK1},
                    {Brewery.SENSOR_T2, Brewery.TANK2},
                    {Brewery.SENSOR_T3, Brewery.TANK3}};
            for (int i = 0; i < 30; i++) {
                String[] s = sensors[RANDOM.nextInt(sensors.length)];
                double temp = 18 + RANDOM.nextDouble() * 14;   // 18–32 °C
                Brewery.pushObservation(fermentation, s[0], s[1], Brewery.TEMPERATURE, temp);
                Thread.sleep(150);
            }
            Thread.sleep(1000);
        }
        System.out.println("\nDone.");
    }
}
