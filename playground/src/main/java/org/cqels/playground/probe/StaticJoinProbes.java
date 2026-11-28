package org.cqels.playground.probe;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.playground.Fleet;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.cqels.playground.probe.ProbeSupport.*;

/**
 * Joins with the static graph: static patterns beside a {@code STREAM} block, the reverse-edge guard,
 * {@code FROM <graph>},
 * a static {@code UNION}, and the static unit conversions {@code TrailerHealthMonitor} relies on.
 *
 * <p>{@link CapabilityProbe} runs these checks and names the documentation each one guards.
 */
final class StaticJoinProbes {

    private StaticJoinProbes() {
    }

    /** #54 (fixed): a static pattern with no matching data must eliminate the row. */
    static boolean staticPatternEliminates() throws Exception {
        List<Object> rows = run(engine -> {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                conn.add(VF.createIRI(C + "a"), VF.createIRI(C + "broader"), VF.createIRI(C + "b"));
            }
        }, """
            SELECT ?x FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sof> ?x . }
                    ?x <%sbroader> <%sNoSuchThing> . }
            """.formatted(C, C, C),
                s -> s.pushTriple(C + "o1", C + "of", C + "a"));
        return rows.isEmpty();   // eliminated == fixed
    }

    /**
     * #67, fixed since 2.0.0-alpha.21: a static guard that reaches the join key by a REVERSE edge —
     * {@code ex:coll ex:member ?f}, with {@code ?f} bound by the stream in object position — must
     * admit a matching element and must not admit one that cannot match the stream block.
     *
     * <p>Before 2.0.0-alpha.21 it did neither reliably, in two opposite ways depending on shape: it
     * <em>eliminated</em> rows that fully matched (this exact {@code [TRIPLES 1]} two-pattern shape),
     * and elsewhere it <em>fabricated</em> rows from the static data alone when the pushed element
     * matched no stream pattern at all. Issue #67 has the history.
     *
     * <p>This was a caveat until the alpha.21 bump, and the body below is unchanged from that
     * version: the same two phases that reported "still open" on alpha.20 report correct behaviour
     * on alpha.21. That is the point of flipping a caveat rather than rewriting it — the assertion
     * that changed its verdict is the evidence.
     *
     * <p>Two phases are required, not one. An earlier version returned {@code !rows.isEmpty()},
     * which would have read a FABRICATED row as the bug being fixed. A fixed engine must emit on the
     * matching element ({@code admitsMatch}) and stay silent on the unmatchable one
     * ({@code fabricates}). The forward-typed guard runs alongside as a control in both phases.
     *
     * <p>A 162-case sweep over six windows, both guard directions, four push kinds, six static
     * shapes and four projections was correct on alpha.21 and wrong in 24 cases on alpha.20 —
     * see {@code CQELS-QL_SPEC.md} §6. This check pins one cell of it; the next check pins the
     * {@code [NOW]} half, which was the more serious.
     */
    static boolean reverseEdgeGuardAdmits() throws Exception {
        // Phase 1 — a MATCHING element. A fixed engine emits the row here.
        boolean admitsMatch = !reverseGuardRun(true).isEmpty();
        // Phase 2 — an element that CANNOT match the stream block. A fixed engine emits nothing;
        // this engine fabricates a row from the static data alone (issue #67 §A). Without this
        // phase the check could not tell "fixed" from "got worse": the old version returned
        // !reverse.isEmpty(), so a fabricated row would have been reported as NOW FIXED and the
        // caveat removed from the docs — precisely backwards.
        boolean fabricates = !reverseGuardRun(false).isEmpty();
        // Phase 3 — an element that DOES match the stream block, keyed on b, which is not a member
        // of the collection. Only the static guard can reject it; phase 2 never reaches the guard,
        // so without this phase an engine that ignored the guard would pass (codex, review of #116).
        boolean guardRejects = reverseGuardRun(null).isEmpty();
        return admitsMatch && !fabricates && guardRejects;
    }

    /**
     * One run of the #67 comparison. Pushes a matching two-statement element ({@code true}), one
     * that cannot match the stream block at all ({@code false}), or one that matches the stream
     * block keyed on a non-member {@code b} ({@code null}), and returns what the reverse-guarded
     * query emitted.
     * The forward-typed guard runs alongside as a control: on the matching push it must admit, and
     * on the non-matching push it must stay silent. If either fails, the reverse verdict is not
     * measuring what it claims to and a divergence says so.
     */
    private static List<Object> reverseGuardRun(Boolean mode) throws Exception {
        boolean matching = Boolean.TRUE.equals(mode);
        boolean nonMember = mode == null;
        try (CQELSEngine engine = CQELSEngine.builder().id("probe-67").withMemoryStore().build()) {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                // Both directions asserted, exactly as the s2dm exporter emits them.
                conn.add(VF.createIRI(C + "a"), VF.createIRI(Fleet.RDF_TYPE), VF.createIRI(C + "Field"));
                conn.add(VF.createIRI(C + "coll"), VF.createIRI(C + "member"), VF.createIRI(C + "a"));
            }
            DataStream stream = engine.createStream("S");
            List<Object> reverse = new CopyOnWriteArrayList<>();
            List<Object> forward = new CopyOnWriteArrayList<>();
            // Two patterns in the STREAM block — the shape that triggers it. Each query projects
            // exactly what its own BGP binds; getting that wrong is how a shape difference gets
            // mistaken for a guard difference.
            String block = "?o <" + C + "of> ?f . ?o <" + C + "v> ?val .";
            engine.registerCqelsQuery(withRegister("SELECT ?f ?val FROM STREAM S [TRIPLES 1]\n"
                    + "WHERE { STREAM S { " + block + " } <" + C + "coll> <" + C + "member> ?f . }\n"),
                    reverse::add);
            engine.registerCqelsQuery(withRegister("SELECT ?f ?val FROM STREAM S [TRIPLES 1]\n"
                    + "WHERE { STREAM S { " + block + " } ?f <" + Fleet.RDF_TYPE + "> <" + C + "Field> . }\n"),
                    forward::add);
            engine.start();
            IRI o = VF.createIRI(C + "o1");
            if (matching || nonMember) {
                stream.push(List.of(
                        VF.createStatement(o, VF.createIRI(C + "of"), VF.createIRI(C + (nonMember ? "b" : "a"))),
                        VF.createStatement(o, VF.createIRI(C + "v"), VF.createLiteral(1.0))));
            } else {
                stream.push(VF.createStatement(o, VF.createIRI(C + "unrelated"), VF.createIRI(C + "x")));
            }
            Thread.sleep(1200);
            if (matching && forward.isEmpty()) {
                DIVERGENCES.add("REGRESSED — the forward-typed guard no longer admits a matching element\n"
                        + "      the #67 comparison is meaningless in this run; investigate before "
                        + "trusting its verdict");
            } else if (!matching && !forward.isEmpty()) {
                // b is not typed Field either, so the forward guard must reject the non-member too.
                DIVERGENCES.add("REGRESSED — the forward-typed guard now fabricates too\n"
                        + "      it emitted for an element that cannot match the stream block; the "
                        + "#67 control is no longer sound");
            }
            return reverse;
        }
    }

    /**
     * #67's {@code [NOW]} half, fixed since 2.0.0-alpha.21. Two stream patterns plus a <em>satisfied</em>
     * static pattern, and an element carrying only the FIRST stream statement: the second stream
     * pattern has no match, so the correct answer is no row.
     *
     * <p>Before 2.0.0-alpha.21 the engine emitted one anyway, with the unmatched variable simply
     * absent from the binding. It did not depend on the guard's direction — any satisfied static
     * pattern with a determinable subject triggered it — and removing the static pattern made the
     * same query correct. Of the two #67 failures this one was the worse: a consumer cannot tell a
     * fabricated row from a real one.
     *
     * <p>The full element is the control. If it stops being admitted, the static pattern has started
     * eliminating instead, and "no row on the partial element" would no longer mean what it claims.
     */
    static boolean nowStaticPatternEnforcesConjunction() throws Exception {
        Seeder seeded = engine -> {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                conn.add(VF.createIRI(C + "allowed"), VF.createIRI(Fleet.RDF_TYPE), VF.createIRI(C + "Field"));
            }
        };
        String query = """
            SELECT ?f ?val FROM STREAM S [NOW]
            WHERE { STREAM S { ?o <%1$sof> ?f . ?o <%1$sv> ?val . } ?f <%2$s> <%1$sField> . }
            """.formatted(C, Fleet.RDF_TYPE);
        IRI o = VF.createIRI(C + "o1");
        List<Object> full = run(seeded, query, s -> s.push(List.of(
                VF.createStatement(o, VF.createIRI(C + "of"), VF.createIRI(C + "allowed")),
                VF.createStatement(o, VF.createIRI(C + "v"), VF.createLiteral(1.0)))));
        List<Object> partial = run(seeded, query,
                s -> s.push(VF.createStatement(o, VF.createIRI(C + "of"), VF.createIRI(C + "allowed"))));
        if (full.isEmpty()) {
            DIVERGENCES.add("REGRESSED — a complete element under [NOW] plus a satisfied static pattern "
                    + "no longer matches\n      the #67 [NOW] check's control failed; its verdict on the "
                    + "partial element is meaningless until that is understood");
        }
        return !full.isEmpty() && partial.isEmpty();
    }

    /**
     * Upstream lists it as a known issue of 2.0.0-alpha.22: a static {@code UNION} joined to a stream
     * pattern drops every row on the flat paths when the variable joining it is not projected.
     * Measured under {@code [NOW]}: {@code SELECT ?r ?city} answers nothing; the control, the same
     * query also projecting {@code ?p}, answers the one row.
     *
     * <p>Returns {@code 0 rows} as documented, {@code 1 row} once fixed, or the count.
     */
    static String unionWithUnprojectedJoinVariable() throws Exception {
        Seeder seeded = engine -> {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                conn.add(VF.createIRI(C + "p1"), VF.createIRI(C + "city"), VF.createLiteral("Berlin"));
            }
        };
        String shape = """
            SELECT %2$s FROM STREAM S [NOW]
            WHERE { STREAM S { ?r <%1$sperson> ?p . }
                    { ?p <%1$scity> ?city } UNION { ?p <%1$stown> ?city } }
            """;
        Pusher request = s -> s.push(VF.createStatement(VF.createIRI(C + "r1"), VF.createIRI(C + "person"),
                VF.createIRI(C + "p1")));
        List<Object> control = run(seeded, shape.formatted(C, "?r ?p ?city"), request);
        if (!exactRows(control, List.of(row("r", iri("r1"), "p", iri("p1"), "city", "Berlin")))) {
            DIVERGENCES.add("REGRESSED — the control (UNION with ?p projected) returned " + control
                    + ", not one row\n      the static UNION itself is failing; the projection verdict is meaningless");
        }
        int n = run(seeded, shape.formatted(C, "?r ?city"), request).size();
        return n == 0 ? "0 rows" : n == 1 ? "1 row" : n + " rows";
    }

    /**
     * Fixed in 2.0.0-alpha.22, outside the 162-case sweep that showed #67 fixed: on the composed
     * windowed route (a multi-pattern {@code STREAM} block under an accumulating window), a stream
     * solution whose join key binds a LITERAL used to drop its row on alpha.21. The identical query
     * keyed on an IRI answers — that is the control, and if it stops answering the comparison means
     * nothing.
     *
     * <p>Returns true when the literal-keyed row carries {@code ?item = item1}, and a literal key
     * with no static match ({@code "ZZ"}) answers nothing. A non-empty result alone would also pass
     * if the static join were skipped (codex, review of #116).
     */
    static boolean literalJoinKeyAdmits() throws Exception {
        Seeder seeded = engine -> {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                conn.add(VF.createIRI(C + "item1"), VF.createIRI(C + "itemCode"), VF.createLiteral("A1"));
                conn.add(VF.createIRI(C + "item1"), VF.createIRI(C + "itemCodeIri"), VF.createIRI(C + "A1"));
            }
        };
        IRI o = VF.createIRI(C + "o1");
        List<Object> literal = run(seeded, """
            SELECT ?o ?item FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%1$scode> ?k . ?o <%1$stag> ?t . } ?item <%1$sitemCode> ?k . }
            """.formatted(C), s -> s.push(List.of(
                VF.createStatement(o, VF.createIRI(C + "code"), VF.createLiteral("A1")),
                VF.createStatement(o, VF.createIRI(C + "tag"), VF.createLiteral("t")))));
        List<Object> unmatched = run(seeded, """
            SELECT ?o ?item FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%1$scode> ?k . ?o <%1$stag> ?t . } ?item <%1$sitemCode> ?k . }
            """.formatted(C), s -> s.push(List.of(
                VF.createStatement(o, VF.createIRI(C + "code"), VF.createLiteral("ZZ")),
                VF.createStatement(o, VF.createIRI(C + "tag"), VF.createLiteral("t")))));
        List<Object> iri = run(seeded, """
            SELECT ?o ?item FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%1$scodeIri> ?k . ?o <%1$stag> ?t . } ?item <%1$sitemCodeIri> ?k . }
            """.formatted(C), s -> s.push(List.of(
                VF.createStatement(o, VF.createIRI(C + "codeIri"), VF.createIRI(C + "A1")),
                VF.createStatement(o, VF.createIRI(C + "tag"), VF.createLiteral("t")))));
        if (iri.isEmpty()) {
            DIVERGENCES.add("REGRESSED — the IRI-keyed control of the literal-join-key check no longer "
                    + "answers\n      the composed route now drops IRI-keyed rows too; the literal "
                    + "verdict is meaningless until that is understood");
        }
        return exactRows(literal, List.of(row("o", iri("o1"), "item", iri("item1")))) && unmatched.isEmpty();
    }

    /**
     * #83, fixed in 2.0.0-alpha.22: on a query with a static graph pattern, a {@code FILTER} over a
     * {@code BIND} target must see the target, as SPARQL 1.1 requires. On alpha.21 the
     * {@code FILTER} ran before the {@code BIND} and saw the variable unbound, so a comparison on it
     * dropped every row. Checking the dropping direction is enough: a regression of the order flips
     * both. The query deliberately has no {@code OPTIONAL} / {@code UNION} block: on alpha.21 a
     * {@code BIND} written above the first of those already ran first, which would hide a
     * regression.
     *
     * <p>The control filters on the variable the {@code BIND} reads instead, which answers; if it
     * stops answering, the static lookup itself has broken and this verdict means nothing.
     *
     * <p>Two subjects arrive: {@code o1} with {@code ?x} 4 ({@code ?y} 8) and {@code o2} with 2
     * ({@code ?y} 4). {@code FILTER(?y > 5)} must keep exactly {@code o1}: a check that only looks
     * for a surviving row also passes when the {@code FILTER} is ignored (codex, review of #116).
     */
    static boolean filterSeesBindTargetBesideStaticPattern() throws Exception {
        Seeder seeded = engine -> {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                conn.add(VF.createIRI(C + "o1"), VF.createIRI(C + "kind"), VF.createIRI(C + "K"));
                conn.add(VF.createIRI(C + "o2"), VF.createIRI(C + "kind"), VF.createIRI(C + "K"));
            }
        };
        Pusher four = s -> {
            s.push(VF.createStatement(VF.createIRI(C + "o1"), VF.createIRI(C + "v"), VF.createLiteral(4.0)));
            s.push(VF.createStatement(VF.createIRI(C + "o2"), VF.createIRI(C + "v"), VF.createLiteral(2.0)));
        };
        String shape = """
            SELECT ?o ?y FROM STREAM S [NOW]
            WHERE { STREAM S { ?o <%1$sv> ?x . } ?o <%1$skind> <%1$sK> . BIND(?x * 2 AS ?y) FILTER(%2$s) }
            """;
        List<Object> onBindTarget = run(seeded, shape.formatted(C, "?y > 5"), four);
        Set<String> onInput = subjects(run(seeded, shape.formatted(C, "?x > 2.5"), four));
        if (!onInput.equals(Set.of("o1"))) {
            DIVERGENCES.add("REGRESSED — the control (a FILTER on the BIND's input) no longer answers\n"
                    + "      the static lookup itself is failing; the BIND/FILTER verdict is meaningless");
        }
        return exactRows(onBindTarget, List.of(row("o", iri("o1"), "y", num(8))));
    }

    /**
     * Fixed in 2.0.0-alpha.22: on a query over one stream, {@code FROM <g>} scopes the static lookup.
     * On alpha.21 a static pattern matched a triple stored only in another named graph, or in the
     * default graph, exactly as if the clause were absent. (A two-stream {@code [RANGE]} join was
     * already scoped, so this check stays on one stream.) Raised in #79.
     *
     * <p>The control stores the triple in the declared graph, where it must match; if it stops
     * matching, the static lookup itself has broken.
     *
     * <p>Returns true when neither the other named graph nor the default graph is matched; a
     * regression for only one of them is reported as PARTIAL.
     */
    static boolean fromGraphScopesStaticLookup() throws Exception {
        String where = """
            SELECT ?city FROM STREAM S [NOW] FROM <%1$sgraph>
            WHERE { STREAM S { ?req <%1$spersonIri> ?person . } ?person <%1$scity> ?city . }
            """.formatted(C);
        Pusher request = s -> s.push(VF.createStatement(VF.createIRI(C + "req1"),
                VF.createIRI(C + "personIri"), VF.createIRI(C + "alice")));
        boolean otherScoped = run(inGraph("other"), where, request).isEmpty();
        boolean defaultScoped = run(inGraph(null), where, request).isEmpty();
        if (run(inGraph("graph"), where, request).isEmpty()) {
            DIVERGENCES.add("REGRESSED — the control (data in the declared graph) no longer answers\n"
                    + "      the static lookup itself is failing; the FROM <graph> verdict is meaningless");
        }
        if (otherScoped != defaultScoped) {
            DIVERGENCES.add("PARTIAL — FROM <graph> scopes the lookup against "
                    + (otherScoped ? "another named graph" : "the default graph") + " only");
        }
        return otherScoped && defaultScoped;
    }

    /** Seeds {@code alice city "Berlin"} into the named graph {@code graph}, or the default graph if null. */
    private static Seeder inGraph(String graph) {
        return engine -> {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                if (graph == null) {
                    conn.add(VF.createIRI(C + "alice"), VF.createIRI(C + "city"), VF.createLiteral("Berlin"));
                } else {
                    conn.add(VF.createIRI(C + "alice"), VF.createIRI(C + "city"), VF.createLiteral("Berlin"),
                            VF.createIRI(C + graph));
                }
            }
        };
    }

    static boolean staticEnrichmentBinds() throws Exception {
        List<Object> rows = run(engine -> {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                conn.add(VF.createIRI(C + "a"), VF.createIRI(C + "label"), VF.createLiteral("Alpha"));
            }
        }, """
            SELECT ?o ?label FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sof> ?x . } ?x <%slabel> ?label . }
            """.formatted(C, C),
                s -> s.pushTriple(C + "o1", C + "of", C + "a"));
        return exactRows(rows, List.of(row("o", iri("o1"), "label", "Alpha")));
    }

    /** One atomic element from (predicate local name, object) pairs about {@code C + subject}. */
    private static List<Statement> element(String subject, Object... po) {
        List<Statement> statements = new ArrayList<>();
        for (int i = 0; i < po.length; i += 2) {
            org.eclipse.rdf4j.model.Value o = po[i + 1] instanceof Number n
                    ? VF.createLiteral(n.toString(), org.eclipse.rdf4j.model.vocabulary.XSD.INTEGER)
                    : VF.createIRI(C + po[i + 1]);
            statements.add(VF.createStatement(VF.createIRI(C + subject), VF.createIRI(C + po[i]), o));
        }
        return statements;
    }

    /** A static conversion (blank node: to, factor, offset) from unit {@code from} to {@code to}. */
    private static void addConversion(RepositoryConnection conn, String from, String to, String factor, String offset) {
        org.eclipse.rdf4j.model.BNode c = VF.createBNode();
        conn.add(VF.createIRI(C + from), VF.createIRI(C + "conversion"), c);
        conn.add(c, VF.createIRI(C + "to"), VF.createIRI(C + to));
        conn.add(c, VF.createIRI(C + "factor"), VF.createLiteral(new java.math.BigDecimal(factor)));
        conn.add(c, VF.createIRI(C + "offset"), VF.createLiteral(new java.math.BigDecimal(offset)));
    }

    /**
     * What TrailerHealthMonitor's LowestPressure relies on: the target unit read from a static
     * triple (the field's declared unit), each element's value converted with a factor and offset
     * found by a second static join on it, then MIN per group over a [RANGE] window. 4 in u1 is
     * 4 * 2.5 + 1 = 11 in u2, below the 20 sent in u2, so the last report must be MIN 11 over 2.
     */
    static boolean convertedValueFeedsWindowedMin() throws Exception {
        List<Object> rows = run(engine -> {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                addConversion(conn, "u1", "u2", "2.5", "1.0");
                addConversion(conn, "u2", "u2", "1.0", "0.0");
                addConversion(conn, "u2", "u1", "0.4", "-0.4");
                conn.add(VF.createIRI(C + "field"), VF.createIRI(C + "declared"), VF.createIRI(C + "u2"));
            }
        }, """
            SELECT ?g (MIN(?x) AS ?lowest) (COUNT(*) AS ?n) FROM STREAM S [RANGE 10s]
            WHERE { STREAM S { ?r <%1$sval> ?v ; <%1$sunit> ?u ; <%1$sgrp> ?g . }
                    <%1$sfield> <%1$sdeclared> ?declared .
                    ?u <%1$sconversion> ?c . ?c <%1$sto> ?declared ; <%1$sfactor> ?f ; <%1$soffset> ?o .
                    BIND(?v * ?f + ?o AS ?x) }
            GROUP BY ?g
            """.formatted(C),
                s -> {
                    s.push(element("r1", "val", 20, "unit", "u2", "grp", "g"));
                    Thread.sleep(300);
                    s.push(element("r2", "val", 4, "unit", "u1", "grp", "g"));
                });
        return !rows.isEmpty() && exactRows(List.of(rows.get(rows.size() - 1)),
                List.of(row("g", iri("g"), "lowest", num(11.0), "n", num(2))));
    }

    /**
     * What TrailerHealthMonitor's UnitMismatch relies on: OPTIONAL over the static store, then
     * FILTER(!BOUND) keeps only the element with no conversion (r2's unit u3), not r1's (u1).
     */
    static boolean optionalStaticUnboundFilter() throws Exception {
        List<Object> rows = run(engine -> {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                addConversion(conn, "u1", "u2", "2.5", "1.0");
            }
        }, """
            SELECT ?r FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?r <%1$sunit> ?u ; <%1$swant> ?w . }
                    OPTIONAL { ?u <%1$sconversion> ?c . ?c <%1$sto> ?w . }
                    FILTER(!BOUND(?c)) }
            """.formatted(C),
                s -> {
                    s.push(element("r1", "unit", "u1", "want", "u2"));
                    Thread.sleep(300);
                    s.push(element("r2", "unit", "u3", "want", "u2"));
                });
        return exactRows(rows, List.of(row("r", iri("r2"))));
    }
}
