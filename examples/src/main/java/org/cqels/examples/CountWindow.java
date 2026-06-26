package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

/**
 * Example 7 — count-based windows ({@code [TRIPLES N]}).
 *
 * <p>Not every window is time-based. {@code [TRIPLES N]} keeps the most recent N stream triples
 * and re-evaluates as they arrive — useful for "the last N readings" rather than "the last N
 * seconds". Here we count temperature observations per sensor over the most recent triples.
 *
 * <p>(The window counts stream <em>triples</em>; each observation contributes several, so size the
 * window accordingly.)
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.CountWindow}
 */
public class CountWindow {

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("count-window")
                .withMemoryStore()
                .build()) {

            DataStream fermentation = engine.createStream("Fermentation");

            String query = Brewery.PREFIXES + """
                    REGISTER QUERY ReadingsPerSensor AS
                    SELECT ?sensor (COUNT(*) AS ?readings)
                    FROM STREAM Fermentation [TRIPLES 30]
                    WHERE { STREAM Fermentation { ?obs sosa:madeBySensor ?sensor . } }
                    GROUP BY ?sensor
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  recent-readings -> " + row));

            engine.start();
            System.out.println("Engine started. Observations per sensor over the most recent triples.\n");

            String[][] order = {
                    {Brewery.SENSOR_T1, Brewery.TANK1}, {Brewery.SENSOR_T1, Brewery.TANK1},
                    {Brewery.SENSOR_T2, Brewery.TANK2}, {Brewery.SENSOR_T1, Brewery.TANK1},
                    {Brewery.SENSOR_T3, Brewery.TANK3}, {Brewery.SENSOR_T2, Brewery.TANK2},
                    {Brewery.SENSOR_T1, Brewery.TANK1}, {Brewery.SENSOR_T3, Brewery.TANK3}};
            for (int i = 0; i < order.length; i++) {
                System.out.println("push: observation from " + order[i][0].substring(Brewery.EX.length()));
                Brewery.pushObservation(fermentation, order[i][0], order[i][1],
                        Brewery.TEMPERATURE, 20 + i);
                Thread.sleep(150);
            }
            Thread.sleep(500);
        }
        System.out.println("\nDone.");
    }
}
