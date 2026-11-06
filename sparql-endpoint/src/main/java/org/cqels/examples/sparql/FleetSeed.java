package org.cqels.examples.sparql;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;

/**
 * The examples' electric-vehicle fleet / V2G background graph, so a fresh endpoint has
 * something to answer. The facts, IRIs and the target graph are the ones
 * {@code mcp-server}'s {@code FleetMcpServerLauncher.seedFleetWorld} writes, kept in step by
 * hand: the two launchers are independent Maven projects with no shared module (there is no
 * aggregator in this repository), and a copy of ~40 lines was judged cheaper than inventing
 * one. If the MCP seed changes, change this too — the smoke scripts of both projects query
 * {@code fleet:assignedDriver} and would disagree.
 *
 * <p>The facts go into the MCP server's long-term memory graph rather than the default graph.
 * A SPARQL query with no dataset sees every graph on an RDF4J store, so plain
 * {@code SELECT … WHERE { … }} finds them, and the same store directory serves both launchers.
 */
final class FleetSeed {

    static final String LONGTERM_GRAPH = "cqels://memory/longterm";

    private static final String SOSA = "http://www.w3.org/ns/sosa/";
    private static final String VSS = "https://covesa.global/vss#";
    private static final String VSSO_CORE = "https://github.com/w3c/vsso-core#";
    private static final String QUDT_UNIT = "http://qudt.org/vocab/unit/";
    private static final String CHARGING = "https://covesa.global/charging#";
    private static final String ZONE = "https://covesa.global/zone#";
    private static final String FLEET = "https://covesa.global/fleet#";
    private static final String SVC = "https://covesa.global/service#";
    private static final String TRAFFIC = "https://covesa.global/traffic#";
    private static final String EX = "https://example.org/fleet/";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String GEO_WKT = "http://www.opengis.net/ont/geosparql#wktLiteral";

    private static final String EV1 = EX + "vehicle/EV-7Q2";
    private static final String EV2 = EX + "vehicle/EV-3K8";
    private static final String EV3 = EX + "vehicle/EV-9TZ";
    private static final String DEPOT = EX + "depot/north";

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private FleetSeed() { }

    /** Adds the fleet world (idempotent: RDF set semantics) and returns how many facts were new. */
    static int seed(Repository repository) {
        IRI graph = VF.createIRI(LONGTERM_GRAPH);
        try (RepositoryConnection conn = repository.getConnection()) {
            long before = conn.size(graph);

            for (String v : new String[]{EV1, EV2, EV3}) {
                conn.add(VF.createIRI(v), VF.createIRI(RDF_TYPE), VF.createIRI(VSSO_CORE + "Vehicle"), graph);
            }
            for (String s : new String[]{EX + "sensor/ev1-telematics", EX + "sensor/ev2-telematics",
                    EX + "sensor/ev3-telematics"}) {
                conn.add(VF.createIRI(s), VF.createIRI(RDF_TYPE), VF.createIRI(SOSA + "Sensor"), graph);
            }

            assign(conn, graph, EV1, "Alice", "D", 35);
            assign(conn, graph, EV2, "Bob", "D", 50);
            assign(conn, graph, EV3, "Carol", "B", 20);

            station(conn, graph, CHARGING + "station/depot-north", "Depot North", 150, "POINT(2 2)");
            station(conn, graph, CHARGING + "station/city-west", "City West", 50, "POINT(20 20)");

            zone(conn, graph, ZONE + "depot", 20, "POLYGON((0 0, 10 0, 10 10, 0 10, 0 0))");
            zone(conn, graph, ZONE + "school", 30, "POLYGON((0 0, 10 0, 10 10, 0 10, 0 0))");
            zone(conn, graph, ZONE + "highway", 130, "POLYGON((100 100, 200 100, 200 200, 100 200, 100 100))");

            conn.add(VF.createIRI(TRAFFIC + "sensor/approach-1"), VF.createIRI(TRAFFIC + "congestionLevel"),
                    VF.createLiteral(0.7), graph);
            conn.add(VF.createIRI(TRAFFIC + "sensor/approach-1"), VF.createIRI(TRAFFIC + "covers"),
                    VF.createIRI(ZONE + "depot"), graph);

            conn.add(VF.createIRI(VSS + "Speed"), VF.createIRI("http://qudt.org/schema/qudt/hasUnit"),
                    VF.createIRI(QUDT_UNIT + "KiloM-PER-HR"), graph);
            conn.add(VF.createIRI(VSS + "Powertrain.TractionBattery.StateOfCharge.Current"),
                    VF.createIRI("http://qudt.org/schema/qudt/hasUnit"), VF.createIRI(QUDT_UNIT + "PERCENT"), graph);

            return (int) (conn.size(graph) - before);
        }
    }

    private static void assign(RepositoryConnection conn, IRI graph, String vehicle,
                               String driverName, String licenseClass, int nextDutyReservePct) {
        IRI v = VF.createIRI(vehicle);
        IRI driver = VF.createIRI(EX + "driver/" + driverName.toLowerCase());
        conn.add(v, VF.createIRI(FLEET + "belongsToFleet"), VF.createIRI(EX + "fleet/depot-north"), graph);
        conn.add(v, VF.createIRI(FLEET + "hostedAt"), VF.createIRI(DEPOT), graph);
        conn.add(v, VF.createIRI(FLEET + "assignedDriver"), driver, graph);
        conn.add(driver, VF.createIRI(FLEET + "name"), VF.createLiteral(driverName), graph);
        conn.add(driver, VF.createIRI(FLEET + "licenseClass"), VF.createLiteral(licenseClass), graph);
        conn.add(v, VF.createIRI(SVC + "nextDutyReserve"), VF.createLiteral(nextDutyReservePct), graph);
        conn.add(v, VF.createIRI(SVC + "route"), VF.createLiteral("Route-" + licenseClass), graph);
    }

    private static void station(RepositoryConnection conn, IRI graph, String station, String name,
                                int maxPowerKw, String wkt) {
        IRI s = VF.createIRI(station);
        conn.add(s, VF.createIRI(CHARGING + "name"), VF.createLiteral(name), graph);
        conn.add(s, VF.createIRI(CHARGING + "maxPower"), VF.createLiteral(maxPowerKw), graph);
        conn.add(s, VF.createIRI(EX + "location"), VF.createLiteral(wkt, VF.createIRI(GEO_WKT)), graph);
    }

    private static void zone(RepositoryConnection conn, IRI graph, String zoneIri, int speedLimit, String wkt) {
        IRI z = VF.createIRI(zoneIri);
        conn.add(z, VF.createIRI(ZONE + "speedLimit"), VF.createLiteral(speedLimit), graph);
        conn.add(z, VF.createIRI(EX + "area"), VF.createLiteral(wkt, VF.createIRI(GEO_WKT)), graph);
    }
}
