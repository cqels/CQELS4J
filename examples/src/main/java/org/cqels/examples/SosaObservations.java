package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

import java.util.Random;

/**
 * Example — the anatomy of a {@code sosa:Observation} (W3C SOSA/SSN + VSSo + VSS).
 *
 * <p>Every reading in the fleet world is a full {@code sosa:Observation}: it is
 * {@code madeBySensor} an onboard sensor, has an {@code observedProperty} (a COVESA VSS signal such
 * as {@code vss:Speed} or {@code vss:…StateOfCharge.Current}, which VSSo types as an
 * {@code ObservableVehicleProperty}), is about a {@code hasFeatureOfInterest} (the vehicle, a
 * {@code vsso:Vehicle}), and carries a {@code hasSimpleResult} value.
 *
 * <p>This query joins those patterns and groups by <em>vehicle × observed property</em>, so the
 * {@code observedProperty} dimension keeps speed and battery readings apart — the value of wrapping
 * VSS signals as SOSA observations rather than flat triples.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.SosaObservations}
 */
public class SosaObservations {

    private static final Random RANDOM = new Random(11);

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("sosa-observations")
                .withMemoryStore()
                .build()) {

            DataStream telemetry = engine.createStream("Telemetry");

            String query = Fleet.PREFIXES + """
                    REGISTER QUERY PerSignalAvg AS
                    SELECT ?vehicle ?signal (AVG(?v) AS ?avgValue) (COUNT(*) AS ?n)
                    FROM STREAM Telemetry [RANGE 3s]
                    WHERE {
                      STREAM Telemetry {
                        ?obs sosa:hasFeatureOfInterest ?vehicle .
                        ?obs sosa:observedProperty ?signal .
                        ?obs sosa:hasSimpleResult ?v .
                      }
                    }
                    GROUP BY ?vehicle ?signal
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  per-signal avg -> " + row));

            engine.start();
            System.out.println("Engine started. Average value per (vehicle, observed VSS signal) each 3s.\n");

            String[][] fleet = {
                    {Fleet.SENSOR_EV1, Fleet.EV1}, {Fleet.SENSOR_EV2, Fleet.EV2}};
            for (int i = 0; i < 30; i++) {
                String[] ev = fleet[RANDOM.nextInt(fleet.length)];
                boolean speed = RANDOM.nextBoolean();
                Fleet.pushObservation(telemetry, ev[0], ev[1],
                        speed ? Fleet.SPEED : Fleet.SOC,
                        speed ? 40 + RANDOM.nextDouble() * 60 : 20 + RANDOM.nextDouble() * 70);
                Thread.sleep(150);
            }
            Thread.sleep(1000);
        }
        System.out.println("\nDone.");
    }
}
