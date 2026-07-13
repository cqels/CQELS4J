package org.cqels.examples.reasoning;

import org.cqels.asp.config.AspStreamSolveConfig;
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

/**
 * Example — ASP <em>temporal persistence</em>: three consecutive violations, no compliant
 * reading in between (cqels-asp).
 *
 * <p>The scenario: the depot zone caps speed at 20 km/h. One over-limit reading is a blip; a
 * <em>persistent</em> violation is three consecutive over-limit readings from the same vehicle
 * with no compliant reading breaking the chain. "No compliant reading in between" is a
 * <em>closed-world default</em> over an unbounded set of interleaving readings — ASP's
 * negation-as-failure states it directly, where SPARQL would need a correlated
 * {@code FILTER NOT EXISTS} over an explicitly scoped window.
 *
 * <p>The program (facts are the stream's RDF triples, mapped to {@code rdf(S, P, O)}):
 * <pre>
 *   reading_vehicle(R, V)       :- R is a SPEED reading of vehicle V.
 *   violating(R)                :- reading R is tagged over-limit for the depot zone.
 *   violation(R, V)             :- violating(R), reading_vehicle(R, V).
 *   compliant_between(V, R1, R3):- violation(R1, V), violation(R3, V), R1 &lt; R3,
 *                                  reading_vehicle(R, V), not violating(R),
 *                                  R1 &lt; R, R &lt; R3.          % a compliant reading breaks the chain
 *   persistent_violation(V, R3) :- violation(R1, V), violation(R2, V), violation(R3, V),
 *                                  R1 &lt; R2, R2 &lt; R3,
 *                                  not compliant_between(V, R1, R3).   % second, stratified NAF layer
 * </pre>
 *
 * <p>Two conventions worth copying:
 * <ul>
 *   <li><strong>Zero-padded reading IRIs</strong> — readings are numbered {@code ...-01},
 *       {@code ...-02}, … so the solver's lexical term ordering on IRIs coincides with arrival
 *       order and {@code R1 &lt; R2} is a genuine temporal comparison.</li>
 *   <li><strong>Tag numerics at ingestion</strong> — the numeric speed-vs-limit check runs where
 *       the reading is produced (here, in the push helper) and ships as a semantic tag triple
 *       ({@code fleet:speedViolationIn <zone>}); the ASP program owns what rules are actually
 *       good at — joins, ordering and non-monotonic chain logic over those tags.</li>
 * </ul>
 *
 * <p>The demo interleaves two vehicles inside the depot zone: EV-7Q2 posts three over-limit
 * readings in a row (fires on the third), while EV-9TZ posts over-limit, <em>compliant</em>,
 * over-limit, over-limit — and stays silent, because the compliant reading sits inside every
 * candidate chain. The interleaving also shows the chains are tracked per vehicle: EV-9TZ's
 * compliant reading cannot rescue EV-7Q2.
 *
 * <p>Add-on dependency: {@code org.cqels:cqels-asp}.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.reasoning.PersistentViolationAsp}
 */
public class PersistentViolationAsp {

    /** The reading exceeded the speed limit of the given zone (tagged at ingestion). */
    private static final String SPEED_VIOLATION_IN = Fleet.FLEET + "speedViolationIn";
    /** The shared fleet world's depot zone — speed capped at 20 km/h. */
    private static final String DEPOT_ZONE = Fleet.ZONE + "depot";
    private static final double DEPOT_LIMIT_KMH = 20.0;

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    public static void main(String[] args) throws InterruptedException {
        Set<String> printed = ConcurrentHashMap.newKeySet();
        Set<String> flagged = ConcurrentHashMap.newKeySet();

        try (CQELSEngine engine = CQELSEngine.builder()
                .id("persistent-violation-asp")
                .withMemoryStore()
                .build()) {

            DataStream telemetry = engine.createStream("Telemetry");

            // Build the program from the shared Fleet constants so the predicate IRIs can never
            // drift from what the push helper actually emits.
            String program =
                    "reading_vehicle(R, V) :- rdf(R, iri(\"" + Fleet.OBSERVED_PROPERTY + "\"), iri(\"" + Fleet.SPEED + "\")),\n"
                    + "                        rdf(R, iri(\"" + Fleet.HAS_FEATURE_OF_INTEREST + "\"), V).\n"
                    + "violating(R) :- rdf(R, iri(\"" + SPEED_VIOLATION_IN + "\"), iri(\"" + DEPOT_ZONE + "\")).\n"
                    + "violation(R, V) :- violating(R), reading_vehicle(R, V).\n"
                    + "compliant_between(V, R1, R3) :-\n"
                    + "    violation(R1, V), violation(R3, V), R1 < R3,\n"
                    + "    reading_vehicle(R, V), not violating(R),\n"
                    + "    R1 < R, R < R3.\n"
                    + "persistent_violation(V, R3) :-\n"
                    + "    violation(R1, V), violation(R2, V), violation(R3, V),\n"
                    + "    R1 < R2, R2 < R3,\n"
                    + "    not compliant_between(V, R1, R3).\n";

            AspStreamSolveConfig config = AspStreamSolveConfig.builder()
                    .inputStreamName("Telemetry")
                    .build();

            // Facts accumulate across the stream, so a derived atom re-appears in every later
            // solve — the `printed` set keeps the log to first sight.
            engine.registerAspQuery("PersistentViolation", program, config,
                    "persistent_violation", List.of("v", "r"),
                    new QueryResultListener<Map<String, Object>>() {
                        @Override
                        public void onResult(Map<String, Object> row) {
                            String v = String.valueOf(row.get("v"));
                            String r = String.valueOf(row.get("r"));
                            flagged.add(v);
                            if (printed.add(v + "|" + r)) {
                                System.out.println("  PERSISTENT VIOLATION -> " + shortId(v)
                                        + " (3rd consecutive over-limit reading: " + shortId(r) + ")");
                            }
                        }

                        @Override
                        public void onError(Throwable error) {
                            System.err.println("  ASP error: " + error.getMessage());
                        }
                    });

            engine.start();
            System.out.println("Rule: persistent_violation = 3 consecutive over-limit readings,"
                    + " no compliant reading in between.\n");

            System.out.println("Interleaved depot-zone readings (limit " + (int) DEPOT_LIMIT_KMH + " km/h):");
            pushSpeedReading(telemetry, Fleet.SENSOR_EV1, Fleet.EV1, 1, 34.0);   // EV-7Q2 over
            pushSpeedReading(telemetry, Fleet.SENSOR_EV3, Fleet.EV3, 1, 31.0);   // EV-9TZ over
            Thread.sleep(300);
            pushSpeedReading(telemetry, Fleet.SENSOR_EV1, Fleet.EV1, 2, 27.0);   // EV-7Q2 over
            pushSpeedReading(telemetry, Fleet.SENSOR_EV3, Fleet.EV3, 2, 12.0);   // EV-9TZ compliant — breaks its chain
            Thread.sleep(300);
            pushSpeedReading(telemetry, Fleet.SENSOR_EV1, Fleet.EV1, 3, 29.0);   // EV-7Q2 over -> fires here
            Thread.sleep(700);
            pushSpeedReading(telemetry, Fleet.SENSOR_EV3, Fleet.EV3, 3, 26.0);   // EV-9TZ over
            pushSpeedReading(telemetry, Fleet.SENSOR_EV3, Fleet.EV3, 4, 33.0);   // EV-9TZ over — still only 2 in a row
            Thread.sleep(1500);

            System.out.println("\nSummary:");
            System.out.println("  EV-7Q2 flagged: " + flagged.contains(Fleet.EV1) + " (expected true)");
            System.out.println("  EV-9TZ flagged: " + flagged.contains(Fleet.EV3)
                    + " (expected false — its compliant 12 km/h reading sits inside every"
                    + " candidate chain, so `not compliant_between` fails)");
        }
        System.out.println("\nDone.");
    }

    /**
     * Push one speed reading as a {@code sosa:Observation} with a zero-padded per-vehicle
     * reading IRI (lexical order == arrival order), tagging it
     * {@code fleet:speedViolationIn zone:depot} when the speed exceeds the zone limit.
     */
    private static void pushSpeedReading(DataStream stream, String sensor, String vehicle,
                                         int seq, double speedKmh) {
        boolean over = speedKmh > DEPOT_LIMIT_KMH;
        // Log first: the ASP path solves synchronously inside push, so a derived atom
        // prints immediately after the reading that completed its chain.
        System.out.println("push: " + shortId(vehicle) + " reading " + String.format("%02d", seq)
                + " @ " + speedKmh + " km/h " + (over ? "(OVER limit)" : "(compliant)"));
        IRI reading = VF.createIRI(Fleet.EX + "reading/" + shortId(vehicle)
                + "-" + String.format("%02d", seq));
        stream.push(VF.createStatement(reading, VF.createIRI(Fleet.RDF_TYPE), VF.createIRI(Fleet.OBSERVATION)));
        stream.push(VF.createStatement(reading, VF.createIRI(Fleet.MADE_BY_SENSOR), VF.createIRI(sensor)));
        stream.push(VF.createStatement(reading, VF.createIRI(Fleet.OBSERVED_PROPERTY), VF.createIRI(Fleet.SPEED)));
        stream.push(VF.createStatement(reading, VF.createIRI(Fleet.HAS_FEATURE_OF_INTEREST), VF.createIRI(vehicle)));
        stream.push(VF.createStatement(reading, VF.createIRI(Fleet.HAS_SIMPLE_RESULT), VF.createLiteral(speedKmh)));
        if (over) {
            stream.push(VF.createStatement(reading,
                    VF.createIRI(SPEED_VIOLATION_IN), VF.createIRI(DEPOT_ZONE)));
        }
    }

    private static String shortId(String iri) {
        return iri.substring(iri.lastIndexOf('/') + 1);
    }
}
