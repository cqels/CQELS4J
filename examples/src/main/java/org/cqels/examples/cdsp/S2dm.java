package org.cqels.examples.cdsp;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.examples.Fleet;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared vocabulary and helpers for the <strong>COVESA S2DM</strong> demos — the concept layer
 * that sits <em>above</em> the raw VSS signals the rest of the fleet world streams.
 *
 * <p><a href="https://github.com/COVESA/s2dm">S2DM</a> (Simplified Semantic Data Modeling) is a
 * COVESA approach for modelling data across domains. Subject-matter experts write
 * <strong>GraphQL SDL</strong>; the tooling projects that schema into <strong>SKOS</strong> RDF —
 * which is the artifact CQELS can actually consume. The projection, as specified by
 * s2dm's own {@code skos-mapping-diagram.md}, is:
 *
 * <pre>
 *   GraphQL object type   -> skos:Concept, member of the ObjectConcepts collection
 *   GraphQL field         -> skos:Concept ("Type.field"), member of FieldConcepts
 *   GraphQL enum          -> skos:Collection
 *   GraphQL enum value    -> skos:Concept, member of that enum's collection AND of FieldConcepts
 * </pre>
 *
 * <p>Produced by {@code s2dm generate skos-skeleton --schema Vehicle.graphql --output vss.ttl}.
 * The concepts seeded in {@link #seedConceptGraph} follow the <em>shape</em> the exporter emits —
 * the typing, labelling and collection structure read off its {@code skos.py} — so the demos
 * exercise real s2dm output rather than an invented encoding. The concepts themselves are only
 * partly drawn from the repository's schemas ({@code examples/graphql-to-skos/sample.graphql}
 * contributes {@code Vehicle.averageSpeed}; {@code examples/seat-to-vspec} contributes
 * {@code Cabin} and {@code Seat}); {@code Door} and {@code Powertrain} are added here so the
 * demos have a hierarchy worth rolling up. It is a faithful shape over a convenient vocabulary,
 * not a reproduction of either schema.
 *
 * <p>Two things the real exporter also emits that are not modelled here, because no demo reads
 * them: {@code skos:note} on described concepts, and the per-enum {@code skos:Collection} for
 * each GraphQL enum.
 *
 * <p><strong>Why this matters for CQELS.</strong> A VSS observation carries a signal IRI and a
 * number. That is enough to compute with, but not enough to <em>explain</em>: nothing in the
 * stream says what {@code Cabin.isAnyDoorOpen} means, which broader part of the vehicle it
 * belongs to, or what a consumer should call it. The s2dm SKOS graph supplies exactly that, and
 * because it is ordinary RDF it drops into CQELS as static context — so a continuous query can
 * join live telemetry against the concept catalogue in one pattern (see
 * {@link S2dmConceptCatalog}), roll observations up a concept hierarchy
 * ({@link SkosConceptRollup}), or partition them by instance tag ({@link S2dmInstanceZones}).
 *
 * <p><strong>Instance tags.</strong> VSS explodes repeated structures into paths
 * ({@code Vehicle.Cabin.Door.Row1.DriverSide.IsOpen}). S2DM models the repetition once and tags
 * the instance with a small object — the {@code InCabinZone} pattern from
 * {@code /spec/common_enums.graphql}:
 * <pre>
 *   type InCabinZone { row: InCabinRowEnum!  side: InCabinSideEnum! }
 *   enum InCabinRowEnum  { FRONT REAR }
 *   enum InCabinSideEnum { DRIVER_SIDE PASSENGER_SIDE }
 *   type Door { instance: InCabinZone  isOpen: Boolean }
 * </pre>
 * {@link #pushZonedSignal} streams a reading in that shape: one atomic element carrying the
 * signal, the value, the vehicle and the zone's row/side.
 */
public final class S2dm {

    // ---- namespaces -----------------------------------------------------------------------
    /** W3C SKOS — the vocabulary s2dm projects its GraphQL schema into. */
    public static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    /** The official COVESA s2dm ontology namespace (see s2dm's skos-mapping-diagram.md). */
    public static final String S2DM = "https://covesa.global/models/s2dm#";
    /** Concept namespace — the {@code --namespace} argument of {@code s2dm generate skos-skeleton}. */
    public static final String CONCEPT = "https://example.org/vss#";

    // ---- SKOS terms -----------------------------------------------------------------------
    public static final String SKOS_CONCEPT = SKOS + "Concept";
    public static final String SKOS_COLLECTION = SKOS + "Collection";
    public static final String SKOS_PREF_LABEL = SKOS + "prefLabel";
    public static final String SKOS_DEFINITION = SKOS + "definition";
    public static final String SKOS_BROADER = SKOS + "broader";
    public static final String SKOS_MEMBER = SKOS + "member";

    // The exporter gives every concept a SECOND rdf:type from the s2dm ontology, which is what a
    // consumer queries when it wants "all object types" without relying on collection membership.
    public static final String S2DM_OBJECT_TYPE = S2DM + "ObjectType";
    public static final String S2DM_FIELD = S2DM + "Field";

    // ---- the two collections every s2dm skeleton emits --------------------------------------
    public static final String OBJECT_CONCEPTS = CONCEPT + "ObjectConcepts";
    public static final String FIELD_CONCEPTS = CONCEPT + "FieldConcepts";

    // ---- object concepts (GraphQL types) ----------------------------------------------------
    public static final String C_VEHICLE = CONCEPT + "Vehicle";
    public static final String C_CABIN = CONCEPT + "Cabin";
    public static final String C_SEAT = CONCEPT + "Seat";
    public static final String C_DOOR = CONCEPT + "Door";
    public static final String C_POWERTRAIN = CONCEPT + "Powertrain";

    // ---- field concepts (GraphQL fields, "Type.field") ---------------------------------------
    public static final String F_AVERAGE_SPEED = CONCEPT + "Vehicle.averageSpeed";
    public static final String F_SEAT_OCCUPIED = CONCEPT + "Seat.isOccupied";
    public static final String F_DOOR_OPEN = CONCEPT + "Door.isOpen";
    public static final String F_BATTERY_SOC = CONCEPT + "Powertrain.stateOfCharge";

    // ---- the InCabinZone instance-tag vocabulary --------------------------------------------
    public static final String ZONE_ROW = CONCEPT + "row";
    public static final String ZONE_SIDE = CONCEPT + "side";
    public static final String ROW_FRONT = CONCEPT + "FRONT";
    public static final String ROW_REAR = CONCEPT + "REAR";
    public static final String SIDE_DRIVER = CONCEPT + "DRIVER_SIDE";
    public static final String SIDE_PASSENGER = CONCEPT + "PASSENGER_SIDE";

    /** The predicate linking an observation to the s2dm concept it instantiates. */
    public static final String OF_CONCEPT = CONCEPT + "ofConcept";

    /** PREFIX header for the s2dm demos — the fleet prefixes plus SKOS and the concept namespace. */
    public static final String PREFIXES = Fleet.PREFIXES + """
            PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
            PREFIX s2dm: <https://covesa.global/models/s2dm#>
            PREFIX c:    <https://example.org/vss#>
            """;

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final AtomicLong OBS_SEQ = new AtomicLong();

    private S2dm() { }

    /**
     * Seed the s2dm SKOS concept graph — the RDF a {@code s2dm generate skos-skeleton} run emits
     * for a small vehicle schema.
     *
     * <p>Every object type and field becomes a {@code skos:Concept} with a {@code skos:prefLabel}
     * (mandatory in the s2dm SHACL shapes) and, where the GraphQL description existed, a
     * {@code skos:definition}. Each is a member of {@code ObjectConcepts} or {@code FieldConcepts}
     * respectively.
     *
     * <p>On top of the skeleton this adds the {@code skos:broader} edges that make the catalogue a
     * <em>hierarchy</em> — {@code Seat} and {@code Door} are narrower than {@code Cabin}, which is
     * narrower than {@code Vehicle}. The skeleton generator does not infer those (nothing in a
     * GraphQL type graph says a field reference is a taxonomic narrowing), so in a real deployment
     * a modeller curates them; here they are asserted directly. {@link SkosConceptRollup} is the
     * demo that puts them to work.
     */
    public static void seedConceptGraph(CQELSEngine engine) {
        try (RepositoryConnection conn = engine.getRepository().getConnection()) {
            // the two collections the s2dm SHACL shapes require to be non-empty
            collection(conn, OBJECT_CONCEPTS, "Object Concepts");
            collection(conn, FIELD_CONCEPTS, "Field Concepts");

            // -- object concepts (GraphQL types) --
            objectConcept(conn, C_VEHICLE, "Vehicle", "High-level vehicle data.");
            objectConcept(conn, C_CABIN, "Cabin", "All in-cabin components, including doors.");
            objectConcept(conn, C_SEAT, "Seat", "A seat within the cabin, tagged by in-cabin zone.");
            objectConcept(conn, C_DOOR, "Door", "A door within the cabin, tagged by in-cabin zone.");
            objectConcept(conn, C_POWERTRAIN, "Powertrain", "Powertrain and traction-battery data.");

            // -- field concepts (GraphQL fields) --
            fieldConcept(conn, F_AVERAGE_SPEED, "Vehicle.averageSpeed", "Average speed of the vehicle");
            fieldConcept(conn, F_SEAT_OCCUPIED, "Seat.isOccupied", "Whether the seat is occupied.");
            fieldConcept(conn, F_DOOR_OPEN, "Door.isOpen", "Whether the door is open.");
            fieldConcept(conn, F_BATTERY_SOC, "Powertrain.stateOfCharge", "Traction-battery state of charge.");

            // -- the curated taxonomy: narrower -> broader --
            broader(conn, C_SEAT, C_CABIN);
            broader(conn, C_DOOR, C_CABIN);
            broader(conn, C_CABIN, C_VEHICLE);
            broader(conn, C_POWERTRAIN, C_VEHICLE);

            // -- which VSS signal realises which s2dm field concept --
            // The bridge between the streaming layer (VSS signal IRIs) and the concept layer.
            conn.add(VF.createIRI(Fleet.SPEED), VF.createIRI(OF_CONCEPT), VF.createIRI(F_AVERAGE_SPEED));
            conn.add(VF.createIRI(Fleet.SOC), VF.createIRI(OF_CONCEPT), VF.createIRI(F_BATTERY_SOC));
        }
    }

    /** A collection, with the language-tagged prefLabel the exporter gives it. */
    private static void collection(RepositoryConnection conn, String iri, String label) {
        conn.add(VF.createIRI(iri), VF.createIRI(Fleet.RDF_TYPE), VF.createIRI(SKOS_COLLECTION));
        conn.add(VF.createIRI(iri), VF.createIRI(SKOS_PREF_LABEL), VF.createLiteral(label, "en"));
    }

    /** A GraphQL object type as {@code skos:Concept} + membership of {@code ObjectConcepts}. */
    private static void objectConcept(RepositoryConnection conn, String iri, String label, String definition) {
        concept(conn, iri, label, definition, OBJECT_CONCEPTS, S2DM_OBJECT_TYPE);
    }

    /** A GraphQL field as {@code skos:Concept} + membership of {@code FieldConcepts}. */
    private static void fieldConcept(RepositoryConnection conn, String iri, String label, String definition) {
        concept(conn, iri, label, definition, FIELD_CONCEPTS, S2DM_FIELD);
    }

    /**
     * One concept in the shape {@code s2dm generate skos-skeleton} actually emits — read off the
     * exporter's {@code skos.py} rather than assumed:
     * <ul>
     *   <li>{@code rdf:type skos:Concept} <em>and</em> a second type from the s2dm ontology
     *       ({@code s2dm:ObjectType} or {@code s2dm:Field});</li>
     *   <li>a <strong>language-tagged</strong> {@code skos:prefLabel} — the exporter always writes
     *       {@code Literal(label, lang=...)}, so a query matching a plain literal finds nothing;</li>
     *   <li>an untagged {@code skos:definition};</li>
     *   <li>membership of the matching collection.</li>
     * </ul>
     */
    private static void concept(RepositoryConnection conn, String iri, String label,
                                String definition, String collection, String s2dmType) {
        IRI c = VF.createIRI(iri);
        conn.add(c, VF.createIRI(Fleet.RDF_TYPE), VF.createIRI(SKOS_CONCEPT));
        conn.add(c, VF.createIRI(Fleet.RDF_TYPE), VF.createIRI(s2dmType));
        conn.add(c, VF.createIRI(SKOS_PREF_LABEL), VF.createLiteral(label, "en"));
        conn.add(c, VF.createIRI(SKOS_DEFINITION), VF.createLiteral(definition));
        conn.add(VF.createIRI(collection), VF.createIRI(SKOS_MEMBER), c);
    }

    private static void broader(RepositoryConnection conn, String narrower, String broader) {
        conn.add(VF.createIRI(narrower), VF.createIRI(SKOS_BROADER), VF.createIRI(broader));
    }

    /**
     * Push one zone-tagged signal reading as a single <em>atomic</em> five-statement element:
     * the observation's concept, its value, its vehicle, and the {@code InCabinZone} row/side that
     * identifies <em>which</em> door or seat produced it.
     *
     * <p>Atomicity matters for the same reason it does in {@link CorrelatedFaultCascade}: a query
     * that binds concept, vehicle, row and side in one graph pattern only sees a consistent
     * assignment if all five statements arrive as one element. Pushed separately they would
     * cross-product across vehicles and zones.
     */
    public static void pushZonedSignal(DataStream stream, String vehicle, String fieldConcept,
                                       String row, String side, double value) {
        IRI obs = VF.createIRI(Fleet.EX + "s2dm-obs/" + OBS_SEQ.incrementAndGet());
        stream.push(List.of(
                VF.createStatement(obs, VF.createIRI(OF_CONCEPT), VF.createIRI(fieldConcept)),
                VF.createStatement(obs, VF.createIRI(Fleet.HAS_FEATURE_OF_INTEREST), VF.createIRI(vehicle)),
                VF.createStatement(obs, VF.createIRI(ZONE_ROW), VF.createIRI(row)),
                VF.createStatement(obs, VF.createIRI(ZONE_SIDE), VF.createIRI(side)),
                VF.createStatement(obs, VF.createIRI(Fleet.HAS_SIMPLE_RESULT), VF.createLiteral(value))));
    }

    /**
     * Push one observation tagged with the s2dm concept it instantiates, with no instance tag —
     * three statements in one atomic element.
     *
     * <p>The concept may be a <em>field</em> concept ({@code Vehicle.averageSpeed}), as in
     * {@link S2dmConceptCatalog}, or an <em>object</em> concept ({@code Seat}, {@code Door}),
     * as in {@link SkosConceptRollup} where it is rolled up the {@code skos:broader} chain.
     * Use {@link #pushZonedSignal} when the reading needs an {@code InCabinZone} tag as well.
     */
    public static void pushConceptSignal(DataStream stream, String vehicle, String concept, double value) {
        IRI obs = VF.createIRI(Fleet.EX + "s2dm-obs/" + OBS_SEQ.incrementAndGet());
        stream.push(List.of(
                VF.createStatement(obs, VF.createIRI(OF_CONCEPT), VF.createIRI(concept)),
                VF.createStatement(obs, VF.createIRI(Fleet.HAS_FEATURE_OF_INTEREST), VF.createIRI(vehicle)),
                VF.createStatement(obs, VF.createIRI(Fleet.HAS_SIMPLE_RESULT), VF.createLiteral(value))));
    }

    /**
     * Push the same observation as {@link #pushConceptSignal}, but as <em>separate single-statement
     * elements</em> rather than one atomic element.
     *
     * <p>This exists solely to work around a reasoner limitation on 2.0.0-alpha.18:
     * {@code ReactiveReteAdapter} processes single-statement stream elements and silently skips
     * multi-statement (atomic graph push) ones, so an observation pushed atomically never reaches
     * the rule network. Verified with a two-case probe — see issue #57.
     *
     * <p>The cost of the workaround is real: the statements are no longer atomic, so a query
     * joining them must use a window wide enough to hold the whole group, and concurrent producers
     * can interleave. {@link #pushConceptSignal} remains the right call everywhere reasoning is
     * not involved.
     */
    public static void pushConceptSignalUnbatched(DataStream stream, String vehicle,
                                                  String concept, double value) {
        String obs = Fleet.EX + "s2dm-obs/" + OBS_SEQ.incrementAndGet();
        stream.pushTriple(obs, OF_CONCEPT, concept);
        stream.pushTriple(obs, Fleet.HAS_FEATURE_OF_INTEREST, vehicle);
        stream.push(obs, Fleet.HAS_SIMPLE_RESULT, value);
    }

    /** Short display form for a concept IRI ({@code https://example.org/vss#Door.isOpen} -> {@code Door.isOpen}). */
    public static String shortConcept(String iri) {
        return iri.startsWith(CONCEPT) ? iri.substring(CONCEPT.length()) : iri;
    }
}
