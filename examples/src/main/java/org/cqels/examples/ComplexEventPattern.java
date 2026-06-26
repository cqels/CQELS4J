package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.engine.cep.PatternMatch;
import org.cqels.stream.StreamElement;

/**
 * Example — Complex Event Processing: declarative {@code FILTER(SEQ(...))} sequence detection.
 *
 * <p>Beyond windows and aggregates, CQELS-QL detects <em>ordered</em> event sequences. A classic
 * fleet-safety pattern is road-rage: a sharp speed <em>drop</em> <strong>then</strong> a sharp speed
 * <em>spike</em> within a window — {@code FILTER(SEQ(?drop ; ?spike))}. Order matters: a spike then a
 * drop does not match.
 *
 * <p>The drop/spike events are derived upstream (e.g. by a windowed delta query) and emitted to the
 * stream; this demo emits them directly via {@link Fleet#pushDrivingEvent}.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.ComplexEventPattern}
 */
public class ComplexEventPattern {

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("complex-event-pattern")
                .withMemoryStore()
                .build()) {

            DataStream events = engine.createStream("Events");

            String query = Fleet.PREFIXES + """
                    REGISTER QUERY RoadRage AS
                    SELECT ?e1
                    FROM STREAM Events [RANGE 30s]
                    WHERE {
                      ?e1 fleet:event fleet:SpeedDropEvent .
                      ?e2 fleet:event fleet:SpeedSpikeEvent .
                      FILTER(SEQ(?e1 ; ?e2))
                    }
                    """;
            engine.registerCepQuery(query, (PatternMatch<StreamElement> match) ->
                    System.out.println("  ROAD RAGE (speed drop then spike) -> " + match));

            engine.start();
            System.out.println("Watching for: a sharp speed drop, then a sharp speed spike.\n");

            // Drop -> Spike : matches.
            System.out.println("push: SpeedDropEvent");
            Fleet.pushDrivingEvent(events, Fleet.SPEED_DROP);
            Thread.sleep(300);
            System.out.println("push: SpeedSpikeEvent");
            Fleet.pushDrivingEvent(events, Fleet.SPEED_SPIKE);
            Thread.sleep(1000);
        }
        System.out.println("\nDone.");
    }
}
