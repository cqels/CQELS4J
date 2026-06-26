package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

/**
 * Example — Linked Streams: joining the observation stream against a static background graph.
 *
 * <p>This is the idea CQELS is named for. Triple patterns <em>inside</em> a {@code STREAM { … }}
 * block match live observations; patterns <em>outside</em> it resolve against the static brewery
 * graph (see {@link Brewery#seedStatic}). Each temperature observation is enriched with the
 * sensor's tank and that tank's room — joining stream and background knowledge.
 *
 * <p>Uses a {@code [TRIPLES 5]} window so each observation (five triples) is joined as a unit.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.StreamStaticJoin}
 */
public class StreamStaticJoin {

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("stream-static-join")
                .withMemoryStore()
                .build()) {

            // Seed the static graph (sensor -> tank deployment, tank -> room) BEFORE start().
            Brewery.seedStatic(engine);

            DataStream fermentation = engine.createStream("Fermentation");

            String query = Brewery.PREFIXES + """
                    REGISTER QUERY EnrichedReadings AS
                    SELECT ?sensor ?temp ?tank ?room
                    FROM STREAM Fermentation [TRIPLES 5]
                    WHERE {
                      STREAM Fermentation {
                        ?obs sosa:madeBySensor ?sensor .
                        ?obs sosa:hasSimpleResult ?temp .
                      }
                      ?sensor sosa:hasFeatureOfInterest ?tank .
                      ?tank ex:room ?room .
                    }
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  enriched -> " + row));

            engine.start();
            System.out.println("Engine started. Each reading is joined to its tank and room.\n");

            String[][] s = {
                    {Brewery.SENSOR_T1, Brewery.TANK1},
                    {Brewery.SENSOR_T2, Brewery.TANK2},
                    {Brewery.SENSOR_T3, Brewery.TANK3}};
            double[] temps = {21.4, 27.9, 30.1, 19.8, 33.2};
            for (int i = 0; i < temps.length; i++) {
                String[] sensor = s[i % s.length];
                System.out.printf("push: %s temperature = %.1f °C%n",
                        sensor[1].substring(Brewery.EX.length()), temps[i]);
                Brewery.pushObservation(fermentation, sensor[0], sensor[1],
                        Brewery.TEMPERATURE, temps[i]);
                Thread.sleep(300);
            }
            Thread.sleep(600);
        }
        System.out.println("\nDone.");
    }
}
