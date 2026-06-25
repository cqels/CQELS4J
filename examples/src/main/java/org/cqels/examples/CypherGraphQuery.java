package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

/**
 * Example 12 — CypherQL: continuous property-graph queries.
 *
 * <p>CQELS also speaks Cypher. The same streaming model (windows, continuous results)
 * applies, but you match property-graph patterns instead of triple patterns. RDF data maps
 * naturally: {@code rdf:type} becomes a node label, and predicates become node properties.
 *
 * <p>Here a {@code MATCH (p:Person) WHERE p.age > 18 RETURN p, p.age} continuously reports
 * the adults appearing on the stream.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.CypherGraphQuery}
 */
public class CypherGraphQuery {

    private static final String EX = "http://example.org/";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("cypher-graph-query")
                .withMemoryStore()
                .build()) {

            DataStream people = engine.createStream("People");

            String cypher = """
                    FROM STREAM People [NOW]
                    MATCH (p:Person)
                    RETURN p
                    """;

            engine.registerCypherQuery(cypher, row ->
                    System.out.println("  person -> " + row));

            engine.start();
            System.out.println("Engine started. Cypher MATCH (p:Person) over the stream.\n");

            // Each person is a label triple (rdf:type Person); MATCH (p:Person) fires per element.
            for (String name : new String[]{"alice", "bob", "carol"}) {
                System.out.println("push: " + name + " a Person");
                people.pushTriple(EX + name, RDF_TYPE, EX + "Person");
                Thread.sleep(250);
            }

            Thread.sleep(800);
        }
        System.out.println("\nDone.");
    }

    private static void pushPerson(DataStream s, String name, long age) throws InterruptedException {
        System.out.printf("push: %s (age %d)%n", name, age);
        s.pushTriple(EX + name, RDF_TYPE, EX + "Person");
        s.push(EX + name, EX + "age", age);
        Thread.sleep(100);
    }
}
