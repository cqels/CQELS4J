package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

import java.util.Random;

/**
 * Example 3 — Sliding windows for continuous trends.
 *
 * <p>A sliding window ({@code [SLIDE 4s STEP 2s]}) keeps a 4-second view of the
 * stream and re-emits every 2 seconds, so consecutive results overlap. This is the
 * shape you want for moving averages and live dashboards, where each update should
 * reflect the recent past rather than a disjoint bucket.
 *
 * <p>Here we track a moving price summary per instrument: each 4-second window emits,
 * for every symbol, the trade count and the average / lowest / highest price, refreshed
 * every 2 seconds.
 *
 * <p>Key ideas:
 * <ul>
 *   <li>{@code [SLIDE Ws STEP Ss]} — a W-second window that advances every S seconds;
 *       successive firings overlap by {@code W - S}.</li>
 *   <li>Aggregates are applied per group — {@code GROUP BY ?symbol} yields one row per
 *       instrument per firing.</li>
 * </ul>
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.SlidingWindowTrends}
 */
public class SlidingWindowTrends {

    private static final Random RANDOM = new Random(7);
    private static final String[] SYMBOLS = {"ACME", "GLOBEX", "INITECH"};

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("sliding-window-trends")
                .withMemoryStore()
                .build()) {

            DataStream trades = engine.createStream("Trades");

            String query = """
                    PREFIX ex: <http://example.org/>
                    REGISTER QUERY PriceTrend AS
                    SELECT ?symbol (COUNT(*) AS ?trades) (AVG(?price) AS ?avgPrice) (MIN(?price) AS ?low) (MAX(?price) AS ?high)
                    FROM STREAM Trades [SLIDE 4s STEP 2s]
                    WHERE {
                      STREAM Trades { ?symbol ex:price ?price . }
                    }
                    GROUP BY ?symbol
                    """;

            engine.registerCqelsQuery(query, row ->
                    System.out.println("  trailing-4s trend -> " + row));

            engine.start();
            System.out.println("Engine started. A 4s window re-emits every 2s, grouped by symbol.\n");

            double[] price = {100.0, 250.0, 40.0};
            for (int i = 0; i < 60; i++) {
                int s = i % SYMBOLS.length;
                price[s] += (RANDOM.nextDouble() - 0.5) * 4; // random walk per symbol
                trades.push("http://example.org/" + SYMBOLS[s], "http://example.org/price", price[s]);
                Thread.sleep(200);
            }

            Thread.sleep(1000);
        }
        System.out.println("\nDone.");
    }
}
