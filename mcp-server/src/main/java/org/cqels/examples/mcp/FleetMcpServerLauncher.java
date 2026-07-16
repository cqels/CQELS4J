package org.cqels.examples.mcp;

import org.cqels.engine.CQELSEngine;
import org.cqels.mcp.server.CqelsMcpServer;
import org.cqels.mcp.server.CqelsMcpServerConfig;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launches the published CQELS MCP server ({@code org.cqels:cqels-mcp}) over stdio and seeds it
 * with the <strong>electric-vehicle fleet / V2G</strong> world that the {@code examples/} use —
 * so an AI client connects to a server that already knows the fleet, its drivers, charging
 * stations and geofenced zones, and can immediately query, watch and reason over them.
 *
 * <p>This class embeds the real server rather than re-implementing tools: it builds a
 * {@link CqelsMcpServerConfig} with demo defaults (stdio transport, in-memory store),
 * {@link CqelsMcpServer#start() starts} the server — which wires the full tool/resource/prompt
 * surface and starts the engine — and then writes the fleet background graph through the
 * server's engine handle ({@link CqelsMcpServer#getEngine()}).
 *
 * <p><strong>Where the seed lands.</strong> The server's {@code store_memory} tool stores
 * long-term facts in the {@code cqels://memory/longterm} named graph, and {@code recall_memory}
 * pattern recall reads exactly the long-term + user graphs. Seeding into that same graph makes
 * the fleet world visible to both the one-shot {@code query} (SPARQL) tool and
 * {@code recall_memory} pattern recall, exactly as if a client had stored it — the cleanest
 * pre-seed the published API allows (the server exposes no dedicated bootstrap hook; the engine
 * handle is the documented advanced-use path). One boundary: {@code recall_memory}'s
 * <em>text/vector</em> search only indexes facts that arrive through {@code store_memory}, so
 * seeded facts are found by SPARQL and pattern recall, not by free-text search.
 *
 * <p><strong>stdout is reserved for the JSON-RPC protocol.</strong> All logging goes to stderr
 * (slf4j-simple's default); this class never writes to stdout. Seeding runs right after
 * {@code start()} returns — an MCP client's {@code initialize} round-trip comfortably outlasts
 * the few milliseconds the ~40-fact seed takes.
 *
 * <p>Build: {@code mvn -q package} → {@code target/cqels-mcp-server.jar}; an MCP client (e.g.
 * Claude Desktop) launches the jar and talks JSON-RPC over stdin/stdout. See README.md.
 */
public final class FleetMcpServerLauncher {

    static {
        // Same stdout-safety guard as the upstream launcher: slf4j-simple reads
        // -Dorg.slf4j.simpleLogger.logFile BEFORE any properties file, so a stray "System.out"
        // (e.g. inherited via JAVA_TOOL_OPTIONS) would interleave log lines into the JSON-RPC
        // stream. Neutralize exactly that value; stderr/file destinations are left untouched.
        String logFileProp = "org.slf4j.simpleLogger.logFile";
        if ("System.out".equalsIgnoreCase(System.getProperty(logFileProp, ""))) {
            System.setProperty(logFileProp, "System.err");
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(FleetMcpServerLauncher.class);

    // ---- the examples' fleet vocabulary (same namespaces/entities as examples/.../Fleet.java) ----
    private static final String SOSA = "http://www.w3.org/ns/sosa/";
    private static final String VSS = "https://covesa.global/vss#";                 // COVESA VSS signals
    private static final String VSSO_CORE = "https://github.com/w3c/vsso-core#";    // VSSo core ontology
    private static final String QUDT_UNIT = "http://qudt.org/vocab/unit/";
    private static final String CHARGING = "https://covesa.global/charging#";
    private static final String ZONE = "https://covesa.global/zone#";
    private static final String FLEET = "https://covesa.global/fleet#";
    private static final String SVC = "https://covesa.global/service#";
    private static final String TRAFFIC = "https://covesa.global/traffic#";
    private static final String EX = "https://example.org/fleet/";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String GEO_WKT = "http://www.opengis.net/ont/geosparql#wktLiteral";

    private static final String VEHICLE_CLASS = VSSO_CORE + "Vehicle";
    private static final String SENSOR_CLASS = SOSA + "Sensor";
    private static final String SPEED = VSS + "Speed";
    private static final String SOC = VSS + "Powertrain.TractionBattery.StateOfCharge.Current";

    private static final String EV1 = EX + "vehicle/EV-7Q2";
    private static final String EV2 = EX + "vehicle/EV-3K8";
    private static final String EV3 = EX + "vehicle/EV-9TZ";
    private static final String DEPOT = EX + "depot/north";
    private static final String STATION1 = CHARGING + "station/depot-north";
    private static final String STATION2 = CHARGING + "station/city-west";

    /**
     * The server's long-term memory graph — where {@code store_memory} puts durable facts and
     * where {@code recall_memory} pattern recall reads. Seeding here makes the fleet world
     * indistinguishable from client-stored knowledge.
     */
    private static final String LONGTERM_GRAPH = "cqels://memory/longterm";

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    public static void main(String[] args) {
        CqelsMcpServerConfig config = CqelsMcpServerConfig.builder()
                .serverName("cqels-fleet-mcp")
                .engineId("cqels-fleet-engine")
                // Everything else keeps the server's demo-friendly defaults: stdio transport,
                // in-memory store (session-scoped), semantic search on, buffered stream queries.
                .build();
        CqelsMcpServer server = new CqelsMcpServer(config);

        // SIGTERM/Ctrl-C (how MCP clients stop a stdio server) and the finally block below can
        // both fire; CqelsMcpServer.close() is idempotent, so the second call is a safe no-op.
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));

        try {
            // Wires the full tool/resource/prompt surface, starts the engine, and begins
            // serving stdio on the SDK's own threads — then returns.
            server.start();
            int seeded = seedFleetWorld(server.getEngine());
            logger.info("CQELS fleet MCP server is running (stdio); seeded {} V2G world facts "
                    + "into {}", seeded, LONGTERM_GRAPH);

            // Park main: the server runs on its own threads until the client signals us.
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.info("Main thread interrupted");
        } catch (Exception e) {
            logger.error("Fatal error starting the CQELS fleet MCP server", e);
            System.exit(1);
        } finally {
            server.close();
        }
    }

    /**
     * Seed the fleet background graph (same world as {@code examples/.../Fleet.seedStatic}):
     * vehicles + onboard sensors with their SOSA/VSSo types, fleet/driver/depot assignments,
     * charging stations, geofenced zones with speed limits, a traffic sensor, and signal→QUDT
     * unit facts. Returns the number of facts written.
     */
    static int seedFleetWorld(CQELSEngine engine) {
        IRI graph = VF.createIRI(LONGTERM_GRAPH);
        try (RepositoryConnection conn = engine.getRepository().getConnection()) {
            long before = conn.size(graph);

            // stable type facts — the VSSo/SOSA typing layer
            for (String v : new String[]{EV1, EV2, EV3}) {
                conn.add(VF.createIRI(v), VF.createIRI(RDF_TYPE), VF.createIRI(VEHICLE_CLASS), graph);
            }
            for (String s : new String[]{EX + "sensor/ev1-telematics", EX + "sensor/ev2-telematics",
                    EX + "sensor/ev3-telematics"}) {
                conn.add(VF.createIRI(s), VF.createIRI(RDF_TYPE), VF.createIRI(SENSOR_CLASS), graph);
            }

            // fleet / driver / depot (pseudonymous vehicle asset ids, never plates)
            assign(conn, graph, EV1, "Alice", "D", 35);   // next-duty reserve 35%
            assign(conn, graph, EV2, "Bob", "D", 50);
            assign(conn, graph, EV3, "Carol", "B", 20);

            // charging stations: name, max power (kW), WKT location
            station(conn, graph, STATION1, "Depot North", 150, "POINT(2 2)");
            station(conn, graph, STATION2, "City West", 50, "POINT(20 20)");

            // geofenced zones: speed limit (km/h) + WKT area
            zone(conn, graph, ZONE + "depot", 20, "POLYGON((0 0, 10 0, 10 10, 0 10, 0 0))");
            zone(conn, graph, ZONE + "school", 30, "POLYGON((0 0, 10 0, 10 10, 0 10, 0 0))");
            zone(conn, graph, ZONE + "highway", 130, "POLYGON((100 100, 200 100, 200 200, 100 200, 100 100))");

            // a traffic sensor reporting congestion on the depot approach
            conn.add(VF.createIRI(TRAFFIC + "sensor/approach-1"),
                    VF.createIRI(TRAFFIC + "congestionLevel"), VF.createLiteral(0.7), graph);
            conn.add(VF.createIRI(TRAFFIC + "sensor/approach-1"),
                    VF.createIRI(TRAFFIC + "covers"), VF.createIRI(ZONE + "depot"), graph);

            // signal → QUDT unit (per-signal, not per-observation)
            conn.add(VF.createIRI(SPEED), VF.createIRI("http://qudt.org/schema/qudt/hasUnit"),
                    VF.createIRI(QUDT_UNIT + "KiloM-PER-HR"), graph);
            conn.add(VF.createIRI(SOC), VF.createIRI("http://qudt.org/schema/qudt/hasUnit"),
                    VF.createIRI(QUDT_UNIT + "PERCENT"), graph);

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
        // GTFS-style service assignment: the next duty needs this much battery in reserve
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

    private FleetMcpServerLauncher() { }
}
