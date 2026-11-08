package org.cqels.examples.s2dm;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.examples.Fleet;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.rio.RDFFormat;

/**
 * Example — trailer wheel telemetry queried through the <strong>real output</strong> of COVESA
 * s2dm's RDF exporter.
 *
 * <p>The scenario: a truck tows up to three trailers, each with up to fifteen axles and up to
 * eight wheels per side of an axle. Every wheel reports tire pressure, a low-pressure flag, brake
 * pad wear and a worn-brakes flag. The fleet operator wants three things from that stream, none of
 * which should need a query edited when the model grows.
 *
 * <p><strong>Where the model comes from.</strong> {@code src/main/resources/s2dm/trailer/} holds
 * {@code skos.ttl} and {@code data_graph.ttl}, written by {@code s2dm export rdf} from s2dm's own
 * trailer example schema ({@code examples/trailer/full_schema.graphl}) at a pinned s2dm commit.
 * They are committed exactly as the tool wrote them; {@code scripts/s2dm/export-trailer.sh}
 * regenerates them in Docker. Nothing in them is hand-written, which is the difference from the
 * {@code org.cqels.examples.cdsp.S2dm*} demos, whose concept graph is hand-built in the
 * exporter's shape.
 *
 * <p>The two files describe the <em>schema</em>, not any vehicle:
 * <ul>
 *   <li>{@code data_graph.ttl} — each GraphQL type ({@code s2dm:ObjectType}), its fields
 *       ({@code s2dm:hasField}), what each field returns ({@code s2dm:hasOutputType}: another
 *       type, an enum, or a scalar such as {@code s2dm:Boolean}), and each enum's values
 *       ({@code s2dm:hasEnumValue});</li>
 *   <li>{@code skos.ttl} — a {@code skos:Concept} per type, field and enum value, with a
 *       {@code skos:prefLabel}, and a {@code skos:definition} where the schema had a description
 *       on a <em>type</em>.</li>
 * </ul>
 *
 * <p><strong>The stream encoding is this demo's, not s2dm's.</strong> s2dm defines the model but
 * no RDF form for instance data, so each reading here is one atomic element: the s2dm field it
 * reports ({@code ex:field trailer:TrailerWheelTire.pressure}), the vehicle, the value, and the
 * instance tags the schema declares with {@code @instanceTag} for a wheel: which trailer
 * ({@code TrailerTag.instance}, a {@code ThreeTrailers} value), and which wheel on it
 * ({@code TrailerWheelTag}: the axle, an {@code AxlePositionEnum} value; the side, a
 * {@code TwoSides} value; and the position counted from the outside in, an
 * {@code EightOutsideInNumber} value, which tells twin tires apart). The tags are the exporter's
 * own enum-value IRIs.
 *
 * <p>Three queries:
 * <ol>
 *   <li><em>Where in the model is this reading?</em> Each reading is placed by walking the data
 *       graph one fixed step up: the field's owning type, the slot that type fills, and the type
 *       that owns the slot, with the owning type's {@code skos:definition}. A tire pressure
 *       reading comes back as "{@code VehicleTrailerWheel.tire} → {@code TrailerWheelTire}: Tire
 *       signals for wheel." No path is spelled out in the query; it is read from the model.</li>
 *   <li><em>Every boolean alarm, without naming one.</em> The query selects readings whose field
 *       returns {@code s2dm:Boolean} and whose value is {@code true}. On this schema that is
 *       {@code isPressureLow}, {@code isBrakesWorn} and {@code isFluidLevelLow}; none of the
 *       three appears in the query text. A boolean field added to the schema is covered after the
 *       next export, with no query edit. That is only right while every boolean in the model is a
 *       fault flag, as all three here are: a field where {@code true} is good news, such as an
 *       {@code isHealthy}, would raise a false alarm, and the model has no marking that tells the
 *       two kinds apart.</li>
 *   <li><em>Lowest tire pressure per trailer and axle.</em> A windowed {@code GROUP BY} over the
 *       instance tags, restricted to the tire-pressure field. It prints its current groups on
 *       every arrival, including readings it filters out, so the same {@code [lowest]} line
 *       repeats; see "Reading the output" in {@code examples/README.md}.</li>
 * </ol>
 *
 * <p>Query 1 places only fields whose owning type fills a slot of another type and has a
 * description in the schema; a field of the root {@code Vehicle} type, or of an undescribed type,
 * is not placed. On this schema every wheel field qualifies.
 *
 * <p>One reading is deliberately wrong: {@code TrailerWheelTire.isPresureLow = true}, a misspelled
 * field. It produces no {@code [placed]} and no {@code [ALARM]} line, because the model does not
 * contain that field. Query 2 without its {@code s2dm:Boolean} guard <em>does</em> raise an alarm
 * for it, so the guard is what rejects it, not the filter on the value.
 *
 * <p><strong>What the export does not carry</strong>, so this demo cannot use it: units (the
 * schema says pressure is in kPa, via {@code pressure(unit: Pressure_Unit_Enum = KILOPASCAL)}, but
 * the RDF export drops field arguments), {@code @range} limits, which fields are instance tags,
 * and descriptions on fields (only types get a {@code skos:definition}). s2dm's VSPEC, LinkML and
 * JSON Schema exporters carry some of these, but at the pinned commit all three reject this same
 * trailer schema. See {@code S2DM_MAPPING.md}.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.s2dm.TrailerHealthMonitor}
 */
public class TrailerHealthMonitor {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final AtomicLong SEQ = new AtomicLong();

    /** The {@code --namespace} the export was run with (see scripts/s2dm/export-trailer.sh). */
    static final String T = "https://example.org/s2dm/trailer#";

    // s2dm fields this demo streams (all from the exported data graph)
    static final String TIRE_PRESSURE = T + "TrailerWheelTire.pressure";
    static final String TIRE_PRESSURE_LOW = T + "TrailerWheelTire.isPressureLow";
    static final String BRAKE_PAD_WEAR = T + "TrailerWheelBrake.padWear";
    static final String BRAKES_WORN = T + "TrailerWheelBrake.isBrakesWorn";

    // stream vocabulary: this demo's encoding of a reading (s2dm defines none)
    static final String FIELD = Fleet.EX + "field";
    static final String VEHICLE = Fleet.EX + "vehicle";
    static final String TRAILER = Fleet.EX + "trailer";
    static final String AXLE = Fleet.EX + "axle";
    static final String SIDE = Fleet.EX + "side";
    static final String NTH = Fleet.EX + "outsideIn";

    static final String PREFIXES = Fleet.PREFIXES + """
            PREFIX skos:    <http://www.w3.org/2004/02/skos/core#>
            PREFIX s2dm:    <https://covesa.global/models/s2dm#>
            PREFIX trailer: <https://example.org/s2dm/trailer#>
            """;

    public static void main(String[] args) throws Exception {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("s2dm-trailer-health")
                .withMemoryStore()
                .build()) {

            long triples = loadModel(engine);
            System.out.println("loaded the s2dm trailer model: " + triples + " triples"
                    + " (skos.ttl + data_graph.ttl, as exported)");

            DataStream wheels = engine.createStream("Wheels");

            // 1 — place each reading in the model: field -> owning type -> slot -> owner.
            String placed = PREFIXES + """
                    REGISTER QUERY PlacedReading AS
                    SELECT ?trailer ?axle ?side ?nth ?field ?value ?owner ?slot ?component ?about
                    FROM STREAM Wheels [TRIPLES 1]
                    WHERE {
                      STREAM Wheels {
                        ?r ex:field ?field ; ex:trailer ?trailer ; ex:axle ?axle ;
                           ex:side ?side ; ex:outsideIn ?nth ; sosa:hasSimpleResult ?value .
                      }
                      ?component s2dm:hasField ?field .
                      ?slot s2dm:hasOutputType ?component .
                      ?owner s2dm:hasField ?slot .
                      ?component skos:definition ?about .
                    }
                    """;
            engine.registerCqelsQuery(placed, row -> System.out.println("  [placed] " + brief(row)));

            // 2 — every true boolean is an alarm (all three in this schema are fault flags); the
            // field's output type is read from the model.
            String alarms = PREFIXES + """
                    REGISTER QUERY BooleanAlarm AS
                    SELECT ?vehicle ?trailer ?axle ?side ?nth ?field
                    FROM STREAM Wheels [TRIPLES 1]
                    WHERE {
                      STREAM Wheels {
                        ?r ex:field ?field ; ex:vehicle ?vehicle ; ex:trailer ?trailer ;
                           ex:axle ?axle ; ex:side ?side ; ex:outsideIn ?nth ;
                           sosa:hasSimpleResult ?v .
                      }
                      ?field s2dm:hasOutputType s2dm:Boolean .
                      FILTER(?v = true)
                    }
                    """;
            engine.registerCqelsQuery(alarms, row -> System.out.println("  [ALARM] " + brief(row)));

            // 3 — lowest tire pressure per trailer and axle over the last 10 s.
            String lowest = PREFIXES + """
                    REGISTER QUERY LowestPressure AS
                    SELECT ?trailer ?axle (MIN(?kpa) AS ?minKpa) (COUNT(*) AS ?readings)
                    FROM STREAM Wheels [RANGE 10s]
                    WHERE {
                      STREAM Wheels {
                        ?r ex:field trailer:TrailerWheelTire.pressure ; ex:trailer ?trailer ;
                           ex:axle ?axle ; sosa:hasSimpleResult ?kpa .
                      }
                    }
                    GROUP BY ?trailer ?axle
                    """;
            engine.registerCqelsQuery(lowest, row -> System.out.println("  [lowest] " + brief(row)));

            engine.start();

            String truck = Fleet.EX + "vehicle/TRUCK-41";
            System.out.println("\npush: trailer 1, axle 2, left — tire pressure 612 kPa");
            push(wheels, truck, TIRE_PRESSURE, "TRAILER_1", "POSITION_2", "LEFT", "_1ST", VF.createLiteral(612));
            Thread.sleep(700);

            System.out.println("\npush: trailer 1, axle 2, right, inner twin — tire pressure 455 kPa");
            push(wheels, truck, TIRE_PRESSURE, "TRAILER_1", "POSITION_2", "RIGHT", "_2ND", VF.createLiteral(455));
            Thread.sleep(700);

            System.out.println("\npush: trailer 1, axle 2, right, inner twin — isPressureLow = true");
            push(wheels, truck, TIRE_PRESSURE_LOW, "TRAILER_1", "POSITION_2", "RIGHT", "_2ND", VF.createLiteral(true));
            Thread.sleep(700);

            System.out.println("\npush: trailer 2, axle 1, left — brake pad wear 35 %");
            push(wheels, truck, BRAKE_PAD_WEAR, "TRAILER_2", "POSITION_1", "LEFT", "_1ST", VF.createLiteral(35));
            Thread.sleep(700);

            System.out.println("\npush: trailer 2, axle 1, left — isBrakesWorn = false   (a boolean"
                    + " field, but false: no alarm)");
            push(wheels, truck, BRAKES_WORN, "TRAILER_2", "POSITION_1", "LEFT", "_1ST", VF.createLiteral(false));
            Thread.sleep(700);

            System.out.println("\npush: trailer 2, axle 3, right — isBrakesWorn = true");
            push(wheels, truck, BRAKES_WORN, "TRAILER_2", "POSITION_3", "RIGHT", "_1ST", VF.createLiteral(true));
            Thread.sleep(700);

            // Not a field of the model (a typo for "isPressureLow"), carrying the value true. The
            // model is what rejects it: query 1 finds no owning type for it, and query 2's guard
            // finds no s2dm:Boolean output type. Without that guard it WOULD raise an alarm.
            System.out.println("\npush: trailer 1, axle 4, left — TrailerWheelTire.isPresureLow = true"
                    + "   (no such field in the model: no [placed], no [ALARM])");
            push(wheels, truck, T + "TrailerWheelTire.isPresureLow", "TRAILER_1", "POSITION_4", "LEFT",
                    "_1ST", VF.createLiteral(true));
            Thread.sleep(700);

            System.out.println("\npush: trailer 2, axle 1, left — tire pressure 598 kPa");
            push(wheels, truck, TIRE_PRESSURE, "TRAILER_2", "POSITION_1", "LEFT", "_1ST", VF.createLiteral(598));
            Thread.sleep(900);
        }
        System.out.println("\nDone.");
    }

    /** A result row with the export's namespace stripped, so it fits on a line. */
    static String brief(Object row) {
        return String.valueOf(row).replace(T, "");
    }

    /** Load the two exported Turtle files into the engine's static store. */
    static long loadModel(CQELSEngine engine) throws IOException {
        try (RepositoryConnection conn = engine.getRepository().getConnection()) {
            for (String file : List.of("skos.ttl", "data_graph.ttl")) {
                try (InputStream in = TrailerHealthMonitor.class
                        .getResourceAsStream("/s2dm/trailer/" + file)) {
                    if (in == null) {
                        throw new IOException("missing resource /s2dm/trailer/" + file);
                    }
                    conn.add(in, T, RDFFormat.TURTLE);
                }
            }
            return conn.size();
        }
    }

    /**
     * Push one wheel reading as a single atomic element: the s2dm field, the vehicle, the four
     * instance tags (as the exporter's enum-value IRIs) and the value.
     */
    static void push(DataStream stream, String vehicle, String field,
                     String trailer, String axle, String side, String outsideIn, Literal value) {
        IRI r = VF.createIRI(Fleet.EX + "wheel-reading/" + SEQ.incrementAndGet());
        stream.push(List.of(
                VF.createStatement(r, VF.createIRI(FIELD), VF.createIRI(field)),
                VF.createStatement(r, VF.createIRI(VEHICLE), VF.createIRI(vehicle)),
                VF.createStatement(r, VF.createIRI(TRAILER), VF.createIRI(T + "ThreeTrailers." + trailer)),
                VF.createStatement(r, VF.createIRI(AXLE), VF.createIRI(T + "AxlePositionEnum." + axle)),
                VF.createStatement(r, VF.createIRI(SIDE), VF.createIRI(T + "TwoSides." + side)),
                VF.createStatement(r, VF.createIRI(NTH), VF.createIRI(T + "EightOutsideInNumber." + outsideIn)),
                VF.createStatement(r, VF.createIRI(Fleet.HAS_SIMPLE_RESULT), value)));
    }
}
