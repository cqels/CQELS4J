package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

import java.util.Random;

/**
 * Example 6 — Connected-vehicle signals (CDSP / COVESA VSS).
 *
 * <p>In a COVESA Common Data Standard for the Practice (CDSP) / Vehicle Signal
 * Specification (VSS) setting, vehicles emit signal samples such as {@code vss:Speed}.
 * This example monitors a fleet: each 5-second window reports, per vehicle, the average
 * and top speed and the number of samples — and a {@code HAVING} clause keeps only the
 * vehicles that exceeded a speed limit in that window (a continuous "speeding" alert).
 *
 * <p>Key ideas:
 * <ul>
 *   <li>Domain RDF stream over the COVESA VSS namespace.</li>
 *   <li>{@code GROUP BY ?vehicle} with {@code AVG} / {@code MAX} / {@code COUNT}.</li>
 *   <li>{@code HAVING(?topSpeed > 120)} — post-aggregation filter on the SELECT alias,
 *       so only speeding vehicles are emitted.</li>
 * </ul>
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.VehicleSignalsCdsp}
 */
public class VehicleSignalsCdsp {

    private static final String VSS = "https://covesa.global/vss#";
    private static final Random RANDOM = new Random(23);

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("vehicle-signals-cdsp")
                .withMemoryStore()
                .build()) {

            DataStream speed = engine.createStream("VehicleSpeed");

            String query = """
                    PREFIX vss: <https://covesa.global/vss#>
                    REGISTER QUERY Speeding AS
                    SELECT ?vehicle (MAX(?kmh) AS ?topSpeed) (AVG(?kmh) AS ?avgSpeed) (COUNT(*) AS ?samples)
                    FROM STREAM VehicleSpeed [RANGE 5s]
                    WHERE {
                      STREAM VehicleSpeed { ?vehicle vss:Speed ?kmh . }
                    }
                    GROUP BY ?vehicle
                    HAVING(?topSpeed > 120)
                    """;

            engine.registerCqelsQuery(query, row ->
                    System.out.println("  SPEEDING -> " + row));

            engine.start();
            System.out.println("Engine started. Per-vehicle speed over 5s windows; only > 120 km/h reported.\n");

            // Three vehicles; one is biased fast so it trips the HAVING filter.
            double[] base = {90.0, 110.0, 135.0};
            for (int i = 0; i < 60; i++) {
                int v = i % base.length;
                double kmh = base[v] + (RANDOM.nextDouble() - 0.5) * 20;
                speed.push("https://example.org/vehicle/" + v, VSS + "Speed", kmh);
                Thread.sleep(200);
            }

            Thread.sleep(1000);
        }
        System.out.println("\nDone.");
    }
}
