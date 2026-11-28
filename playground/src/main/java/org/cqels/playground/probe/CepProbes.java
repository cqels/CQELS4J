package org.cqels.playground.probe;

import org.cqels.engine.CQELSEngine;
import org.cqels.engine.DataStream;
import org.cqels.engine.cep.PatternMatch;
import org.cqels.stream.StreamElement;
import org.eclipse.rdf4j.model.Statement;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.cqels.playground.probe.ProbeSupport.*;

/**
 * Complex event processing: what a {@code FILTER(SEQ(...))} query accepts at registration.
 *
 * <p>{@link CapabilityProbe} runs these checks and names the documentation each one guards.
 */
final class CepProbes {

    private CepProbes() {
    }

    /**
     * #74, fixed in 2.0.0-alpha.22: a {@code SEQ} argument must be the subject of a triple pattern
     * (CQELS-QL_SPEC.md §7), and one that is not is refused at registration. On alpha.21 it
     * registered and matched any element. Both measured forms are checked — the variable mentioned
     * nowhere else, and the variable used only as an object — each with an alert followed by an
     * unrelated element, in case a regression lets one register again.
     *
     * <p>The control makes the second argument a subject and pushes two alerts: it must match. If it
     * stops matching, CEP itself has broken and this verdict means nothing.
     *
     * <p>Returns true when both forms are refused at registration, which is what §7 documents; a
     * form that registers fails the check even if it matches nothing, and one form refused while
     * the other is not is reported as PARTIAL.
     */
    static boolean seqVariableMustBeASubject() throws Exception {
        String alert = "<" + C + "hasAlert> <" + C + "Overheat>";
        Statement unrelated = VF.createStatement(VF.createIRI(C + "x1"), VF.createIRI(C + "unrelated"),
                VF.createIRI(C + "y"));
        List<Object> unmentioned = cep("?e1 " + alert + " . FILTER(SEQ(?e1 ; ?zz))", unrelated);
        List<Object> objectOnly = cep("?e1 " + alert + " . ?x <" + C + "hasAlert> ?e2 . "
                + "FILTER(SEQ(?e1 ; ?e2))", unrelated);
        List<Object> control = cep("?e1 " + alert + " . ?e2 " + alert + " . FILTER(SEQ(?e1 ; ?e2))",
                VF.createStatement(VF.createIRI(C + "m2"), VF.createIRI(C + "hasAlert"), VF.createIRI(C + "Overheat")));
        if (control == null || control.isEmpty()) {
            DIVERGENCES.add("REGRESSED — the control (two alerts, both SEQ arguments subjects) no longer matches\n"
                    + "      CEP itself is failing; the #74 verdict is meaningless");
        }
        boolean unmentionedFixed = unmentioned == null;
        boolean objectOnlyFixed = objectOnly == null;
        if (unmentionedFixed != objectOnlyFixed) {
            DIVERGENCES.add("PARTIAL — #74 holds for one form only: unmentioned variable "
                    + (unmentionedFixed ? "refused" : "registers, matches " + unmentioned)
                    + ", object-only variable "
                    + (objectOnlyFixed ? "refused" : "registers, matches " + objectOnly));
        }
        return unmentionedFixed && objectOnlyFixed;
    }

    /**
     * Registers {@code SELECT ?e1 … [RANGE 30s] WHERE { body }} as a CEP query, pushes an alert at
     * 1 s and {@code second} at 2 s, and returns the matches, or null when registration is refused.
     */
    private static List<Object> cep(String body, Statement second) throws Exception {
        try (CQELSEngine engine = CQELSEngine.builder().id("probe-" + System.nanoTime()).withMemoryStore().build()) {
            DataStream stream = engine.createStream("S");
            List<Object> matches = new CopyOnWriteArrayList<>();
            try {
                engine.registerCepQuery(withRegister("SELECT ?e1 FROM STREAM S [RANGE 30s]\nWHERE { " + body + " }\n"),
                        (PatternMatch<StreamElement> m) -> matches.add(m.getEvents().size()));
            } catch (Exception refused) {
                // Only the documented refusal counts as one; anything else (an unrelated failure)
                // must not read as the #74 validation (codex, review of #116).
                if (String.valueOf(refused.getMessage()).contains("is not the subject of a stream triple pattern")) {
                    return null;
                }
                DIVERGENCES.add("UNEXPECTED REFUSAL — a CEP query was refused for another reason: "
                        + refused.getMessage() + "\n      the #74 check cannot tell what it measured");
                return List.of();
            }
            engine.start();
            stream.push(VF.createStatement(VF.createIRI(C + "m1"), VF.createIRI(C + "hasAlert"),
                    VF.createIRI(C + "Overheat")), 1000L);
            Thread.sleep(100);
            stream.push(second, 2000L);
            Thread.sleep(1500);
            return matches;
        }
    }
}
