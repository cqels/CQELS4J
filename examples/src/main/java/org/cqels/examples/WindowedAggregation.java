package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

import java.util.Random;

/**
 * Example 2 — Windowed aggregation with GROUP BY.
 *
 * <p>Tumbling time windows ({@code [RANGE 3s]}) partition the stream into
 * consecutive, non-overlapping 3-second buckets. At the end of each bucket the
 * query emits one row per sensor with the average, count and peak temperature
 * seen in that window.
 *
 * <p>Key ideas:
 * <ul>
 *   <li>{@code [RANGE Ns]} — tumbling time window of N seconds.</li>
 *   <li>SPARQL-style aggregates: {@code AVG}, {@code COUNT(*)}, {@code MAX}.</li>
 *   <li>{@code GROUP BY ?sensor} — one result row per group, per window firing.</li>
 * </ul>
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.WindowedAggregation}
 */
public class WindowedAggregation {

    private static final Random RANDOM = new Random(42);

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("windowed-aggregation")
                .withMemoryStore()
                .build()) {

            DataStream readings = engine.createStream("Readings");

            String query = """
                    PREFIX ex: <http://example.org/>
                    REGISTER QUERY PerSensorStats AS
                    SELECT ?sensor (AVG(?temp) AS ?avgTemp) (COUNT(*) AS ?n) (MAX(?temp) AS ?peak)
                    FROM STREAM Readings [RANGE 3s]
                    WHERE {
                      STREAM Readings { ?sensor ex:temperature ?temp . }
                    }
                    GROUP BY ?sensor
                    """;

            engine.registerCqelsQuery(query, row ->
                    System.out.println("  window result -> " + row));

            engine.start();
            System.out.println("Engine started. Each 3s window emits avg/count/peak per sensor.\n");

            // ~9 seconds of readings from 3 sensors => roughly three window firings.
            for (int i = 0; i < 45; i++) {
                String sensor = "http://example.org/sensor/" + (i % 3);
                double temp = 18 + RANDOM.nextDouble() * 20; // 18..38
                readings.push(sensor, "http://example.org/temperature", temp);
                Thread.sleep(200);
            }

            Thread.sleep(1000); // let the final window fire
        }
        System.out.println("\nDone.");
    }
}
