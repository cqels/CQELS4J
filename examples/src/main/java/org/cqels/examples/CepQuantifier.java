package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.engine.cep.PatternMatch;
import org.cqels.stream.StreamElement;

/**
 * Example 11 — CEP with a quantifier (one-or-more).
 *
 * <p>Beyond a fixed sequence ({@code SEQ(?a ; ?b)}), CQELS-QL CEP supports quantifiers on a
 * step. Here {@code ?retry+} matches <em>one or more</em> retry events between the initial
 * critical alert and the eventual failure — an escalation signature of variable length.
 *
 * <p>Quantifiers: {@code ?e+} (1+), {@code ?e*} (0+), {@code ?e?} (0/1), {@code ?e{n}},
 * {@code ?e{m,n}}. A step can also be negated with {@code NOT ?e} ("must not occur between
 * the neighbours") — see {@code CQELS-QL_SPEC.md} for the full CEP grammar.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.CepQuantifier}
 */
public class CepQuantifier {

    private static final String EX = "http://example.org/";

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("cep-quantifier")
                .withMemoryStore()
                .build()) {

            DataStream alerts = engine.createStream("Alerts");

            String query = """
                    PREFIX ex: <http://example.org/>
                    REGISTER QUERY Escalation AS
                    SELECT ?e1
                    FROM STREAM Alerts [RANGE 30s]
                    WHERE {
                      ?e1 ex:level ex:Critical .
                      ?e2 ex:level ex:Retry .
                      ?e3 ex:level ex:Failure .
                      FILTER(SEQ(?e1 ; ?e2+ ; ?e3))
                    }
                    """;

            engine.registerCepQuery(query, (PatternMatch<StreamElement> match) ->
                    System.out.println("  ESCALATION MATCHED (" + match.size() + " events): " + match));

            engine.start();
            System.out.println("Watching for: Critical, then one+ Retry, then Failure.\n");

            // Critical -> Retry -> Retry -> Failure : matches (?e2+ consumes both retries).
            for (String[] ev : new String[][]{
                    {"a1", "Critical"}, {"a2", "Retry"}, {"a3", "Retry"}, {"a4", "Failure"}}) {
                System.out.println("push: " + ev[1]);
                alerts.pushTriple(EX + "alert/" + ev[0], EX + "level", EX + ev[1]);
                Thread.sleep(300);
            }
            Thread.sleep(1500);
        }
        System.out.println("\nDone.");
    }
}
