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
 * Example 15 — Continuous SHACL validation (cqels-shacl).
 *
 * <p>Validate a stream against W3C [SHACL](https://www.w3.org/TR/shacl/) shapes as data
 * arrives. The shape below requires {@code ex:alice} to have at least one {@code ex:knows}
 * edge ({@code sh:minCount 1}). We first push an unrelated triple (the constraint is
 * violated → {@code conforms=false}), then push the missing {@code ex:knows} edge (now
 * {@code conforms=true}).
 *
 * <p>Add-on dependency: {@code org.cqels:cqels-shacl}.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.ShaclValidation}
 */
public class ShaclValidation {

    private static final String SHAPES = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .

            ex:PersonShape a sh:NodeShape ;
                sh:targetNode ex:alice ;
                sh:property ex:knowsShape .

            ex:knowsShape a sh:PropertyShape ;
                sh:path ex:knows ;
                sh:minCount 1 .
            """;

    public static void main(String[] args) throws InterruptedException {
        List<Statement> shapes = new ShaclShapeParser().parseTurtle(SHAPES);

        ShaclStreamSolveConfig config = ShaclStreamSolveConfig.builder()
                .inputStreamName("events")
                .shapeStatements(shapes)
                .build();

        ShaclContinuousQuery query = new ShaclContinuousQuery("shacl-demo", "", config);

        try (CQELSEngine engine = CQELSEngine.builder()
                .id("shacl-validation")
                .withMemoryStore()
                .build()) {

            DataStream events = engine.createStream("events");

            engine.registerQuery(query, new QueryResultListener<ShaclValidationResult>() {
                @Override
                public void onResult(ShaclValidationResult r) {
                    System.out.printf("  validation -> conforms=%s violations=%d%n",
                            r.isConforms(), r.getViolations().size());
                    r.getViolations().forEach(v ->
                            System.out.println("      violation: " + v.getConstraint()
                                    + " on " + v.getFocusNode()));
                }

                @Override
                public void onError(Throwable error) {
                    System.err.println("  SHACL error: " + error.getMessage());
                }

                @Override
                public void onComplete() { }
            });

            engine.start();
            System.out.println("Engine started. Shape: ex:alice must have >=1 ex:knows.\n");

            System.out.println("push: ex:alice ex:worksAt ex:acme  (knows still missing)");
            events.pushTriple("http://example.org/alice",
                    "http://example.org/worksAt", "http://example.org/acme");
            Thread.sleep(1500);

            System.out.println("push: ex:alice ex:knows ex:bob  (constraint now satisfied)");
            events.pushTriple("http://example.org/alice",
                    "http://example.org/knows", "http://example.org/bob");
            Thread.sleep(2500);
        }
        System.out.println("\nDone.");
    }
}
