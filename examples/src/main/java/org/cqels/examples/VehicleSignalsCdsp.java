package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

import java.util.Random;

/**
 * Example — the flagship VSS scenario: per-vehicle speeding detection (GROUP BY + HAVING).
 *
 * <p>The fleet's defining query: over a 5-second window, compute each vehicle's top and average
 * speed from its {@code vss:Speed} observations and report only those exceeding a limit, via
 * {@code GROUP BY ?vehicle} + {@code HAVING}. This is the COVESA VSS use case that motivated the
 * whole fleet domain (cf. the COVESA CDSP), expressed over SOSA-wrapped VSS observations.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.VehicleSignalsCdsp}
 */
public class VehicleSignalsCdsp {

    private static final Random RANDOM = new Random(42);

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("vehicle-signals-cdsp")
                .withMemoryStore()
                .build()) {

            DataStream telemetry = engine.createStream("Telemetry");

            String query = Fleet.PREFIXES + """
                    REGISTER QUERY Speeding AS
                    SELECT ?vehicle (MAX(?kmh) AS ?topSpeed) (AVG(?kmh) AS ?avgSpeed) (COUNT(*) AS ?samples)
                    FROM STREAM Telemetry [RANGE 5s]
                    WHERE {
                      STREAM Telemetry {
                        ?obs sosa:observedProperty vss:Speed .
                        ?obs sosa:hasFeatureOfInterest ?vehicle .
                        ?obs sosa:hasSimpleResult ?kmh .
                      }
                    }
                    GROUP BY ?vehicle
                    HAVING(?topSpeed > 120)
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  SPEEDING -> " + row));

            engine.start();
            System.out.println("Engine started. Per-vehicle speeding (top speed > 120 km/h) each 5s.\n");

            // Three EVs; EV-9TZ runs fast enough to trip the HAVING filter.
            String[][] fleet = {
                    {Fleet.SENSOR_EV1, Fleet.EV1}, {Fleet.SENSOR_EV2, Fleet.EV2}, {Fleet.SENSOR_EV3, Fleet.EV3}};
            double[] base = {90.0, 105.0, 135.0};
            for (int i = 0; i < 60; i++) {
                int v = i % fleet.length;
                double kmh = base[v] + (RANDOM.nextDouble() - 0.5) * 20;
                Fleet.pushObservation(telemetry, fleet[v][0], fleet[v][1], Fleet.SPEED, kmh);
                Thread.sleep(80);
            }
            Thread.sleep(1000);
        }
        System.out.println("\nDone.");
    }
}
