package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.engine.cep.PatternMatch;
import org.cqels.stream.StreamElement;

/**
 * Example — CEP with a quantifier (one-or-more).
 *
 * <p>A CEP step can carry a quantifier. Here the middle step {@code ?e2+} matches <em>one or more</em>
 * lane-weave events between an initial speed drop and an eventual speed spike — an impaired-driving
 * pattern of variable length: drop → weave… → spike.
 *
 * <p>Quantifiers: {@code ?e+} (1+), {@code ?e*} (0+), {@code ?e?} (0/1), {@code ?e{n}},
 * {@code ?e{m,n}}; a step can also be negated with {@code NOT ?e} (see {@code CQELS-QL_SPEC.md}).
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.CepQuantifier}
 */
public class CepQuantifier {

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("cep-quantifier")
                .withMemoryStore()
                .build()) {

            DataStream events = engine.createStream("Events");

            String query = Fleet.PREFIXES + """
                    REGISTER QUERY ImpairedDriving AS
                    SELECT ?e1
                    FROM STREAM Events [RANGE 30s]
                    WHERE {
                      ?e1 fleet:event fleet:SpeedDropEvent .
                      ?e2 fleet:event fleet:LaneWeaveEvent .
                      ?e3 fleet:event fleet:SpeedSpikeEvent .
                      FILTER(SEQ(?e1 ; ?e2+ ; ?e3))
                    }
                    """;
            engine.registerCepQuery(query, (PatternMatch<StreamElement> match) ->
                    System.out.println("  IMPAIRED DRIVING MATCHED (" + match.size() + " events): " + match));

            engine.start();
            System.out.println("Watching for: a speed drop, then one+ lane weaves, then a speed spike.\n");

            // Drop -> Weave -> Weave -> Spike : matches (?e2+ consumes both weaves).
            for (String ev : new String[]{
                    Fleet.SPEED_DROP, Fleet.LANE_WEAVE, Fleet.LANE_WEAVE, Fleet.SPEED_SPIKE}) {
                System.out.println("push: " + ev.substring(Fleet.FLEET.length()));
                Fleet.pushDrivingEvent(events, ev);
                Thread.sleep(300);
            }
            Thread.sleep(1500);
        }
        System.out.println("\nDone.");
    }
}
