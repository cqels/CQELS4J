package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.engine.cep.PatternMatch;
import org.cqels.stream.StreamElement;

/**
 * Example — CEP with a quantifier (one-or-more).
 *
 * <p>A CEP step can carry a quantifier. Here {@code ?pressure+} matches <em>one or more</em>
 * pressure-rise alerts between the initial overheat and the eventual foaming — a brewery escalation
 * of variable length: overheat → pressure rises… → foam over.
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

            DataStream alerts = engine.createStream("Alerts");

            String query = Brewery.PREFIXES + """
                    REGISTER QUERY Escalation AS
                    SELECT ?e1
                    FROM STREAM Alerts [RANGE 30s]
                    WHERE {
                      ?e1 ex:alert ex:OverheatAlert .
                      ?e2 ex:alert ex:PressureRiseAlert .
                      ?e3 ex:alert ex:FoamingAlert .
                      FILTER(SEQ(?e1 ; ?e2+ ; ?e3))
                    }
                    """;
            engine.registerCepQuery(query, (PatternMatch<StreamElement> match) ->
                    System.out.println("  ESCALATION MATCHED (" + match.size() + " events): " + match));

            engine.start();
            System.out.println("Watching for: overheat, then one+ pressure rises, then foaming.\n");

            // Overheat -> PressureRise -> PressureRise -> Foaming : matches (?e2+ consumes both rises).
            for (String alert : new String[]{
                    Brewery.OVERHEAT_ALERT, Brewery.PRESSURE_RISE_ALERT,
                    Brewery.PRESSURE_RISE_ALERT, Brewery.FOAMING_ALERT}) {
                System.out.println("push: " + alert.substring(Brewery.EX.length()));
                Brewery.pushAlert(alerts, alert);
                Thread.sleep(300);
            }
            Thread.sleep(1500);
        }
        System.out.println("\nDone.");
    }
}
