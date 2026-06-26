package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/**
 * Example — GeoSPARQL spatial filtering (cqels-geo).
 *
 * <p>With {@code cqels-geo} on the classpath, OGC [GeoSPARQL](https://www.ogc.org/standard/geosparql/)
 * {@code geof:*} functions become available in {@code FILTER} / {@code BIND}. Each vehicle streams a
 * location {@code sosa:Observation} ({@code observedProperty vss:CurrentLocation}, result = a WKT
 * point) alongside the depot geofence outline; {@code geof:sfWithin} keeps only the vehicles currently
 * inside the depot zone (e.g. plugged in and chargeable).
 *
 * <p>Note: CQELS-QL has no inline {@code "…"^^geo:wktLiteral} literal syntax — geometries are supplied
 * as typed RDF literals in the data ({@code Fleet.GEO_WKT}) and compared as variables. To keep the
 * demo single-stream and self-contained the geofence polygon is shipped with each reading; in practice
 * you would seed it once into the static graph (cf. {@link Fleet#seedStatic}, which stores the same
 * {@code zone:depot} outline) and join it as in {@code StreamStaticJoin}.
 *
 * <p>Add-on dependency: {@code org.cqels:cqels-geo}.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.GeoSpatialFilter}
 */
public class GeoSpatialFilter {

    private static final String DEPOT_GEOFENCE = "POLYGON((0 0, 10 0, 10 10, 0 10, 0 0))";

    public static void main(String[] args) throws InterruptedException {
        ValueFactory vf = SimpleValueFactory.getInstance();

        try (CQELSEngine engine = CQELSEngine.builder()
                .id("geo-spatial-filter")
                .withMemoryStore()
                .build()) {

            DataStream telemetry = engine.createStream("Telemetry");

            String query = Fleet.PREFIXES + """
                    PREFIX geof: <http://www.opengis.net/def/function/geosparql/>
                    REGISTER QUERY InDepot AS
                    SELECT ?vehicle ?loc
                    FROM STREAM Telemetry [TRIPLES 12]
                    WHERE {
                      STREAM Telemetry {
                        ?obs sosa:observedProperty vss:CurrentLocation .
                        ?obs sosa:hasFeatureOfInterest ?vehicle .
                        ?obs sosa:hasSimpleResult ?loc .
                        ?vehicle ex:geofence ?area .
                      }
                      FILTER(geof:sfWithin(?loc, ?area))
                    }
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  in-depot -> " + row));

            engine.start();
            System.out.println("Engine started. Depot geofence = " + DEPOT_GEOFENCE + ".\n");

            // EV-7Q2 (POINT(2 2)) is inside the depot zone; EV-9TZ (POINT(20 20)) is out on the road.
            pushLocation(telemetry, vf, Fleet.SENSOR_EV1, Fleet.EV1, "POINT(2 2)");
            Thread.sleep(300);
            pushLocation(telemetry, vf, Fleet.SENSOR_EV3, Fleet.EV3, "POINT(20 20)");
            Thread.sleep(600);
        }
        System.out.println("\nDone.");
    }

    /** Push a vehicle's location as a SOSA observation (WKT result) plus the depot geofence to test against. */
    private static void pushLocation(DataStream s, ValueFactory vf, String sensor, String vehicle, String wktPoint) {
        System.out.println("push: " + vehicle.substring(Fleet.EX.length()) + " at " + wktPoint);
        Fleet.pushLocationObservation(s, sensor, vehicle, wktPoint);
        s.push(vf.createStatement(vf.createIRI(vehicle), vf.createIRI(Fleet.EX + "geofence"),
                vf.createLiteral(DEPOT_GEOFENCE, vf.createIRI(Fleet.GEO_WKT))));
    }
}
