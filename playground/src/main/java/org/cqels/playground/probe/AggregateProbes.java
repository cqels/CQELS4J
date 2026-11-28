package org.cqels.playground.probe;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.cqels.playground.probe.ProbeSupport.*;

/**
 * Aggregates and windows: what {@code GROUP BY} aggregates report, and when a windowed
 * aggregate reports.
 *
 * <p>{@link CapabilityProbe} runs these checks and names the documentation each one guards.
 */
final class AggregateProbes {

    private AggregateProbes() {
    }

    /**
     * The <em>exact</em> shape probed below — one stream pattern, one {@code COUNT}, no
     * {@code FILTER}, {@code HAVING}, static join or {@code OPTIONAL}, over {@code [RANGE 3s]} —
     * reports on epoch-aligned <em>tumbling buckets</em>: the closed bucket's aggregate is emitted
     * when an element arrives whose event time falls past the boundary.
     * {@code DataStream.complete()} flushes it too, so a subsequent arrival is not the only
     * trigger; wall-clock time alone is not one at all.
     *
     * <p>Deliberately stated as one measured shape rather than a rule. Neighbouring shapes differ in
     * <em>which elements are aggregated</em>, not merely in when results appear, and the boundary
     * between them is not derivable from pattern and aggregate counts: adding {@code HAVING}, a
     * static join or {@code OPTIONAL} switches this same query to a rolling window, while
     * {@code FILTER} and {@code GROUP BY} do not. {@code CQELS-QL_SPEC.md} §9 carries the measured
     * table; this check pins one row of it.
     *
     * <p>Two earlier versions of this check were wrong, which is why it looks the way it does:
     * <ul>
     *   <li>The first pushed three elements into a {@code [RANGE 10s]} window, waited 1.5 s, saw
     *       nothing and reported the combination BROKEN — a claim that reached §9 and an upstream
     *       issue before a reviewer refuted it with one late push. The window had not closed.</li>
     *   <li>The second pushed on wall-clock timing and asserted the exact count. Three pushes take
     *       ~11 ms, but if they straddle a bucket boundary the engine correctly emits mid-burst and
     *       the check fails for a reason unrelated to the engine. It failed 2 runs in 10 under
     *       probe load while passing 8/8 in isolation.</li>
     * </ul>
     * Hence <strong>timestamped</strong> pushes: event time, not the scheduler, decides which
     * bucket each element lands in, so the expected count is exact and the result deterministic.
     */
    static boolean singlePatternRangeAggregatesAtWindowClose() throws Exception {
        try (CQELSEngine engine = CQELSEngine.builder().id("probe-range-agg").withMemoryStore().build()) {
            DataStream stream = engine.createStream("S");
            List<Object> rows = new CopyOnWriteArrayList<>();
            engine.registerCqelsQuery(withRegister(
                    "SELECT (COUNT(?val) AS ?n) FROM STREAM S [RANGE 3s]\n"
                    + "WHERE { STREAM S { ?o <" + C + "v> ?val . } }\n"), rows::add);
            engine.start();
            // All three inside the bucket [0, 3000).
            for (int i = 1; i <= 3; i++) {
                stream.push(List.of(VF.createStatement(VF.createIRI(C + "o" + i),
                        VF.createIRI(C + "v"), VF.createLiteral((double) i))), 100L * i);
                Thread.sleep(60);
            }
            // Checked immediately before the trigger, leaving no unobserved gap in which an
            // autonomous emission could occur and be mistaken for a triggered one. A per-arrival
            // route would already have produced rows here.
            boolean quietBeforeTrigger = rows.isEmpty();
            stream.push(List.of(VF.createStatement(VF.createIRI(C + "o4"),
                    VF.createIRI(C + "v"), VF.createLiteral(4.0))), 3100L);
            for (int i = 0; i < 40 && rows.isEmpty(); i++) {
                Thread.sleep(100);
            }
            Thread.sleep(500);   // let any further row arrive, so an extra one is seen, not missed
            // n=3 exactly: the closed bucket holds the three timestamped elements and not the
            // trigger. Asserting the value, not just non-emptiness, rejects a raw or mis-scoped
            // result that happens to arrive at the right moment. Matched as a typed NUMBER: an
            // earlier version used String.contains("n=3"), which a reviewer showed accepts n=30.
            // Exactly one row, n bound to the NUMBER 3: "any row matching n=3" accepted an extra
            // {n=99} beside it, and the regex accepted "3wrong" (codex, round 4 of #116).
            return quietBeforeTrigger && exactRows(rows, List.of(row("n", num(3))));
        }
    }

    /**
     * Upstream lists it as a known issue of 2.0.0-alpha.22: {@code ORDER BY}, {@code MIN} and
     * {@code MAX} still compare durations as text, although a {@code FILTER} now compares them by
     * value. Measured: {@code MAX} over {@code PT9S} then {@code PT10S} under {@code [TRIPLES 3]}
     * reports {@code PT9S}.
     *
     * <p>The control is {@code MAX} over two durations whose text and value orders agree
     * ({@code PT1S}, {@code PT2S}): it must report {@code PT2S}, or the aggregate itself is failing.
     *
     * <p>Every arrival's report is read, in both input orders, so a lost final evaluation cannot
     * leave the first arrival's value standing in for the two-element answer (codex, review of
     * #116). Returns the reports as {@code "PT9S PT9S / PT10S PT9S"} — {@code PT9S,PT10S} then
     * {@code PT10S,PT9S}: that string as documented (text order), {@code "PT9S PT10S / PT10S PT10S"}
     * once fixed.
     */
    static String durationMax() throws Exception {
        String agreeing = maxReports("PT1S", "PT2S");
        if (!"PT1S PT2S".equals(agreeing)) {
            DIVERGENCES.add("REGRESSED — the control (MAX of PT1S, PT2S) reported " + agreeing
                    + ", not PT1S PT2S\n      MAX over durations is failing outright; the text-order "
                    + "verdict is meaningless");
        }
        return maxReports("PT9S", "PT10S") + " / " + maxReports("PT10S", "PT9S");
    }

    /** Pushes two {@code xsd:duration} values and returns every {@code ?m} MAX reported, space-separated. */
    private static String maxReports(String first, String second) throws Exception {
        IRI duration = VF.createIRI("http://www.w3.org/2001/XMLSchema#duration");
        List<Object> rows = run(null, """
            SELECT (MAX(?d) AS ?m) FROM STREAM S [TRIPLES 3]
            WHERE { STREAM S { ?o <%sd> ?d . } }
            """.formatted(C), s -> {
                s.push(VF.createStatement(VF.createIRI(C + "o1"), VF.createIRI(C + "d"),
                        VF.createLiteral(first, duration)), 1000L);
                Thread.sleep(100);
                s.push(VF.createStatement(VF.createIRI(C + "o2"), VF.createIRI(C + "d"),
                        VF.createLiteral(second, duration)), 1100L);
            });
        // Each report must be an xsd:duration literal; anything else is reported by its full term,
        // so a string or xsd:dayTimeDuration cannot read as the documented label (codex, round 5).
        List<String> reports = new ArrayList<>();
        for (Object row : rows) {
            Object m = ((Map<?, ?>) row).get("m");
            reports.add(m instanceof Literal l && l.getLanguage().isEmpty()
                    && org.eclipse.rdf4j.model.vocabulary.XSD.DURATION.equals(l.getDatatype())
                    ? l.getLabel() : "<" + m + (m == null ? "" : " : " + m.getClass().getSimpleName()) + ">");
        }
        return reports.isEmpty() ? "no row" : String.join(" ", reports);
    }

    /**
     * Upstream lists it as a known issue of 2.0.0-alpha.22: {@code AVG} of a group in which no row
     * binds the argument is null, where SPARQL 1.1 defines {@code Avg} of an empty multiset as 0.
     * Measured: one element whose {@code OPTIONAL} finds no label in the store, grouped by subject under
     * {@code [TRIPLES 2]}, reports {@code a=null}.
     *
     * <p>The control stores the label, so the {@code OPTIONAL} binds the argument: {@code AVG} must
     * then report the label's value, or the aggregate route itself is failing.
     *
     * <p>Returns the last {@code ?a} for the unbound group: {@code null} as documented, {@code 0}
     * once fixed, {@code no row} if nothing was reported.
     */
    static String avgOfUnboundGroup() throws Exception {
        String query = """
            SELECT ?o (AVG(?l) AS ?a) FROM STREAM S [TRIPLES 2]
            WHERE { STREAM S { ?o <%1$sv> ?x . } OPTIONAL { ?o <%1$slabel> ?l } } GROUP BY ?o
            """.formatted(C);
        Pusher one = s -> s.push(List.of(VF.createStatement(VF.createIRI(C + "o1"), VF.createIRI(C + "v"),
                VF.createLiteral(1))), 1000L);
        // The OPTIONAL reads the store, as in the #85 check: the control seeds the label there.
        Seeder labelled = engine -> {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                conn.add(VF.createIRI(C + "o1"), VF.createIRI(C + "label"), VF.createLiteral(8));
            }
        };
        List<Object> control = run(labelled, query, one);
        if (!exactRows(control, List.of(row("o", iri("o1"), "a", num(8))))) {
            DIVERGENCES.add("REGRESSED — the control (AVG over a bound label 8) reported " + control
                    + "\n      the aggregate route itself is failing; the empty-group verdict is meaningless");
        }
        // Exactly the one group row: an extra or empty row must not read as the documented null
        // (codex, round 3 of #116). The alias must be present, bound to null.
        List<Object> rows = run(null, query, one);
        if (rows.size() == 1 && ((Map<?, ?>) rows.get(0)).containsKey("a")
                && exactRows(rows, List.of(row("o", iri("o1"), "a", UNBOUND)))) {
            return "null";
        }
        if (exactRows(rows, List.of(row("o", iri("o1"), "a", num(0))))) {
            return "0";
        }
        return rows.isEmpty() ? "no row" : String.valueOf(rows);
    }

    /**
     * Upstream lists it as a known issue of 2.0.0-alpha.22: on the windowed hash join a solution whose
     * projection is empty is dropped before aggregation, so an aggregate whose argument no solution
     * binds reports no row instead of {@code COUNT} 0. Measured: {@code COUNT(?missing)}, with
     * {@code ?missing} in no pattern, over one matching element under {@code [TRIPLES 3]} reports
     * nothing.
     *
     * <p>The control is {@code COUNT(*)} over the same element: it must report 1, or the aggregate
     * route itself is failing.
     *
     * <p>Returns {@code no row} as documented, {@code 0} once fixed, or what was reported.
     */
    static String countOfNeverBoundArgument() throws Exception {
        Pusher one = s -> s.push(VF.createStatement(VF.createIRI(C + "o1"), VF.createIRI(C + "v"),
                VF.createLiteral(1)), 1000L);
        List<Object> control = run(null, """
            SELECT (COUNT(*) AS ?n) FROM STREAM S [TRIPLES 3] WHERE { STREAM S { ?o <%sv> ?x . } }
            """.formatted(C), one);
        if (!exactRows(control, List.of(row("n", num(1))))) {
            DIVERGENCES.add("REGRESSED — the control (COUNT(*) over one element) reported " + control
                    + "\n      the aggregate route itself is failing; the unbound-argument verdict is meaningless");
        }
        List<Object> rows = run(null, """
            SELECT (COUNT(?missing) AS ?n) FROM STREAM S [TRIPLES 3] WHERE { STREAM S { ?o <%sv> ?x . } }
            """.formatted(C), one);
        if (rows.isEmpty()) {
            return "no row";
        }
        return exactRows(rows, List.of(row("n", num(0)))) ? "0" : String.valueOf(rows);
    }

    /**
     * Upstream lists it as a known issue of 2.0.0-alpha.22: a {@code GROUP BY} key holds a literal's
     * label without its language tag or datatype. Measured: {@code "cat"@en} then {@code "cat"@fr}
     * under {@code [TRIPLES 3]}, grouped by the literal, end in one group of 2.
     *
     * <p>The control is the documented workaround — {@code BIND(LANG(?l) AS ?lang)} and
     * {@code GROUP BY ?l ?lang} — which must report both tags, or grouping itself is failing.
     *
     * <p>Both outputs are read <em>per arrival</em>: each arrival's rows are collected before the
     * next element is pushed, and compared exactly. The documented result is {@code {cat,1}} on the
     * first arrival and {@code {"cat"@en,2}} on the second; a fix reports {@code {"cat"@en,1}}, then
     * both tags at 1. Keys are compared as RDF terms, language tag included. The workaround control must be {@code en:1}, then {@code en:1} and {@code fr:1}. One
     * flattened multiset accepted reversed or batched histories (codex, round 3 of #116).
     *
     * <p>Returns {@code 1 group} as documented, {@code 2 groups} once fixed, or the rows.
     */
    static String groupsOverLanguageTaggedKey() throws Exception {
        List<Pusher> twoTags = List.of(
                s -> s.push(VF.createStatement(iri("o1"), iri("l"), VF.createLiteral("cat", "en")), 1000L),
                s -> s.push(VF.createStatement(iri("o2"), iri("l"), VF.createLiteral("cat", "fr")), 1100L));
        List<List<Object>> control = runPerArrival("""
            SELECT ?l ?lang (COUNT(*) AS ?n) FROM STREAM S [TRIPLES 3]
            WHERE { STREAM S { ?o <%sl> ?l . } BIND(LANG(?l) AS ?lang) } GROUP BY ?l ?lang
            """.formatted(C), twoTags);
        Literal en = VF.createLiteral("cat", "en");
        Literal fr = VF.createLiteral("cat", "fr");
        if (!(exactRows(control.get(0), List.of(row("l", en, "lang", "en", "n", num(1))))
                && exactRows(control.get(1), List.of(row("l", en, "lang", "en", "n", num(1)),
                        row("l", fr, "lang", "fr", "n", num(1)))))) {
            DIVERGENCES.add("REGRESSED — the documented workaround (GROUP BY ?l ?lang) reported " + control
                    + "\n      fix the workaround in GETTING_STARTED.md before trusting the verdict");
        }
        List<List<Object>> rows = runPerArrival("""
            SELECT ?l (COUNT(*) AS ?n) FROM STREAM S [TRIPLES 3]
            WHERE { STREAM S { ?o <%sl> ?l . } } GROUP BY ?l
            """.formatted(C), twoTags);
        // Measured: the merged group keeps the first key it saw, "cat"@en, and counts 2.
        boolean first = exactRows(rows.get(0), List.of(row("l", en, "n", num(1))));
        if (first && exactRows(rows.get(1), List.of(row("l", en, "n", num(2))))) {
            return "1 group";
        }
        if (first && exactRows(rows.get(1), List.of(row("l", en, "n", num(1)), row("l", fr, "n", num(1))))) {
            return "2 groups";
        }
        return String.valueOf(rows);
    }

    /**
     * CQELS-QL_SPEC.md §9 lists "two stream patterns" as a rolling {@code [RANGE]} aggregate, and
     * the WindowedAggregation example depends on it: a running count on every arrival over the last
     * 3 s of event time, not a bucket reported when it closes. Raised in #77.
     *
     * <p>Three elements, timestamped inside one 3 s bucket, each satisfying both patterns. Each
     * arrival must report the running count — {@code n=1}, then {@code n=2}, then {@code n=3} — before
     * the next element is pushed, which rejects close-only and batched reporting. A fourth element at
     * 10 s must then report {@code n=1}, which rejects a window that never evicts.
     */
    static boolean twoPatternRangeAggregateRolls() throws Exception {
        try (CQELSEngine engine = CQELSEngine.builder().id("probe-range-roll").withMemoryStore().build()) {
            DataStream stream = engine.createStream("S");
            List<Object> rows = new CopyOnWriteArrayList<>();
            engine.registerCqelsQuery(withRegister(
                    "SELECT (COUNT(*) AS ?n) FROM STREAM S [RANGE 3s]\n"
                    + "WHERE { STREAM S { ?o <" + C + "v> ?val . ?o <" + C + "w> ?w . } }\n"), rows::add);
            engine.start();
            long[] timestamps = {100L, 200L, 300L, 10_000L};
            int[] expected = {1, 2, 3, 1};
            for (int k = 0; k < timestamps.length; k++) {
                int before = rows.size();
                stream.push(List.of(
                        VF.createStatement(VF.createIRI(C + "o" + k), VF.createIRI(C + "v"), VF.createLiteral(k)),
                        VF.createStatement(VF.createIRI(C + "o" + k), VF.createIRI(C + "w"), VF.createLiteral(k))),
                        timestamps[k]);
                for (int i = 0; i < 30 && rows.size() == before; i++) {
                    Thread.sleep(100);
                }
                Thread.sleep(300);   // let any further row of this arrival land before judging it
                // This arrival's rows must be exactly one {n = expected}: reading only the last row
                // accepted an extra {n=99} before it (codex, round 4 of #116).
                if (!exactRows(new ArrayList<>(rows.subList(before, rows.size())),
                        List.of(row("n", num(expected[k]))))) {
                    return false;
                }
            }
            return true;
        }
    }

    static boolean groupByWorks() throws Exception {
        List<Object> rows = run(null, """
            SELECT ?g (COUNT(*) AS ?n)
            FROM STREAM S [RANGE 1s]
            WHERE { STREAM S { ?o <%sgroup> ?g . } }
            GROUP BY ?g
            """.formatted(C),
                s -> {
                    // A windowed aggregate is evaluated when an element ARRIVES, not on a timer,
                    // so the bucket needs a later element to close it. Event time decides the
                    // bucket: the first three at 100-300 ms land in [0, 1000), and the fourth at
                    // 1500 ms closes it. Wall-clock pacing used to let the scheduler split the
                    // bucket and fail the check with a correct engine (codex, review of #116).
                    s.push(st("o1", "group", "g1"), 100L);
                    s.push(st("o2", "group", "g1"), 200L);
                    s.push(st("o3", "group", "g2"), 300L);
                    Thread.sleep(200);
                    s.push(st("o4", "group", "g2"), 1500L);
                });
        // Exactly the closed bucket: {g1, 2} and {g2, 1}, nothing else. Substring matching across
        // rows passed on {g1,n=1}+{g2,n=2} and on tenfold counts (codex, rounds 2 and 3 of #66;
        // review of #116).
        return exactRows(rows, List.of(row("g", iri("g1"), "n", num(2)), row("g", iri("g2"), "n", num(1))));
    }

    /** One query, one fresh engine, so checks cannot contaminate each other. */
    /**
     * Like {@link ProbeSupport#run}, but pushes one element per {@link Pusher} and returns the rows each arrival
     * produced, collected before the next element is pushed — so a check can tell one arrival's
     * output from the next one's.
     */
    private static List<List<Object>> runPerArrival(String where, List<Pusher> arrivals) throws Exception {
        List<Object> rows = new CopyOnWriteArrayList<>();
        List<List<Object>> perArrival = new ArrayList<>();
        try (CQELSEngine engine = CQELSEngine.builder().id("probe-" + System.nanoTime()).withMemoryStore().build()) {
            DataStream stream = engine.createStream("S");
            engine.registerCqelsQuery(withRegister(where), rows::add);
            engine.start();
            for (Pusher arrival : arrivals) {
                int before = rows.size();
                arrival.push(stream);
                Thread.sleep(1500);
                perArrival.add(new ArrayList<>(rows.subList(before, rows.size())));
            }
        }
        return perArrival;
    }
}
