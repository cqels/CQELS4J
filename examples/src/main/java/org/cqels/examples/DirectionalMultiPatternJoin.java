package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/**
 * Example — OPT-IN multi-pattern directional SELECT (alpha.7): a per-vehicle star join inside a
 * forward-looking {@code [FUTURE 2s]} window.
 *
 * <p>Directional (LARS-style) windows previously executed single-pattern shapes only (see
 * {@link DirectionalWindow}). alpha.7 adds an opt-in path — behind the
 * {@code cqels.directional.multiPatternSelect} system property, read per gate evaluation — where a
 * SELECT with <em>two or more</em> stream triple patterns over one {@code FUTURE}/centered window
 * joins each closed window's final contents. Here: pair a vehicle's battery state-of-charge with
 * its V2G charge/discharge power <em>observed in the same forward window</em> — a V2G-readiness
 * snapshot per window.
 *
 * <p>Semantics to watch in the output (each window covers {@code (anchor, anchor+2s]}):
 * <ul>
 *   <li>a join row may span <em>separate</em> stream elements (EV-7Q2's SoC and power arrive
 *       500 ms apart, yet join);</li>
 *   <li>the join is per-window: EV-3K8's SoC and power fall in <em>different</em> windows, so
 *       they never join;</li>
 *   <li>rows surface only when a window CLOSES — i.e. when the event-time watermark passes the
 *       window's end (each row is anchor-stamped internally with its window's LARS anchor), so a
 *       finite demo needs a later "closer" event. Windows close in ascending anchor order.</li>
 * </ul>
 *
 * <p>Uses flat vehicle-subject triples (like the MCP server's smoke test) rather than the 5-triple
 * {@code sosa:Observation} wrapper: the star join keys on the shared {@code ?vehicle} subject, and
 * every observation IRI is unique so an observation-subject join could never pair two signals.
 * Explicit event timestamps make the windows deterministic.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.DirectionalMultiPatternJoin}
 */
public class DirectionalMultiPatternJoin {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    public static void main(String[] args) throws InterruptedException {
        // OPT-IN (alpha.7): without this property the multi-pattern directional shape fails
        // loudly at the shape guard. The flag is read per gate evaluation (never cached), so
        // setting it before registering the query is sufficient.
        System.setProperty("cqels.directional.multiPatternSelect", "true");

        try (CQELSEngine engine = CQELSEngine.builder()
                .id("directional-multi-pattern-join")
                .withMemoryStore()
                .build()) {

            DataStream telemetry = engine.createStream("Telemetry");

            String query = Fleet.PREFIXES + """
                    REGISTER QUERY V2gReadiness AS
                    SELECT ?vehicle ?soc ?powerW
                    FROM STREAM Telemetry [FUTURE 2s]
                    WHERE { STREAM Telemetry {
                      ?vehicle vss:Powertrain.TractionBattery.StateOfCharge.Current ?soc .
                      ?vehicle vss:Powertrain.TractionBattery.Charging.PowerW ?powerW . } }
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  [window closed] JOINED -> " + row));

            engine.start();
            System.out.println("Engine started. Joining SoC + charge power per vehicle inside each"
                    + " forward 2s window (windows close at the watermark).\n");

            // Window (0, 2000]: EV-7Q2's SoC and (negative = discharging-to-grid) power both land
            // in it -> one joined row when it closes.
            push(telemetry, Fleet.EV1, Fleet.SOC, 68.0, 1000L);
            push(telemetry, Fleet.EV1, Fleet.CHARGE_POWER, -7400.0, 1500L);
            // EV-3K8's pair SPLITS across windows: SoC in (0, 2000], power in (2000, 4000] ->
            // never joins (per-window join, not a cross-window one). The power reading @2600
            // also advances the watermark past 2000, so window (0, 2000] closes right here and
            // EV-7Q2's joined row prints.
            push(telemetry, Fleet.EV2, Fleet.SOC, 35.0, 1700L);
            push(telemetry, Fleet.EV2, Fleet.CHARGE_POWER, 3600.0, 2600L);
            // Window (2000, 4000]: EV-9TZ's SoC and charging power both land in it -> joined row.
            push(telemetry, Fleet.EV3, Fleet.SOC, 52.0, 2200L);
            push(telemetry, Fleet.EV3, Fleet.CHARGE_POWER, 11000.0, 3000L);
            Thread.sleep(300);
            // Closer: a later reading (matches neither pattern) advances the watermark past
            // window (2000, 4000]'s end, closing it -> EV-9TZ's joined row prints.
            System.out.println("push: closer event @ t=8000 ms (advances the watermark; the second window closes)");
            push(telemetry, Fleet.EV1, Fleet.SPEED, 45.0, 8000L);
            Thread.sleep(800);
        }
        System.out.println("\nDone.");
    }

    /** Push one flat vehicle-subject VSS reading with an explicit event timestamp. */
    private static void push(DataStream stream, String vehicle, String vssSignal,
                             double value, long timestamp) {
        System.out.printf("push: %s %s = %.1f @ t=%d ms%n",
                vehicle.substring(Fleet.EX.length()),
                vssSignal.substring(Fleet.VSS.length()), value, timestamp);
        stream.push(VF.createIRI(vehicle), VF.createIRI(vssSignal), value, timestamp);
    }
}
