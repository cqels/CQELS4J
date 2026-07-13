package org.cqels.examples.reasoning;

import org.cqels.examples.Fleet;
import org.cqels.reasoning.InferredRDFStreamElement;
import org.cqels.reasoning.Rule;
import org.cqels.reasoning.RuleCondition;
import org.cqels.reasoning.RuleConsequent;
import org.cqels.reasoning.RuleSet;
import org.cqels.reasoning.TriplePattern;
import org.cqels.reasoning.TripleTemplate;
import org.cqels.reasoning.config.ReasoningConfig;
import org.cqels.reasoning.engine.ReteNetwork;
import org.cqels.stream.RDFStreamElement;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Example — opt-in TRUTH MAINTENANCE: retracting a fact un-derives its consequences
 * (cqels-reasoning-rete).
 *
 * <p>Forward-chaining reasoners normally only move forward: once a rule has fired, the derived
 * fact stays. With {@code ReasoningConfig.enableTruthMaintenance(true)} the RETE network also
 * tracks <em>justifications</em> — which premises support each derived fact — so
 * {@code ReteNetwork.retract(fact)} can remove an asserted fact AND report exactly which
 * conclusions lost their last support.
 *
 * <p>The scenario: a coolant-temperature reading above 95 °C derives an overheat alert for the
 * vehicle. The 104 °C reading turns out to be a glitching sensor, so the depot retracts it — and
 * the derived alert disappears with it, because nothing else supports it. The demo dumps the
 * asserted facts and derived alerts before and after the retraction.
 *
 * <p>This demo drives the RETE network directly through its public API
 * ({@code ReteNetwork.compile} / {@code processElement} / {@code retract}) — no engine or stream
 * wiring needed, which keeps the assert/derive/retract lifecycle easy to see.
 *
 * <p>Add-on dependency: {@code org.cqels:cqels-reasoning-rete}.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.examples.reasoning.RetractableInference}
 */
public class RetractableInference {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private static final IRI COOLANT_TEMP = VF.createIRI(Fleet.EX + "coolantTempCelsius");
    private static final IRI HAS_ALERT = VF.createIRI(Fleet.FLEET + "hasAlert");
    private static final IRI OVERHEAT = VF.createIRI(Fleet.FLEET + "OverheatAlert");
    private static final IRI EV1 = VF.createIRI(Fleet.EV1);

    public static void main(String[] args) {
        // Rule: ?v ex:coolantTempCelsius ?t  (t > 95.0)  ->  ?v fleet:hasAlert fleet:OverheatAlert
        Rule overheat = Rule.builder()
                .id("overheat-alert")
                .condition(RuleCondition.builder()
                        .addPattern(TriplePattern.builder()
                                .subjectVar("v").predicate(COOLANT_TEMP).objectVar("t").build())
                        .addFilter(b -> b.get("t") instanceof Literal l && l.doubleValue() > 95.0)
                        .build())
                .consequent(RuleConsequent.builder()
                        .addTemplate(TripleTemplate.builder()
                                .subjectVar("v").predicate(HAS_ALERT).object(OVERHEAT).build())
                        .build())
                .priority(100)
                .build();

        // Truth maintenance is opt-in: without it, retract(...) is rejected and derived
        // facts can never be withdrawn.
        ReteNetwork network = ReteNetwork.compile(ReasoningConfig.builder()
                .ruleSet(RuleSet.of(overheat))
                .defaultWindow(Duration.ofMinutes(5))
                .enableTruthMaintenance(true)
                .build());

        // The demo's view of what is currently derived, maintained purely from the
        // network's public return values (processElement adds, retract removes).
        Set<Statement> derived = new LinkedHashSet<>();

        System.out.println("RETE network compiled with truth maintenance ON."
                + " Rule: coolant > 95 C -> OverheatAlert.\n");

        // 1) A normal reading — below the threshold, nothing derived.
        Statement normal = VF.createStatement(EV1, COOLANT_TEMP, VF.createLiteral(78.0));
        System.out.println("assert: " + fmt(normal) + "   (below threshold -> must stay quiet)");
        apply(network.processElement(new RDFStreamElement(normal, 1_000L)), derived);

        // 2) The suspect reading — fires the rule, deriving the alert.
        Statement hot = VF.createStatement(EV1, COOLANT_TEMP, VF.createLiteral(104.0));
        System.out.println("assert: " + fmt(hot));
        apply(network.processElement(new RDFStreamElement(hot, 2_000L)), derived);

        dump(network, derived, "state BEFORE retraction");

        // 3) The 104 C reading was a sensor glitch — retract it. The returned list is
        //    exactly the set of conclusions that lost their last support.
        System.out.println("retract: " + fmt(hot) + "   (sensor glitch — reading withdrawn)");
        List<Statement> lost = network.retract(hot);
        lost.forEach(s -> {
            derived.remove(s);
            System.out.println("  no longer derivable -> " + fmt(s));
        });

        dump(network, derived, "state AFTER retraction");
        System.out.println("The alert vanished with its premise — the 78 C reading alone"
                + " supports nothing.");
        System.out.println("\nDone.");
    }

    /** Record the network's newly inferred facts and print each one. */
    private static void apply(List<InferredRDFStreamElement> inferred, Set<Statement> derived) {
        if (inferred.isEmpty()) {
            System.out.println("  derived: (none)");
            return;
        }
        for (InferredRDFStreamElement e : inferred) {
            derived.add(e.asStatement());
            System.out.println("  derived [" + e.getInferredBy().getId() + "] -> "
                    + fmt(e.asStatement()));
        }
    }

    /** Dump the asserted working memory plus the currently-derived facts. */
    private static void dump(ReteNetwork network, Set<Statement> derived, String label) {
        System.out.println("\n--- " + label + " ---");
        System.out.println("  asserted facts (working memory):");
        network.getWorkingMemory().getAllFacts().forEach(s ->
                System.out.println("    " + fmt(s)));
        System.out.println("  derived facts:");
        if (derived.isEmpty()) {
            System.out.println("    (none)");
        } else {
            derived.forEach(s -> System.out.println("    " + fmt(s)));
        }
        System.out.println();
    }

    /** Compact triple rendering: local names only. */
    private static String fmt(Statement s) {
        return local(s.getSubject().stringValue()) + " "
                + local(s.getPredicate().stringValue()) + " "
                + (s.getObject() instanceof Literal l ? l.getLabel() : local(s.getObject().stringValue()));
    }

    private static String local(String iri) {
        int cut = Math.max(iri.lastIndexOf('/'), iri.lastIndexOf('#'));
        return cut >= 0 ? iri.substring(cut + 1) : iri;
    }
}
