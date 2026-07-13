package org.cqels.examples.cdsp;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.examples.Fleet;

/**
 * CDSP-style analytics — a rolling per-vehicle risk leaderboard with a violation floor.
 *
 * <p>Each telemetry reading joins two signals of the same reading node — speed and steering
 * angle — and a compound {@code FILTER(?speed > 100 || ABS(?angle) > 60)} keeps only
 * <em>violations</em> (speeding or violent steering). {@code GROUP BY ?vehicle} then scores each
 * vehicle over a {@code [RANGE 10s]} window with {@code COUNT(*)} (how many violations) and
 * {@code AVG(?speed)} (how fast while violating), and {@code HAVING(?violationCount > 3)} keeps
 * occasional slips off the board: only vehicles with more than three violations in the window
 * are reported. EV-9TZ is driven hard (6 violations) and tops the board; EV-3K8 commits exactly
 * two violations and is gated out by HAVING; EV-7Q2 stays clean and never even forms a group.
 * Adapted from the driving-risk-score analytics of the COVESA CDSP scenario suite.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.cdsp.FleetRiskLeaderboard}
 */
public class FleetRiskLeaderboard {

    private static long readingSeq = 0;

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("fleet-risk-leaderboard")
                .withMemoryStore()
                .build()) {

            DataStream telemetry = engine.createStream("Telemetry");

            String query = Fleet.PREFIXES + """
                    REGISTER QUERY FleetRiskLeaderboard AS
                    SELECT ?vehicle (COUNT(*) AS ?violationCount) (AVG(?speed) AS ?avgSpeed)
                    FROM STREAM Telemetry [RANGE 10s]
                    WHERE {
                      ?r1 vss:Speed ?speed .
                      ?r1 vss:Chassis.SteeringWheel.Angle ?angle .
                      ?r1 vss:Vehicle ?vehicle .
                      FILTER(?speed > 100 || ABS(?angle) > 60)
                    }
                    GROUP BY ?vehicle
                    HAVING(?violationCount > 3)
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  RISK -> " + row));

            engine.start();
            System.out.println("Risk leaderboard: violations = speed > 100 km/h OR |steering| > 60 degrees;");
            System.out.println("a vehicle makes the board only with more than 3 violations in the 10s window.\n");
            System.out.println("Pushing 6 readings per vehicle into one window:");
            System.out.println("  EV-9TZ driven hard   (6 violations - MUST top the leaderboard)");
            System.out.println("  EV-3K8 borderline    (2 violations - HAVING must gate it out)");
            System.out.println("  EV-7Q2 clean         (0 violations - never grouped)\n");

            for (int i = 0; i < 6; i++) {
                if (i % 2 == 0) {
                    pushRiskReading(telemetry, Fleet.EV3, 120, 10);   // speeding violation
                } else {
                    pushRiskReading(telemetry, Fleet.EV3, 60, 75);    // violent-steering violation
                }
                pushRiskReading(telemetry, Fleet.EV2, i < 2 ? 110 : 80, 5);   // 2 violations, then clean
                pushRiskReading(telemetry, Fleet.EV1, 60, 5);                 // always clean
                Thread.sleep(250);
            }

            // End of shift: complete the stream so the aggregation window flushes its final
            // leaderboard (same idiom as the CDSP test suite for windowed GROUP BY queries).
            telemetry.complete();
            Thread.sleep(1500);
        }
        System.out.println("\nDone.");
    }

    /** One flat telemetry reading: speed + steering angle on the same reading node. */
    private static void pushRiskReading(DataStream telemetry, String vehicle,
                                        double speedKmh, double steeringDeg) {
        long seq = ++readingSeq;
        String reading = Fleet.EX + "reading/" + seq;
        telemetry.pushTriple(reading, Fleet.VSS + "Vehicle", vehicle);
        telemetry.push(reading, Fleet.SPEED, speedKmh);
        telemetry.push(reading, Fleet.STEERING, steeringDeg);
        System.out.println("push: " + vehicle.substring(vehicle.lastIndexOf('/') + 1)
                + " speed=" + speedKmh + " angle=" + steeringDeg);
    }
}
