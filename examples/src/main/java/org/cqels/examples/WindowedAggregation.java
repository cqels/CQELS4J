package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

import java.util.Random;

/**
 * Example 2 — tumbling windowed aggregation.
 *
 * <p>Over each 3-second window, compute per-vehicle speed statistics — average, peak, and sample
 * count. A {@code [RANGE 3s]} tumbling window joins each speed {@code sosa:Observation}'s
 * {@code observedProperty} ( = {@code vss:Speed}), {@code hasFeatureOfInterest} ( = the vehicle) and
 * {@code hasSimpleResult} ( = km/h), and {@code GROUP BY} aggregates per vehicle.
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

            DataStream telemetry = engine.createStream("Telemetry");

            String query = Fleet.PREFIXES + """
                    REGISTER QUERY PerVehicleSpeed AS
                    SELECT ?vehicle (AVG(?kmh) AS ?avgSpeed) (MAX(?kmh) AS ?peak) (COUNT(*) AS ?n)
                    FROM STREAM Telemetry [RANGE 3s]
                    WHERE {
                      STREAM Telemetry {
                        ?obs sosa:observedProperty vss:Speed .
                        ?obs sosa:hasFeatureOfInterest ?vehicle .
                        ?obs sosa:hasSimpleResult ?kmh .
                      }
                    }
                    GROUP BY ?vehicle
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  window stats -> " + row));

            engine.start();
            System.out.println("Engine started. Per-vehicle avg/peak speed each 3s.\n");

            String[][] fleet = {
                    {Fleet.SENSOR_EV1, Fleet.EV1}, {Fleet.SENSOR_EV2, Fleet.EV2}, {Fleet.SENSOR_EV3, Fleet.EV3}};
            for (int i = 0; i < 30; i++) {
                String[] ev = fleet[RANDOM.nextInt(fleet.length)];
                double kmh = 40 + RANDOM.nextDouble() * 60;   // 40–100 km/h
                Fleet.pushObservation(telemetry, ev[0], ev[1], Fleet.SPEED, kmh);
                Thread.sleep(150);
            }
            Thread.sleep(1000);
        }
        System.out.println("\nDone.");
    }
}
