package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;

/**
 * Example — SPARQL operators over the telemetry stream: OPTIONAL, UNION, FILTER NOT EXISTS, BIND.
 *
 * <p>CQELS-QL supports the SPARQL 1.1 algebra over streams. Each speed reading is enriched against
 * the static fleet graph, exercising four operators in one query:
 * <ul>
 *   <li>{@code OPTIONAL} — the vehicle's (possibly missing) battery state-of-health;</li>
 *   <li>{@code { … } UNION { … }} — a contact from either the supervisor or the dispatch backup;</li>
 *   <li>{@code FILTER NOT EXISTS} — drop vehicles currently in maintenance;</li>
 *   <li>{@code BIND} — compute remaining headroom to a 130 km/h reference.</li>
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

            ValueFactory vf = SimpleValueFactory.getInstance();
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                // EV1: full record — state-of-health + a named supervisor
                conn.add(vf.createIRI(Fleet.EV1), vf.createIRI(Fleet.EX + "soh"), vf.createLiteral(92.0));
                conn.add(vf.createIRI(Fleet.EV1), vf.createIRI(Fleet.EX + "supervisor"), vf.createLiteral("Alice"));
                // EV2: no SoH, only a dispatch backup contact (exercises OPTIONAL miss + UNION right side)
                conn.add(vf.createIRI(Fleet.EV2), vf.createIRI(Fleet.EX + "backupContact"), vf.createLiteral("Dispatch"));
                // EV3 is in maintenance -> excluded by FILTER NOT EXISTS
                conn.add(vf.createIRI(Fleet.EV3), vf.createIRI(Fleet.EX + "inMaintenance"), vf.createLiteral(true));
            }

            DataStream telemetry = engine.createStream("Telemetry");

            String query = Fleet.PREFIXES + """
                    REGISTER QUERY EnrichedSpeed AS
                    SELECT ?vehicle ?kmh ?contact ?soh ?headroom
                    FROM STREAM Telemetry [TRIPLES 10]
                    WHERE {
                      STREAM Telemetry {
                        ?obs sosa:observedProperty vss:Speed .
                        ?obs sosa:hasFeatureOfInterest ?vehicle .
                        ?obs sosa:hasSimpleResult ?kmh .
                      }
                      OPTIONAL { ?vehicle ex:soh ?soh . }
                      { ?vehicle ex:supervisor ?contact . } UNION { ?vehicle ex:backupContact ?contact . }
                      FILTER NOT EXISTS { ?vehicle ex:inMaintenance ?m . }
                      BIND(130 - ?kmh AS ?headroom)
                    }
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  enriched -> " + row));

            engine.start();
            System.out.println("Engine started. EV1 (full), EV2 (no SoH, backup contact), EV3 (in maintenance, dropped).\n");

            String[][] fleet = {
                    {Fleet.SENSOR_EV1, Fleet.EV1}, {Fleet.SENSOR_EV2, Fleet.EV2}, {Fleet.SENSOR_EV3, Fleet.EV3}};
            for (String[] ev : fleet) {
                System.out.println("push: " + ev[1].substring(Fleet.EX.length()) + " speed = 96 km/h");
                Fleet.pushObservation(telemetry, ev[0], ev[1], Fleet.SPEED, 96.0);
                Thread.sleep(300);
            }
            Thread.sleep(600);
        }
        System.out.println("\nDone.");
    }
}
