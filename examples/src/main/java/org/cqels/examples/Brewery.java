package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared vocabulary and helpers for the demos, so every example tells one coherent
 * <strong>SOSA/SSN</strong> story: a <em>smart brewery</em> where fermentation tanks
 * ({@code sosa:FeatureOfInterest}) are monitored by InkBird IBS-TH2 temperature/humidity
 * sensors ({@code sosa:Sensor}) that emit {@code sosa:Observation}s.
 *
 * <p>The schema follows W3C <a href="https://www.w3.org/TR/vocab-ssn/">SOSA/SSN</a> and the
 * <a href="https://github.com/w3c/sdw-sosa-ssn/tree/gh-pages/ssn/rdf/examples">ACME-Beer /
 * IBS-TH2 worked example</a>. Each observation is five triples sharing one observation IRI:
 * <pre>
 *   ?obs a sosa:Observation ;
 *        sosa:madeBySensor       ?sensor ;
 *        sosa:observedProperty   qk:Temperature | qk:RelativeHumidity ;
 *        sosa:hasFeatureOfInterest ?tank ;
 *        sosa:hasSimpleResult    ?value .
 * </pre>
 *
 * <p>The fixed entities (Tank1–3, their temperature sensors, rooms and locations) are reused
 * across the demos so they form a single connected world rather than ad-hoc per-demo data.
 */
public final class Brewery {

    // ---- namespaces -----------------------------------------------------------------------
    public static final String SOSA = "http://www.w3.org/ns/sosa/";
    public static final String QK = "http://qudt.org/vocab/quantitykind/";
    public static final String EX = "http://example.org/brewery/";
    public static final String SENSOR_NS = "http://example.org/sensor/";
    public static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    public static final String RDFS_SUBCLASSOF = "http://www.w3.org/2000/01/rdf-schema#subClassOf";
    public static final String GEO_WKT = "http://www.opengis.net/ont/geosparql#wktLiteral";

    // ---- SOSA terms -----------------------------------------------------------------------
    public static final String OBSERVATION = SOSA + "Observation";
    public static final String SENSOR_CLASS = SOSA + "Sensor";
    public static final String MADE_BY_SENSOR = SOSA + "madeBySensor";
    public static final String OBSERVED_PROPERTY = SOSA + "observedProperty";
    public static final String HAS_SIMPLE_RESULT = SOSA + "hasSimpleResult";
    public static final String HAS_FEATURE_OF_INTEREST = SOSA + "hasFeatureOfInterest";

    // ---- quantity kinds (what is observed) ------------------------------------------------
    public static final String TEMPERATURE = QK + "Temperature";
    public static final String REL_HUMIDITY = QK + "RelativeHumidity";

    // ---- sensor type classes (subtypes of sosa:Sensor — drives the reasoning demo) --------
    public static final String IBS_TH2_T = SENSOR_NS + "IBS-TH2-Plus-T";   // temperature sensor
    public static final String IBS_TH2_H = SENSOR_NS + "IBS-TH2-Plus-H";   // humidity sensor

    // ---- fixed entities reused across demos -----------------------------------------------
    public static final String TANK1 = EX + "Tank1";
    public static final String TANK2 = EX + "Tank2";
    public static final String TANK3 = EX + "Tank3";
    public static final String SENSOR_T1 = EX + "sensor/tank1-T";
    public static final String SENSOR_T2 = EX + "sensor/tank2-T";
    public static final String SENSOR_T3 = EX + "sensor/tank3-T";

    /** PREFIX header shared by the CQELS-QL queries. */
    public static final String PREFIXES = """
            PREFIX sosa: <http://www.w3.org/ns/sosa/>
            PREFIX qk:   <http://qudt.org/vocab/quantitykind/>
            PREFIX ex:   <http://example.org/brewery/>
            """;

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final AtomicLong OBS_SEQ = new AtomicLong();

    private Brewery() { }

    /**
     * Push one {@code sosa:Observation} (five triples sharing a fresh observation IRI) onto a
     * stream — the canonical event used throughout the demos.
     */
    public static void pushObservation(DataStream stream, String sensor, String tank,
                                       String quantityKind, double value) {
        String obs = EX + "obs/" + OBS_SEQ.incrementAndGet();
        stream.pushTriple(obs, RDF_TYPE, OBSERVATION);
        stream.pushTriple(obs, MADE_BY_SENSOR, sensor);
        stream.pushTriple(obs, OBSERVED_PROPERTY, quantityKind);
        stream.pushTriple(obs, HAS_FEATURE_OF_INTEREST, tank);
        stream.push(obs, HAS_SIMPLE_RESULT, value);   // numeric literal
    }

    /**
     * Push one {@code sosa:Observation} with an explicit event timestamp (ms) on all five
     * triples — used by the directional/LARS window demo, which assigns events by event time.
     */
    public static void pushObservationAt(DataStream stream, String sensor, String tank,
                                         String quantityKind, double value, long timestamp) {
        IRI obs = VF.createIRI(EX + "obs/" + OBS_SEQ.incrementAndGet());
        stream.push(obs, VF.createIRI(RDF_TYPE), VF.createIRI(OBSERVATION), timestamp);
        stream.push(obs, VF.createIRI(MADE_BY_SENSOR), VF.createIRI(sensor), timestamp);
        stream.push(obs, VF.createIRI(OBSERVED_PROPERTY), VF.createIRI(quantityKind), timestamp);
        stream.push(obs, VF.createIRI(HAS_FEATURE_OF_INTEREST), VF.createIRI(tank), timestamp);
        stream.push(obs, VF.createIRI(HAS_SIMPLE_RESULT), VF.createLiteral(value), timestamp);
    }

    /**
     * Seed the static background graph: each temperature sensor's tank deployment
     * ({@code sosa:hasFeatureOfInterest}) plus each tank's room and WKT location. Used by the
     * stream–static join and geospatial demos.
     */
    public static void seedStatic(CQELSEngine engine) {
        try (RepositoryConnection conn = engine.getRepository().getConnection()) {
            deploy(conn, SENSOR_T1, TANK1, "Cellar-A", "POINT(2 2)");
            deploy(conn, SENSOR_T2, TANK2, "Cellar-A", "POINT(4 3)");
            deploy(conn, SENSOR_T3, TANK3, "Cellar-B", "POINT(20 20)");
        }
    }

    private static void deploy(RepositoryConnection conn, String sensor, String tank,
                               String room, String wkt) {
        IRI s = VF.createIRI(sensor);
        IRI t = VF.createIRI(tank);
        conn.add(s, VF.createIRI(HAS_FEATURE_OF_INTEREST), t);
        conn.add(t, VF.createIRI(EX + "room"), VF.createLiteral(room));
        conn.add(t, VF.createIRI(EX + "location"), VF.createLiteral(wkt, VF.createIRI(GEO_WKT)));
    }
}
