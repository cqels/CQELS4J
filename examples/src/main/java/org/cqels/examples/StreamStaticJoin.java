package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;

/**
 * Example 9 — Linked Streams: joining a stream against a static background graph.
 *
 * <p>This is the idea CQELS is named for. Triple patterns <em>inside</em> a
 * {@code STREAM { … }} block match the live stream; patterns <em>outside</em> it are
 * resolved against a static RDF graph (a lookup join). Each stream element is enriched
 * with background knowledge — here, a sensor reading is joined to the sensor's room and
 * floor from a seeded catalogue.
 *
 * <p>Key ideas:
 * <ul>
 *   <li>Seed the static data via {@code engine.getRepository().getConnection()} before
 *       {@code start()}.</li>
 *   <li>Patterns <em>outside</em> {@code STREAM { }} are matched against the engine's
 *       repository (the static side); patterns <em>inside</em> it match the live stream.</li>
 * </ul>
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.StreamStaticJoin}
 */
public class StreamStaticJoin {

    private static final String EX = "http://example.org/";

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("stream-static-join")
                .withMemoryStore()
                .build()) {

            // 1. Seed the static catalogue (sensor -> room, floor) BEFORE start().
            ValueFactory vf = SimpleValueFactory.getInstance();
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                for (int s = 0; s < 3; s++) {
                    conn.add(vf.createIRI(EX + "sensor/" + s), vf.createIRI(EX + "room"),
                            vf.createLiteral("Room-" + (char) ('A' + s)));
                    conn.add(vf.createIRI(EX + "sensor/" + s), vf.createIRI(EX + "floor"),
                            vf.createLiteral((long) (s % 2 + 1)));
                }
            }

            DataStream readings = engine.createStream("Readings");

            String query = """
                    PREFIX ex: <http://example.org/>
                    REGISTER QUERY EnrichedReadings AS
                    SELECT ?sensor ?temp ?room ?floor
                    FROM STREAM Readings [NOW]
                    WHERE {
                      STREAM Readings { ?sensor ex:temperature ?temp . }
                      ?sensor ex:room ?room ;
                              ex:floor ?floor .
                    }
                    """;

            engine.registerCqelsQuery(query, row ->
                    System.out.println("  enriched -> " + row));

            engine.start();
            System.out.println("Engine started. Each reading is joined to its sensor's room/floor.\n");

            double[] temps = {21.4, 27.9, 30.1, 19.8, 33.2};
            for (int i = 0; i < temps.length; i++) {
                String sensor = EX + "sensor/" + (i % 3);
                System.out.printf("push: sensor%d = %.1f%n", i % 3, temps[i]);
                readings.push(sensor, EX + "temperature", temps[i]);
                Thread.sleep(250);
            }
            Thread.sleep(500);
        }
        System.out.println("\nDone.");
    }
}
