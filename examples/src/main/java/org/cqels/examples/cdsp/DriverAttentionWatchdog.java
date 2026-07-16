package org.cqels.examples.cdsp;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.engine.cep.PatternMatch;
import org.cqels.examples.Fleet;
import org.cqels.stream.StreamElement;

/**
 * Example — CEP with a <em>correlated negated</em> sequence step:
 * {@code FILTER(SEQ(?e1; NOT ?e2; ?e3))} where the negated step carries its own cross-event
 * guard, {@code FILTER(STR(?e1) = STR(?e2))}.
 *
 * <p>The scenario: a fleet-safety watchdog for driver attention. A vehicle is travelling fast
 * ({@code ?s1 > 80} km/h) and the telemetry later shows it fast again ({@code ?s3 > 60} km/h)
 * with <em>no braking episode by that same vehicle in between</em> — none of <em>its</em>
 * readings ever dropped below 40 km/h. The driver never reacted between the two fast readings,
 * so the watchdog raises an attention alert.
 *
 * <p>{@code NOT ?e2} is a <strong>gap</strong> constraint, not adjacency: it reads "{@code ?e1},
 * then no {@code ?e2}-matching reading until {@code ?e3}". Any number of readings may sit in the
 * gap as long as none matches the negated step. A single reading inside the gap that satisfies
 * the negated step's conditions kills the sequence. The {@code [RANGE 5s]} window doubles as the
 * timeout on a partial match. {@code BIND(?e1 AS ?v)} re-binds the first event so
 * {@code SELECT ?v} projects a bound variable — the standard projection idiom for CEP queries.
 *
 * <p>Each reading's subject is the vehicle IRI, so the cross-event guard
 * {@code FILTER(STR(?e1) = STR(?e3))} pins the two <em>fast</em> readings to one vehicle — one
 * vehicle's 90 km/h can never pair with another's 75. The <em>negated</em> step is correlated
 * the same way: {@code FILTER(STR(?e1) = STR(?e2))} pins the braking reading to that vehicle
 * too, so only the watched vehicle's <em>own</em> hard-brake clears its pending watchdog — some
 * <em>other</em> vehicle braking elsewhere in the fleet no longer suppresses the alert. Since
 * CQELS {@code 2.0.0-alpha.13}, cross-event FILTER guards on negated steps are honored; before
 * that they were ignored on the negated step (any vehicle's braking reading killed the match),
 * which is why earlier versions of this demo left the step vehicle-agnostic.
 *
 * <p>The demo pushes a matching episode (fast, coasts without braking, fast again), a
 * non-matching one (fast, <em>brakes hard</em>, fast again) that must stay quiet, a two-vehicle
 * episode (one vehicle fast, a different vehicle fast) that must stay quiet because the fast
 * readings cannot pair across vehicles, and finally the correlated-negation showcase: the
 * watched vehicle is fast twice while a <em>different</em> vehicle brakes in between — the alert
 * still fires, because the guard rejects the other vehicle's braking reading as an {@code ?e2}.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.cdsp.DriverAttentionWatchdog}
 */
public class DriverAttentionWatchdog {

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("driver-attention-watchdog")
                .withMemoryStore()
                .build()) {

            DataStream telemetry = engine.createStream("Telemetry");

            String query = Fleet.PREFIXES + """
                    REGISTER QUERY DriverAttentionWatchdog AS
                    SELECT ?v
                    FROM STREAM Telemetry [RANGE 5s]
                    WHERE {
                      ?e1 vss:Speed ?s1 .
                      ?e2 vss:Speed ?s2 .
                      ?e3 vss:Speed ?s3 .
                      FILTER(?s1 > 80)
                      FILTER(?s2 < 40)
                      FILTER(?s3 > 60)
                      FILTER(STR(?e1) = STR(?e3))
                      FILTER(STR(?e1) = STR(?e2))
                      FILTER(SEQ(?e1; NOT ?e2; ?e3))
                      BIND(?e1 AS ?v)
                    }
                    """;
            engine.registerCepQuery(query, (PatternMatch<StreamElement> match) ->
                    System.out.println("  ATTENTION ALERT (fast, never braked, fast again) -> " + match));

            engine.start();
            System.out.println("Watching for: speed > 80, then NO braking reading (< 40) from"
                    + " the SAME vehicle before the next fast reading (> 60).\n");

            // -- Scenario 1: 90 -> 55 -> 75. The middle reading is a lift-off coast — it is NOT a
            // braking episode (never below 40), so the NOT gap holds and the alert fires on the 75.
            // (55 deliberately matches none of the three step conditions, and 75 < 80 cannot start
            // a new ?e1, so exactly one alert is expected.)
            System.out.println("Scenario 1 — fast, coasts to 55 (no brake), fast again (should fire):");
            pushSpeed(telemetry, Fleet.EV1, 90);
            Thread.sleep(300);
            pushSpeed(telemetry, Fleet.EV1, 55);
            Thread.sleep(300);
            pushSpeed(telemetry, Fleet.EV1, 75);
            Thread.sleep(1500);   // let the alert print

            // Wait out the 5s RANGE so nothing from scenario 1 is still pending — for a CEP query
            // the window is also the timeout on a partial match.
            System.out.println("\n(pausing 5.5s so the first episode falls out of the 5s window)\n");
            Thread.sleep(5500);

            // -- Scenario 2: 90 -> 30 -> 75, all EV-7Q2. The 30 IS a braking episode by the SAME
            // vehicle: it satisfies the negated step's own filter (?s2 < 40) AND the correlation
            // guard STR(?e1)=STR(?e2), so it kills the sequence — no alert even though 90 and 75
            // both occur.
            System.out.println("Scenario 2 — fast, BRAKES to 30, fast again (should stay quiet):");
            pushSpeed(telemetry, Fleet.EV1, 90);
            Thread.sleep(300);
            pushSpeed(telemetry, Fleet.EV1, 30);
            Thread.sleep(300);
            pushSpeed(telemetry, Fleet.EV1, 75);
            Thread.sleep(1500);
            System.out.println("  (no alert: the same vehicle's braking reading matched the negated"
                    + " step NOT ?e2 — the driver reacted, so the sequence was discarded)");

            System.out.println("\n(pausing 5.5s so the second episode falls out of the 5s window)\n");
            Thread.sleep(5500);

            // -- Scenario 3: EV-3K8 at 90, then EV-9TZ at 75 — two fast readings, but from
            // DIFFERENT vehicles. The STR(?e1)=STR(?e3) guard rejects the cross-vehicle pairing,
            // so no alert even though "fast then fast, no brake between" holds stream-wide.
            System.out.println(
                    "Scenario 3 — EV-3K8 fast, then a DIFFERENT vehicle fast (should stay quiet):");
            pushSpeed(telemetry, Fleet.EV2, 90);
            Thread.sleep(300);
            pushSpeed(telemetry, Fleet.EV3, 75);
            Thread.sleep(1500);
            System.out.println("  (no alert: the same-vehicle guard STR(?e1)=STR(?e3) rejects a"
                    + " cross-vehicle pairing of the two fast readings)");

            System.out.println("\n(pausing 5.5s so the third episode falls out of the 5s window)\n");
            Thread.sleep(5500);

            // -- Scenario 4 (correlated negation): EV-7Q2 at 90, EV-3K8 brakes to 30, EV-7Q2 at
            // 75. The braking reading is from a DIFFERENT vehicle, so the guard
            // STR(?e1)=STR(?e2) rejects it as an ?e2 for EV-7Q2's pending match — the gap holds
            // and the alert STILL fires. Before CQELS 2.0.0-alpha.13, cross-event guards on
            // negated steps were ignored, so ANY vehicle's brake would have wrongly suppressed
            // this alert.
            System.out.println("Scenario 4 — EV-7Q2 fast, a DIFFERENT vehicle brakes to 30, EV-7Q2"
                    + " fast again (should STILL fire — correlated negation):");
            pushSpeed(telemetry, Fleet.EV1, 90);
            Thread.sleep(300);
            pushSpeed(telemetry, Fleet.EV2, 30);
            Thread.sleep(300);
            pushSpeed(telemetry, Fleet.EV1, 75);
            Thread.sleep(1500);   // let the alert print
        }
        System.out.println("\nDone.");
    }

    /** Push one raw speed reading ({@code <vehicle> vss:Speed value}). */
    private static void pushSpeed(DataStream stream, String vehicle, double kmh) {
        stream.push(vehicle, Fleet.SPEED, kmh);
        System.out.println("push: " + vehicle.substring(vehicle.lastIndexOf('/') + 1)
                + " vss:Speed " + kmh);
    }
}
