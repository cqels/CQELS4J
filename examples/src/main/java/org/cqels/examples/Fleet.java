package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared vocabulary and helpers so every demo tells one coherent
 * <strong>electric-vehicle fleet / V2G</strong> story, layering
 * <strong>W3C SOSA/SSN → VSSo → COVESA VSS</strong> for richer semantics.
 *
 * <p>The world: a depot operates a small fleet of electric vehicles. Each vehicle streams
 * telemetry — speed, battery state-of-charge, location, steering, charge power — and every
 * reading is wrapped as a {@code sosa:Observation}:
 * <pre>
 *   ?obs a sosa:Observation ;
 *        sosa:madeBySensor         ?sensor ;        # an onboard sosa:Sensor
 *        sosa:observedProperty     vss:Speed ;      # a COVESA VSS signal (a VSSo ObservableVehicleProperty)
 *        sosa:hasFeatureOfInterest ?vehicle ;       # the EV (a vsso-core:Vehicle)
 *        sosa:hasSimpleResult      87.0 .           # the value (QUDT-typed unit, see signal→unit facts)
 * </pre>
 *
 * <p>The layering follows the W3C <a href="https://www.w3.org/TR/vocab-ssn/">SOSA/SSN</a> and
 * <a href="https://www.w3.org/TR/vsso/">VSSo</a> ontologies over the
 * <a href="https://covesa.global/project/vehicle-signal-specification/">COVESA VSS</a> catalogue.
 * Vehicle ids are <em>pseudonymous</em> asset ids (never plates) — the same privacy stance as a real
 * fleet deployment.
 *
 * <p>Static context (depot, charging stations + locations, geofenced zones + speed limits, drivers,
 * GTFS-style service assignments with a next-duty reserve, and a traffic sensor) is reused across the
 * demos so they form a single connected world rather than ad-hoc per-demo data.
 */
public final class Fleet {

    // ---- namespaces -----------------------------------------------------------------------
    public static final String SOSA = "http://www.w3.org/ns/sosa/";
    public static final String VSS = "https://covesa.global/vss#";                 // COVESA VSS signals
    public static final String VSSO_CORE = "https://github.com/w3c/vsso-core#";    // VSSo core ontology
    public static final String QUDT_UNIT = "http://qudt.org/vocab/unit/";          // units
    public static final String CHARGING = "https://covesa.global/charging#";       // charging stations
    public static final String ZONE = "https://covesa.global/zone#";               // geofenced zones
    public static final String FLEET = "https://covesa.global/fleet#";             // fleet / driver
    public static final String SVC = "https://covesa.global/service#";             // GTFS-style duty
    public static final String TRAFFIC = "https://covesa.global/traffic#";         // traffic sensors
    public static final String EX = "https://example.org/fleet/";                  // instance namespace
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

    // ---- VSSo ----------------------------------------------------------------------------
    public static final String VEHICLE_CLASS = VSSO_CORE + "Vehicle";

    // ---- COVESA VSS signals (the observed properties) -------------------------------------
    public static final String SPEED = VSS + "Speed";                                              // km/h
    public static final String SOC = VSS + "Powertrain.TractionBattery.StateOfCharge.Current";     // %
    public static final String CHARGE_POWER = VSS + "Powertrain.TractionBattery.Charging.PowerW";   // W (V2G charge/discharge)
    public static final String STEERING = VSS + "Chassis.SteeringWheel.Angle";                     // degrees
    public static final String LATITUDE = VSS + "CurrentLocation.Latitude";
    public static final String LONGITUDE = VSS + "CurrentLocation.Longitude";

    // ---- driving incident events (for the CEP demos) -------------------------------------
    public static final String EVENT = FLEET + "event";
    public static final String SPEED_DROP = FLEET + "SpeedDropEvent";
    public static final String SPEED_SPIKE = FLEET + "SpeedSpikeEvent";
    public static final String LANE_WEAVE = FLEET + "LaneWeaveEvent";

    // ---- fixed entities reused across demos (pseudonymous vehicle asset ids) --------------
    public static final String EV1 = EX + "vehicle/EV-7Q2";
    public static final String EV2 = EX + "vehicle/EV-3K8";
    public static final String EV3 = EX + "vehicle/EV-9TZ";
    public static final String SENSOR_EV1 = EX + "sensor/ev1-telematics";
    public static final String SENSOR_EV2 = EX + "sensor/ev2-telematics";
    public static final String SENSOR_EV3 = EX + "sensor/ev3-telematics";
    public static final String DEPOT = EX + "depot/north";
    public static final String STATION1 = CHARGING + "station/depot-north";
    public static final String STATION2 = CHARGING + "station/city-west";

    /** PREFIX header shared by the CQELS-QL queries. */
    public static final String PREFIXES = """
            PREFIX sosa:    <http://www.w3.org/ns/sosa/>
            PREFIX vss:     <https://covesa.global/vss#>
            PREFIX vsso:    <https://github.com/w3c/vsso-core#>
            PREFIX charging:<https://covesa.global/charging#>
            PREFIX zone:    <https://covesa.global/zone#>
            PREFIX fleet:   <https://covesa.global/fleet#>
            PREFIX svc:     <https://covesa.global/service#>
            PREFIX traffic: <https://covesa.global/traffic#>
            PREFIX ex:      <https://example.org/fleet/>
            """;

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final AtomicLong OBS_SEQ = new AtomicLong();
    private static final AtomicLong EVT_SEQ = new AtomicLong();

    private Fleet() { }

    /**
     * Push one VSS reading wrapped as a {@code sosa:Observation} (five triples sharing a fresh
     * observation IRI) — the canonical event used throughout the demos.
     */
    public static void pushObservation(DataStream stream, String sensor, String vehicle,
                                       String vssSignal, double value) {
        String obs = EX + "obs/" + OBS_SEQ.incrementAndGet();
        stream.pushTriple(obs, RDF_TYPE, OBSERVATION);
        stream.pushTriple(obs, MADE_BY_SENSOR, sensor);
        stream.pushTriple(obs, OBSERVED_PROPERTY, vssSignal);
        stream.pushTriple(obs, HAS_FEATURE_OF_INTEREST, vehicle);
        stream.push(obs, HAS_SIMPLE_RESULT, value);   // numeric literal
    }

    /**
     * Push one {@code sosa:Observation} with an explicit event timestamp (ms) on all five triples —
     * used by the directional/LARS window demo, which assigns events by event time.
     */
    public static void pushObservationAt(DataStream stream, String sensor, String vehicle,
                                         String vssSignal, double value, long timestamp) {
        IRI obs = VF.createIRI(EX + "obs/" + OBS_SEQ.incrementAndGet());
        stream.push(obs, VF.createIRI(RDF_TYPE), VF.createIRI(OBSERVATION), timestamp);
        stream.push(obs, VF.createIRI(MADE_BY_SENSOR), VF.createIRI(sensor), timestamp);
        stream.push(obs, VF.createIRI(OBSERVED_PROPERTY), VF.createIRI(vssSignal), timestamp);
        stream.push(obs, VF.createIRI(HAS_FEATURE_OF_INTEREST), VF.createIRI(vehicle), timestamp);
        stream.push(obs, VF.createIRI(HAS_SIMPLE_RESULT), VF.createLiteral(value), timestamp);
    }

    /** Push a driving-incident event ({@code ?event fleet:event <eventClass>}) — used by the CEP demos. */
    public static void pushDrivingEvent(DataStream stream, String eventClass) {
        stream.pushTriple(FLEET + "event/" + EVT_SEQ.incrementAndGet(), EVENT, eventClass);
    }

    /**
     * Seed the static background graph: fleet membership + driver + depot per vehicle; charging
     * stations (name, max power, WKT location); geofenced zones (speed limit + WKT area); GTFS-style
     * service assignments with a next-duty SoC reserve; and a traffic sensor. Plus a few signal→unit
     * (QUDT) facts to show the unit layer. Used by the stream–static join, geo, ASP and mission demos.
     */
    public static void seedStatic(CQELSEngine engine) {
        try (RepositoryConnection conn = engine.getRepository().getConnection()) {
            // fleet / driver / depot
            assign(conn, EV1, "Alice", "D", 35);   // next-duty reserve 35%
            assign(conn, EV2, "Bob", "D", 50);
            assign(conn, EV3, "Carol", "B", 20);

            // charging stations: name, max power (kW), WKT location
            station(conn, STATION1, "Depot North", 150, "POINT(2 2)");
            station(conn, STATION2, "City West", 50, "POINT(20 20)");

            // geofenced zones: speed limit (km/h) + WKT area
            zone(conn, ZONE + "depot", 20, "POLYGON((0 0, 10 0, 10 10, 0 10, 0 0))");
            zone(conn, ZONE + "school", 30, "POLYGON((0 0, 10 0, 10 10, 0 10, 0 0))");
            zone(conn, ZONE + "highway", 130, "POLYGON((100 100, 200 100, 200 200, 100 200, 100 100))");

            // a traffic sensor reporting congestion on the depot approach
            conn.add(VF.createIRI(TRAFFIC + "sensor/approach-1"),
                    VF.createIRI(TRAFFIC + "congestionLevel"), VF.createLiteral(0.7));
            conn.add(VF.createIRI(TRAFFIC + "sensor/approach-1"),
                    VF.createIRI(TRAFFIC + "covers"), VF.createIRI(ZONE + "depot"));

            // signal → QUDT unit (the unit layer; per-signal, not per-observation)
            conn.add(VF.createIRI(SPEED), VF.createIRI("http://qudt.org/schema/qudt/hasUnit"),
                    VF.createIRI(QUDT_UNIT + "KiloM-PER-HR"));
            conn.add(VF.createIRI(SOC), VF.createIRI("http://qudt.org/schema/qudt/hasUnit"),
                    VF.createIRI(QUDT_UNIT + "PERCENT"));
        }
    }

    private static void assign(RepositoryConnection conn, String vehicle, String driverName,
                               String licenseClass, int nextDutyReservePct) {
        IRI v = VF.createIRI(vehicle);
        IRI driver = VF.createIRI(EX + "driver/" + driverName.toLowerCase());
        conn.add(v, VF.createIRI(FLEET + "belongsToFleet"), VF.createIRI(EX + "fleet/depot-north"));
        conn.add(v, VF.createIRI(FLEET + "hostedAt"), VF.createIRI(DEPOT));
        conn.add(v, VF.createIRI(FLEET + "assignedDriver"), driver);
        conn.add(driver, VF.createIRI(FLEET + "name"), VF.createLiteral(driverName));
        conn.add(driver, VF.createIRI(FLEET + "licenseClass"), VF.createLiteral(licenseClass));
        // GTFS-style service assignment: the next duty needs this much battery in reserve
        conn.add(v, VF.createIRI(SVC + "nextDutyReserve"), VF.createLiteral(nextDutyReservePct));
        conn.add(v, VF.createIRI(SVC + "route"), VF.createLiteral("Route-" + licenseClass));
    }

    private static void station(RepositoryConnection conn, String station, String name,
                                int maxPowerKw, String wkt) {
        IRI s = VF.createIRI(station);
        conn.add(s, VF.createIRI(CHARGING + "name"), VF.createLiteral(name));
        conn.add(s, VF.createIRI(CHARGING + "maxPower"), VF.createLiteral(maxPowerKw));
        conn.add(s, VF.createIRI(EX + "location"), VF.createLiteral(wkt, VF.createIRI(GEO_WKT)));
    }

    private static void zone(RepositoryConnection conn, String zoneIri, int speedLimit, String wkt) {
        IRI z = VF.createIRI(zoneIri);
        conn.add(z, VF.createIRI(ZONE + "speedLimit"), VF.createLiteral(speedLimit));
        conn.add(z, VF.createIRI(EX + "area"), VF.createLiteral(wkt, VF.createIRI(GEO_WKT)));
    }
}
