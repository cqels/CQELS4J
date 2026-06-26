package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;

/**
 * Example — SPARQL operators over the observation stream: OPTIONAL, UNION, FILTER NOT EXISTS, BIND.
 *
 * <p>CQELS-QL supports the SPARQL 1.1 algebra over streams. Each temperature observation is enriched
 * against the static brewery graph, exercising four operators in one query:
 * <ul>
 *   <li>{@code OPTIONAL} — the tank's (possibly missing) target temperature;</li>
 *   <li>{@code { … } UNION { … }} — a contact from either the supervisor or the backup;</li>
 *   <li>{@code FILTER NOT EXISTS} — drop readings from decommissioned sensors;</li>
 *   <li>{@code BIND} — compute the deviation from the fermentation set-point.</li>
 * </ul>
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.AdvancedQueryOperators}
 */
public class AdvancedQueryOperators {

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("advanced-operators")
                .withMemoryStore()
                .build()) {

            Brewery.seedStatic(engine);     // sensor -> tank -> room
            // Extra static facts for this demo's operators:
            ValueFactory vf = SimpleValueFactory.getInstance();
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                // Tank1: full record — target temp + supervisor
                conn.add(vf.createIRI(Brewery.TANK1), vf.createIRI(Brewery.EX + "targetTemp"), vf.createLiteral(20.0));
                conn.add(vf.createIRI(Brewery.TANK1), vf.createIRI(Brewery.EX + "supervisor"), vf.createLiteral("Alice"));
                // Tank2: no target temp, only a backup contact (exercises OPTIONAL miss + UNION right side)
                conn.add(vf.createIRI(Brewery.TANK2), vf.createIRI(Brewery.EX + "backupContact"), vf.createLiteral("Bob"));
                // Tank3's sensor is decommissioned -> excluded by FILTER NOT EXISTS
                conn.add(vf.createIRI(Brewery.SENSOR_T3), vf.createIRI(Brewery.EX + "decommissioned"), vf.createLiteral(true));
            }

            DataStream fermentation = engine.createStream("Fermentation");

            String query = Brewery.PREFIXES + """
                    REGISTER QUERY EnrichedReadings AS
                    SELECT ?sensor ?temp ?tank ?contact ?target ?deviation
                    FROM STREAM Fermentation [TRIPLES 10]
                    WHERE {
                      STREAM Fermentation {
                        ?obs sosa:madeBySensor ?sensor .
                        ?obs sosa:hasSimpleResult ?temp .
                      }
                      ?sensor ex:monitors ?tank .
                      OPTIONAL { ?tank ex:targetTemp ?target . }
                      { ?tank ex:supervisor ?contact . } UNION { ?tank ex:backupContact ?contact . }
                      FILTER NOT EXISTS { ?sensor ex:decommissioned ?d . }
                      BIND(?temp - 20 AS ?deviation)
                    }
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  enriched -> " + row));

            engine.start();
            System.out.println("Engine started. Tank1 (full), Tank2 (no target, backup contact), Tank3 (decommissioned, dropped).\n");

            String[][] s = {
                    {Brewery.SENSOR_T1, Brewery.TANK1},
                    {Brewery.SENSOR_T2, Brewery.TANK2},
                    {Brewery.SENSOR_T3, Brewery.TANK3}};
            for (String[] sensor : s) {
                System.out.println("push: " + sensor[1].substring(Brewery.EX.length()) + " temperature = 24.0 °C");
                Brewery.pushObservation(fermentation, sensor[0], sensor[1], Brewery.TEMPERATURE, 24.0);
                Thread.sleep(300);
            }
            Thread.sleep(600);
        }
        System.out.println("\nDone.");
    }
}
