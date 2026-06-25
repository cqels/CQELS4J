package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

/**
 * Example 1 — Hello, CQELS: your first continuous query.
 *
 * <p>The smallest useful CQELS program. It registers a continuous CQELS-QL query
 * that fires an alert for every sensor reading above a threshold, then pushes a
 * handful of readings into the stream.
 *
 * <p>Key ideas:
 * <ul>
 *   <li>{@code [NOW]} is an instantaneous window — the query is evaluated on each
 *       incoming triple.</li>
 *   <li>A {@code QueryResultListener} (here a lambda) receives each result row as a
 *       {@code Map} of variable name → value.</li>
 *   <li>The engine is {@link AutoCloseable}; try-with-resources stops it cleanly.</li>
 * </ul>
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.HelloCqels}
 */
public class HelloCqels {

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("hello-cqels")
                .withMemoryStore()
                .build()) {

            // A named stream we will push sensor readings into.
            DataStream sensors = engine.createStream("Sensors");

            // Continuous query: alert whenever a reading exceeds 30 degrees.
            String query = """
                    PREFIX ex: <http://example.org/>
                    REGISTER QUERY HighTemperature AS
                    SELECT ?sensor ?temp
                    FROM STREAM Sensors [NOW]
                    WHERE {
                      STREAM Sensors { ?sensor ex:temperature ?temp . }
                      FILTER(?temp > 30)
                    }
                    """;

            // Each matching row is delivered to this listener as a Map<String, Object>.
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  ALERT  high temperature -> " + row));

            engine.start();
            System.out.println("Engine started. Pushing 10 readings; alerts fire for temp > 30.\n");

            double[] readings = {21.5, 28.0, 31.2, 19.8, 35.6, 24.1, 30.5, 41.0, 26.3, 33.3};
            for (int i = 0; i < readings.length; i++) {
                String sensor = "http://example.org/sensor/" + (i % 3);
                System.out.printf("push: sensor%d = %.1f%n", i % 3, readings[i]);
                sensors.push(sensor, "http://example.org/temperature", readings[i]);
                Thread.sleep(300);
            }

            Thread.sleep(500); // let the last results flush
        }
        System.out.println("\nDone.");
    }
}
