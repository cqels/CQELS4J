package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.engine.cep.PatternMatch;
import org.cqels.stream.StreamElement;

/**
 * Example — Complex Event Processing: declarative {@code FILTER(SEQ(...))} sequence detection.
 *
 * <p>Beyond windows and aggregates, CQELS-QL detects <em>ordered</em> event sequences. In the
 * brewery, a fermentation incident is an overheat alert <strong>then</strong> a foaming alert from
 * a tank, within a window: {@code FILTER(SEQ(?overheat ; ?foaming))}. Order matters — foaming then
 * overheating does not match.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.ComplexEventPattern}
 */
public class ComplexEventPattern {

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("complex-event-pattern")
                .withMemoryStore()
                .build()) {

            DataStream alerts = engine.createStream("Alerts");

            String query = Brewery.PREFIXES + """
                    REGISTER QUERY Incident AS
                    SELECT ?e1
                    FROM STREAM Alerts [RANGE 30s]
                    WHERE {
                      ?e1 ex:alert ex:OverheatAlert .
                      ?e2 ex:alert ex:FoamingAlert .
                      FILTER(SEQ(?e1 ; ?e2))
                    }
                    """;
            engine.registerCepQuery(query, (PatternMatch<StreamElement> match) ->
                    System.out.println("  INCIDENT (overheat then foaming) -> " + match));

            engine.start();
            System.out.println("Watching for: an overheat alert, then a foaming alert.\n");

            // Overheat -> Foaming : matches.
            System.out.println("push: OverheatAlert");
            Brewery.pushAlert(alerts, Brewery.OVERHEAT_ALERT);
            Thread.sleep(300);
            System.out.println("push: FoamingAlert");
            Brewery.pushAlert(alerts, Brewery.FOAMING_ALERT);
            Thread.sleep(1000);
        }
        System.out.println("\nDone.");
    }
}
