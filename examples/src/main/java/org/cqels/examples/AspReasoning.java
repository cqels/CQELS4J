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
 * <p>The rule derives {@code monitors(Sensor, Tank)} from each observation — joining
 * {@code sosa:madeBySensor} and {@code sosa:hasFeatureOfInterest} on the observation:
 * <pre>monitors(S, T) :- rdf(O, iri(".../madeBySensor"), S), rdf(O, iri(".../hasFeatureOfInterest"), T).</pre>
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

            DataStream observations = engine.createStream("Observations");

            // Build the rule from the shared Brewery constants so it can never drift from the
            // predicate IRIs the push helper actually emits.
            String program =
                    "monitors(S, T) :- rdf(O, iri(\"" + Brewery.MADE_BY_SENSOR + "\"), S),\n"
                    + "                  rdf(O, iri(\"" + Brewery.HAS_FEATURE_OF_INTEREST + "\"), T).\n";

            AspStreamSolveConfig config = AspStreamSolveConfig.builder()
                    .inputStreamName("Observations")
                    .build();

            engine.registerAspQuery("Monitors", program, config,
                    "monitors", List.of("s", "t"),
                    new QueryResultListener<Map<String, Object>>() {
                        @Override
                        public void onResult(Map<String, Object> row) {
                            System.out.println("  monitors -> " + row);
                        }

                        @Override
                        public void onError(Throwable error) {
                            System.err.println("  ASP error: " + error.getMessage());
                        }

                        @Override
                        public void onComplete() { }
                    });

            engine.start();
            System.out.println("Engine started. Rule: a sensor monitors the tank its observations are about.\n");

            String[][] s = {
                    {Brewery.SENSOR_T1, Brewery.TANK1},
                    {Brewery.SENSOR_T2, Brewery.TANK2},
                    {Brewery.SENSOR_T3, Brewery.TANK3}};
            for (String[] sensor : s) {
                System.out.println("push: observation from " + sensor[0].substring(Brewery.EX.length()));
                Brewery.pushObservation(observations, sensor[0], sensor[1], Brewery.TEMPERATURE, 22.0);
                Thread.sleep(300);
            }
            Thread.sleep(1000);
        }
        System.out.println("\nDone.");
    }
}
