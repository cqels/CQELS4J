package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.stream.codec.RdfMessageCodec;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Example — the <strong>W3C RSP Community Group "RDF Messages"</strong> stream envelope (CQELS 2.0.0-alpha.9).
 *
 * <p>An <em>RDF Message</em> is "an RDF Dataset that is intended to be interpreted atomically as a single
 * communicative act". CQELS
 * ships a codec for the N-Quads framing (§2.1): a {@code VERSION "1.2-messages"} directive followed by
 * {@code MESSAGE}-delimited datasets. A message maps 1:1 onto one atomic CQELS stream element, so a
 * multi-triple observation (here a five-triple {@code sosa:Observation}) is delivered as ONE unit that never
 * splits across a window boundary.
 *
 * <p>Why that matters is the point of this demo. The query joins two predicates of the <em>same</em>
 * observation — {@code sosa:hasFeatureOfInterest} (the vehicle) and {@code sosa:hasSimpleResult} (the SoC).
 * Because each message is pushed atomically ({@link DataStream#push(java.util.Collection, long)}), the
 * {@code [NOW]} window sees both triples together and the join binds; feeding the triples one-at-a-time would
 * leave {@code ?veh} and {@code ?soc} in different elements and the query would never match.
 *
 * <p>The demo also shows the codec's writer: it builds the observations, serializes them to the on-the-wire
 * RDF-Messages document with {@link RdfMessageCodec#writeNQuads}, prints it, then parses it back with
 * {@link RdfMessageCodec#parseNQuads} — a full round-trip — before ingesting.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.RdfMessageIngestion}
 */
public class RdfMessageIngestion {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final String XSD_DOUBLE = "http://www.w3.org/2001/XMLSchema#double";

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("rdf-message-ingestion")
                .withMemoryStore()
                .build()) {

            DataStream telemetry = engine.createStream("Telemetry");

            // A multi-pattern [NOW] query: it binds the vehicle AND the SoC from the SAME observation. This
            // only matches because each observation arrives as one atomic RDF Message / stream element.
            String query = Fleet.PREFIXES + """
                    REGISTER QUERY LowBatteryFromMessage AS
                    SELECT ?veh ?soc
                    FROM STREAM Telemetry [NOW]
                    WHERE {
                      STREAM Telemetry {
                        ?obs sosa:hasFeatureOfInterest ?veh ;
                             sosa:hasSimpleResult      ?soc .
                      }
                      FILTER(?soc < 20)
                    }
                    """;
            engine.registerCqelsQuery(query, row ->
                    System.out.println("  LOW BATTERY -> " + row));

            engine.start();
            System.out.println("Engine started. Alerting on battery state-of-charge below 20 %.\n");

            // 1. Build three SoC observations (one below the 20 % threshold) as RDF Messages.
            List<List<Statement>> observations = List.of(
                    socObservation(Fleet.SENSOR_EV1, Fleet.EV1, 64.0),
                    socObservation(Fleet.SENSOR_EV2, Fleet.EV2, 18.5),   // <- below threshold
                    socObservation(Fleet.SENSOR_EV3, Fleet.EV3, 41.0));

            // 2. Serialize to the on-the-wire RDF-Messages N-Quads document (this is what a producer sends).
            String wire = RdfMessageCodec.writeNQuads(observations);
            System.out.println("--- RDF-Messages N-Quads document (" + observations.size() + " messages) ---");
            System.out.println(wire);
            System.out.println("--- end document ---\n");

            // 3. Parse it back (a consumer decoding the stream) and ingest each message atomically with an
            //    explicit event time, so windowed / [NOW] semantics are deterministic.
            List<List<Statement>> messages = RdfMessageCodec.parseNQuads(wire);
            long eventTime = System.currentTimeMillis();
            for (List<Statement> message : messages) {
                System.out.println("push: one atomic observation (" + message.size() + " triples)");
                telemetry.push(message, eventTime);
                eventTime += 1000;
                Thread.sleep(200);
            }
            Thread.sleep(500);
        }
        System.out.println("\nDone.");
    }

    /**
     * One VSS battery state-of-charge reading wrapped as a five-triple {@code sosa:Observation} sharing a
     * fresh observation IRI — the statements of one RDF Message.
     */
    private static List<Statement> socObservation(String sensor, String vehicle, double soc) {
        IRI obs = VF.createIRI(Fleet.EX + "obs/" + java.util.UUID.randomUUID());
        List<Statement> statements = new ArrayList<>();
        statements.add(VF.createStatement(obs, VF.createIRI(Fleet.RDF_TYPE), VF.createIRI(Fleet.OBSERVATION)));
        statements.add(VF.createStatement(obs, VF.createIRI(Fleet.MADE_BY_SENSOR), VF.createIRI(sensor)));
        statements.add(VF.createStatement(obs, VF.createIRI(Fleet.OBSERVED_PROPERTY), VF.createIRI(Fleet.SOC)));
        statements.add(VF.createStatement(obs, VF.createIRI(Fleet.HAS_FEATURE_OF_INTEREST), VF.createIRI(vehicle)));
        statements.add(VF.createStatement(obs, VF.createIRI(Fleet.HAS_SIMPLE_RESULT),
                VF.createLiteral(String.valueOf(soc), VF.createIRI(XSD_DOUBLE))));
        return statements;
    }
}
