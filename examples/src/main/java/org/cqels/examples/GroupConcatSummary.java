package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

import java.util.Random;

/**
 * Example 13 — String aggregation with GROUP_CONCAT.
 *
 * <p>Over each 3-second window, summarise sales per product: a count plus the distinct-ish
 * list of regions the product sold in, concatenated with {@code GROUP_CONCAT(?region;
 * SEPARATOR=", ")}.
 *
 * <p>Key idea: {@code GROUP_CONCAT(?v; SEPARATOR="…")} collapses a group's values into one
 * string (default separator is {@code ","}). Note: {@code ORDER BY} / {@code LIMIT} are part
 * of CQELS-QL (see the spec) but are not exercised here — over a streaming windowed
 * aggregate the engine emits per-group rows rather than a single ranked, truncated result
 * set.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.GroupConcatSummary}
 */
public class GroupConcatSummary {

    private static final String EX = "http://example.org/";
    private static final Random RANDOM = new Random(5);
    private static final String[] PRODUCTS = {"Widget", "Gadget", "Gizmo"};
    private static final String[] REGIONS = {"NY", "LA", "SF", "CHI"};

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("group-concat-summary")
                .withMemoryStore()
                .build()) {

            DataStream sales = engine.createStream("Sales");

            String query = """
                    PREFIX ex: <http://example.org/>
                    REGISTER QUERY RegionalSummary AS
                    SELECT ?product (COUNT(*) AS ?sales) (GROUP_CONCAT(?region; SEPARATOR=", ") AS ?regions)
                    FROM STREAM Sales [RANGE 3s]
                    WHERE { STREAM Sales { ?product ex:soldIn ?region . } }
                    GROUP BY ?product
                    """;

            engine.registerCqelsQuery(query, row ->
                    System.out.println("  summary -> " + row));

            engine.start();
            System.out.println("Engine started. Per-3s window: sales count + regions per product.\n");

            for (int i = 0; i < 30; i++) {
                String product = PRODUCTS[RANDOM.nextInt(PRODUCTS.length)];
                String region = REGIONS[RANDOM.nextInt(REGIONS.length)];
                sales.push(EX + product, EX + "soldIn", region);
                Thread.sleep(150);
            }
            Thread.sleep(1000);
        }
        System.out.println("\nDone.");
    }
}
