package org.cqels.playground.probe;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.playground.Fleet;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.cqels.playground.probe.ProbeSupport.*;

/**
 * Query evaluation inside a {@code STREAM} block: {@code FILTER} and its builtins, prefixed names,
 * property paths, term equality, the reverse-edge guard, atomic elements, and the CypherQL dialect.
 *
 * <p>{@link CapabilityProbe} runs these checks and names the documentation each one guards.
 */
final class QueryProbes {

    private QueryProbes() {
    }

    /** #55 (fixed): a prefixed name as a FILTER constant must resolve. */
    static boolean pnameInFilterResolves() throws Exception {
        // A non-matching value must be filtered out, or an ignored FILTER would pass (codex, #116).
        List<Object> rows = run(null, """
            PREFIX p: <%s>
            SELECT ?o ?x FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o p:of ?x . } FILTER(?x = p:a) }
            """.formatted(C),
                s -> {
                    s.pushTriple(C + "o1", C + "of", C + "a");
                    s.pushTriple(C + "o2", C + "of", C + "b");
                });
        return exactRows(rows, List.of(row("o", iri("o1"), "x", iri("a"))));
    }

    /**
     * Property paths, since 2.0.0-alpha.22: EXECUTED on one route, REFUSED by name on every other.
     * CQELS-QL_SPEC.md §9 states both halves, so the check asserts both.
     *
     * <p>Executed: inside the one {@code STREAM} block of a single-stream {@code [RANGE]} query with
     * no static graph pattern. The hierarchy is {@code leaf -> mid -> root}, pushed as two elements;
     * {@code ?x broader+ root} must bind BOTH {@code mid} (one hop) and {@code leaf} (two), and a
     * path to a target that does not exist must bind nothing. The oracle lives entirely inside the
     * stream block on purpose: an earlier version put the path in the static graph, where #54 used
     * to make a non-matching pattern survive (codex, round 2). Binding only {@code mid} is the old
     * #56 silent mis-evaluation, so it fails the check.
     *
     * <p>Refused: under {@code [NOW]}, and beside a static graph pattern. Each refusal must name the
     * path ("path" in the message): a refusal for some unrelated reason must not read as the
     * documented one. Before alpha.22 every path was refused, which this check reports as a
     * regression — the docs now promise the executed route.
     */
    static boolean propertyPathRoute() throws Exception {
        String path = "?x <" + C + "broader>+ ";
        String now = pathRefusal("SELECT ?x FROM STREAM S [NOW]\n"
                + "WHERE { STREAM S { " + path + "<" + C + "root> . } }\n");
        String besideStatic = pathRefusal("SELECT ?x ?l FROM STREAM S [RANGE 5s]\n"
                + "WHERE { STREAM S { " + path + "?r . } ?r <" + C + "label> ?l . }\n");
        boolean refusedByName = now != null && now.toLowerCase().contains("path")
                && besideStatic != null && besideStatic.toLowerCase().contains("path");

        try (CQELSEngine engine = CQELSEngine.builder().id("probe-path").withMemoryStore().build()) {
            DataStream stream = engine.createStream("S");
            List<Object> toRoot = new CopyOnWriteArrayList<>();
            List<Object> toBogus = new CopyOnWriteArrayList<>();
            try {
                engine.registerCqelsQuery(withRegister("SELECT ?x FROM STREAM S [RANGE 5s]\n"
                        + "WHERE { STREAM S { " + path + "<" + C + "root> . } }\n"), toRoot::add);
                engine.registerCqelsQuery(withRegister("SELECT ?x FROM STREAM S [RANGE 5s]\n"
                        + "WHERE { STREAM S { " + path + "<" + C + "nope> . } }\n"), toBogus::add);
            } catch (Exception e) {
                DIVERGENCES.add("REFUSED — a '+' path inside a stream-only [RANGE] STREAM block was "
                        + "refused at registration (" + e.getMessage() + ")\n      CQELS-QL_SPEC.md §9 "
                        + "says this route executes paths");
                return false;
            }
            engine.start();
            stream.pushTriple(C + "leaf", C + "broader", C + "mid");
            Thread.sleep(200);
            stream.pushTriple(C + "mid", C + "broader", C + "root");
            Thread.sleep(1500);
            String got = toRoot.toString();
            boolean executes = exactRows(toRoot, List.of(row("x", iri("mid")), row("x", iri("leaf"))))
                    && toBogus.isEmpty();
            if (!executes) {
                DIVERGENCES.add("MIS-EVALUATED — '+' over leaf -> mid -> root returned " + got
                        + " (bogus target: " + toBogus + ")\n      expected mid and leaf, and nothing "
                        + "for the bogus target; this is the silent-mangling case from #56");
            }
            if (!refusedByName) {
                DIVERGENCES.add("NOT REFUSED BY NAME — [NOW]: " + now + "; beside a static pattern: "
                        + besideStatic + "\n      CQELS-QL_SPEC.md §9 says both are refused at "
                        + "registration with a message naming the path");
            }
            return executes && refusedByName;
        }
    }

    /** Registers a CQELS-QL query; returns null if it registers, or the refusal's message. */
    private static String pathRefusal(String body) throws Exception {
        try (CQELSEngine engine = CQELSEngine.builder().id("probe-path-refusal-" + QUERY_SEQ.incrementAndGet())
                .withMemoryStore().build()) {
            engine.createStream("S");
            try {
                engine.registerCqelsQuery(withRegister(body), row -> { });
                return null;
            } catch (Exception e) {
                return String.valueOf(e.getMessage());
            }
        }
    }

    /**
     * REPLACE (SPARQL 1.1 §17.4.3.14) must evaluate; fixed in alpha.22 (before, the variable stayed
     * unbound). The value is asserted, not just its presence: {@code STR(?x)} alone would also bind
     * {@code ?r} (codex, review of #116). The second case is the nested segment-id cleanup that
     * CDSP_MAPPING.md now says ports directly.
     */
    static boolean replaceEvaluates() throws Exception {
        List<Object> single = run(null, """
            SELECT ?r FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sof> ?x . } BIND(REPLACE(STR(?x), "^.*#", "") AS ?r) }
            """.formatted(C),
                s -> s.pushTriple(C + "o1", C + "of", C + "a"));
        List<Object> nested = run(null, """
            SELECT ?r FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sof> ?x . }
                    BIND(REPLACE(REPLACE(STR(?x), "^.*#", ""), "[^a-zA-Z0-9]", "") AS ?r) }
            """.formatted(C),
                s -> s.pushTriple(C + "o1", C + "of", C + "a-b_c"));
        // Exactly one plain-string row each: an extra row or a language-tagged "a"@fr must fail.
        return exactRows(single, List.of(row("r", "a"))) && exactRows(nested, List.of(row("r", "abc")));
    }

    /** sameTerm (SPARQL 1.1 §17.4.1.1) must evaluate, even over one variable with itself. */
    static boolean sameTermEvaluates() throws Exception {
        // A positive alone is a tautology: FILTER(sameTerm(?x, ?x)) also passes on an engine that
        // treats an unknown function as "true", or ignores the filter entirely (codex, review of
        // #66). What this guards is SPARQL *term identity*, so it needs the two distinctions that
        // separate sameTerm from a loose comparison — a different IRI, and an IRI versus a string
        // literal with the same lexical form. Both verified against alpha.20 before being asserted.
        List<Object> same = run(null, """
            SELECT ?x FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sof> ?x . } FILTER(sameTerm(?x, ?x)) }
            """.formatted(C),
                s -> s.pushTriple(C + "o1", C + "of", C + "a"));

        List<Object> otherIri = run(null, """
            SELECT ?x FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sof> ?x . } FILTER(sameTerm(?x, <%szzz>)) }
            """.formatted(C, C),
                s -> s.pushTriple(C + "o1", C + "of", C + "a"));

        // ?x is the IRI <...#a>; ?lit is the plain string "...#a". STR() cannot tell them apart;
        // sameTerm must.
        List<Object> iriVsLiteral = run(null, """
            SELECT ?x FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sof> ?x . ?o <%slit> ?lit . } FILTER(sameTerm(?x, ?lit)) }
            """.formatted(C, C),
                s -> {
                    IRI o = VF.createIRI(C + "o1");
                    s.push(List.of(
                            VF.createStatement(o, VF.createIRI(C + "of"), VF.createIRI(C + "a")),
                            VF.createStatement(o, VF.createIRI(C + "lit"), VF.createLiteral(C + "a"))));
                });

        // "1"^^xsd:int and "1.0"^^xsd:decimal are EQUAL values but distinct terms: = admits the pair
        // (the control: if it stops, the fixture no longer separates the two), sameTerm must not.
        // Without this pair, replacing sameTerm with = passed (codex, round 5 of #116).
        Pusher equalValues = s -> {
            IRI o = VF.createIRI(C + "o1");
            s.push(List.of(
                    VF.createStatement(o, VF.createIRI(C + "a"), VF.createLiteral("1", org.eclipse.rdf4j.model.vocabulary.XSD.INT)),
                    VF.createStatement(o, VF.createIRI(C + "b"), VF.createLiteral("1.0", org.eclipse.rdf4j.model.vocabulary.XSD.DECIMAL))));
        };
        String pair = """
            SELECT ?o FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%1$sa> ?x . ?o <%1$sb> ?y . } FILTER(%2$s) }
            """;
        List<Object> valueEqual = run(null, pair.formatted(C, "?x = ?y"), equalValues);
        List<Object> termEqual = run(null, pair.formatted(C, "sameTerm(?x, ?y)"), equalValues);
        // Positive literal cases: two separately bound copies of the SAME literal term, numeric and
        // language-tagged, must satisfy sameTerm. Every earlier positive used IRIs, so a sameTerm
        // that only recognised IRIs passed (codex, round 6 of #116).
        List<Map<String, Object>> o1 = List.of(row("o", iri("o1")));
        boolean literalsSame = true;
        for (Literal lit : List.of(VF.createLiteral("1", org.eclipse.rdf4j.model.vocabulary.XSD.INT),
                VF.createLiteral("cat", "fr"))) {
            literalsSame &= exactRows(run(null, pair.formatted(C, "sameTerm(?x, ?y)"), s -> s.push(List.of(
                    VF.createStatement(iri("o1"), iri("a"), lit),
                    VF.createStatement(iri("o1"), iri("b"), lit)))), o1);
        }
        if (valueEqual.isEmpty()) {
            DIVERGENCES.add("REGRESSED — the control (\"1\"^^xsd:int = \"1.0\"^^xsd:decimal) no longer "
                    + "matches\n      the sameTerm-vs-equality verdict is meaningless until that is understood");
        }

        // Negative literal cases that vary ONE part of the term each: the datatype alone
        // ("1"^^xsd:int vs "1"^^xsd:decimal) and the lexical form alone ("1" vs "01", both xsd:int).
        // Each pair is value-equal, so = admits it (the control) while sameTerm must not. The
        // int/decimal pair above changes both parts at once and could not tell which one sameTerm
        // checks (codex, round 7 of #116).
        boolean partsChecked = true;
        Literal int1 = VF.createLiteral("1", org.eclipse.rdf4j.model.vocabulary.XSD.INT);
        for (Literal other : List.of(VF.createLiteral("1", org.eclipse.rdf4j.model.vocabulary.XSD.DECIMAL),
                VF.createLiteral("01", org.eclipse.rdf4j.model.vocabulary.XSD.INT))) {
            Pusher differ = s -> s.push(List.of(
                    VF.createStatement(iri("o1"), iri("a"), int1),
                    VF.createStatement(iri("o1"), iri("b"), other)));
            if (!exactRows(run(null, pair.formatted(C, "?x = ?y"), differ), o1)) {
                DIVERGENCES.add("REGRESSED — the control (" + int1 + " = " + other + ") no longer matches"
                        + "\n      the sameTerm verdict for that pair is meaningless until that is understood");
            }
            partsChecked &= run(null, pair.formatted(C, "sameTerm(?x, ?y)"), differ).isEmpty();
        }

        return !same.isEmpty() && otherIri.isEmpty() && iriVsLiteral.isEmpty() && termEqual.isEmpty()
                && literalsSame && partsChecked;
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
     * #82, fixed in 2.0.0-alpha.22: a {@code FILTER} compares {@code xsd:duration} values by value.
     * Through alpha.21 every result was consistent with comparing the literals' text:
     *
     * <pre>
     *   "PT2S"  &gt; "PT1S"    matched        (true as durations, true as text)
     *   "PT10S" &gt; "PT9S"    did NOT match  (true as durations, false as text)
     *   "PT60S" = "PT1M"    did NOT match  (equal durations, different text)
     * </pre>
     *
     * <p>All three must match now, and their negations must NOT: a check made only of comparisons
     * that should hold also passes on an engine that ignores the {@code FILTER} (codex, review of
     * #116). The first pair is the control: {@code PT2S > PT1S} held as text too, so it is the
     * row that would catch a type error. A row-bound duration from the stream is checked as well,
     * against a constant, both ways.
     */
    static boolean durationFilterIsByValue() throws Exception {
        String d = "^^<%1$sduration>";
        boolean holds = durationFilterMatches("\"PT2S\"" + d + " > \"PT1S\"" + d)
                && durationFilterMatches("\"PT10S\"" + d + " > \"PT9S\"" + d)
                && durationFilterMatches("\"PT60S\"" + d + " = \"PT1M\"" + d);
        boolean rejects = !durationFilterMatches("\"PT1S\"" + d + " > \"PT2S\"" + d)
                && !durationFilterMatches("\"PT9S\"" + d + " > \"PT10S\"" + d)
                && !durationFilterMatches("\"PT60S\"" + d + " != \"PT1M\"" + d);
        IRI duration = VF.createIRI("http://www.w3.org/2001/XMLSchema#duration");
        Set<String> rowBound = subjects(run(null, """
            SELECT ?o FROM STREAM S [NOW]
            WHERE { STREAM S { ?o <%1$sd> ?d . } FILTER(?d > "PT9S"^^<http://www.w3.org/2001/XMLSchema#duration>) }
            """.formatted(C), s -> {
                s.push(VF.createStatement(VF.createIRI(C + "o1"), VF.createIRI(C + "d"),
                        VF.createLiteral("PT10S", duration)));
                s.push(VF.createStatement(VF.createIRI(C + "o2"), VF.createIRI(C + "d"),
                        VF.createLiteral("PT8S", duration)));
            }));
        return holds && rejects && rowBound.equals(Set.of("o1"));
    }

    /**
     * Measured on 2.0.0-alpha.22 (and the same on alpha.21): {@code =} compares a language-tagged
     * literal by its label, so {@code "cat"@en = "cat"@fr} holds and {@code !=} does not. In SPARQL
     * 1.1 the two are distinct terms, so {@code =} must not hold; RDF4J's own evaluator, the
     * reference used here, answers {@code =} false and {@code !=} true.
     * Both arrive in one atomic element under {@code [TRIPLES 1]}.
     *
     * <p>The control is {@code sameTerm}, which must reject the pair, and {@code =} over two
     * identical {@code "cat"@en} values, which must hold — or the fixture itself is failing.
     *
     * <p>Returns {@code equal} as documented ({@code =} one row, {@code !=} none), {@code not equal}
     * once fixed, or the rows.
     */
    static String languageTaggedEquality() throws Exception {
        String pair = """
            SELECT ?o FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%1$sa> ?x . ?o <%1$sb> ?y . } FILTER(%2$s) }
            """;
        Pusher tags = s -> s.push(List.of(
                VF.createStatement(iri("o1"), iri("a"), VF.createLiteral("cat", "en")),
                VF.createStatement(iri("o1"), iri("b"), VF.createLiteral("cat", "fr"))));
        Pusher same = s -> s.push(List.of(
                VF.createStatement(iri("o1"), iri("a"), VF.createLiteral("cat", "en")),
                VF.createStatement(iri("o1"), iri("b"), VF.createLiteral("cat", "en"))));
        List<Map<String, Object>> o1 = List.of(row("o", iri("o1")));
        if (!run(null, pair.formatted(C, "sameTerm(?x, ?y)"), tags).isEmpty()
                || !exactRows(run(null, pair.formatted(C, "?x = ?y"), same), o1)) {
            DIVERGENCES.add("REGRESSED — the controls (sameTerm rejects @en/@fr; = holds for @en/@en) "
                    + "failed\n      the language-tag equality verdict is meaningless until that is understood");
        }
        List<Object> eq = run(null, pair.formatted(C, "?x = ?y"), tags);
        List<Object> ne = run(null, pair.formatted(C, "?x != ?y"), tags);
        if (exactRows(eq, o1) && ne.isEmpty()) {
            return "equal";
        }
        if (eq.isEmpty() && exactRows(ne, o1)) {
            return "not equal";
        }
        return "= " + eq + ", != " + ne;
    }

    private static boolean durationFilterMatches(String filter) throws Exception {
        String xsd = "http://www.w3.org/2001/XMLSchema#";
        List<Object> rows = run(null, """
            SELECT ?o FROM STREAM S [NOW]
            WHERE { STREAM S { ?o <%sv> ?x . } FILTER(%s) }
            """.formatted(C, filter.formatted(xsd)),
                s -> s.push(VF.createStatement(VF.createIRI(C + "o1"), VF.createIRI(C + "v"),
                        VF.createLiteral(1.0))));
        return !rows.isEmpty();
    }

    /**
     * #85, fixed in 2.0.0-alpha.22: on a stream-only query, a {@code FILTER} over a variable an
     * {@code OPTIONAL} binds must run after the {@code OPTIONAL}. On alpha.20 and alpha.21 it ran
     * first and saw the variable unbound: {@code FILTER(BOUND(?l))} dropped a row whose {@code ?l}
     * the {@code OPTIONAL} binds, and {@code FILTER(!BOUND(?l))} admitted it, carrying the bound
     * value.
     *
     * <p>Two subjects arrive: {@code o1}, which the {@code OPTIONAL} labels, and {@code o2}, which it
     * does not. The control is the query with no {@code FILTER}: it must return {@code o1} with
     * {@code ?l} bound and {@code o2} without, or the {@code OPTIONAL} itself has broken and the
     * verdict means nothing. The verdict needs both directions right — {@code BOUND} keeps only
     * {@code o1}, {@code !BOUND} keeps only {@code o2} — so a query that loses its {@code FILTER}
     * altogether does not read as a fix.
     *
     * <p>Returns true when both filters answer as SPARQL requires.
     */
    static boolean filterSeesOptionalVariableOnStreamOnlyQuery() throws Exception {
        Seeder seeded = engine -> {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                conn.add(VF.createIRI(C + "o1"), VF.createIRI(C + "label"), VF.createLiteral(8));
            }
        };
        Pusher both = s -> {
            s.push(VF.createStatement(VF.createIRI(C + "o1"), VF.createIRI(C + "v"), VF.createLiteral(3)));
            s.push(VF.createStatement(VF.createIRI(C + "o2"), VF.createIRI(C + "v"), VF.createLiteral(4)));
        };
        String shape = """
            SELECT ?o ?l FROM STREAM S [NOW]
            WHERE { STREAM S { ?o <%1$sv> ?x . } OPTIONAL { ?o <%1$slabel> ?l } %2$s }
            """;
        Set<String> unfiltered = subjects(run(seeded, shape.formatted(C, ""), both));
        Set<String> bound = subjects(run(seeded, shape.formatted(C, "FILTER(BOUND(?l))"), both));
        Set<String> unbound = subjects(run(seeded, shape.formatted(C, "FILTER(!BOUND(?l))"), both));
        if (!unfiltered.equals(Set.of("o1+l", "o2"))) {
            DIVERGENCES.add("REGRESSED — the control (the OPTIONAL with no FILTER) returned " + unfiltered
                    + ", not [o1+l, o2]\n      the stream-only OPTIONAL itself is failing; the "
                    + "OPTIONAL/FILTER verdict is meaningless");
        }
        boolean boundRight = bound.equals(Set.of("o1+l"));
        boolean unboundRight = unbound.equals(Set.of("o2"));
        if (boundRight != unboundRight) {
            DIVERGENCES.add("PARTIAL — #85 holds in one direction only: FILTER(BOUND(?l)) returned "
                    + bound + ", FILTER(!BOUND(?l)) returned " + unbound);
        }
        return boundRight && unboundRight;
    }

    /**
     * CQELS-QL_SPEC.md §10 states the CypherQL clause order: {@code FROM STREAM} first, and a query
     * starting with {@code MATCH} is a syntax error (the spec's own example had it the wrong way
     * round until #76). Both halves are checked, so the sentence goes stale in either direction.
     */
    static boolean cypherClauseOrder() throws Exception {
        String match = "MATCH (req:Request)-[:FOR]->(p:Person)-[:LOCATED_IN]->(c:City)\n";
        String from = "FROM STREAM ProfileRequests [NOW]\n";
        String ret = "RETURN p.firstName, p.lastName, c.name";
        return cypherRefusal(from + match + ret) == null
                && String.valueOf(cypherRefusal(match + from + ret)).contains("Syntax error");
    }

    /** Registers the CypherQL query; returns null if it registers, or the refusal's message. */
    private static String cypherRefusal(String query) throws Exception {
        try (CQELSEngine engine = CQELSEngine.builder().id("probe-" + System.nanoTime()).withMemoryStore().build()) {
            engine.createStream("ProfileRequests");
            try {
                engine.registerCypherQuery(query, row -> { });
                return null;
            } catch (Exception refused) {
                return String.valueOf(refused.getMessage());
            }
        }
    }

    static boolean builtinsWork() throws Exception {
        List<Object> rows = run(null, """
            SELECT ?d ?c ?i ?u ?f FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sval> ?v . }
                    BIND(ABS(?v - 100.0) AS ?d)
                    BIND(CONCAT("x", STR(?v)) AS ?c)
                    BIND(IRI(CONCAT("%s", STR(?v))) AS ?i)
                    BIND(SUBSTR(STR(?v), 1, 2) AS ?u)
                    BIND(IF(?v > 10, "hi", "lo") AS ?f) }
            """.formatted(C, C),
                s -> {
                    s.push(C + "o1", C + "val", 250.0);
                    s.push(C + "o2", C + "val", 5.0);
                });
        // Assert the VALUES, not merely that the bindings exist: a wrong ABS or SUBSTR would
        // otherwise pass, and this check exists to catch behavioural regressions.
        // Compared as exact values per binding: substring matching accepted d=150.01 (codex, #116).
        return exactRows(rows, List.of(row(
                "d", num(150.0),                // ABS(250 - 100)
                "c", "x250.0",                  // CONCAT("x", STR(250.0))
                "i", iri("250.0"),              // IRI(...) must bind an IRI, not a string
                "u", "25",                      // SUBSTR("250.0", 1, 2)
                "f", "hi"),                     // IF(250 > 10, "hi", "lo")
                // The second input separates ABS from its argument (-95) and IF's branches: with
                // 250 alone, a missing ABS or a constant "hi" passed (codex, round 5 of #116).
                row("d", num(95.0), "c", "x5.0", "i", iri("5.0"), "u", "5.", "f", "lo")));
    }

    static boolean atomicElementJoins() throws Exception {
        List<Object> rows = run(null, """
            SELECT ?v ?w FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sval> ?v . ?o <%sother> ?w . } }
            """.formatted(C, C),
                s -> {
                    IRI o = VF.createIRI(C + "o1");
                    s.push(List.of(
                            VF.createStatement(o, VF.createIRI(C + "val"), VF.createLiteral(1.0)),
                            VF.createStatement(o, VF.createIRI(C + "other"), VF.createLiteral(2.0))));
                });
        return exactRows(rows, List.of(row("v", num(1.0), "w", num(2.0))));
    }
}
