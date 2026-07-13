package org.cqels.examples.cdsp;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.examples.Fleet;

/**
 * CDSP-style analytics — a smoothly-updating per-vehicle efficiency ticker on a sliding window.
 *
 * <p>Each tick a vehicle emits one <em>reading node</em> carrying two co-bound metrics: battery
 * power ({@code vss:Powertrain.TractionBattery.Charging.PowerW}, signed per the V2G convention —
 * positive while charging, negative while discharging to drive) and {@code vss:Speed}. Hanging
 * both values off a fresh per-tick reading IRI (rather than off the vehicle itself) is what makes
 * them genuinely co-bound: each power value joins the speed measured on the <em>same tick</em>,
 * never a neighbour's. The {@code [SLIDE 3s STEP 1s]} window keeps only the trailing 3 seconds,
 * so the per-vehicle {@code AVG(?powerW)} / {@code AVG(?speed)} ticker tracks recent readings —
 * old ticks age out and the averages follow the ramp instead of converging to an all-time mean.
 * (This alpha re-evaluates the sliding window on each arriving reading rather than only at the
 * step boundary, so expect a burst of overlapping emissions per tick — see the "Reading the
 * output" note in examples/README.md.) Reading the ticker: the hard-driven EV-9TZ draws roughly
 * three times the battery power of the economical EV-7Q2 while going only about twice as fast —
 * a worse energy-per-distance profile — while depot-parked EV-3K8 shows positive power
 * (V2G charging) at speed 0. Adapted from the energy-efficiency analytics of the COVESA CDSP
 * scenario suite.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.cdsp.LiveEfficiencyTicker}
 */
public class LiveEfficiencyTicker {

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("live-efficiency-ticker")
                .withMemoryStore()
                .build()) {

            DataStream telemetry = engine.createStream("Telemetry");

            String query = Fleet.PREFIXES + """
                    REGISTER QUERY LiveEfficiencyTicker AS
                    SELECT ?vehicle (AVG(?powerW) AS ?avgPowerW) (AVG(?speed) AS ?avgSpeed)
                    FROM STREAM Telemetry [SLIDE 3s STEP 1s]
                    WHERE {
                      ?r vss:Vehicle ?vehicle .
                      ?r vss:Powertrain.TractionBattery.Charging.PowerW ?powerW .
                      ?r vss:Speed ?speed .
                    }
                    GROUP BY ?vehicle
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  ticker -> " + row));

            engine.start();
            System.out.println("Trailing-3s efficiency ticker (avg battery power vs avg speed), sliding every 1s.");
            System.out.println("Power sign is the V2G convention: charging > 0, discharging (driving) < 0.");
            System.out.println("The run outlasts the window, so early ticks age out and the averages track the ramp.\n");

            for (int i = 0; i < 16; i++) {
                // EV-7Q2 economical: ~ -7.5 kW draw at ~ 50 km/h
                pushReading(telemetry, Fleet.EV1, i, -7200.0 - i * 100.0, 48.0 + i);
                // EV-9TZ heavy-footed: ~ -24 kW draw at ~ 95 km/h
                pushReading(telemetry, Fleet.EV3, i, -23000.0 - i * 250.0, 92.0 + i);
                // EV-3K8 parked at the depot, V2G charging: +11 kW at speed 0
                pushReading(telemetry, Fleet.EV2, i, 11000.0, 0.0);
                System.out.println("tick " + (i + 1)
                        + ": EV-7Q2 " + (-7200 - i * 100) + "W @ " + (48 + i) + " km/h"
                        + " | EV-9TZ " + (-23000 - i * 250) + "W @ " + (92 + i) + " km/h"
                        + " | EV-3K8 +11000W @ 0 km/h");
                Thread.sleep(400);
            }

            // End of run: complete the stream so the sliding aggregation flushes its final
            // ticker (same idiom as the CDSP test suite for windowed GROUP BY queries).
            telemetry.complete();
            Thread.sleep(1500);
        }
        System.out.println("\nDone.");
    }

    /**
     * Push one per-tick reading node — a fresh reading IRI carrying the vehicle plus BOTH
     * metrics, so power and speed are co-bound on the same tick.
     */
    private static void pushReading(DataStream stream, String vehicle, int tick,
                                    double powerW, double speed) {
        String reading = Fleet.EX + "reading/" + vehicle.substring(vehicle.lastIndexOf('/') + 1)
                + "-" + tick;
        stream.pushTriple(reading, Fleet.VSS + "Vehicle", vehicle);
        stream.push(reading, Fleet.CHARGE_POWER, powerW);
        stream.push(reading, Fleet.SPEED, speed);
    }
}
