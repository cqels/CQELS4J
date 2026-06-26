package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

/**
 * Example — Linked Streams: joining the telemetry stream against the static fleet graph.
 *
 * <p>This is the idea CQELS is named for. Triple patterns <em>inside</em> a {@code STREAM { … }}
 * block match live observations; patterns <em>outside</em> it resolve against the static fleet graph
 * (see {@link Fleet#seedStatic}). Each speed reading is enriched with the assigned driver and the
 * vehicle's GTFS-style service route — joining live telemetry with mission context.
 *
 * <p>Uses a {@code [TRIPLES 10]} window so each observation (five triples) is comfortably co-resident
 * with its mates when the stream patterns are matched.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.StreamStaticJoin}
 */
public class StreamStaticJoin {

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("stream-static-join")
                .withMemoryStore()
                .build()) {

            // Seed the static graph (fleet membership, driver, service route) BEFORE start().
            Fleet.seedStatic(engine);

            DataStream telemetry = engine.createStream("Telemetry");

            String query = Fleet.PREFIXES + """
                    REGISTER QUERY EnrichedSpeed AS
                    SELECT ?vehicle ?kmh ?driver ?route
                    FROM STREAM Telemetry [TRIPLES 10]
                    WHERE {
                      STREAM Telemetry {
                        ?obs sosa:observedProperty vss:Speed .
                        ?obs sosa:hasFeatureOfInterest ?vehicle .
                        ?obs sosa:hasSimpleResult ?kmh .
                      }
                      ?vehicle fleet:assignedDriver ?d .
                      ?d fleet:name ?driver .
                      ?vehicle svc:route ?route .
                    }
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  enriched -> " + row));

            engine.start();
            System.out.println("Engine started. Each speed reading is joined to its driver and service route.\n");

            String[][] fleet = {
                    {Fleet.SENSOR_EV1, Fleet.EV1}, {Fleet.SENSOR_EV2, Fleet.EV2}, {Fleet.SENSOR_EV3, Fleet.EV3}};
            double[] kmh = {52.0, 64.0, 48.0};
            for (int i = 0; i < kmh.length; i++) {
                String[] ev = fleet[i];
                System.out.printf("push: %s speed = %.0f km/h%n", ev[1].substring(Fleet.EX.length()), kmh[i]);
                Fleet.pushObservation(telemetry, ev[0], ev[1], Fleet.SPEED, kmh[i]);
                Thread.sleep(300);
            }
            Thread.sleep(600);
        }
        System.out.println("\nDone.");
    }
}
