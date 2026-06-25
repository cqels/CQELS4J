package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

/**
 * Example 7 — Count-based windows ({@code [TRIPLES N]}).
 *
 * <p>Not every window is time-based. {@code [TRIPLES N]} keeps the most recent N stream
 * elements and re-evaluates as they arrive — useful when you care about "the last N
 * events" rather than "the last N seconds". Here we count page views per user over the
 * last 8 views.
 *
 * <p>(Aggregates are computed per group, so a {@code GROUP BY} is required — see the
 * language spec.)
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.CountWindow}
 */
public class CountWindow {

    private static final String EX = "http://example.org/";

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("count-window")
                .withMemoryStore()
                .build()) {

            DataStream views = engine.createStream("PageViews");

            String query = """
                    PREFIX ex: <http://example.org/>
                    REGISTER QUERY ViewsPerUser AS
                    SELECT ?user (COUNT(*) AS ?views)
                    FROM STREAM PageViews [TRIPLES 8]
                    WHERE { STREAM PageViews { ?user ex:viewed ?page . } }
                    GROUP BY ?user
                    """;

            engine.registerCqelsQuery(query, row ->
                    System.out.println("  last-8-views -> " + row));

            engine.start();
            System.out.println("Engine started. Count-based window over the last 8 page views.\n");

            String[] users = {"alice", "alice", "bob", "alice", "carol", "bob", "alice", "carol", "bob", "alice"};
            for (int i = 0; i < users.length; i++) {
                System.out.println("push: " + users[i] + " viewed page" + i);
                views.pushTriple(EX + "user/" + users[i], EX + "viewed", EX + "page/" + i);
                Thread.sleep(150);
            }
            Thread.sleep(500);
        }
        System.out.println("\nDone.");
    }
}
