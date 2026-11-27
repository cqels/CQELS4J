package org.cqels.playground.cdsp;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.playground.Fleet;

/**
 * CDSP-style analytics — context-gated hard braking: a deceleration pair that only counts on a
 * wet road.
 *
 * <p>Same two-reading self-join family as {@link SuddenSwerveDetector}, but the pair is gated by
 * a <em>context triple</em>: the earlier reading {@code ?r1} must also carry
 * {@code vss:Exterior.IsRaining}, and the final {@code FILTER(?decel > 20 && ?rain > 0)} keeps
 * the pair only when it was raining when braking began. The identical 35 km/h speed drop on a
 * dry road stays silent — context, not just magnitude, decides. Readings are ordered by an
 * explicit {@code vss:ReadingTimestamp}, and {@code BIND(?speed1 - ?speed2 AS ?decel)} computes
 * the drop. Adapted from the wet-road-braking analytics of the COVESA CDSP scenario suite.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.playground.cdsp.WetRoadBraking}
 */
public class WetRoadBraking {

    private static long readingSeq = 0;

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("wet-road-braking")
                .withMemoryStore()
                .build()) {

            DataStream telemetry = engine.createStream("Telemetry");

            String query = Fleet.PREFIXES + """
                    REGISTER QUERY WetRoadBraking AS
                    SELECT ?vehicle ?speed1 ?speed2 ?decel ?rain
                    FROM STREAM Telemetry [RANGE 5s]
                    WHERE {
                      STREAM Telemetry {
                        ?r1 vss:Speed ?speed1 .
                        ?r1 vss:ReadingTimestamp ?ts1 .
                        ?r1 vss:Vehicle ?vehicle .
                        ?r1 vss:Exterior.IsRaining ?rain .
                      }
                      STREAM Telemetry {
                        ?r2 vss:Speed ?speed2 .
                        ?r2 vss:ReadingTimestamp ?ts2 .
                        ?r2 vss:Vehicle ?vehicle .
                      }
                      FILTER(?ts2 > ?ts1)
                      BIND(?speed1 - ?speed2 AS ?decel)
                      FILTER(?decel > 20 && ?rain > 0)
                    }
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  WET-ROAD HARD BRAKING -> " + row));

            engine.start();
            System.out.println("Watching for: a > 20 km/h speed drop when the first reading reports rain.\n");

            // Every reading carries an explicit event time (ms), following the pauses below, so the
            // windows hold the same readings every run.
            System.out.println("Phase 1: EV-7Q2 brakes hard on a DRY road, 100 -> 65 (must NOT match - rain gate).");
            pushRoadReading(telemetry, Fleet.EV1, 100, 0, 1_000);
            Thread.sleep(300);
            pushRoadReading(telemetry, Fleet.EV1, 65, 0, 1_300);
            Thread.sleep(5500);   // pacing; the next reading's event time, 5.5 s on, takes phase 1 out of the 5s window

            System.out.println("Phase 2: EV-3K8 eases off in the rain, 80 -> 70 (must NOT match - drop below 20 km/h).");
            pushRoadReading(telemetry, Fleet.EV2, 80, 1, 6_800);
            Thread.sleep(300);
            pushRoadReading(telemetry, Fleet.EV2, 70, 1, 7_100);
            Thread.sleep(5500);

            System.out.println("Phase 3: EV-9TZ brakes hard in the rain, 95 -> 60 (MUST match: decel 35 while raining).");
            pushRoadReading(telemetry, Fleet.EV3, 95, 1, 12_600);
            Thread.sleep(300);
            pushRoadReading(telemetry, Fleet.EV3, 60, 1, 12_900);
            Thread.sleep(1500);
        }
        System.out.println("\nDone.");
    }

    /**
     * One flat road reading — speed, the rain flag (1 = raining, 0 = dry) accompanying the
     * reading, and an explicit {@code vss:ReadingTimestamp} sequence number for ordering.
     */
    private static void pushRoadReading(DataStream telemetry, String vehicle,
                                        double speedKmh, double isRaining, long t) {
        long seq = ++readingSeq;
        String reading = Fleet.EX + "reading/" + seq;
        Fleet.pushTripleAt(telemetry, reading, Fleet.VSS + "Vehicle", vehicle, t);
        Fleet.pushAt(telemetry, reading, Fleet.VSS + "ReadingTimestamp", seq, t);
        Fleet.pushAt(telemetry, reading, Fleet.SPEED, speedKmh, t);
        Fleet.pushAt(telemetry, reading, Fleet.VSS + "Exterior.IsRaining", isRaining, t);
        System.out.println("push: " + vehicle.substring(vehicle.lastIndexOf('/') + 1)
                + " speed=" + speedKmh + " rain=" + isRaining);
    }
}
