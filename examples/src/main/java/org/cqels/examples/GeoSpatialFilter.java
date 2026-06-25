package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.geo.geosparql.GeoVocabulary;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/**
 * Example 17 — GeoSPARQL spatial filtering (cqels-geo).
 *
 * <p>With {@code cqels-geo} on the classpath, OGC [GeoSPARQL](https://www.ogc.org/standard/geosparql/)
 * {@code geof:*} functions become available inside {@code FILTER} / {@code BIND}. Here each
 * reading carries a sensor's location and the zone polygon to check it against (both as
 * typed {@code geo:wktLiteral} values); {@code geof:sfWithin} keeps only the sensors that
 * fall inside their zone.
 *
 * <p>Note: CQELS-QL has no inline {@code "…"^^geo:wktLiteral} literal syntax — geometries
 * are supplied as typed RDF literals in the data ({@code GeoVocabulary.WKT_LITERAL}) and
 * compared as variables.
 *
 * <p>Add-on dependency: {@code org.cqels:cqels-geo}.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.GeoSpatialFilter}
 */
public class GeoSpatialFilter {

    private static final String EX = "http://example.org/";
    private static final String ZONE = "POLYGON((0 0, 10 0, 10 10, 0 10, 0 0))";

    public static void main(String[] args) throws InterruptedException {
        ValueFactory vf = SimpleValueFactory.getInstance();

        try (CQELSEngine engine = CQELSEngine.builder()
                .id("geo-spatial-filter")
                .withMemoryStore()
                .build()) {

            DataStream sensors = engine.createStream("SensorData");

            String query = """
                    PREFIX ex: <http://example.org/>
                    PREFIX geof: <http://www.opengis.net/def/function/geosparql/>
                    REGISTER QUERY InZone AS
                    SELECT ?sensor ?point
                    FROM STREAM SensorData [TRIPLES 2]
                    WHERE {
                      STREAM SensorData { ?sensor ex:location ?point . ?sensor ex:zone ?zone . }
                      FILTER(geof:sfWithin(?point, ?zone))
                    }
                    """;

            engine.registerCqelsQuery(query, row ->
                    System.out.println("  in-zone -> " + row));

            engine.start();
            System.out.println("Engine started. Zone = " + ZONE + ".\n");

            // sensorA is inside the zone; sensorB is outside.
            pushReading(sensors, vf, "sensorA", "POINT(5 5)");
            Thread.sleep(300);
            pushReading(sensors, vf, "sensorB", "POINT(20 20)");
            Thread.sleep(600);
        }
        System.out.println("\nDone.");
    }

    /**
     * Push a reading as two typed-WKT triples (location + the zone to test against). The zone
     * is repeated per reading purely to keep this example single-stream and self-contained; in
     * practice you would seed the zone once into the static graph (see {@code StreamStaticJoin}).
     */
    private static void pushReading(DataStream s, ValueFactory vf, String sensor, String wktPoint) {
        System.out.println("push: " + sensor + " at " + wktPoint);
        s.push(vf.createStatement(vf.createIRI(EX + sensor), vf.createIRI(EX + "location"),
                vf.createLiteral(wktPoint, GeoVocabulary.WKT_LITERAL)));
        s.push(vf.createStatement(vf.createIRI(EX + sensor), vf.createIRI(EX + "zone"),
                vf.createLiteral(ZONE, GeoVocabulary.WKT_LITERAL)));
    }
}
