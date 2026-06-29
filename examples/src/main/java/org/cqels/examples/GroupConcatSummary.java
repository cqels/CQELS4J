package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

import java.util.Random;

/**
 * Example 13 — string aggregation with {@code GROUP_CONCAT}.
 *
 * <p>Over each 3-second window, summarise activity per vehicle: how many observations arrived and the
 * list of observed VSS signals ({@code vss:Speed} / {@code vss:…StateOfCharge.Current} /
 * {@code vss:…Charging.PowerW}), concatenated with {@code GROUP_CONCAT(?signal; SEPARATOR=", ")}.
 *
 * <p>{@code GROUP_CONCAT} collapses a group's values into one string and keeps repeats (CQELS-QL's
 * {@code GROUP_CONCAT} takes only the optional {@code SEPARATOR} — there is no per-aggregate
 * {@code DISTINCT}).
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.GroupConcatSummary}
 */
public class GroupConcatSummary {

    private static final Random RANDOM = new Random(5);

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("group-concat-summary")
                .withMemoryStore()
                .build()) {

            DataStream telemetry = engine.createStream("Telemetry");

            String query = Fleet.PREFIXES + """
                    REGISTER QUERY VehicleSummary AS
                    SELECT ?vehicle (COUNT(*) AS ?readings) (GROUP_CONCAT(?signal; SEPARATOR=", ") AS ?signals)
                    FROM STREAM Telemetry [RANGE 3s]
                    WHERE {
                      STREAM Telemetry {
                        ?obs sosa:hasFeatureOfInterest ?vehicle .
                        ?obs sosa:observedProperty ?signal .
                      }
                    }
                    GROUP BY ?vehicle
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  vehicle summary -> " + row));

            engine.start();
            System.out.println("Engine started. Per-3s window: reading count + observed signals per vehicle.\n");

            String[] signals = {Fleet.SPEED, Fleet.SOC, Fleet.CHARGE_POWER};
            String[][] fleet = {
                    {Fleet.SENSOR_EV1, Fleet.EV1}, {Fleet.SENSOR_EV2, Fleet.EV2}, {Fleet.SENSOR_EV3, Fleet.EV3}};
            for (int i = 0; i < 30; i++) {
                String[] ev = fleet[RANDOM.nextInt(fleet.length)];
                Fleet.pushObservation(telemetry, ev[0], ev[1],
                        signals[RANDOM.nextInt(signals.length)], 10 + RANDOM.nextDouble() * 90);
                Thread.sleep(150);
            }
            Thread.sleep(1000);
        }
        System.out.println("\nDone.");
    }
}
