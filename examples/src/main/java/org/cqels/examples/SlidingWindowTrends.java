package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

import java.util.Random;

/**
 * Example 3 — sliding windows for moving trends.
 *
 * <p>A {@code [SLIDE 4s STEP 2s]} window keeps the trailing 4 seconds of battery readings, so
 * consecutive results overlap — handy for moving trends. Here: each vehicle's moving average / min /
 * max state-of-charge, useful for watching battery drain. (This alpha re-evaluates the sliding window
 * on each arriving reading rather than only at the 2-second step boundary — see the engine note in
 * {@code examples/README.md}.)
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

            DataStream telemetry = engine.createStream("Telemetry");

            String query = Fleet.PREFIXES + """
                    REGISTER QUERY SocTrend AS
                    SELECT ?vehicle (AVG(?soc) AS ?avgSoc) (MIN(?soc) AS ?minSoc) (MAX(?soc) AS ?maxSoc)
                    FROM STREAM Telemetry [SLIDE 4s STEP 2s]
                    WHERE {
                      STREAM Telemetry {
                        ?obs sosa:observedProperty vss:Powertrain.TractionBattery.StateOfCharge.Current .
                        ?obs sosa:hasFeatureOfInterest ?vehicle .
                        ?obs sosa:hasSimpleResult ?soc .
                      }
                    }
                    GROUP BY ?vehicle
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  trailing-4s SoC trend -> " + row));

            engine.start();
            System.out.println("Engine started. Moving avg/min/max battery SoC, re-emitted every 2s.\n");

            String[][] fleet = {
                    {Fleet.SENSOR_EV1, Fleet.EV1}, {Fleet.SENSOR_EV2, Fleet.EV2}};
            for (int i = 0; i < 24; i++) {
                String[] ev = fleet[RANDOM.nextInt(fleet.length)];
                Fleet.pushObservation(telemetry, ev[0], ev[1], Fleet.SOC, 20 + RANDOM.nextDouble() * 70);
                Thread.sleep(300);
            }
            Thread.sleep(1000);
        }
        System.out.println("\nDone.");
    }
}
