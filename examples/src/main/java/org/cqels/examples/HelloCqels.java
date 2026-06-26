package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

/**
 * Example 1 — the "hello world" of continuous queries.
 *
 * <p>Our running scenario (shared by every demo) is a <strong>smart brewery</strong>: fermentation
 * tanks monitored by IBS-TH2 sensors that emit W3C SOSA/SSN {@code sosa:Observation}s. Here the
 * minimal query raises an alert on every temperature reading above the fermentation ceiling
 * (28&nbsp;°C) — a {@code [NOW]} window (each event on its own) plus a {@code FILTER}.
 *
 * <p>See {@link Brewery} for the shared vocabulary and the observation schema.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.HelloCqels}
 */
public class HelloCqels {

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("hello-cqels")
                .withMemoryStore()
                .build()) {

            DataStream fermentation = engine.createStream("Fermentation");

            String query = Brewery.PREFIXES + """
                    REGISTER QUERY HighTemperature AS
                    SELECT ?obs ?temp
                    FROM STREAM Fermentation [NOW]
                    WHERE { STREAM Fermentation { ?obs sosa:hasSimpleResult ?temp . } FILTER(?temp > 28) }
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  ALERT high temperature -> " + row));

            engine.start();
            System.out.println("Engine started. Alerting on tank temperatures above 28 °C.\n");

            // Temperature observations from Tank 1's sensor; 30.1 and 31.5 breach the 28 °C ceiling.
            double[] temps = {21.4, 27.9, 30.1, 19.8, 31.5};
            for (double t : temps) {
                System.out.printf("push: Tank1 temperature = %.1f °C%n", t);
                Brewery.pushObservation(fermentation, Brewery.SENSOR_T1, Brewery.TANK1,
                        Brewery.TEMPERATURE, t);
                Thread.sleep(250);
            }
            Thread.sleep(500);
        }
        System.out.println("\nDone.");
    }
}
