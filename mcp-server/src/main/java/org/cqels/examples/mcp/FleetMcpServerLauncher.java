package org.cqels.examples.mcp;

import org.cqels.mcp.server.CqelsMcpServer;
import org.cqels.mcp.server.CqelsMcpServerConfig;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Launches the published CQELS MCP server ({@code org.cqels:cqels-mcp}) over stdio, pre-seeded
 * with the <strong>electric-vehicle fleet / V2G</strong> world that the {@code examples/} use —
 * so an AI client connects to a server that already knows the fleet, its drivers, charging
 * stations and geofenced zones, and can immediately query, watch and reason over them.
 *
 * <p>This class embeds the real server rather than re-implementing tools, and seeds
 * <em>before</em> the server ever accepts a request:
 * <ol>
 *   <li>create a per-launch RDF store directory (a fresh temp directory by default, so demo
 *       semantics stay ephemeral; set {@code CQELS_FLEET_STORE_DIR} to persist across runs);</li>
 *   <li>open an RDF4J NativeStore at that path, write the fleet background graph, and shut the
 *       repository down (releasing the NativeStore directory lock);</li>
 *   <li>build a {@link CqelsMcpServerConfig} pointing {@code rdfStorePath} at the same
 *       directory and {@link CqelsMcpServer#start() start} the server — it reopens the store,
 *       so the world is in place before the stdio transport comes up. No startup race: even a
 *       client's very first {@code tools/call} sees the seed.</li>
 * </ol>
 *
 * <p><strong>Where the seed lands.</strong> The server's {@code store_memory} tool stores
 * long-term facts in the {@code cqels://memory/longterm} named graph, and {@code recall_memory}
 * pattern recall reads exactly the long-term + user graphs. Seeding into that same graph makes
 * the fleet world visible to both the one-shot {@code query} (SPARQL) tool and
 * {@code recall_memory} pattern recall, exactly as if a client had stored it. One boundary:
 * {@code recall_memory}'s <em>text/vector</em> search only indexes facts that arrive through
 * {@code store_memory} at runtime, so seeded facts are found by SPARQL and pattern recall, not
 * by free-text search.
 *
 * <p><strong>stdout is reserved for the JSON-RPC protocol.</strong> All logging goes to stderr
 * (slf4j-simple's default); this class never writes to stdout.
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

    /** Optional: a directory to keep the RDF store in across runs. Unset = fresh temp dir per launch. */
    static final String ENV_STORE_DIR = "CQELS_FLEET_STORE_DIR";

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
        // 1-2. Resolve the store directory and seed it BEFORE the server exists — the seeding
        //      repository is shut down (lock released) before the server reopens the same path.
        String userDir = System.getenv(ENV_STORE_DIR);
        boolean ephemeral = userDir == null || userDir.isBlank();
        Path storeDir;
        int seeded;
        try {
            storeDir = ephemeral
                    ? Files.createTempDirectory("cqels-fleet-store-")
                    : Files.createDirectories(Path.of(userDir.strip()));
            seeded = seedFleetWorld(storeDir);
        } catch (IOException e) {
            logger.error("Fatal: could not prepare the fleet RDF store directory", e);
            System.exit(1);
            return;
        }

        // 3. Point the server at the pre-seeded store. Everything else keeps the demo-friendly
        //    defaults: stdio transport, semantic search on, buffered stream queries.
        CqelsMcpServerConfig config = CqelsMcpServerConfig.builder()
                .serverName("cqels-fleet-mcp")
                .engineId("cqels-fleet-engine")
                .rdfStorePath(storeDir.toString())
                .build();
        CqelsMcpServer server = new CqelsMcpServer(config);

        // SIGTERM/Ctrl-C (how MCP clients stop a stdio server) and the finally block below can
        // both fire; CqelsMcpServer.close() is idempotent, so the second call is a safe no-op.
        // A per-launch temp store is deleted after close (the tidy demo leaves no residue); a
        // user-chosen CQELS_FLEET_STORE_DIR is kept — that is the point of setting it.
        Path cleanupDir = ephemeral ? storeDir : null;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            if (cleanupDir != null) {
                deleteRecursively(cleanupDir);
            }
        }));

        try {
            // 4. Reopens the seeded store, wires the full tool/resource/prompt surface, starts
            //    the engine, and begins serving stdio on the SDK's own threads — then returns.
            server.start();
            logger.info("CQELS fleet MCP server is running (stdio); {} V2G world facts seeded "
                    + "into {} at '{}'{}", seeded, LONGTERM_GRAPH, storeDir,
                    ephemeral ? " (per-launch temp store; set " + ENV_STORE_DIR + " to persist)" : "");

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
     * Seed the fleet background graph (same world as {@code examples/.../Fleet.seedStatic}) into
     * an RDF4J NativeStore at {@code storeDir}: vehicles + onboard sensors with their SOSA/VSSo
     * types, fleet/driver/depot assignments, charging stations, geofenced zones with speed
     * limits, a traffic sensor, and signal→QUDT unit facts. The repository is shut down before
     * returning, releasing the NativeStore directory lock so the server can open the same path.
     * Adding is idempotent (RDF set semantics), so re-seeding a kept store is harmless. Returns
     * the number of facts newly written.
     */
    static int seedFleetWorld(Path storeDir) {
        Repository repo = new SailRepository(new NativeStore(storeDir.toFile()));
        repo.init();
        try {
            IRI graph = VF.createIRI(LONGTERM_GRAPH);
            try (RepositoryConnection conn = repo.getConnection()) {
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
        } finally {
            repo.shutDown();   // release the NativeStore lock — the server reopens this directory
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

    /** Best-effort recursive delete of the per-launch temp store (children before parents). */
    private static void deleteRecursively(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup of a temp dir
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup of a temp dir
        }
    }

    private FleetMcpServerLauncher() { }
}
