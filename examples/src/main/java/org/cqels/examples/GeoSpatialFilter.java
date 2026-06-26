package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/**
 * Example — GeoSPARQL spatial filtering (cqels-geo).
 *
 * <p>With {@code cqels-geo} on the classpath, OGC [GeoSPARQL](https://www.ogc.org/standard/geosparql/)
 * {@code geof:*} functions become available in {@code FILTER} / {@code BIND}. Here each reading
 * carries the tank's WKT location and the WKT outline of a cellar zone; {@code geof:sfWithin} keeps
 * only the tanks located inside that zone.
 *
 * <p>Note: CQELS-QL has no inline {@code "…"^^geo:wktLiteral} literal syntax — geometries are supplied
 * as typed RDF literals in the data ({@code Brewery.GEO_WKT}) and compared as variables.
 *
 * <p>Add-on dependency: {@code org.cqels:cqels-geo}.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.GeoSpatialFilter}
 */
public class GeoSpatialFilter {

    private static final String CELLAR_ZONE = "POLYGON((0 0, 10 0, 10 10, 0 10, 0 0))";

    public static void main(String[] args) throws InterruptedException {
        ValueFactory vf = SimpleValueFactory.getInstance();

        try (CQELSEngine engine = CQELSEngine.builder()
                .id("geo-spatial-filter")
                .withMemoryStore()
                .build()) {

            DataStream readings = engine.createStream("Readings");

            String query = Brewery.PREFIXES + """
                    PREFIX geof: <http://www.opengis.net/def/function/geosparql/>
                    REGISTER QUERY InZone AS
                    SELECT ?tank ?loc
                    FROM STREAM Readings [TRIPLES 2]
                    WHERE {
                      STREAM Readings { ?tank ex:location ?loc . ?tank ex:zone ?zone . }
                      FILTER(geof:sfWithin(?loc, ?zone))
                    }
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  in-zone -> " + row));

            engine.start();
            System.out.println("Engine started. Cellar zone = " + CELLAR_ZONE + ".\n");

            // Tank1 (POINT 2 2) is inside the cellar zone; Tank3 (POINT 20 20) is outside.
            pushLocation(readings, vf, Brewery.TANK1, "POINT(2 2)");
            Thread.sleep(300);
            pushLocation(readings, vf, Brewery.TANK3, "POINT(20 20)");
            Thread.sleep(600);
        }
        System.out.println("\nDone.");
    }

    /** Push a reading as two typed-WKT triples (the tank location + the cellar zone to test against). */
    private static void pushLocation(DataStream s, ValueFactory vf, String tank, String wktPoint) {
        System.out.println("push: " + tank.substring(Brewery.EX.length()) + " at " + wktPoint);
        s.push(vf.createStatement(vf.createIRI(tank), vf.createIRI(Brewery.EX + "location"),
                vf.createLiteral(wktPoint, vf.createIRI(Brewery.GEO_WKT))));
        s.push(vf.createStatement(vf.createIRI(tank), vf.createIRI(Brewery.EX + "zone"),
                vf.createLiteral(CELLAR_ZONE, vf.createIRI(Brewery.GEO_WKT))));
    }
}
