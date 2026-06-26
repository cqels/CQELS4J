package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

/**
 * Example — directional (LARS-style) window with an emission policy.
 *
 * <p>Beyond backward-looking windows, CQELS-QL supports <em>directional</em> windows. A
 * {@code [FUTURE 2s …]} window anchored at an observation covers the next 2 seconds, and
 * {@code EMIT EARLY_AND_FINAL} reports both running and final results — here, how many readings each
 * vehicle produces in the forward window during a V2G charging burst (the demo streams charge-power
 * readings, so the count is of charge readings).
 *
 * <p>Uses explicit event timestamps ({@link Fleet#pushObservationAt}) so the directional window is
 * deterministic. Directional ({@code FUTURE}/centered) windows execute as single-pattern windowed
 * aggregates, so this counts the observation per vehicle rather than joining the signal type.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.DirectionalWindow}
 */
public class DirectionalWindow {

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("directional-window")
                .withMemoryStore()
                .build()) {

            DataStream telemetry = engine.createStream("Telemetry");

            String query = Fleet.PREFIXES + """
                    REGISTER QUERY ForwardCharge AS
                    SELECT ?vehicle (COUNT(*) AS ?c)
                    FROM STREAM Telemetry [FUTURE 2s EMIT EARLY_AND_FINAL]
                    WHERE { STREAM Telemetry { ?obs sosa:hasFeatureOfInterest ?vehicle . } }
                    GROUP BY ?vehicle
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  [FUTURE 2s] -> " + row));

            engine.start();
            System.out.println("Engine started. Forward 2s window, running + final charge-reading counts.\n");

            // Anchor at t0; charge readings at +1000/+1500/+2000 ms, then a later one closes the window.
            Fleet.pushObservationAt(telemetry, Fleet.SENSOR_EV1, Fleet.EV1, Fleet.CHARGE_POWER, 7400.0, 1000L);
            Fleet.pushObservationAt(telemetry, Fleet.SENSOR_EV2, Fleet.EV2, Fleet.CHARGE_POWER, 11000.0, 1500L);
            Fleet.pushObservationAt(telemetry, Fleet.SENSOR_EV1, Fleet.EV1, Fleet.CHARGE_POWER, 7300.0, 2000L);
            Thread.sleep(300);
            Fleet.pushObservationAt(telemetry, Fleet.SENSOR_EV3, Fleet.EV3, Fleet.CHARGE_POWER, 3600.0, 4000L);
            Thread.sleep(600);
        }
        System.out.println("\nDone.");
    }
}
