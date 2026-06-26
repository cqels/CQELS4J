package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

/**
 * Example 7 — count-based windows ({@code [TRIPLES N]}).
 *
 * <p>Not every window is time-based. {@code [TRIPLES N]} keeps the most recent N stream triples and
 * re-evaluates as they arrive — "the last N readings" rather than "the last N seconds". Here we count
 * observations per vehicle over the most recent triples.
 *
 * <p>(The window counts stream <em>triples</em>; each observation contributes five, so size it
 * accordingly — {@code [TRIPLES 30]} holds roughly the last 6 observations and the earliest age out.)
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.CountWindow}
 */
public class CountWindow {

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("count-window")
                .withMemoryStore()
                .build()) {

            DataStream telemetry = engine.createStream("Telemetry");

            String query = Fleet.PREFIXES + """
                    REGISTER QUERY ReadingsPerVehicle AS
                    SELECT ?vehicle (COUNT(*) AS ?readings)
                    FROM STREAM Telemetry [TRIPLES 30]
                    WHERE { STREAM Telemetry { ?obs sosa:hasFeatureOfInterest ?vehicle . } }
                    GROUP BY ?vehicle
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  recent-readings -> " + row));

            engine.start();
            System.out.println("Engine started. Observations per vehicle over the most recent triples.\n");

            String[][] order = {
                    {Fleet.SENSOR_EV1, Fleet.EV1}, {Fleet.SENSOR_EV1, Fleet.EV1},
                    {Fleet.SENSOR_EV2, Fleet.EV2}, {Fleet.SENSOR_EV1, Fleet.EV1},
                    {Fleet.SENSOR_EV3, Fleet.EV3}, {Fleet.SENSOR_EV2, Fleet.EV2},
                    {Fleet.SENSOR_EV1, Fleet.EV1}, {Fleet.SENSOR_EV3, Fleet.EV3}};
            for (int i = 0; i < order.length; i++) {
                System.out.println("push: reading from " + order[i][1].substring(Fleet.EX.length()));
                Fleet.pushObservation(telemetry, order[i][0], order[i][1], Fleet.SPEED, 50 + i);
                Thread.sleep(150);
            }
            Thread.sleep(500);
        }
        System.out.println("\nDone.");
    }
}
