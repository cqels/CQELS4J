package org.cqels.examples.cdsp;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.engine.cep.PatternMatch;
import org.cqels.examples.Fleet;
import org.cqels.stream.StreamElement;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Example — CEP: a three-event sequence with <em>cross-event correlation</em> filters over
 * <em>multi-triple (reified) events</em>.
 *
 * <p>The scenario: one subsystem alert recurring on a vehicle is routine maintenance noise.
 * Three <em>different</em> subsystem alerts — inverter over-temperature, brake wear, tire
 * pressure — from the <em>same</em> vehicle in quick succession suggest a correlated fault
 * cascade (one root cause stressing several systems), so the depot pulls that vehicle in.
 *
 * <p>CQELS-QL features shown:
 * <ul>
 *   <li><strong>Multi-triple events</strong> — each SEQ step {@code ?eN} is the subject of TWO
 *       triple patterns ({@code fleet:hasAlert} and {@code fleet:vehicle}). The step matches only
 *       when a single <em>atomic</em> stream element carries a consistent assignment for both, so
 *       every alert is pushed as one two-statement batch element (see {@link #pushAlert}).</li>
 *   <li><strong>Cross-event FILTERs</strong> — {@code STR()} inequality across the three alert-type
 *       bindings enforces distinctness, and {@code STR()} equality across the vehicle bindings pins
 *       all three events to one vehicle. These compile to guards evaluated once all participating
 *       events have matched.</li>
 *   <li>{@code FILTER(SEQ(?e1; ?e2; ?e3))} — the alerts must arrive in this order within the
 *       {@code [RANGE 15s]} window (the window doubles as the partial-match timeout).</li>
 * </ul>
 *
 * <p>The demo pushes a matching cascade on one vehicle, then the same three alert types spread
 * across two other vehicles — which must stay silent, because the same-vehicle guard can never
 * hold.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.cdsp.CorrelatedFaultCascade}
 */
public class CorrelatedFaultCascade {

    // Subsystem alert types local to this demo (three unrelated EV subsystems).
    private static final String INVERTER_OVER_TEMP = Fleet.FLEET + "InverterOverTempEvent";
    private static final String BRAKE_WEAR = Fleet.FLEET + "BrakeWearEvent";
    private static final String TIRE_PRESSURE = Fleet.FLEET + "TirePressureEvent";
    private static final String HAS_ALERT = Fleet.FLEET + "hasAlert";
    private static final String VEHICLE = Fleet.FLEET + "vehicle";

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final AtomicLong ALERT_SEQ = new AtomicLong();

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("correlated-fault-cascade")
                .withMemoryStore()
                .build()) {

            DataStream alerts = engine.createStream("Alerts");

            String query = Fleet.PREFIXES + """
                    REGISTER QUERY CorrelatedFaultCascade AS
                    SELECT ?e1 ?e2 ?e3
                    FROM STREAM Alerts [RANGE 15s]
                    WHERE {
                      ?e1 fleet:hasAlert ?alertType1 .
                      ?e1 fleet:vehicle ?v1 .
                      ?e2 fleet:hasAlert ?alertType2 .
                      ?e2 fleet:vehicle ?v2 .
                      ?e3 fleet:hasAlert ?alertType3 .
                      ?e3 fleet:vehicle ?v3 .
                      FILTER(STR(?alertType1) != STR(?alertType2))
                      FILTER(STR(?alertType2) != STR(?alertType3))
                      FILTER(STR(?alertType1) != STR(?alertType3))
                      FILTER(STR(?v1) = STR(?v2))
                      FILTER(STR(?v2) = STR(?v3))
                      FILTER(SEQ(?e1; ?e2; ?e3))
                    }
                    """;
            engine.registerCepQuery(query, (PatternMatch<StreamElement> match) ->
                    System.out.println("  FAULT CASCADE (" + match.size()
                            + " distinct alerts, one vehicle) -> send to depot: " + match));

            engine.start();
            System.out.println(
                    "Watching for: three DIFFERENT subsystem alerts from the SAME vehicle within 15s.\n");

            // -- Scenario 1: EV-7Q2 raises three distinct subsystem alerts -> must match.
            System.out.println("Scenario 1 — one vehicle, three distinct subsystems (should fire):");
            pushAlert(alerts, Fleet.EV1, INVERTER_OVER_TEMP);
            Thread.sleep(300);
            pushAlert(alerts, Fleet.EV1, BRAKE_WEAR);
            Thread.sleep(300);
            pushAlert(alerts, Fleet.EV1, TIRE_PRESSURE);
            Thread.sleep(1500);   // let the compound alert print

            // -- Scenario 2: the same three alert types, but split across two OTHER vehicles.
            // The distinctness filters pass, yet the same-vehicle guards
            // (STR(?v1)=STR(?v2), STR(?v2)=STR(?v3)) can never hold -> must NOT match.
            // EV-7Q2 is deliberately kept out of this scenario: its scenario-1 alerts are
            // still inside the 15s window and could otherwise correlate with new ones.
            System.out.println(
                    "\nScenario 2 — same alert types, spread across two vehicles (should stay quiet):");
            pushAlert(alerts, Fleet.EV2, INVERTER_OVER_TEMP);
            Thread.sleep(300);
            pushAlert(alerts, Fleet.EV3, BRAKE_WEAR);
            Thread.sleep(300);
            pushAlert(alerts, Fleet.EV2, TIRE_PRESSURE);
            Thread.sleep(1500);
            System.out.println("  (no cascade: three distinct alerts, but never three from ONE vehicle —"
                    + " the cross-event same-vehicle guard suppressed the match)");
        }
        System.out.println("\nDone.");
    }

    /**
     * Push one reified subsystem alert as a single <em>atomic</em> two-statement stream element:
     * a fresh alert-event subject carrying {@code fleet:hasAlert <type>} and
     * {@code fleet:vehicle <vehicle>}. Keeping both triples in one element is what lets the
     * multi-triple CEP matcher bind the alert type AND the vehicle on the same SEQ step —
     * pushed separately they would never form one event.
     */
    private static void pushAlert(DataStream stream, String vehicle, String alertType) {
        IRI event = VF.createIRI(Fleet.EX + "alert/" + ALERT_SEQ.incrementAndGet());
        stream.push(List.of(
                VF.createStatement(event, VF.createIRI(HAS_ALERT), VF.createIRI(alertType)),
                VF.createStatement(event, VF.createIRI(VEHICLE), VF.createIRI(vehicle))));
        System.out.println("push: " + shortId(vehicle) + " "
                + alertType.substring(Fleet.FLEET.length()));
    }

    private static String shortId(String iri) {
        return iri.substring(iri.lastIndexOf('/') + 1);
    }
}
