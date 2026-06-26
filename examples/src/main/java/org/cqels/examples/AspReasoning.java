package org.cqels.examples;

import org.cqels.asp.config.AspStreamSolveConfig;
import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.engine.QueryResultListener;

import java.util.List;
import java.util.Map;

/**
 * Example — Answer-Set Programming over the stream (cqels-asp).
 *
 * <p>For rule-based derivation beyond SPARQL, CQELS evaluates ASP programs continuously. Each RDF
 * triple {@code (s, p, o)} becomes an ASP fact {@code rdf(s, p, o)}; the program derives new atoms
 * over each stream delta.
 *
 * <p>The rule derives {@code convoy(V1, V2)} for any two distinct vehicles currently reporting
 * telemetry — a genuine join + inequality over the {@code sosa:hasFeatureOfInterest} links, i.e. the
 * set of vehicles active together:
 * <pre>convoy(V1, V2) :- rdf(O1, iri(".../hasFeatureOfInterest"), V1),
 *                    rdf(O2, iri(".../hasFeatureOfInterest"), V2), V1 != V2.</pre>
 *
 * <p>Add-on dependency: {@code org.cqels:cqels-asp}.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.AspReasoning}
 */
public class AspReasoning {

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("asp-reasoning")
                .withMemoryStore()
                .build()) {

            DataStream telemetry = engine.createStream("Telemetry");

            // Build the rule from the shared Fleet constant so it can never drift from the predicate
            // IRI the push helper actually emits.
            String program =
                    "convoy(V1, V2) :- rdf(O1, iri(\"" + Fleet.HAS_FEATURE_OF_INTEREST + "\"), V1),\n"
                    + "                  rdf(O2, iri(\"" + Fleet.HAS_FEATURE_OF_INTEREST + "\"), V2),\n"
                    + "                  V1 != V2.\n";

            AspStreamSolveConfig config = AspStreamSolveConfig.builder()
                    .inputStreamName("Telemetry")
                    .build();

            engine.registerAspQuery("Convoy", program, config,
                    "convoy", List.of("v1", "v2"),
                    new QueryResultListener<Map<String, Object>>() {
                        @Override
                        public void onResult(Map<String, Object> row) {
                            System.out.println("  convoy -> " + row);
                        }

                        @Override
                        public void onError(Throwable error) {
                            System.err.println("  ASP error: " + error.getMessage());
                        }

                        @Override
                        public void onComplete() { }
                    });

            engine.start();
            System.out.println("Engine started. Rule: vehicles reporting telemetry together form a convoy.\n");

            String[][] fleet = {
                    {Fleet.SENSOR_EV1, Fleet.EV1}, {Fleet.SENSOR_EV2, Fleet.EV2}};
            for (String[] ev : fleet) {
                System.out.println("push: telemetry from " + ev[1].substring(Fleet.EX.length()));
                Fleet.pushObservation(telemetry, ev[0], ev[1], Fleet.SPEED, 60.0);
                Thread.sleep(300);
            }
            Thread.sleep(1000);
        }
        System.out.println("\nDone.");
    }
}
