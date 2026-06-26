package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

import java.util.Random;

/**
 * Example 13 — string aggregation with {@code GROUP_CONCAT}.
 *
 * <p>Over each 3-second window, summarise activity per tank: how many observations arrived and the
 * list of observed quantity-kinds ({@code qk:Temperature} / {@code qk:RelativeHumidity}),
 * concatenated with {@code GROUP_CONCAT(?qk; SEPARATOR=", ")}.
 *
 * <p>{@code GROUP_CONCAT} collapses a group's values into one string and keeps repeats (CQELS-QL's
 * {@code GROUP_CONCAT} takes only the optional {@code SEPARATOR} — there is no per-aggregate
 * {@code DISTINCT}). {@code ORDER BY}/{@code LIMIT} are part of CQELS-QL but over a streaming
 * windowed aggregate the engine emits per-group rows (see the spec).
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.GroupConcatSummary}
 */
public class GroupConcatSummary {

    private static final Random RANDOM = new Random(5);

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("group-concat-summary")
                .withMemoryStore()
                .build()) {

            DataStream observations = engine.createStream("Observations");

            String query = Brewery.PREFIXES + """
                    REGISTER QUERY TankSummary AS
                    SELECT ?tank (COUNT(*) AS ?readings) (GROUP_CONCAT(?qk; SEPARATOR=", ") AS ?kinds)
                    FROM STREAM Observations [RANGE 3s]
                    WHERE {
                      STREAM Observations {
                        ?obs sosa:hasFeatureOfInterest ?tank .
                        ?obs sosa:observedProperty ?qk .
                      }
                    }
                    GROUP BY ?tank
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  tank summary -> " + row));

            engine.start();
            System.out.println("Engine started. Per-3s window: reading count + observed kinds per tank.\n");

            String[][] s = {
                    {Brewery.SENSOR_T1, Brewery.TANK1},
                    {Brewery.SENSOR_T2, Brewery.TANK2},
                    {Brewery.SENSOR_T3, Brewery.TANK3}};
            for (int i = 0; i < 30; i++) {
                String[] sensor = s[RANDOM.nextInt(s.length)];
                boolean temp = RANDOM.nextBoolean();
                Brewery.pushObservation(observations, sensor[0], sensor[1],
                        temp ? Brewery.TEMPERATURE : Brewery.REL_HUMIDITY,
                        temp ? 18 + RANDOM.nextDouble() * 12 : 40 + RANDOM.nextDouble() * 20);
                Thread.sleep(150);
            }
            Thread.sleep(1000);
        }
        System.out.println("\nDone.");
    }
}
