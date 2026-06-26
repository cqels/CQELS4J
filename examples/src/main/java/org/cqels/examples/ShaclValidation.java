package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.engine.QueryResultListener;
import org.cqels.shacl.config.ShaclStreamSolveConfig;
import org.cqels.shacl.parser.ShaclShapeParser;
import org.cqels.shacl.query.ShaclContinuousQuery;
import org.cqels.shacl.result.ShaclValidationResult;
import org.eclipse.rdf4j.model.Statement;

import java.util.List;

/**
 * Example — continuous SHACL validation (cqels-shacl).
 *
 * <p>Validate the telemetry stream against W3C [SHACL](https://www.w3.org/TR/shacl/) shapes as data
 * arrives. The shape below requires every {@code sosa:Observation} to carry a numeric result
 * ({@code sosa:hasSimpleResult}, {@code sh:minCount 1}). We first push a malformed observation
 * (sensor + signal, but no result → {@code conforms=false}), then supply the result for the same
 * observation ({@code conforms=true}).
 *
 * <p>Add-on dependency: {@code org.cqels:cqels-shacl}.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.ShaclValidation}
 */
public class ShaclValidation {

    private static final String SHAPES = """
            @prefix sh:   <http://www.w3.org/ns/shacl#> .
            @prefix sosa: <http://www.w3.org/ns/sosa/> .
            @prefix ex:   <https://example.org/fleet/> .

            ex:ObservationShape a sh:NodeShape ;
                sh:targetClass sosa:Observation ;
                sh:property ex:resultShape .

            ex:resultShape a sh:PropertyShape ;
                sh:path sosa:hasSimpleResult ;
                sh:minCount 1 .
            """;

    public static void main(String[] args) throws InterruptedException {
        List<Statement> shapes = new ShaclShapeParser().parseTurtle(SHAPES);

        ShaclStreamSolveConfig config = ShaclStreamSolveConfig.builder()
                .inputStreamName("Telemetry")
                .shapeStatements(shapes)
                .build();

        ShaclContinuousQuery query = new ShaclContinuousQuery("shacl-demo", "", config);

        try (CQELSEngine engine = CQELSEngine.builder()
                .id("shacl-validation")
                .withMemoryStore()
                .build()) {

            DataStream telemetry = engine.createStream("Telemetry");

            engine.registerQuery(query, new QueryResultListener<ShaclValidationResult>() {
                @Override
                public void onResult(ShaclValidationResult r) {
                    System.out.printf("  validation -> conforms=%s violations=%d%n",
                            r.isConforms(), r.getViolations().size());
                    r.getViolations().forEach(v ->
                            System.out.println("      violation: " + v.getConstraint() + " on " + v.getFocusNode()));
                }

                @Override
                public void onError(Throwable error) {
                    System.err.println("  SHACL error: " + error.getMessage());
                }

                @Override
                public void onComplete() { }
            });

            engine.start();
            System.out.println("Engine started. Shape: every sosa:Observation needs a result.\n");

            // An observation typed + sensor + signal, but (at first) NO sosa:hasSimpleResult -> violation.
            String obs = Fleet.EX + "obs/1";
            System.out.println("push: observation " + obs.substring(Fleet.EX.length()) + " without a result");
            telemetry.pushTriple(obs, Fleet.RDF_TYPE, Fleet.OBSERVATION);
            telemetry.pushTriple(obs, Fleet.MADE_BY_SENSOR, Fleet.SENSOR_EV1);
            telemetry.pushTriple(obs, Fleet.OBSERVED_PROPERTY, Fleet.SOC);
            Thread.sleep(1500);

            // Supply the missing result for the SAME observation -> it now conforms.
            System.out.println("push: the missing result for the same observation");
            telemetry.push(obs, Fleet.HAS_SIMPLE_RESULT, 54.0);
            Thread.sleep(2500);
        }
        System.out.println("\nDone.");
    }
}
