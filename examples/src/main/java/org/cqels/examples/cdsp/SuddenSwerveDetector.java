package org.cqels.examples.cdsp;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.examples.Fleet;

/**
 * CDSP-style analytics — sudden-swerve detection via a two-reading temporal self-join.
 *
 * <p>A swerve is invisible in any single reading: it is a large steering-wheel swing
 * <em>between two readings</em> while the vehicle is moving fast. The query joins the stream
 * with itself inside one {@code [RANGE 3s]} window — {@code ?r1} is the earlier reading and
 * {@code ?r2} the later one, ordered by an explicit {@code vss:ReadingTimestamp} carried on
 * every reading ({@code FILTER(?ts1 < ?ts2)} also rules out pairing a reading with itself).
 * {@code BIND(ABS(?angle1 - ?angle2) AS ?angleDelta)} computes the swing, and a compound
 * {@code FILTER} applies the swing threshold (&gt; 90 degrees) plus the speed gate
 * (&gt; 50 km/h). A gentle cruise wiggle and a hard wheel swing at parking speed both stay
 * silent; the emitted row carries the GPS fix of the later reading, so the incident is placed
 * on the map. Adapted from the aggressive-driving analytics of the COVESA CDSP scenario suite.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.cdsp.SuddenSwerveDetector}
 */
public class SuddenSwerveDetector {

    private static long readingSeq = 0;

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("sudden-swerve-detector")
                .withMemoryStore()
                .build()) {

            DataStream telemetry = engine.createStream("Telemetry");

            String query = Fleet.PREFIXES + """
                    REGISTER QUERY SuddenSwerve AS
                    SELECT ?vehicle ?speed ?angle1 ?angle2 ?angleDelta ?lat ?long
                    FROM STREAM Telemetry [RANGE 3s]
                    WHERE {
                      STREAM Telemetry {
                        ?r1 vss:Chassis.SteeringWheel.Angle ?angle1 .
                        ?r1 vss:ReadingTimestamp ?ts1 .
                        ?r1 vss:Vehicle ?vehicle .
                      }
                      STREAM Telemetry {
                        ?r2 vss:Chassis.SteeringWheel.Angle ?angle2 .
                        ?r2 vss:ReadingTimestamp ?ts2 .
                        ?r2 vss:Speed ?speed .
                        ?r2 vss:CurrentLocation.Latitude ?lat .
                        ?r2 vss:CurrentLocation.Longitude ?long .
                        ?r2 vss:Vehicle ?vehicle .
                      }
                      FILTER(?ts1 < ?ts2)
                      BIND(ABS(?angle1 - ?angle2) AS ?angleDelta)
                      FILTER(?angleDelta > 90 && ?speed > 50)
                    }
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  SWERVE -> " + row));

            engine.start();
            System.out.println("Watching for: steering swing > 90 degrees between two readings at > 50 km/h.\n");

            System.out.println("Phase 1: EV-7Q2 cruises at 60 km/h with a gentle wiggle (must NOT match - swing too small).");
            pushReading(telemetry, Fleet.EV1, 60, -4);
            Thread.sleep(250);
            pushReading(telemetry, Fleet.EV1, 61, 5);
            Thread.sleep(250);
            pushReading(telemetry, Fleet.EV1, 60, -3);
            Thread.sleep(3500);   // let the 3s window drain between phases

            System.out.println("Phase 2: EV-3K8 swings the wheel -40 -> +70 degrees at parking speed (must NOT match - speed gate).");
            pushReading(telemetry, Fleet.EV2, 15, -40);
            Thread.sleep(250);
            pushReading(telemetry, Fleet.EV2, 14, 70);
            Thread.sleep(3500);

            System.out.println("Phase 3: EV-9TZ at 85 km/h snaps the wheel -35 -> +80 degrees (MUST match: delta 115 at speed 86).");
            pushReading(telemetry, Fleet.EV3, 85, -35);
            Thread.sleep(250);
            pushReading(telemetry, Fleet.EV3, 86, 80);
            Thread.sleep(1500);
        }
        System.out.println("\nDone.");
    }

    /**
     * One flat telemetry reading — speed, steering angle, GPS fix, and an explicit
     * {@code vss:ReadingTimestamp} (a monotonic sequence number) so the query can order the
     * two readings of the self-join.
     */
    private static void pushReading(DataStream telemetry, String vehicle,
                                    double speedKmh, double steeringDeg) {
        long seq = ++readingSeq;
        String reading = Fleet.EX + "reading/" + seq;
        telemetry.pushTriple(reading, Fleet.VSS + "Vehicle", vehicle);
        telemetry.push(reading, Fleet.VSS + "ReadingTimestamp", seq);
        telemetry.push(reading, Fleet.SPEED, speedKmh);
        telemetry.push(reading, Fleet.STEERING, steeringDeg);
        telemetry.push(reading, Fleet.LATITUDE, 48.14 + seq * 1e-4);
        telemetry.push(reading, Fleet.LONGITUDE, 11.58 + seq * 1e-4);
        System.out.println("push: " + vehicle.substring(vehicle.lastIndexOf('/') + 1)
                + " speed=" + speedKmh + " angle=" + steeringDeg);
    }
}
