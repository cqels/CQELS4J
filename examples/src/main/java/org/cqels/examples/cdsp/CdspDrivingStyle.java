package org.cqels.examples.cdsp;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.examples.Fleet;

/**
 * Example — the COVESA CDSP <strong>aggressive-driving / driving-style</strong> use case,
 * expressed as a single CQELS-QL continuous query.
 *
 * <p>CDSP's knowledge layer implements this today as eight RDFox Datalog rules
 * ({@code driving_style_inference_rules.dlog}) plus a SPARQL output query
 * ({@code select_driving_style.rq}), driven by a C++ triple assembler. This demo is the
 * streaming formulation of the same detection, and it is a good measure of what a native
 * continuous-query engine removes from the problem.
 *
 * <p><strong>What the Datalog rules spend their effort on, and why CQELS does not need it:</strong>
 * <ul>
 *   <li>Rule 2 {@code CurrentObservation} — an {@code AGGREGATE MAX(phenomenonTime)} per property,
 *       to recover "the latest reading" from a store that keeps everything. A window is <em>not</em>
 *       a substitute: it bounds how OLD a reading may be, not which one is newest, and the two
 *       differ exactly when a signal changes inside the window. Believing otherwise is what caused
 *       the stale-speed defect described below. What stands in for rule 2 here is the frame model
 *       plus event-time ordering: the speed read is the one in the later of the two frames.</li>
 *   <li>Rule 3 {@code within3s} — pairs readings inside a 3-second window whose size is read from
 *       an ontology individual ({@code car:hasWindowSize "PT3S"^^xsd:duration}). In CQELS the
 *       window is syntax: {@code [RANGE 3s]}. This disappears.</li>
 *   <li>Rules 4, 5, 7 {@code LargeAngleChange} / {@code HighSpeedObservation} /
 *       {@code AggressiveDriving} — a self-join on two angle readings, a speed threshold, and a
 *       timestamp-equality join to bring them together. In one window these are simply three
 *       patterns and two filters, which is the shape {@code SuddenSwerveDetector} already uses.</li>
 *   <li>Rules 8, 9 {@code Segment} / {@code avgAngleChange} — assembling a result individual and
 *       averaging over it becomes {@code GROUP BY} + {@code AVG}.</li>
 * </ul>
 * The eight rules' <strong>detection</strong> becomes one query. The output query's segment
 * assembly does not: it projects a segment id, start and end fix points with coordinates, and both
 * timestamps, all of which need the angle pair ordered <em>in time</em> — the comparison this
 * release cannot make. See {@code CDSP_MAPPING.md} §2.2. The intermediate individuals
 * ({@code LargeAngleChange}, {@code HighSpeedObservation}, {@code AggressiveDriving}) exist only to
 * carry state between Datalog rules; a continuous query has no such handoff to make, so the
 * {@code SKOLEM(...)} minting that names them is not needed either.
 *
 * <p><strong>Two details the naive translation gets wrong</strong>, both found in review and both
 * demonstrated by the scenarios below:
 * <ul>
 *   <li><em>Speed must be co-temporal with the swing.</em> CDSP's rule 2 keeps only the
 *       {@code CurrentObservation} per property, so its speed guard reads the LATEST speed.
 *       Matching a free-standing {@code vss:Speed} observation anywhere in the window does not: a
 *       vehicle that was fast, slowed, then swerved still pairs with the stale fast reading and
 *       alerts (scenario 4). CQELS-QL cannot express "the latest value of X" on this release —
 *       there is no event-time comparison ({@code xsd:dateTime} arithmetic is not evaluated) and no
 *       argmax. The fix is in the data model rather than the query: each telemetry <em>frame</em>
 *       ({@link Fleet#pushFrame}) carries angle and speed in ONE atomic element, as a real vehicle
 *       emits them, so {@code ?f2 vss:Speed ?speed} is by construction the speed at the moment of
 *       the second angle reading.</li>
 *   <li><em>The angle pair is unordered.</em> {@code FILTER(STR(?f1) != STR(?f2))} admits both
 *       {@code (f1,f2)} and {@code (f2,f1)}, so every swing is counted twice — {@code COUNT}
 *       returns 2 for a single swing. {@code STR(?f1) < STR(?f2)} admits exactly one of the two.
 *       It de-duplicates rather than ordering in time: CDSP's rule 4 orders the pair with
 *       {@code FILTER(?pt2 > ?pt1)} on trimmed timestamps — the same comparison this query now
 *       makes on {@code sosa:phenomenonTime}. Ordering is not optional here either: {@code ?f2}
 *       alone supplies the speed, so which frame is {@code ?f2} decides which speed the guard
 *       reads. (The {@code ABS} difference is symmetric; the speed guard is not.)</li>
 * </ul>
 *
 * <p><strong>Ordering is by event time, not arrival.</strong> Co-locating angle and speed in one
 * frame makes them co-temporal; it does not by itself make {@code ?f2} the <em>later</em> frame.
 * An earlier version ordered the pair with {@code STR(?f1) < STR(?f2)} — minted-identifier order,
 * i.e. arrival — which reads a replayed or buffered pair backwards and reinstates the stale-speed
 * alert. Scenario 5 is that case.
 *
 * <p>The fix is to order by {@code sosa:phenomenonTime}, which the frames now carry:
 * {@code FILTER(?t1 < ?t2)}. Relational comparison on {@code xsd:dateTime} <strong>is</strong>
 * evaluated on this release — it is <em>subtraction</em> of two of them, yielding a duration, that
 * is not, and the two are different capabilities. Conflating them is what previously led this
 * documentation to claim event-time ordering was impossible here and to scope the demo to an
 * in-order feed; it is neither impossible nor so scoped. Ordering this way also subsumes the
 * de-duplication that {@code <} was doing, since exactly one of the two orderings satisfies it.
 *
 * <p>Signals are the CDSP input set ({@code inputs/vehicle_data_required.txt}):
 * {@code Vehicle.Chassis.SteeringWheel.Angle} and {@code Vehicle.Speed}.
 *
 * <p>Thresholds follow the CDSP rules: angle difference &gt; 210 and speed &gt; 10.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.cdsp.CdspDrivingStyle}
 */
public class CdspDrivingStyle {

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("cdsp-driving-style")
                .withMemoryStore()
                .build()) {

            DataStream telemetry = engine.createStream("Telemetry");

            // Rules 2-9 and select_driving_style.rq, as one continuous query.
            String query = Fleet.PREFIXES + """
                    REGISTER QUERY AggressiveDriving AS
                    SELECT ?vehicle (MAX(?angleDiff) AS ?peakAngleChange)
                                    (AVG(?angleDiff) AS ?avgAngleChange)
                                    (COUNT(*) AS ?swings)
                    FROM STREAM Telemetry [RANGE 3s]
                    WHERE {
                      STREAM Telemetry {
                        ?f1 sosa:hasFeatureOfInterest ?vehicle .
                        ?f1 sosa:phenomenonTime ?t1 .
                        ?f1 vss:Chassis.SteeringWheel.Angle ?angle1 .
                        ?f2 sosa:hasFeatureOfInterest ?vehicle .
                        ?f2 sosa:phenomenonTime ?t2 .
                        ?f2 vss:Chassis.SteeringWheel.Angle ?angle2 .
                        ?f2 vss:Speed ?speed .
                      }
                      # Order by EVENT time, not arrival. This both de-duplicates the pair (with
                      # '!=' each swing matches twice, once per ordering) and makes ?f2 genuinely
                      # the later frame -- so ?f2's speed is the speed AT the swing however the
                      # frames arrived. Relational comparison on xsd:dateTime IS evaluated on this
                      # release; it is subtraction of two of them that is not.
                      FILTER(?t1 < ?t2)
                      BIND(ABS(?angle1 - ?angle2) AS ?angleDiff)
                      FILTER(?angleDiff > 210)
                      # ?speed comes from ?f2, the SAME frame as the second angle reading, so it is
                      # the speed AT the swing -- not any speed still sitting in the window.
                      FILTER(?speed > 10)
                    }
                    GROUP BY ?vehicle
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  AGGRESSIVE DRIVING -> " + row));

            engine.start();
            System.out.println("""
                    Engine started. CDSP driving-style detection: steering-angle swing > 210 deg
                    within 3 s while speed > 10 — eight Datalog rules as one continuous query.
                    """);

            // Scenario 1 — a hard swerve at speed on EV-7Q2: angle 20 -> 250 (diff 230 > 210).
            System.out.println("Scenario 1 — EV-7Q2 swings 20 -> 250 deg at 64 km/h (should fire):");
            Fleet.pushFrame(telemetry, Fleet.SENSOR_EV1, Fleet.EV1, Fleet.STEERING, 20.0, Fleet.SPEED, 64.0);
            Thread.sleep(300);
            Fleet.pushFrame(telemetry, Fleet.SENSOR_EV1, Fleet.EV1, Fleet.STEERING, 250.0, Fleet.SPEED, 64.0);
            Thread.sleep(1200);

            // Scenario 2 — the same swing, but parked. The speed guard must suppress it.
            Thread.sleep(4000);   // comfortably past the 3s window, so scenario 1 has aged out
            System.out.println("\nScenario 2 — EV-3K8 swings 15 -> 245 deg at 4 km/h (should stay quiet):");
            Fleet.pushFrame(telemetry, Fleet.SENSOR_EV2, Fleet.EV2, Fleet.STEERING, 15.0, Fleet.SPEED, 4.0);
            Thread.sleep(300);
            Fleet.pushFrame(telemetry, Fleet.SENSOR_EV2, Fleet.EV2, Fleet.STEERING, 245.0, Fleet.SPEED, 4.0);
            Thread.sleep(1200);
            System.out.println("  (no alert: the swing is real but the vehicle is barely moving)");

            // Scenario 3 — at speed, but a gentle correction. The angle guard must suppress it.
            Thread.sleep(4000);
            System.out.println("\nScenario 3 — EV-9TZ swings 30 -> 55 deg at 80 km/h (should stay quiet):");
            Fleet.pushFrame(telemetry, Fleet.SENSOR_EV3, Fleet.EV3, Fleet.STEERING, 30.0, Fleet.SPEED, 80.0);
            Thread.sleep(300);
            Fleet.pushFrame(telemetry, Fleet.SENSOR_EV3, Fleet.EV3, Fleet.STEERING, 55.0, Fleet.SPEED, 80.0);
            Thread.sleep(1500);
            System.out.println("  (no alert: fast, but only a 25 deg correction)");

            // Scenario 4 — the case that motivated the frame model: the vehicle WAS fast, then
            // slowed, and only then swerved. An earlier version of this query paired the swing
            // with the stale 64 km/h reading still sitting in the window and alerted. Because
            // speed now travels in the same frame as the angle, the swing carries 4 km/h.
            Thread.sleep(4000);
            System.out.println("\nScenario 4 — EV-7Q2 was at 64, SLOWED to 4, then swings"
                    + " 20 -> 250 (should stay quiet):");
            Fleet.pushFrame(telemetry, Fleet.SENSOR_EV1, Fleet.EV1, Fleet.STEERING, 5.0, Fleet.SPEED, 64.0);
            Thread.sleep(300);
            Fleet.pushFrame(telemetry, Fleet.SENSOR_EV1, Fleet.EV1, Fleet.STEERING, 20.0, Fleet.SPEED, 4.0);
            Thread.sleep(300);
            Fleet.pushFrame(telemetry, Fleet.SENSOR_EV1, Fleet.EV1, Fleet.STEERING, 250.0, Fleet.SPEED, 4.0);
            Thread.sleep(1500);
            System.out.println("  (no alert: the swing happened at 4 km/h, though 64 is still in the window)");

            // Scenario 5 — the same slowed-then-swerved case, REPLAYED out of order: the later
            // frame is pushed FIRST. Ordering by minted identifier (arrival) read this pair
            // backwards and alerted on the stale 64; ordering by sosa:phenomenonTime does not,
            // because ?f2 is the later frame whichever arrives first.
            Thread.sleep(4000);
            System.out.println("\nScenario 5 — the same case REPLAYED out of order, later frame"
                    + " pushed first (should stay quiet):");
            Fleet.pushFrameAt(telemetry, Fleet.SENSOR_EV2, Fleet.EV2, Fleet.STEERING, 250.0,
                    Fleet.SPEED, 4.0, "2026-01-01T00:00:09Z");     // later event, arrives first
            Thread.sleep(300);
            Fleet.pushFrameAt(telemetry, Fleet.SENSOR_EV2, Fleet.EV2, Fleet.STEERING, 20.0,
                    Fleet.SPEED, 64.0, "2026-01-01T00:00:01Z");    // earlier event, arrives second
            Thread.sleep(1500);
            System.out.println("  (no alert: ?f2 is the 00:00:09 frame by event time, so the guard"
                    + " reads 4 km/h — not the 64 that arrived later)");
        }
        System.out.println("\nDone.");
    }
}
