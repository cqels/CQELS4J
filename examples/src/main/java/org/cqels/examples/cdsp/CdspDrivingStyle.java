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
 *       to recover "the latest reading" from a store that keeps everything. A window already means
 *       recent, so this disappears.</li>
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
 * Eight rules and a query become one query. The intermediate individuals
 * ({@code LargeAngleChange}, {@code HighSpeedObservation}, {@code AggressiveDriving}) exist only to
 * carry state between Datalog rules; a continuous query has no such handoff to make, so the
 * {@code SKOLEM(...)} minting that names them is not needed either.
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
                                    (MAX(?speed) AS ?peakSpeed)
                    FROM STREAM Telemetry [RANGE 3s]
                    WHERE {
                      STREAM Telemetry {
                        ?a1 sosa:observedProperty vss:Chassis.SteeringWheel.Angle .
                        ?a1 sosa:hasFeatureOfInterest ?vehicle .
                        ?a1 sosa:hasSimpleResult ?angle1 .
                        ?a2 sosa:observedProperty vss:Chassis.SteeringWheel.Angle .
                        ?a2 sosa:hasFeatureOfInterest ?vehicle .
                        ?a2 sosa:hasSimpleResult ?angle2 .
                        ?sp sosa:observedProperty vss:Speed .
                        ?sp sosa:hasFeatureOfInterest ?vehicle .
                        ?sp sosa:hasSimpleResult ?speed .
                      }
                      FILTER(STR(?a1) != STR(?a2))
                      BIND(ABS(?angle1 - ?angle2) AS ?angleDiff)
                      FILTER(?angleDiff > 210)
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
            Fleet.pushObservation(telemetry, Fleet.SENSOR_EV1, Fleet.EV1, Fleet.SPEED, 64.0);
            Fleet.pushObservation(telemetry, Fleet.SENSOR_EV1, Fleet.EV1, Fleet.STEERING, 20.0);
            Thread.sleep(300);
            Fleet.pushObservation(telemetry, Fleet.SENSOR_EV1, Fleet.EV1, Fleet.STEERING, 250.0);
            Thread.sleep(1200);

            // Scenario 2 — the same swing, but parked. The speed guard must suppress it.
            Thread.sleep(3000);   // let scenario 1 age out of the 3s window
            System.out.println("\nScenario 2 — EV-3K8 swings 15 -> 245 deg at 4 km/h (should stay quiet):");
            Fleet.pushObservation(telemetry, Fleet.SENSOR_EV2, Fleet.EV2, Fleet.SPEED, 4.0);
            Fleet.pushObservation(telemetry, Fleet.SENSOR_EV2, Fleet.EV2, Fleet.STEERING, 15.0);
            Thread.sleep(300);
            Fleet.pushObservation(telemetry, Fleet.SENSOR_EV2, Fleet.EV2, Fleet.STEERING, 245.0);
            Thread.sleep(1200);
            System.out.println("  (no alert: the swing is real but the vehicle is barely moving)");

            // Scenario 3 — at speed, but a gentle correction. The angle guard must suppress it.
            Thread.sleep(3000);
            System.out.println("\nScenario 3 — EV-9TZ swings 30 -> 55 deg at 80 km/h (should stay quiet):");
            Fleet.pushObservation(telemetry, Fleet.SENSOR_EV3, Fleet.EV3, Fleet.SPEED, 80.0);
            Fleet.pushObservation(telemetry, Fleet.SENSOR_EV3, Fleet.EV3, Fleet.STEERING, 30.0);
            Thread.sleep(300);
            Fleet.pushObservation(telemetry, Fleet.SENSOR_EV3, Fleet.EV3, Fleet.STEERING, 55.0);
            Thread.sleep(1500);
            System.out.println("  (no alert: fast, but only a 25 deg correction)");
        }
        System.out.println("\nDone.");
    }
}
