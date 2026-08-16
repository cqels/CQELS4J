package org.cqels.examples;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Behaviour-truth probe — asserts that what this repository <em>says</em> about the engine is still
 * what the engine <em>does</em>.
 *
 * <p><strong>Why this exists.</strong> `scripts/ci/version-truth-gate.sh` was written because a claim
 * in the prose can go stale without anything failing to compile. Capability claims are the same
 * hazard one category over. The examples, `examples/README.md`, `CQELS-QL_SPEC.md` §6/§9 and
 * `CDSP_MAPPING.md` all carry statements of the form "X does not work on 2.0.0-alpha.18, work around
 * it like this". Every one of those becomes <em>false</em> the day the engine is fixed, and nothing
 * would notice: the workarounds keep working, so the build stays green while the documentation
 * quietly starts lying — and readers keep applying workarounds they no longer need.
 *
 * <p>So this probe checks in <strong>both directions</strong>:
 * <ul>
 *   <li><strong>Caveats</strong> — documented as broken. If one starts working, that is a
 *       <em>documentation defect</em>: the prose and the workarounds must be removed. The probe
 *       fails and names the files to edit.</li>
 *   <li><strong>Capabilities</strong> — documented as working, and relied on by the examples. If one
 *       breaks, that is a regression in a new engine release.</li>
 * </ul>
 *
 * <p>Either way a failure means "reality and the documentation have diverged", which is exactly the
 * class of defect this repository exists to avoid. Run it whenever the pin moves — `RELEASING.md`
 * step 4 — and nightly via `.github/workflows/version-truth.yml`.
 *
 * <p>Exit status: <strong>0</strong> if documentation and reality agree, <strong>non-zero</strong>
 * (an exception, so Maven fails the build) if they diverge.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.CapabilityProbe}
 */
public class CapabilityProbe {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final String C = "https://example.org/probe#";
    private static final List<String> DIVERGENCES = new ArrayList<>();
    private static int checks = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("Capability probe — does the engine still behave as this repo documents?\n");

        System.out.println("-- caveats (documented as BROKEN; a pass here means the docs are stale) --");
        caveat("#54 static pattern fails to eliminate a non-matching row",
                staticPatternEliminates(),
                "CQELS-QL_SPEC.md §6 'Known defect', S2dmConceptCatalog.java, CDSP_MAPPING.md §5");
        caveat("#55 prefixed name inside FILTER is not resolved",
                pnameInFilterResolves(),
                "CQELS-QL_SPEC.md §9 gap table, S2dmInstanceZones.java, CDSP_MAPPING.md §5");
        caveat("#56 property path registers despite a parse error",
                propertyPathRejected(),
                "CQELS-QL_SPEC.md §9 'Property paths', CDSP_MAPPING.md §5");
        caveat("     REPLACE() is not evaluated",
                replaceEvaluates(),
                "CQELS-QL_SPEC.md §9 gap table, CDSP_MAPPING.md §4");
        caveat("     sameTerm() is not evaluated",
                sameTermEvaluates(),
                "CQELS-QL_SPEC.md §9 gap table (and issue #55)");

        System.out.println("\n-- capabilities (documented as WORKING; the examples depend on these) --");
        capability("stream-static enrichment binds static variables", staticEnrichmentBinds());
        capability("FILTER/BIND builtins: ABS, CONCAT, IRI, SUBSTR, IF", builtinsWork());
        capability("atomic multi-statement element joins in a query", atomicElementJoins());
        capability("GROUP BY with aggregates", groupByWorks());

        System.out.println("\n" + "-".repeat(72));
        if (DIVERGENCES.isEmpty()) {
            System.out.println("OK: " + checks + " checks — documentation and engine behaviour agree.");
            return;
        }
        System.out.println("DIVERGENCE: " + DIVERGENCES.size() + " of " + checks
                + " checks disagree with the documentation.\n");
        DIVERGENCES.forEach(d -> System.out.println("  " + d));
        throw new IllegalStateException(
                DIVERGENCES.size() + " capability claim(s) in this repository are no longer true. "
                        + "Update the files named above, then re-run.");
    }

    /** A behaviour documented as broken. {@code works == true} means the docs are now stale. */
    private static void caveat(String name, boolean works, String docs) {
        checks++;
        if (works) {
            System.out.println("  NOW FIXED  " + name);
            DIVERGENCES.add("NOW FIXED — " + name + "\n      remove the caveat and its workaround from: " + docs);
        } else {
            System.out.println("  still open " + name);
        }
    }

    /** A behaviour documented as working. {@code works == false} is a regression. */
    private static void capability(String name, boolean works) {
        checks++;
        if (works) {
            System.out.println("  ok         " + name);
        } else {
            System.out.println("  REGRESSED  " + name);
            DIVERGENCES.add("REGRESSED — " + name + "\n      the examples relying on this no longer work");
        }
    }

    // ---- caveat probes -------------------------------------------------------------------

    /** #54: a static pattern with no matching data must eliminate the row. Today it does not. */
    private static boolean staticPatternEliminates() throws Exception {
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

    /** #55: a prefixed name as a FILTER constant must resolve. Today it does not. */
    private static boolean pnameInFilterResolves() throws Exception {
        List<Object> rows = run(null, """
            PREFIX p: <%s>
            SELECT ?x FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o p:of ?x . } FILTER(?x = p:a) }
            """.formatted(C),
                s -> s.pushTriple(C + "o1", C + "of", C + "a"));
        return !rows.isEmpty();
    }

    /** #56: an unsupported property path must be rejected at registration, not silently mangled. */
    private static boolean propertyPathRejected() {
        try (CQELSEngine engine = CQELSEngine.builder().id("probe-path").withMemoryStore().build()) {
            engine.createStream("S");
            engine.registerCqelsQuery(withRegister("""
                    PREFIX p: <%s>
                    SELECT ?x FROM STREAM S [TRIPLES 1]
                    WHERE { STREAM S { ?o p:of ?x . } ?x p:broader+ <%sroot> . }
                    """.formatted(C, C)), r -> { });
            return false;   // accepted a path it cannot execute
        } catch (Exception e) {
            // Rejected. Only counts as "fixed" if it was rejected for the PATH — a probe that
            // treats any failure as a fix would report a false alarm on an unrelated error.
            String msg = String.valueOf(e.getMessage()).toLowerCase();
            return msg.contains("path") || msg.contains("'+'") || msg.contains("extraneous");
        }
    }

    /** REPLACE (SPARQL 1.1 §17.4.3.14) must bind. Today the variable stays unbound. */
    private static boolean replaceEvaluates() throws Exception {
        List<Object> rows = run(null, """
            SELECT ?r FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sof> ?x . } BIND(REPLACE(STR(?x), "^.*#", "") AS ?r) }
            """.formatted(C),
                s -> s.pushTriple(C + "o1", C + "of", C + "a"));
        return rows.stream().anyMatch(r -> r.toString().contains("r="));
    }

    /** sameTerm (SPARQL 1.1 §17.4.1.1) must evaluate, even over one variable with itself. */
    private static boolean sameTermEvaluates() throws Exception {
        List<Object> rows = run(null, """
            SELECT ?x FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sof> ?x . } FILTER(sameTerm(?x, ?x)) }
            """.formatted(C),
                s -> s.pushTriple(C + "o1", C + "of", C + "a"));
        return !rows.isEmpty();
    }

    // ---- capability probes ---------------------------------------------------------------

    private static boolean staticEnrichmentBinds() throws Exception {
        List<Object> rows = run(engine -> {
            try (RepositoryConnection conn = engine.getRepository().getConnection()) {
                conn.add(VF.createIRI(C + "a"), VF.createIRI(C + "label"), VF.createLiteral("Alpha"));
            }
        }, """
            SELECT ?label FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sof> ?x . } ?x <%slabel> ?label . }
            """.formatted(C, C),
                s -> s.pushTriple(C + "o1", C + "of", C + "a"));
        return rows.stream().anyMatch(r -> r.toString().contains("Alpha"));
    }

    private static boolean builtinsWork() throws Exception {
        List<Object> rows = run(null, """
            SELECT ?d ?c ?i ?u ?f FROM STREAM S [TRIPLES 1]
            WHERE { STREAM S { ?o <%sval> ?v . }
                    BIND(ABS(?v - 100.0) AS ?d)
                    BIND(CONCAT("x", STR(?v)) AS ?c)
                    BIND(IRI(CONCAT("%s", STR(?v))) AS ?i)
                    BIND(SUBSTR(STR(?v), 1, 2) AS ?u)
                    BIND(IF(?v > 10, "hi", "lo") AS ?f) }
            """.formatted(C, C),
                s -> s.push(C + "o1", C + "val", 250.0));
        String r = rows.toString();
        return r.contains("d=") && r.contains("c=") && r.contains("i=") && r.contains("u=") && r.contains("f=");
    }

    private static boolean atomicElementJoins() throws Exception {
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
        String r = rows.toString();
        return r.contains("v=") && r.contains("w=");
    }

    private static boolean groupByWorks() throws Exception {
        List<Object> rows = run(null, """
            SELECT ?g (COUNT(*) AS ?n)
            FROM STREAM S [RANGE 1s]
            WHERE { STREAM S { ?o <%sgroup> ?g . } }
            GROUP BY ?g
            """.formatted(C),
                s -> {
                    // A windowed aggregate is evaluated when an element ARRIVES, not on a timer,
                    // so the window needs a later element to close over the earlier ones. Pushing
                    // all three at once would leave the aggregate unemitted and look like a
                    // regression -- which is exactly the false alarm this pacing avoids.
                    s.pushTriple(C + "o1", C + "group", C + "g1");
                    Thread.sleep(300);
                    s.pushTriple(C + "o2", C + "group", C + "g1");
                    Thread.sleep(1200);
                    s.pushTriple(C + "o3", C + "group", C + "g1");
                });
        return rows.stream().anyMatch(r -> r.toString().contains("n="));
    }

    // ---- harness -------------------------------------------------------------------------

    private interface Seeder { void seed(CQELSEngine engine); }
    private interface Pusher { void push(DataStream stream) throws Exception; }

    /**
     * Insert {@code REGISTER QUERY … AS} after any leading {@code PREFIX} lines — the prologue must
     * precede the REGISTER clause, so it cannot simply be prepended.
     */
    private static String withRegister(String query) {
        StringBuilder prologue = new StringBuilder();
        StringBuilder body = new StringBuilder();
        boolean inPrologue = true;
        for (String line : query.stripLeading().split("\n")) {
            if (inPrologue && line.stripLeading().toUpperCase().startsWith("PREFIX ")) {
                prologue.append(line).append('\n');
            } else {
                inPrologue = false;
                body.append(line).append('\n');
            }
        }
        return prologue + "REGISTER QUERY Probe AS\n" + body;
    }

    /** One query, one fresh engine, so checks cannot contaminate each other. */
    private static List<Object> run(Seeder seeder, String where, Pusher pusher) throws Exception {
        List<Object> rows = new CopyOnWriteArrayList<>();
        try (CQELSEngine engine = CQELSEngine.builder()
                .id("probe-" + System.nanoTime())
                .withMemoryStore()
                .build()) {
            if (seeder != null) {
                seeder.seed(engine);
            }
            DataStream stream = engine.createStream("S");
            engine.registerCqelsQuery(withRegister(where), rows::add);
            engine.start();
            pusher.push(stream);
            Thread.sleep(2500);
        }
        return rows;
    }
}
