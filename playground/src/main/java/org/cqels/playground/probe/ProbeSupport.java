package org.cqels.playground.probe;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the probe areas share: the divergence report, a fresh engine per query, and the exact-row
 * oracle that checks compare their rows with.
 */
final class ProbeSupport {

    static final ValueFactory VF = SimpleValueFactory.getInstance();
    static final String C = "https://example.org/probe#";
    static final java.util.concurrent.atomic.AtomicLong QUERY_SEQ =
            new java.util.concurrent.atomic.AtomicLong();
    static final List<String> DIVERGENCES = new ArrayList<>();
    static int checks = 0;

    private ProbeSupport() {
    }

    /** A behaviour documented as broken. {@code works == true} means the docs are now stale. */
    static void caveat(String name, boolean works, String docs) {
        checks++;
        if (works) {
            System.out.println("  NOW FIXED  " + name);
            DIVERGENCES.add("NOW FIXED — " + name + "\n      remove the caveat and its workaround from: " + docs);
        } else {
            System.out.println("  still open " + name);
        }
    }

    /**
     * A behaviour documented as broken in one EXACT way. {@code observed} is compared with the
     * documented broken result and with the result a fix would give; anything else — no result at
     * all, an error, a third value — is a divergence too, because the docs then describe neither
     * what happens nor what a fix looks like. A boolean caveat cannot tell those apart: it reads
     * "no row" as "still broken" (codex, review of #116).
     */
    static void knownBug(String name, String observed, String asDocumented, String fixed, String docs) {
        checks++;
        if (asDocumented.equals(observed)) {
            System.out.println("  still open " + name);
        } else if (fixed.equals(observed)) {
            System.out.println("  NOW FIXED  " + name);
            DIVERGENCES.add("NOW FIXED — " + name + "\n      remove the caveat and its workaround from: " + docs);
        } else {
            System.out.println("  CHANGED    " + name);
            DIVERGENCES.add("CHANGED — " + name + "\n      observed " + observed + "; documented as "
                    + asDocumented + ", fixed would be " + fixed + ". Re-measure and edit: " + docs);
        }
    }

    /** A behaviour documented as working. {@code works == false} is a regression. */
    static void capability(String name, boolean works) {
        checks++;
        if (works) {
            System.out.println("  ok         " + name);
        } else {
            System.out.println("  REGRESSED  " + name);
            DIVERGENCES.add("REGRESSED — " + name + "\n      the examples relying on this no longer work");
        }
    }

    /** Each row as its subject's local name, suffixed {@code +l} when {@code ?l} is bound. */
    static Set<String> subjects(List<Object> rows) {
        Set<String> out = new TreeSet<>();
        for (Object row : rows) {
            Map<?, ?> bindings = (Map<?, ?>) row;
            String o = String.valueOf(bindings.get("o"));
            out.add(o.substring(o.lastIndexOf('#') + 1) + (bindings.get("l") == null ? "" : "+l"));
        }
        return out;
    }

    /**
     * A binding as text: a literal's label, an IRI's string, anything else's {@code toString}. For
     * messages and for the duration reports; checks compare with {@link #sameValue}, never with
     * substrings of a row's {@code toString} (substring matching accepted {@code 150.01} for
     * {@code 150.0} and {@code AlphaWRONG} for {@code Alpha}; codex, review of #116).
     */
    private static String text(Object value) {
        return value == null ? "null" : (value instanceof Literal l ? l.getLabel() : String.valueOf(value));
    }

    /** An expected number: compared numerically, and only against a number or numeric literal. */
    private record Num(double value) { }

    /** An expected unbound variable: absent from the row, or bound to null. */
    static final Object UNBOUND = new Object();

    static IRI iri(String local) {
        return VF.createIRI(C + local);
    }

    static Num num(double value) {
        return new Num(value);
    }

    /**
     * One expected row: alternating variable names and typed values — {@link IRI}, {@link Num}, a
     * plain String (a Java String or simple literal), an exact {@link Literal} (language-tagged or
     * otherwise typed), or {@link #UNBOUND}.
     */
    static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /**
     * True when {@code actual} is the expected value AND of the expected kind: an IRI only matches an
     * IRI, a string only a non-IRI with the same exact lexical form ({@code "025"} is not
     * {@code "25"}), a number only a number. Spelling alone accepted a string where the engine
     * should bind an IRI (codex, round 3 of #116).
     */
    private static boolean sameValue(Object actual, Object expected) {
        if (expected == UNBOUND) {
            return actual == null;
        }
        if (actual == null) {
            return false;
        }
        if (expected instanceof IRI want) {
            return actual instanceof IRI got && got.equals(want);
        }
        if (expected instanceof Num want) {
            if (actual instanceof Number n) {
                return n.doubleValue() == want.value();
            }
            // A literal counts as a number only with a numeric datatype: doubleValue() alone parses
            // the label of "150.0"^^xsd:string too (codex, round 4 of #116).
            if (actual instanceof Literal l && l.getLanguage().isEmpty()
                    && org.eclipse.rdf4j.model.datatypes.XMLDatatypeUtil.isNumericDatatype(l.getDatatype())) {
                try {
                    return l.doubleValue() == want.value();
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            return false;
        }
        if (expected instanceof Literal want) {
            // Term identity — label, language tag and datatype — whatever Literal class the engine uses.
            // Language tags compare case-insensitively, as RDF term equality does (@EN is @en).
            return actual instanceof Literal got && got.getLabel().equals(want.getLabel())
                    && got.getLanguage().map(t -> t.toLowerCase(java.util.Locale.ROOT))
                            .equals(want.getLanguage().map(t -> t.toLowerCase(java.util.Locale.ROOT)))
                    && got.getDatatype().equals(want.getDatatype());
        }
        // A plain string: a Java String, or a simple literal (xsd:string, no language tag). "25"^^xsd:int
        // and "25"@fr are other RDF terms (codex, round 4 of #116); expect them as a Literal instead.
        if (actual instanceof String str) {
            return str.equals(expected);
        }
        return actual instanceof Literal l && l.getLanguage().isEmpty()
                && org.eclipse.rdf4j.model.vocabulary.XSD.STRING.equals(l.getDatatype())
                && l.getLabel().equals(expected);
    }

    /**
     * True when the rows are exactly the expected ones, as a multiset: the same number of rows, and
     * each expected row matched by a distinct actual row that binds every expected variable to the
     * expected value. Variables an expected row does not name are not inspected.
     */
    static boolean exactRows(List<Object> rows, List<Map<String, Object>> expected) {
        if (rows.size() != expected.size()) {
            return false;
        }
        List<Object> left = new ArrayList<>(rows);
        for (Map<String, Object> want : expected) {
            Object hit = null;
            for (Object candidate : left) {
                Map<?, ?> got = (Map<?, ?>) candidate;
                if (want.entrySet().stream().allMatch(e -> (e.getValue() == UNBOUND || got.containsKey(e.getKey()))
                        && sameValue(got.get(e.getKey()), e.getValue()))) {
                    hit = candidate;
                    break;
                }
            }
            if (hit == null) {
                return false;
            }
            left.remove(hit);
        }
        return true;
    }

    interface Seeder { void seed(CQELSEngine engine); }
    interface Pusher { void push(DataStream stream) throws Exception; }

    /**
     * Insert {@code REGISTER QUERY … AS} after any leading {@code PREFIX} lines — the prologue must
     * precede the REGISTER clause, so it cannot simply be prepended.
     *
     * <p>The name is made unique per call. A check that registers two queries in one engine used
     * to give both the same name; the second never took effect, its result list stayed empty,
     * and the check read that as success — reporting #56 FIXED when nothing had changed.
     */
    static String withRegister(String query) {
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
        return prologue + "REGISTER QUERY Probe" + QUERY_SEQ.incrementAndGet() + " AS\n" + body;
    }

    static List<Object> run(Seeder seeder, String where, Pusher pusher) throws Exception {
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
