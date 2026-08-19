package org.cqels.examples.cdsp;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.examples.Fleet;

/**
 * Example — <strong>S2DM instance tags</strong>: querying repeated cabin structures by zone
 * instead of by exploded signal path.
 *
 * <p>The scenario: a door-ajar interlock. The depot must know whether <em>any</em> door is open
 * while a vehicle is moving, and on which side of which row — because a rear passenger-side door
 * ajar with a child aboard is a different alarm from the driver's own door.
 *
 * <p><strong>The modelling problem.</strong> COVESA VSS expands repeated structures into the
 * signal path: {@code Vehicle.Cabin.Door.Row1.DriverSide.IsOpen},
 * {@code …Row1.PassengerSide.IsOpen}, {@code …Row2.DriverSide.IsOpen}, and so on. A query that
 * wants "any door, any row, any side" must enumerate every path, and adding a third row means
 * editing every such query.
 *
 * <p><strong>The S2DM answer</strong> is to model the structure once and tag the instance, using
 * the {@code InCabinZone} pattern from s2dm's {@code /spec/common_enums.graphql}:
 * <pre>
 *   type InCabinZone { row: InCabinRowEnum!  side: InCabinSideEnum! }
 *   enum InCabinRowEnum  { FRONT REAR }
 *   enum InCabinSideEnum { DRIVER_SIDE PASSENGER_SIDE }
 *   type Door { instance: InCabinZone  isOpen: Boolean }
 * </pre>
 * There is one {@code Door.isOpen} concept; the row and side travel with the reading as data. The
 * query below binds {@code ?row} and {@code ?side} as ordinary variables, so it covers every
 * present and future zone without naming any of them. A third row changes the data, not the query.
 *
 * <p>Three queries, increasingly specific:
 * <ol>
 *   <li><em>any zone</em> — every open door, with its zone reported;</li>
 *   <li><em>a filtered zone</em> — rear passenger side only, via a {@code FILTER} on the tag;</li>
 *   <li><em>aggregated by zone</em> — {@code GROUP BY ?row ?side} counting open doors per zone,
 *       which is the shape a fleet dashboard actually renders. It counts door-open
 *       <em>events</em> per vehicle, not current door state — see the comment on that query.</li>
 * </ol>
 *
 * <p>CQELS-QL features shown: multi-pattern atomic stream elements, {@code FILTER} over
 * instance-tag bindings, and {@code GROUP BY} on a compound (row × side) key.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.cdsp.S2dmInstanceZones}
 */
public class S2dmInstanceZones {

    public static void main(String[] args) throws InterruptedException {
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("s2dm-instance-zones")
                .withMemoryStore()
                .build()) {

            S2dm.seedConceptGraph(engine);

            DataStream cabin = engine.createStream("Cabin");

            // 1. Any door, any zone. The query names no row and no side — they are bindings.
            String anyZone = S2dm.PREFIXES + """
                    REGISTER QUERY AnyDoorOpen AS
                    SELECT ?vehicle ?row ?side
                    FROM STREAM Cabin [TRIPLES 1]
                    WHERE {
                      STREAM Cabin {
                        ?obs c:ofConcept c:Door.isOpen .
                        ?obs sosa:hasFeatureOfInterest ?vehicle .
                        ?obs c:row ?row .
                        ?obs c:side ?side .
                        ?obs sosa:hasSimpleResult ?open .
                      }
                      FILTER(?open > 0)
                    }
                    """;
            engine.registerCqelsQuery(anyZone, row ->
                    System.out.println("  DOOR OPEN -> " + row));

            // 2. One specific zone, selected by filtering the instance tag rather than by
            //    addressing a distinct signal path.
            String rearPassenger = S2dm.PREFIXES + """
                    REGISTER QUERY RearPassengerDoorOpen AS
                    SELECT ?vehicle
                    FROM STREAM Cabin [TRIPLES 1]
                    WHERE {
                      STREAM Cabin {
                        ?obs c:ofConcept c:Door.isOpen .
                        ?obs sosa:hasFeatureOfInterest ?vehicle .
                        ?obs c:row ?row .
                        ?obs c:side ?side .
                        ?obs sosa:hasSimpleResult ?open .
                      }
                      FILTER(?open > 0)
                      # NOTE: full IRIs, not the c: prefix. A prefixed name inside a FILTER
                      # is not resolved by this engine build (2.0.0-alpha.18) and the filter
                      # silently never matches — see issue #55. The same
                      # prefix works fine in the triple patterns above.
                      FILTER(?row  = <https://example.org/vss#REAR>)
                      FILTER(?side = <https://example.org/vss#PASSENGER_SIDE>)
                    }
                    """;
            engine.registerCqelsQuery(rearPassenger, row ->
                    System.out.println("    !! REAR PASSENGER-SIDE door ajar -> " + row));

            // 3. The dashboard shape: door-open EVENTS per vehicle and zone over the last 5 s.
            //    Two things this deliberately is not:
            //      - not a per-zone count across the fleet. Without ?vehicle in the GROUP BY,
            //        a door open on one vehicle and another on a second would merge into one
            //        count for "REAR/PASSENGER_SIDE", which no depot dashboard wants.
            //      - not current door STATE. A door that opened and closed inside the window
            //        still contributed an event; deriving state would need the latest reading
            //        per (vehicle, zone), which this release cannot express (no event-time
            //        comparison, no argmax). The name says events, so the query is honest.
            String perZone = S2dm.PREFIXES + """
                    REGISTER QUERY DoorOpenEventsPerZone AS
                    SELECT ?vehicle ?row ?side (COUNT(*) AS ?openEvents)
                    FROM STREAM Cabin [RANGE 5s]
                    WHERE {
                      STREAM Cabin {
                        ?obs c:ofConcept c:Door.isOpen .
                        ?obs sosa:hasFeatureOfInterest ?vehicle .
                        ?obs c:row ?row .
                        ?obs c:side ?side .
                        ?obs sosa:hasSimpleResult ?open .
                      }
                      FILTER(?open > 0)
                    }
                    GROUP BY ?vehicle ?row ?side
                    """;
            engine.registerCqelsQuery(perZone, row ->
                    System.out.println("    [open events per vehicle+zone] " + row));

            engine.start();
            System.out.println("""
                    Engine started. One Door.isOpen concept, four zones — the queries bind
                    row/side as data instead of enumerating VSS signal paths.
                    """);

            // Front driver side opens (the driver getting in) — routine.
            System.out.println("push: EV-7Q2 Door.isOpen FRONT/DRIVER_SIDE = 1");
            S2dm.pushZonedSignal(cabin, Fleet.EV1, S2dm.F_DOOR_OPEN,
                    S2dm.ROW_FRONT, S2dm.SIDE_DRIVER, 1.0);
            Thread.sleep(600);

            // Rear passenger side opens — the one the interlock cares about.
            System.out.println("\npush: EV-7Q2 Door.isOpen REAR/PASSENGER_SIDE = 1");
            S2dm.pushZonedSignal(cabin, Fleet.EV1, S2dm.F_DOOR_OPEN,
                    S2dm.ROW_REAR, S2dm.SIDE_PASSENGER, 1.0);
            Thread.sleep(600);

            // A closed door: present in the stream, filtered out by FILTER(?open > 0).
            System.out.println("\npush: EV-3K8 Door.isOpen FRONT/PASSENGER_SIDE = 0  (closed -> filtered)");
            S2dm.pushZonedSignal(cabin, Fleet.EV2, S2dm.F_DOOR_OPEN,
                    S2dm.ROW_FRONT, S2dm.SIDE_PASSENGER, 0.0);
            Thread.sleep(600);

            // A seat-occupancy reading in the same zone vocabulary — the instance tag is shared
            // across concepts, which is precisely why s2dm factors InCabinZone out as a type.
            System.out.println("\npush: EV-7Q2 Seat.isOccupied REAR/PASSENGER_SIDE = 1"
                    + "  (same zone vocabulary, different concept)");
            S2dm.pushZonedSignal(cabin, Fleet.EV1, S2dm.F_SEAT_OCCUPIED,
                    S2dm.ROW_REAR, S2dm.SIDE_PASSENGER, 1.0);
            Thread.sleep(1200);
        }
        System.out.println("\nDone.");
    }
}
