package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;

/**
 * Example 10 — SPARQL operators over a stream: OPTIONAL, UNION, FILTER NOT EXISTS, BIND.
 *
 * <p>CQELS-QL supports the SPARQL 1.1 algebra over streams. A stream of order events is
 * enriched against a static product catalogue, demonstrating four operators in one query:
 * <ul>
 *   <li>{@code OPTIONAL} — left-join the (possibly missing) product description;</li>
 *   <li>{@code { … } UNION { … }} — accept a supplier from either of two predicates;</li>
 *   <li>{@code FILTER NOT EXISTS} — drop products flagged discontinued;</li>
 *   <li>{@code BIND} — compute a line-total from quantity and price.</li>
 * </ul>
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.AdvancedQueryOperators}
 */
public class AdvancedQueryOperators {

    private static final String EX = "http://example.org/";

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("advanced-operators")
                .withMemoryStore()
                .build()) {

            ValueFactory vf = SimpleValueFactory.getInstance();
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                // p1: full record (description + primary supplier), price 10
                conn.add(vf.createIRI(EX + "p1"), vf.createIRI(EX + "price"), vf.createLiteral(10.0));
                conn.add(vf.createIRI(EX + "p1"), vf.createIRI(EX + "description"), vf.createLiteral("Widget"));
                conn.add(vf.createIRI(EX + "p1"), vf.createIRI(EX + "supplier"), vf.createLiteral("Acme"));
                // p2: no description, only an ALTERNATE supplier (exercises OPTIONAL miss + UNION right side)
                conn.add(vf.createIRI(EX + "p2"), vf.createIRI(EX + "price"), vf.createLiteral(25.0));
                conn.add(vf.createIRI(EX + "p2"), vf.createIRI(EX + "alternateSupplier"), vf.createLiteral("Globex"));
                // p3: discontinued -> excluded by FILTER NOT EXISTS
                conn.add(vf.createIRI(EX + "p3"), vf.createIRI(EX + "price"), vf.createLiteral(5.0));
                conn.add(vf.createIRI(EX + "p3"), vf.createIRI(EX + "supplier"), vf.createLiteral("Initech"));
                conn.add(vf.createIRI(EX + "p3"), vf.createIRI(EX + "discontinued"), vf.createLiteral(true));
            }

            DataStream orders = engine.createStream("Orders");

            String query = """
                    PREFIX ex: <http://example.org/>
                    REGISTER QUERY OrderEnrichment AS
                    SELECT ?product ?qty ?supplier ?description ?lineTotal
                    FROM STREAM Orders [TRIPLES 2]
                    WHERE {
                      STREAM Orders { ?o ex:product ?product ; ex:qty ?qty . }
                      ?product ex:price ?price .
                      OPTIONAL { ?product ex:description ?description . }
                      { ?product ex:supplier ?supplier . }
                      UNION
                      { ?product ex:alternateSupplier ?supplier . }
                      FILTER NOT EXISTS { ?product ex:discontinued ?d . }
                      BIND(?qty * ?price AS ?lineTotal)
                    }
                    """;

            engine.registerCqelsQuery(query, row ->
                    System.out.println("  order -> " + row));

            engine.start();
            System.out.println("Engine started. p1 (full), p2 (alt-supplier, no desc), p3 (discontinued, dropped).\n");

            for (String p : new String[]{"p1", "p2", "p3"}) {
                System.out.println("push: order for " + p + " qty 3");
                orders.pushTriple(EX + "order/" + p, EX + "product", EX + p); // object is an IRI
                orders.push(EX + "order/" + p, EX + "qty", 3L);
                Thread.sleep(300);
            }
            Thread.sleep(600);
        }
        System.out.println("\nDone.");
    }
}
