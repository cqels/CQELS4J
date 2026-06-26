package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

/**
 * Example — directional (LARS-style) window with an emission policy.
 *
 * <p>Beyond backward-looking windows, CQELS-QL supports <em>directional</em> windows. A
 * {@code [FUTURE 2s …]} window anchored at an observation covers the next 2 seconds, and
 * {@code EMIT EARLY_AND_FINAL} reports both running and final results. Here: how many observations
 * each sensor makes in the forward window.
 *
 * <p>Uses explicit event timestamps ({@link Brewery#pushObservationAt}) so the directional window
 * is deterministic.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.DirectionalWindow}
 */
public class DirectionalWindow {

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("directional-window")
                .withMemoryStore()
                .build()) {

            DataStream fermentation = engine.createStream("Fermentation");

            String query = Brewery.PREFIXES + """
                    REGISTER QUERY ForwardCounts AS
                    SELECT ?sensor (COUNT(*) AS ?c)
                    FROM STREAM Fermentation [FUTURE 2s EMIT EARLY_AND_FINAL]
                    WHERE { STREAM Fermentation { ?obs sosa:madeBySensor ?sensor . } }
                    GROUP BY ?sensor
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  [FUTURE 2s] -> " + row));

            engine.start();
            System.out.println("Engine started. Forward 2s window, running + final counts per sensor.\n");

            // Anchor at t0; observations at +1000/+1500/+2000 ms, then a later one closes the window.
            Brewery.pushObservationAt(fermentation, Brewery.SENSOR_T1, Brewery.TANK1, Brewery.TEMPERATURE, 21.0, 1000L);
            Brewery.pushObservationAt(fermentation, Brewery.SENSOR_T2, Brewery.TANK2, Brewery.TEMPERATURE, 22.0, 1500L);
            Brewery.pushObservationAt(fermentation, Brewery.SENSOR_T1, Brewery.TANK1, Brewery.TEMPERATURE, 23.0, 2000L);
            Thread.sleep(300);
            Brewery.pushObservationAt(fermentation, Brewery.SENSOR_T3, Brewery.TANK3, Brewery.TEMPERATURE, 24.0, 4000L);
            Thread.sleep(600);
        }
        System.out.println("\nDone.");
    }
}
