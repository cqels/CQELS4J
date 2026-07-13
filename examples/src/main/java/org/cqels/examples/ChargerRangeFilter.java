package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;

/**
 * Example — a user-defined <strong>extension function</strong> called by IRI from CQELS-QL.
 *
 * <p>Since {@code 2.0.0-alpha.11} the engine resolves an explicit function-IRI call it does not
 * recognise against RDF4J's {@code FunctionRegistry} (SPARQL 1.1 §17.6). The reference pack
 * {@code cqels-functions-ext} ships two such functions and registers them via {@code ServiceLoader},
 * so simply having the jar on the classpath makes them callable — <em>no Java glue, no registration
 * call</em>. Here {@code urn:cqels:fn:haversine(lat1, lon1, lat2, lon2)} (great-circle kilometres)
 * keeps only the vehicles currently within 5&nbsp;km of the depot's charging hub — a distance
 * geofence expressed entirely in the query:
 * <pre>
 *   BIND(cqfn:haversine(?vlat, ?vlon, 48.15, 11.58) AS ?km)   # 48.15,11.58 = depot hub
 *   FILTER(?km &lt;= 5.0)
 * </pre>
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.ChargerRangeFilter}
 */
public class ChargerRangeFilter {

    private static final String LAT = Fleet.EX + "lat";
    private static final String LON = Fleet.EX + "lon";
    private static final String OF_VEHICLE = Fleet.EX + "vehicle";

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("charger-range-filter")
                .withMemoryStore()
                .build()) {

            DataStream positions = engine.createStream("Positions");

            // PREFIX cqfn: <urn:cqels:fn:> makes the reference functions callable by prefixed name.
            String query = Fleet.PREFIXES + """
                    PREFIX cqfn: <urn:cqels:fn:>
                    REGISTER QUERY NearChargeHub AS
                    SELECT ?vehicle ?km
                    FROM STREAM Positions [TRIPLES 20]
                    WHERE {
                      STREAM Positions {
                        ?ping ex:vehicle ?vehicle .
                        ?ping ex:lat ?vlat .
                        ?ping ex:lon ?vlon .
                      }
                      BIND(cqfn:haversine(?vlat, ?vlon, 48.15, 11.58) AS ?km)
                      FILTER(?km <= 5.0)
                    }
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  WITHIN 5 km of charge hub -> " + row));

            engine.start();
            System.out.println("Depot charge hub at (48.15, 11.58). Vehicles currently within 5 km:\n"
                    + "  EV-7Q2 at (48.137, 11.575) ~1.5 km  -> should match\n"
                    + "  EV-3K8 at (48.400, 11.900) ~35 km   -> filtered out\n");

            pushPing(positions, "ping/1", Fleet.EV1, 48.137, 11.575);   // near  -> matches
            pushPing(positions, "ping/2", Fleet.EV2, 48.400, 11.900);   // far   -> filtered

            Thread.sleep(800);
        }
        System.out.println("\nDone.");
    }

    /** Push one position ping (three co-bound triples share the ping IRI). */
    private static void pushPing(DataStream stream, String id, String vehicle, double lat, double lon) {
        String ping = Fleet.EX + id;
        stream.pushTriple(ping, OF_VEHICLE, vehicle);
        stream.push(ping, LAT, lat);
        stream.push(ping, LON, lon);
    }
}
