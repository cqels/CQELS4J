package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

/**
 * Example 1 — the minimal continuous query: a low-battery alert.
 *
 * <p>An {@code [NOW]} window evaluates the query once per arriving element. Here each element is a
 * battery state-of-charge {@code sosa:Observation}; the {@code FILTER} raises an alert whenever an
 * EV's SoC drops below 20 %.
 *
 * <p>This is the smallest possible CQELS-QL program — one stream, one window, one filter — over the
 * shared fleet world (see {@link Fleet}). {@code [NOW]} matches a single triple pattern, so the demo
 * streams only SoC readings.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.HelloCqels}
 */
public class HelloCqels {

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("hello-cqels")
                .withMemoryStore()
                .build()) {

            DataStream telemetry = engine.createStream("Telemetry");

            String query = Fleet.PREFIXES + """
                    REGISTER QUERY LowBattery AS
                    SELECT ?obs ?soc
                    FROM STREAM Telemetry [NOW]
                    WHERE { STREAM Telemetry { ?obs sosa:hasSimpleResult ?soc . } FILTER(?soc < 20) }
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  LOW BATTERY -> " + row));

            engine.start();
            System.out.println("Engine started. Alerting on battery state-of-charge below 20 %.\n");

            // Stream a few SoC readings; two dip below the 20 % threshold.
            String[] sensors = {Fleet.SENSOR_EV1, Fleet.SENSOR_EV2, Fleet.SENSOR_EV3};
            String[] vehicles = {Fleet.EV1, Fleet.EV2, Fleet.EV3};
            double[] soc = {64.0, 18.5, 41.0, 12.0, 27.5};
            for (int i = 0; i < soc.length; i++) {
                int v = i % vehicles.length;
                System.out.printf("push: %s SoC = %.1f %%%n", vehicles[v].substring(Fleet.EX.length()), soc[i]);
                Fleet.pushObservation(telemetry, sensors[v], vehicles[v], Fleet.SOC, soc[i]);
                Thread.sleep(200);
            }
            Thread.sleep(500);
        }
        System.out.println("\nDone.");
    }
}
