package org.cqels.examples;

import org.cqels.asp.config.AspStreamSolveConfig;
import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.engine.QueryResultListener;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

import java.util.List;
import java.util.Map;

/**
 * Example 16 — Answer-Set Programming over a stream (cqels-asp).
 *
 * <p>For rule-based derivation beyond SPARQL, CQELS can evaluate ASP (Answer-Set
 * Programming) programs continuously. Each RDF triple {@code (s, p, o)} becomes an ASP fact
 * {@code rdf(s, p, o)}; the program derives new atoms over each stream delta.
 *
 * <p>The rule below derives {@code colleague(X, Y)} for any two distinct people sharing an
 * employer — a genuine join + inequality, not a simple projection:
 * <pre>colleague(X, Y) :- rdf(X, iri(".../worksAt"), C), rdf(Y, iri(".../worksAt"), C), X != Y.</pre>
 *
 * <p>Add-on dependency: {@code org.cqels:cqels-asp}.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.AspReasoning}
 */
public class AspReasoning {

    private static final String EX = "http://example.org/";

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("asp-reasoning")
                .withMemoryStore()
                .build()) {

            DataStream people = engine.createStream("People");

            String program = """
                    colleague(X, Y) :- rdf(X, iri("http://example.org/worksAt"), C),
                                       rdf(Y, iri("http://example.org/worksAt"), C),
                                       X != Y.
                    """;

            AspStreamSolveConfig config = AspStreamSolveConfig.builder()
                    .inputStreamName("People")
                    .build();

            engine.registerAspQuery("Colleagues", program, config,
                    "colleague", List.of("x", "y"),
                    new QueryResultListener<Map<String, Object>>() {
                        @Override
                        public void onResult(Map<String, Object> row) {
                            System.out.println("  colleague -> " + row);
                        }

                        @Override
                        public void onError(Throwable error) {
                            System.err.println("  ASP error: " + error.getMessage());
                        }

                        @Override
                        public void onComplete() { }
                    });

            engine.start();
            System.out.println("Engine started. Rule: colleagues share an employer.\n");

            ValueFactory vf = SimpleValueFactory.getInstance();
            IRI worksAt = vf.createIRI(EX + "worksAt");
            // alice + bob -> Acme (colleagues); carol -> Globex (alone)
            for (String[] pair : new String[][]{
                    {"alice", "Acme"}, {"bob", "Acme"}, {"carol", "Globex"}}) {
                System.out.println("push: " + pair[0] + " worksAt " + pair[1]);
                people.push(vf.createStatement(
                        vf.createIRI(EX + pair[0]), worksAt, vf.createIRI(EX + pair[1])));
                Thread.sleep(300);
            }
            Thread.sleep(1000);
        }
        System.out.println("\nDone.");
    }
}
