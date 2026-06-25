package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/**
 * Example 8 — Directional (LARS) windows with an emission policy.
 *
 * <p>A distinctive CQELS feature: windows that look <em>forward</em> from an anchor.
 * {@code [FUTURE 2s ...]} groups the events in the 2 seconds after each anchor. Because a
 * forward window can only be complete once enough time has passed, results are emitted at
 * window close — and the {@code EMIT} policy controls what you see in between:
 * {@code ON_UPDATE} (running rows), {@code ON_CLOSE} (final only), or
 * {@code EARLY_AND_FINAL} (both).
 *
 * <p>Explicit event-time timestamps are used so the window boundaries are deterministic;
 * a trailing event past the window advances the watermark and closes it.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.DirectionalWindow}
 */
public class DirectionalWindow {

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("directional-window")
                .withMemoryStore()
                .build()) {

            DataStream events = engine.createStream("Events");

            String query = """
                    PREFIX ex: <http://example.org/>
                    REGISTER QUERY ForwardCountPerKey AS
                    SELECT ?k (COUNT(*) AS ?c)
                    FROM STREAM Events [FUTURE 2s EMIT EARLY_AND_FINAL]
                    WHERE { STREAM Events { ?k ex:value ?v . } }
                    GROUP BY ?k
                    """;

            engine.registerCqelsQuery(query, row ->
                    System.out.println("  [FUTURE 2s] -> " + row));

            engine.start();
            System.out.println("Engine started. Forward 2s window, EARLY_AND_FINAL emission.\n");

            // Anchor at t0 covers (t0, t0+2000]. Push with explicit event-time (ms);
            // explicit-timestamp push uses the IRI-typed overload.
            ValueFactory vf = SimpleValueFactory.getInstance();
            IRI value = vf.createIRI("http://example.org/value");
            events.push(vf.createIRI("http://example.org/a"), value, vf.createLiteral(1L), 1000L);
            events.push(vf.createIRI("http://example.org/b"), value, vf.createLiteral(1L), 1500L);
            events.push(vf.createIRI("http://example.org/a"), value, vf.createLiteral(1L), 2000L);
            Thread.sleep(300);
            // A later event advances the watermark past the window so it closes + finalizes.
            events.push(vf.createIRI("http://example.org/z"), value, vf.createLiteral(1L), 4000L);

            Thread.sleep(1500);
        }
        System.out.println("\nDone.");
    }
}
