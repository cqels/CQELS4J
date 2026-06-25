package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.engine.cep.PatternMatch;
import org.cqels.stream.StreamElement;

/**
 * Example 4 — Complex Event Processing (CEP): detect a temporal sequence.
 *
 * <p>CQELS-QL expresses Complex Event Processing declaratively with
 * {@code FILTER(SEQ(...))}: the engine compiles the pattern into an automaton and
 * reports a match whenever the named events occur <em>in order</em> within the
 * window. Here we detect an overheat alert followed by a stall alert within 30
 * seconds — a classic "this then that" fault signature.
 *
 * <p>Key ideas:
 * <ul>
 *   <li>{@code FILTER(SEQ(?e1; ?e2))} — temporal sequence: {@code ?e1} then {@code ?e2}.</li>
 *   <li>CEP queries are registered with {@code registerCepQuery(...)} and deliver a
 *       {@link PatternMatch} (the matched event sequence) rather than a binding row.</li>
 *   <li>Sequence operators also include quantifiers ({@code ?e+}, {@code ?e{2,5}}) and
 *       negation ({@code NOT ?e}).</li>
 *   <li>To require both events from the <em>same</em> entity, give them a shared
 *       variable and add a cross-event guard, e.g.
 *       {@code ?e1 ex:machine ?m . ?e2 ex:machine ?m . FILTER(?m1 = ?m2)}.</li>
 * </ul>
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.ComplexEventPattern}
 */
public class ComplexEventPattern {

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("complex-event-pattern")
                .withMemoryStore()
                .build()) {

            DataStream signals = engine.createStream("MachineSignals");

            String query = """
                    PREFIX ex: <http://example.org/>
                    REGISTER QUERY OverheatThenStall AS
                    SELECT ?e1 ?e2
                    FROM STREAM MachineSignals [RANGE 30s]
                    WHERE {
                      ?e1 ex:hasAlert ex:OverheatAlert .
                      ?e2 ex:hasAlert ex:StallAlert .
                      FILTER(SEQ(?e1; ?e2))
                    }
                    """;

            engine.registerCepQuery(query, (PatternMatch<StreamElement> match) ->
                    System.out.println("  PATTERN MATCHED  overheat -> stall (" + match.size() + " events): " + match));

            engine.start();
            System.out.println("Engine started. Watching for: OverheatAlert followed by StallAlert.\n");

            // Sequence 1: overheat THEN stall -> should match.
            System.out.println("push: machine/1 OverheatAlert");
            signals.pushTriple("http://example.org/machine/1", "http://example.org/hasAlert",
                    "http://example.org/OverheatAlert");
            Thread.sleep(400);
            System.out.println("push: machine/1 StallAlert");
            signals.pushTriple("http://example.org/machine/1", "http://example.org/hasAlert",
                    "http://example.org/StallAlert");

            Thread.sleep(1500); // give the matcher time to emit
        }
        System.out.println("\nDone.");
    }
}
