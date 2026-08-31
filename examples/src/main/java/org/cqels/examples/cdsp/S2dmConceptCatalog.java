package org.cqels.examples.cdsp;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.examples.Fleet;

/**
 * Example — joining live VSS telemetry against the <strong>COVESA S2DM</strong> concept catalogue
 * (stream–static join over SKOS).
 *
 * <p>The scenario: a fleet operator's dashboard receives a stream of raw signal readings. A signal
 * IRI and a number are enough to threshold on, but not enough to <em>present</em> — the operator
 * wants the human label and the definition the modeller wrote, without the streaming pipeline
 * hard-coding either.
 *
 * <p>S2DM is where those labels come from. Subject-matter experts author the model in GraphQL SDL;
 * {@code s2dm generate skos-skeleton} projects it into SKOS RDF, in which every GraphQL field
 * becomes a {@code skos:Concept} carrying a {@code skos:prefLabel} and a {@code skos:definition}
 * (see {@link S2dm}). That RDF is ordinary static context for CQELS, so the enrichment is a join
 * rather than a lookup table in Java:
 *
 * <pre>
 *   STREAM { ?obs c:ofConcept ?field ; sosa:hasSimpleResult ?value }   -- live
 *   ?field skos:prefLabel ?label ; skos:definition ?definition .        -- s2dm catalogue
 * </pre>
 *
 * <p>The payoff is that the query is written against <em>concepts</em>, not signal paths. When the
 * modeller renames a label or refines a definition, the next {@code skos-skeleton} run changes the
 * static graph and every consumer follows — no query is edited.
 *
 * <p>The demo also shows the reverse direction: the last query filters on the field concept's own
 * {@code s2dm:Field} type, which is how you ask "show me everything the model classifies as a
 * field" without enumerating the fields — see the note on that query for why it guards on the
 * type rather than on {@code FieldConcepts} collection membership.
 *
 * <p>CQELS-QL features shown: stream–static join and multi-pattern stream matching, under a
 * {@code [TRIPLES 1]} window. Note that {@code [TRIPLES N]} counts stream <em>elements</em>, not
 * statements: {@link S2dm#pushConceptSignal} pushes each observation as one atomic three-statement
 * element, so a window of 1 holds exactly the latest reading and each push reports once. Widening
 * it to {@code [TRIPLES 12]} would keep the last twelve readings resident and re-report all of
 * them on every push — correct, but not what a "enrich this reading" query wants.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.cdsp.S2dmConceptCatalog}
 */
public class S2dmConceptCatalog {

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("s2dm-concept-catalog")
                .withMemoryStore()
                .build()) {

            // The s2dm SKOS catalogue is static context — seeded BEFORE start(), exactly as the
            // fleet graph is in StreamStaticJoin.
            Fleet.seedStatic(engine);
            S2dm.seedConceptGraph(engine);

            DataStream telemetry = engine.createStream("Telemetry");

            // Every reading is presented with the label and definition the MODELLER wrote,
            // resolved through the s2dm concept graph rather than hard-coded here.
            String enriched = S2dm.PREFIXES + """
                    REGISTER QUERY ConceptEnrichedReading AS
                    SELECT ?vehicle ?label ?value ?definition
                    FROM STREAM Telemetry [TRIPLES 1]
                    WHERE {
                      STREAM Telemetry {
                        ?obs c:ofConcept ?field .
                        ?obs sosa:hasFeatureOfInterest ?vehicle .
                        ?obs sosa:hasSimpleResult ?value .
                      }
                      ?field skos:prefLabel ?label .
                      ?field skos:definition ?definition .
                    }
                    """;
            engine.registerCqelsQuery(enriched, row ->
                    System.out.println("  enriched -> " + row));

            // Same stream, but the query names no field at all: it asks the catalogue which
            // concepts are classified as FIELDS and reports only those readings. Add a field to
            // the GraphQL schema, regenerate, and it appears here with no query change.
            //
            // Guard is `?field a s2dm:Field` -- a FORWARD edge from the join key -- not
            // `c:FieldConcepts skos:member ?field`, a REVERSE edge INTO it. S2dm#concept asserts
            // both facts for every concept, so either would express "is a field", but they are
            // not interchangeable on this route. This STREAM block has two patterns (?field and
            // ?value both come off ?obs), and CQELS-QL_SPEC.md §6 documents that a STREAM block
            // with more than one pattern always dispatches through the composed windowed lookup --
            // that is the dispatch rule, not the window size or element count. On that route the
            // static side is a forward, subject-rooted view built from the join key's bound value,
            // with no repository fallback, so a pattern needs ?field in SUBJECT position to find
            // anything there. The reverse form matched nothing FOR ANY ?field on that view, so it
            // did not merely fail to discriminate -- it eliminated every row, field or not. The
            // forward form is the same fact, walked the other way, and resolves.
            String byMembership = S2dm.PREFIXES + """
                    REGISTER QUERY AnyModelledField AS
                    SELECT ?label ?value
                    FROM STREAM Telemetry [TRIPLES 1]
                    WHERE {
                      STREAM Telemetry {
                        ?obs c:ofConcept ?field .
                        ?obs sosa:hasSimpleResult ?value .
                      }
                      ?field a s2dm:Field .
                      ?field skos:prefLabel ?label .
                    }
                    """;
            engine.registerCqelsQuery(byMembership, row ->
                    System.out.println("    [FieldConcepts member] " + row));

            engine.start();
            System.out.println("""
                    Engine started. Readings are enriched from the s2dm SKOS catalogue
                    (skos:prefLabel / skos:definition), not from anything hard-coded here.
                    """);

            System.out.println("push: EV-7Q2 Vehicle.averageSpeed = 62.0");
            S2dm.pushConceptSignal(telemetry, Fleet.EV1, S2dm.F_AVERAGE_SPEED, 62.0);
            Thread.sleep(700);

            System.out.println("\npush: EV-3K8 Powertrain.stateOfCharge = 41.5");
            S2dm.pushConceptSignal(telemetry, Fleet.EV2, S2dm.F_BATTERY_SOC, 41.5);
            Thread.sleep(700);

            System.out.println("\npush: EV-9TZ Door.isOpen = 1.0");
            S2dm.pushConceptSignal(telemetry, Fleet.EV3, S2dm.F_DOOR_OPEN, 1.0);
            Thread.sleep(900);

            // The counter-example. c:Seat is an OBJECT concept: it is typed s2dm:ObjectType, never
            // s2dm:Field, so the second query's type guard should exclude it -- discriminated
            // rather than merely asserted.
            System.out.println("\npush: EV-7Q2 c:Seat = 1.0   (an OBJECT concept — the"
                    + " [FieldConcepts member] query SHOULD ignore it)");
            S2dm.pushConceptSignal(telemetry, Fleet.EV1, S2dm.C_SEAT, 1.0);
            Thread.sleep(900);
        }
        System.out.println("\nDone.");
    }
}
