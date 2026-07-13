package org.cqels.examples.reasoning;

import org.cqels.asp.config.AspStreamSolveConfig;
import org.cqels.asp.integration.AspResultMapper;
import org.cqels.asp.solver.AspSolveResult;
import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.engine.QueryResultListener;
import org.cqels.examples.Fleet;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Example — ASP <em>negation-as-failure</em>: "at risk unless charging" (cqels-asp).
 *
 * <p>The scenario: every EV must end its shift with enough battery for its <em>next duty</em>.
 * At start of shift, mission control publishes each vehicle's required state-of-charge reserve
 * onto the stream. Telemetry then reports SoC readings. A vehicle whose SoC falls below its
 * reserve <em>needs charge</em> — but its mission is only <em>at risk</em> if it is not already
 * plugged in. That "unless" is classic default reasoning: ASP states the closed-world default in
 * one line with negation-as-failure, where SPARQL would need a correlated
 * {@code FILTER NOT EXISTS} evaluated against an explicitly scoped dataset/window.
 *
 * <p>The program (facts are the stream's RDF triples, mapped to {@code rdf(S, P, O)}):
 * <pre>
 *   soc(O, V, S)       :- reading O reports SoC band S for vehicle V.
 *   reserve(V, N)      :- mission control requires reserve band N for V.
 *   charging(V)        :- V is plugged in at some station.
 *   needs_charge(V)    :- soc(O, V, S), reserve(V, N), S &lt; N.        % monotonic join
 *   mission_at_risk(V) :- needs_charge(V), not charging(V).           % negation-as-failure
 * </pre>
 *
 * <p>Two conventions worth copying:
 * <ul>
 *   <li><strong>Zero-padded value bands</strong> — SoC and reserve are published as three-digit
 *       percent bands ({@code "028"}, {@code "035"}), so the solver's lexical term ordering on
 *       literals coincides with numeric order and {@code S &lt; N} is a genuine in-ASP
 *       comparison. (The same zero-padding convention makes reading IRIs temporally ordered in
 *       the persistent-violation demo.)</li>
 *   <li><strong>Raw answer-set listener</strong> — one registered program derives several
 *       predicates; the raw {@code AspSolveResult} listener projects both {@code needs_charge}
 *       and {@code mission_at_risk} out of each answer set with {@code AspResultMapper}, instead
 *       of registering one query per predicate.</li>
 * </ul>
 *
 * <p>The demo drops EV-7Q2 below its reserve while driving (fires both predicates) and EV-3K8
 * below its reserve while plugged in at Depot North (fires {@code needs_charge} only — the
 * {@code not charging(V)} default suppresses the alarm, and it stays suppressed).
 *
 * <p>Add-on dependency: {@code org.cqels:cqels-asp}.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.reasoning.MissionPreservationAsp}
 */
public class MissionPreservationAsp {

    /** Mission control publishes the next-duty SoC reserve for a vehicle (zero-padded percent band). */
    private static final String NEXT_DUTY_RESERVE = Fleet.SVC + "nextDutyReserve";
    /** A vehicle is currently plugged in at a charging station. */
    private static final String CHARGING_AT = Fleet.FLEET + "chargingAt";

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final AtomicLong OBS_SEQ = new AtomicLong();

    public static void main(String[] args) throws InterruptedException {
        Set<String> printed = ConcurrentHashMap.newKeySet();
        Set<String> atRisk = ConcurrentHashMap.newKeySet();

        try (CQELSEngine engine = CQELSEngine.builder()
                .id("mission-preservation-asp")
                .withMemoryStore()
                .build()) {

            DataStream telemetry = engine.createStream("Telemetry");

            // Build the program from the shared Fleet constants so the predicate IRIs can never
            // drift from what the push helpers actually emit.
            String program =
                    "soc(O, V, S) :- rdf(O, iri(\"" + Fleet.HAS_FEATURE_OF_INTEREST + "\"), V),\n"
                    + "                rdf(O, iri(\"" + Fleet.HAS_SIMPLE_RESULT + "\"), S),\n"
                    + "                rdf(O, iri(\"" + Fleet.OBSERVED_PROPERTY + "\"), iri(\"" + Fleet.SOC + "\")).\n"
                    + "reserve(V, N) :- rdf(V, iri(\"" + NEXT_DUTY_RESERVE + "\"), N).\n"
                    + "charging(V) :- rdf(V, iri(\"" + CHARGING_AT + "\"), St).\n"
                    + "needs_charge(V) :- soc(O, V, S), reserve(V, N), S < N.\n"
                    + "mission_at_risk(V) :- needs_charge(V), not charging(V).\n";

            AspStreamSolveConfig config = AspStreamSolveConfig.builder()
                    .inputStreamName("Telemetry")
                    .build();

            // Raw listener: each solve delivers the WHOLE answer set; project both derived
            // predicates from it. Facts accumulate across the stream, so a derived atom
            // re-appears in every later solve — the `printed` set keeps the log to first sight.
            engine.registerAspQuery("MissionPreservation", program, config,
                    new QueryResultListener<AspSolveResult>() {
                        @Override
                        public void onResult(AspSolveResult result) {
                            for (Map<String, Object> row : AspResultMapper.toRows(
                                    result, "needs_charge", List.of("v"))) {
                                String v = String.valueOf(row.get("v"));
                                if (printed.add("needs_charge|" + v)) {
                                    System.out.println("  needs_charge    -> " + shortId(v));
                                }
                            }
                            for (Map<String, Object> row : AspResultMapper.toRows(
                                    result, "mission_at_risk", List.of("v"))) {
                                String v = String.valueOf(row.get("v"));
                                atRisk.add(v);
                                if (printed.add("mission_at_risk|" + v)) {
                                    System.out.println("  MISSION AT RISK -> " + shortId(v)
                                            + " (below reserve and NOT charging)");
                                }
                            }
                        }

                        @Override
                        public void onError(Throwable error) {
                            System.err.println("  ASP error: " + error.getMessage());
                        }
                    });

            engine.start();
            System.out.println("Rule: mission_at_risk(V) :- needs_charge(V), not charging(V).\n");

            System.out.println("Start of shift — mission control publishes next-duty reserves:");
            pushReserve(telemetry, Fleet.EV1, 35);   // EV-7Q2 must keep 35 %
            pushReserve(telemetry, Fleet.EV2, 50);   // EV-3K8 must keep 50 %
            Thread.sleep(300);

            System.out.println("\nEV-3K8 plugs in at Depot North:");
            pushChargingAt(telemetry, Fleet.EV2, Fleet.STATION1);
            Thread.sleep(300);

            System.out.println("\nTelemetry — EV-7Q2 at 62 % (above its 35 % reserve, nothing derives):");
            pushSocBand(telemetry, Fleet.SENSOR_EV1, Fleet.EV1, 62);
            Thread.sleep(500);

            System.out.println("\nTelemetry — EV-3K8 drops to 42 % (below its 50 % reserve, but it is charging):");
            pushSocBand(telemetry, Fleet.SENSOR_EV2, Fleet.EV2, 42);
            Thread.sleep(500);

            System.out.println("\nTelemetry — EV-7Q2 drops to 28 % (below its 35 % reserve, still on the road):");
            pushSocBand(telemetry, Fleet.SENSOR_EV1, Fleet.EV1, 28);
            Thread.sleep(1500);

            System.out.println("\nSummary:");
            System.out.println("  EV-7Q2 at risk:     " + atRisk.contains(Fleet.EV1) + " (expected true)");
            System.out.println("  EV-3K8 at risk:     " + atRisk.contains(Fleet.EV2)
                    + " (expected false — needs charge, but `not charging(V)` failed"
                    + " because it is plugged in)");
        }
        System.out.println("\nDone.");
    }

    /** Publish the next-duty reserve for a vehicle as a zero-padded percent band. */
    private static void pushReserve(DataStream stream, String vehicle, int reservePercent) {
        stream.push(VF.createStatement(VF.createIRI(vehicle),
                VF.createIRI(NEXT_DUTY_RESERVE), VF.createLiteral(band(reservePercent))));
        System.out.println("push: " + shortId(vehicle) + " nextDutyReserve " + reservePercent + " %");
    }

    /** Announce that a vehicle is plugged in at a station. */
    private static void pushChargingAt(DataStream stream, String vehicle, String station) {
        stream.push(VF.createStatement(VF.createIRI(vehicle),
                VF.createIRI(CHARGING_AT), VF.createIRI(station)));
        System.out.println("push: " + shortId(vehicle) + " chargingAt " + shortId(station));
    }

    /**
     * Push one SoC reading as a {@code sosa:Observation} whose result is the zero-padded percent
     * band — the value form that makes the in-ASP {@code S < N} comparison numerically faithful.
     */
    private static void pushSocBand(DataStream stream, String sensor, String vehicle, int socPercent) {
        // Log first: the ASP path solves synchronously inside push, so derived atoms
        // print immediately after the reading that triggered them.
        System.out.println("push: " + shortId(vehicle) + " SoC " + socPercent + " %");
        IRI obs = VF.createIRI(Fleet.EX + "obs/soc-" + OBS_SEQ.incrementAndGet());
        stream.push(VF.createStatement(obs, VF.createIRI(Fleet.RDF_TYPE), VF.createIRI(Fleet.OBSERVATION)));
        stream.push(VF.createStatement(obs, VF.createIRI(Fleet.MADE_BY_SENSOR), VF.createIRI(sensor)));
        stream.push(VF.createStatement(obs, VF.createIRI(Fleet.OBSERVED_PROPERTY), VF.createIRI(Fleet.SOC)));
        stream.push(VF.createStatement(obs, VF.createIRI(Fleet.HAS_FEATURE_OF_INTEREST), VF.createIRI(vehicle)));
        stream.push(VF.createStatement(obs, VF.createIRI(Fleet.HAS_SIMPLE_RESULT), VF.createLiteral(band(socPercent))));
    }

    /** Three-digit zero-padded percent band: lexical order == numeric order for 0–100. */
    private static String band(int percent) {
        return String.format("%03d", percent);
    }

    private static String shortId(String iri) {
        return iri.substring(iri.lastIndexOf('/') + 1);
    }
}
