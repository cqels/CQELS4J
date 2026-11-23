package org.cqels.examples;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.engine.cep.AnchoredPatternMatch;
import org.cqels.engine.cep.PatternMatch;
import org.cqels.stream.StreamElement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/**
 * Example — a <strong>combined past-and-future window</strong>,
 * {@code [PAST 2s FUTURE 1s STEP 1s]}, reviewing forward-collision warnings.
 *
 * <p>The scenario: each vehicle's forward radar reports its <em>time to collision</em> (TTC): the
 * gap to the vehicle ahead divided by the closing speed, in seconds. Below 2 s the car warns the
 * driver. That warning has to be live, so it is a plain {@code [NOW]} query. The fleet's safety
 * team also wants every warning <em>reviewed</em> with context from both sides of it:
 * <ul>
 *   <li><em>before</em> it: did the vehicle close in gradually (an earlier TTC between 2 and 4 s),
 *       or did the warning come out of nowhere (another car cut in, or a radar glitch)?</li>
 *   <li><em>after</em> it: did the driver brake, and how fast?</li>
 * </ul>
 * A combined window holds both sides at once.
 *
 * <p><strong>How the window behaves</strong> (measured on {@code 2.0.0-alpha.22}):
 * <ul>
 *   <li>Anchors sit on a fixed grid, one per {@code STEP}: 0, 1000, 2000 … ms of event time. They
 *       are <em>not</em> placed at events.</li>
 *   <li>The window of anchor {@code a} spans {@code (a-2s, a+1s]}: a reading at exactly
 *       {@code a+1s} is in it, one at exactly {@code a-2s} is not. With {@code STEP 1s}, windows
 *       overlap and every reading lands in three of them.</li>
 *   <li>A window reports only once the event-time watermark passes its end. So every result
 *       arrives at least 1 s (the {@code FUTURE} extent) after its anchor, in event time. The live
 *       warning ({@code [NOW]}) has no such delay.</li>
 *   <li>A complex-event match carries its anchor and window ({@link AnchoredPatternMatch}). A
 *       windowed aggregate row does not.</li>
 * </ul>
 *
 * <p><strong>Judging each warning once.</strong> Because the windows overlap, one warning is
 * matched in up to three windows, and in each one it sits at a different place: the earliest
 * window has almost no time after it, the latest almost none before it. The demo judges each
 * warning in the one window where it falls in the second up to the anchor, {@code (a-1s, a]}.
 * There it has at least 1 s of history and at least 1 s of follow-up, and less than 2 s of
 * each. Matches from the other windows are counted but not judged. Choosing that window is
 * the application's rule; the engine reports every window.
 *
 * <p>This is a review, not a deadline. Because anchors are on a grid, "braked within the window"
 * means within 1–2 s after the warning, depending on where it fell. For an exact per-event
 * deadline ("no brake within 1.5 s of this warning"), use a complex-event query over a backward
 * {@code [RANGE 1500ms]} window instead, as {@code cdsp.DriverAttentionWatchdog} does.
 *
 * <p>Queries, all on one stream:
 * <ol>
 *   <li>{@code Warning} — {@code [NOW]}, every TTC reading under 2 s, as it arrives;</li>
 *   <li>{@code ClosingIn} — {@code SEQ(?approach ; ?warning)}: a TTC of 2–4 s, then one under 2 s,
 *       from the same vehicle;</li>
 *   <li>{@code Braked} — {@code SEQ(?warning ; ?brake)}: a brake-pedal reading after the warning,
 *       same vehicle; the match's duration is the reaction time;</li>
 *   <li>{@code NoBrake} — {@code SEQ(?warning ; NOT ?brake)}: no brake-pedal reading from that
 *       vehicle after the warning before the window ends. This is where the {@code FUTURE} part
 *       earns its delay: "nothing happened" can only be decided once the window is over.</li>
 * </ol>
 * The queries tie the readings to one vehicle with {@code FILTER(STR(?warning) = STR(?brake))};
 * without it, one vehicle's brake would answer another's warning.
 *
 * <p>Readings are flat vehicle-subject triples with explicit event timestamps, so the windows are
 * deterministic. {@code ex:timeToCollision} is this demo's own term (VSS has no TTC signal); the
 * brake pedal is VSS {@code Chassis.Brake.PedalPosition}.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.CollisionWarningReview}
 */
public class CollisionWarningReview {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    static final String TTC = Fleet.EX + "timeToCollision";               // seconds
    static final String BRAKE = Fleet.VSS + "Chassis.Brake.PedalPosition";  // percent

    static final String WINDOW = "[PAST 2s FUTURE 1s STEP 1s]";

    /** Per warning (vehicle + event time): what its judging window found, each finding once. */
    private static final Map<String, Set<String>> REVIEW = new TreeMap<>();
    private static int otherWindows = 0;

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("collision-warning-review")
                .withMemoryStore()
                .build()) {

            DataStream radar = engine.createStream("Radar");
            String prefixes = Fleet.PREFIXES + "PREFIX ex: <" + Fleet.EX + ">\n";

            // 1 — the live warning: no window, no delay.
            engine.registerCqelsQuery(prefixes + """
                    REGISTER QUERY Warning AS
                    SELECT ?vehicle ?ttc
                    FROM STREAM Radar [NOW]
                    WHERE { STREAM Radar { ?vehicle ex:timeToCollision ?ttc . } FILTER(?ttc < 2.0) }
                    """, row -> System.out.println("  [WARNING, live] " + brief(row)));

            // 2 — before the warning: an approach reading (TTC 2–4 s), then the warning.
            engine.registerCepQuery(prefixes + """
                    REGISTER QUERY ClosingIn AS
                    SELECT ?warning
                    FROM STREAM Radar %s
                    WHERE {
                      ?approach ex:timeToCollision ?a .
                      ?warning  ex:timeToCollision ?w .
                      FILTER(?a >= 2.0 && ?a < 4.0)
                      FILTER(?w < 2.0)
                      FILTER(STR(?approach) = STR(?warning))
                      FILTER(SEQ(?approach ; ?warning))
                    }
                    """.formatted(WINDOW), m -> judge(m, "warning", "closed in gradually"));

            // 3 — after the warning: a brake-pedal reading from the same vehicle.
            engine.registerCepQuery(prefixes + """
                    REGISTER QUERY Braked AS
                    SELECT ?warning
                    FROM STREAM Radar %s
                    WHERE {
                      ?warning ex:timeToCollision ?w .
                      ?brake   vss:Chassis.Brake.PedalPosition ?p .
                      FILTER(?w < 2.0)
                      FILTER(STR(?warning) = STR(?brake))
                      FILTER(SEQ(?warning ; ?brake))
                    }
                    """.formatted(WINDOW), m -> judge(m, "warning",
                    "braked after " + (m.getEndTimestamp() - m.getStartTimestamp()) + " ms"));

            // 4 — after the warning: no brake-pedal reading from that vehicle before the window ends.
            engine.registerCepQuery(prefixes + """
                    REGISTER QUERY NoBrake AS
                    SELECT ?warning
                    FROM STREAM Radar %s
                    WHERE {
                      ?warning ex:timeToCollision ?w .
                      ?brake   vss:Chassis.Brake.PedalPosition ?p .
                      FILTER(?w < 2.0)
                      FILTER(STR(?warning) = STR(?brake))
                      FILTER(SEQ(?warning ; NOT ?brake))
                    }
                    """.formatted(WINDOW), m -> judge(m, "warning", "NO BRAKE in the window"));

            engine.start();
            System.out.println("Live warning under [NOW]; review over " + WINDOW + ".\n");

            // EV-7Q2: closes in gradually (TTC 3.4 -> 2.6 -> 1.8), the driver brakes 600 ms later.
            push(radar, Fleet.EV1, TTC, 3.4, 1_100);
            push(radar, Fleet.EV1, TTC, 2.6, 1_400);
            push(radar, Fleet.EV1, TTC, 1.8, 1_700);
            push(radar, Fleet.EV1, BRAKE, 45, 2_300);

            // EV-3K8: open road (TTC 6.0), then 1.5 s out of nowhere — a cut-in. Brakes after 400 ms.
            push(radar, Fleet.EV2, TTC, 6.0, 3_200);
            push(radar, Fleet.EV2, TTC, 1.5, 3_500);
            push(radar, Fleet.EV2, BRAKE, 70, 3_900);

            // EV-9TZ: closes in (TTC 3.0 -> 1.6) and never brakes.
            push(radar, Fleet.EV3, TTC, 3.0, 5_100);
            push(radar, Fleet.EV3, TTC, 1.6, 5_600);
            Thread.sleep(300);

            // A later reading moves the watermark past the last window's end, so it reports.
            System.out.println("push: closer @ t=10000 ms (moves the watermark; the remaining windows report)");
            push(radar, Fleet.EV1, TTC, 8.0, 10_000);
            Thread.sleep(800);
        }

        System.out.println("\nReview, one line per warning (from the window where it falls in (anchor-1s, anchor]):");
        REVIEW.forEach((k, v) -> {
            String before = v.remove("closed in gradually") ? "closed in gradually"
                    : "no approach reading before it (a cut-in, or a glitch)";
            System.out.println("  " + k + " : " + before + "; " + String.join("; ", v));
        });
        System.out.println("(" + otherWindows + " further matches came from the overlapping windows and were not judged.)");
        System.out.println("\nDone.");
    }

    /**
     * Record a match if this is the warning's judging window: the warning falls in the second up
     * to the anchor. Matches from the other overlapping windows are only counted. A warning with
     * two approach readings before it matches {@code ClosingIn} twice in the same window; the set
     * keeps that finding once.
     */
    private static synchronized void judge(PatternMatch<StreamElement> m, String warningVar, String verdict) {
        AnchoredPatternMatch<StreamElement> am = (AnchoredPatternMatch<StreamElement>) m;
        StreamElement warning = m.getEvent(warningVar);
        long t = warning.getTimestamp();
        long anchor = am.getAnchor();
        if (t <= anchor - 1_000 || t > anchor) {
            otherWindows++;
            return;
        }
        String vehicle = warning.asStatement().getSubject().stringValue().substring(Fleet.EX.length());
        String key = vehicle + " warning @ " + t + " ms";
        System.out.printf("  [review, window %s around anchor %d ms] %s: %s%n",
                am.getWindow(), anchor, key, verdict);
        REVIEW.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(verdict);
    }

    private static String brief(Object row) {
        return String.valueOf(row).replace(Fleet.EX, "");
    }

    /** Push one flat vehicle-subject reading with an explicit event timestamp. */
    private static void push(DataStream stream, String vehicle, String predicate, double value, long t) {
        String name = predicate.startsWith(Fleet.VSS) ? predicate.substring(Fleet.VSS.length())
                : predicate.substring(Fleet.EX.length());
        System.out.printf("push: %s %s = %s @ t=%d ms%n", vehicle.substring(Fleet.EX.length()), name,
                value, t);
        stream.push(VF.createIRI(vehicle), VF.createIRI(predicate), value, t);
    }
}
