package org.cqels.playground.probe;

import static org.cqels.playground.probe.ProbeSupport.*;

/**
 * Behaviour-truth probe — asserts that what this repository <em>says</em> about the engine is still
 * what the engine <em>does</em>.
 *
 * <p><strong>Why this exists.</strong> `scripts/ci/version-truth-gate.sh` was written because a claim
 * in the prose can go stale without anything failing to compile. Capability claims are the same
 * hazard one category over. The examples, `playground/README.md`, `CQELS-QL_SPEC.md` §6/§9 and
 * `CDSP_MAPPING.md` all carry statements of the form "X does not work on 2.0.0-alpha.22, work around
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
 * <p>Not every claim is about query evaluation. The last pair of checks covers <em>API shape</em>:
 * that the warm parse-cache ASP backend is reachable through the facade's generic
 * {@code registerQuery} (asserted by registering one and seeing it solve), and that
 * {@code registerAspQuery} still has no backend-accepting overload (checked reflectively, since that
 * is a statement about the API's shape rather than about behaviour). Both underwrite the note beside
 * the reasoning demos in {@code playground/README.md}; if the facade grows such an overload, that note
 * needs revisiting and this probe says so.
 *
 * <p>Either way a failure means "reality and the documentation have diverged", which is exactly the
 * class of defect this repository exists to avoid. Run it whenever the pin moves — `RELEASING.md`
 * step 4 — and nightly via `.github/workflows/version-truth.yml`.
 *
 * <p>The checks live in one class per area, each with the helpers only it needs:
 * {@link QueryProbes}, {@link StaticJoinProbes}, {@link AggregateProbes}, {@link CepProbes},
 * {@link ReasoningProbes} and {@link MessageProbes}. {@link ProbeSupport} holds what they share:
 * the divergence report, the one-engine-per-query harness and the exact-row oracle. This class
 * only runs the checks, in the order the output lists them.
 *
 * <p>Exit status: <strong>0</strong> if documentation and reality agree, <strong>non-zero</strong>
 * (an exception, so Maven fails the build) if they diverge.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=org.cqels.playground.probe.CapabilityProbe}
 */
public class CapabilityProbe {

    public static void main(String[] args) throws Exception {
        System.out.println("Capability probe — does the engine still behave as this repo documents?\n");

        System.out.println("-- caveats (documented as BROKEN; a pass here means the docs are stale) --");
        knownBug("     xsd:duration in MAX is compared as text (MAX of PT9S, PT10S is PT9S)",
                AggregateProbes.durationMax(), "PT9S PT9S / PT10S PT9S", "PT9S PT10S / PT10S PT10S",
                "CQELS-QL_SPEC.md §9 gap table, CDSP_MAPPING.md §4, GETTING_STARTED.md limitations table");
        knownBug("     AVG of a group with no bound argument is null, not 0",
                AggregateProbes.avgOfUnboundGroup(), "null", "0",
                "CQELS-QL_SPEC.md §9 Aggregates, GETTING_STARTED.md limitations table");
        knownBug("     an aggregate whose argument nothing binds reports no row, not COUNT 0",
                AggregateProbes.countOfNeverBoundArgument(), "no row", "0",
                "CQELS-QL_SPEC.md §9 Aggregates, GETTING_STARTED.md limitations table");
        knownBug("     GROUP BY merges \"cat\"@en and \"cat\"@fr into one group",
                AggregateProbes.groupsOverLanguageTaggedKey(), "1 group", "2 groups",
                "CQELS-QL_SPEC.md §9 Aggregates, GETTING_STARTED.md limitations table");
        knownBug("#117 = treats \"cat\"@en and \"cat\"@fr as equal (a language tag is ignored)",
                QueryProbes.languageTaggedEquality(), "equal", "not equal",
                "CQELS-QL_SPEC.md §9 FILTER entry, GETTING_STARTED.md limitations table");
        knownBug("     a static UNION drops every row when its join variable is not projected",
                StaticJoinProbes.unionWithUnprojectedJoinVariable(), "0 rows", "1 row",
                "CQELS-QL_SPEC.md §9 UNION entry, GETTING_STARTED.md limitations table");
        knownBug("#126 RdfMessageCodec rejects MESSAGE followed by a comment on the same line",
                MessageProbes.codecMessageWithComment(), "rejected", "2 messages",
                "RdfMessageLog.java (MESSAGE_WITH_COMMENT workaround and Javadoc), RDF_MESSAGES_MAPPING.md §3");
        knownBug("#126 RdfMessageCodec opens an empty message for a comment-only line after the last MESSAGE",
                MessageProbes.codecCommentAfterLastDelimiter(), "[1, 0]", "[1]",
                "RdfMessageLog.java (COMMENT_ONLY_LINE workaround and Javadoc), RDF_MESSAGES_MAPPING.md §3");
        knownBug("#126 RdfMessageCodec.writeNQuads drops a trailing empty message on re-reading",
                MessageProbes.codecTrailingEmptyMessage(), "[1]", "[1, 0]",
                "RdfMessageLog.java (writeNQuads workaround and Javadoc), RDF_MESSAGES_MAPPING.md §3");
        knownBug("#133 RdfMessageCodec cannot parse an RDF 1.2 triple term under VERSION \"1.2-messages\"",
                MessageProbes.codecTripleTerm(), "rejected", "1 triple term",
                "RdfMessageLog.java, JsonLdMessageLog.java, RdfXmlMessageLog.java (#133 refusals), RDF_MESSAGES_MAPPING.md §3 and §6");
        knownBug("#133 the engine's Turtle parser reads << s p o >> as an RDF-star quoted triple, not an RDF 1.2 reifier",
                MessageProbes.turtleReifiedTriple(), "quoted triple", "reifier",
                "RdfMessageLog.java (#133 refusal of <<), RDF_MESSAGES_MAPPING.md §3 and §6");
        knownBug("#125 GRAPH inside a STREAM block is refused at registration",
                MessageProbes.graphInsideStream(), "refused", "registered",
                "MessageLogReplay.java Javadoc, RDF_MESSAGES_MAPPING.md §3-§4");
        caveat("#58 reasoner cannot match the background graph",
                ReasoningProbes.reasonerSeesBackgroundGraph(),
                "SkosConceptRollup.java, CDSP_MAPPING.md §5, GETTING_STARTED.md limitations table");
        caveat("     registerAspQuery has no backend-accepting overload",
                ReasoningProbes.registerAspQueryTakesBackend(),
                "playground/README.md note under 'Reasoning & validation'");

        System.out.println("\n-- capabilities (documented as WORKING; the examples depend on these) --");
        capability("#54 static pattern eliminates a non-matching row (fixed)",
                StaticJoinProbes.staticPatternEliminates());
        capability("#55 prefixed name inside FILTER resolves (fixed)",
                QueryProbes.pnameInFilterResolves());
        capability("property paths run in a stream-only STREAM block and are refused by name elsewhere",
                QueryProbes.propertyPathRoute());
        capability("#65 sameTerm() evaluates (fixed)",
                QueryProbes.sameTermEvaluates());
        capability("#57 reasoner sees multi-statement (atomic) elements (fixed)",
                ReasoningProbes.reasonerSeesAtomicElements());
        capability("#67 reverse-edge guard admits a match, rejects an unmatchable element (fixed)",
                StaticJoinProbes.reverseEdgeGuardAdmits());
        capability("#67 [NOW] static pattern does not admit a missing stream conjunct (fixed)",
                StaticJoinProbes.nowStaticPatternEnforcesConjunction());
        capability("#82 FILTER compares xsd:duration by value (fixed in alpha.22)",
                QueryProbes.durationFilterIsByValue());
        capability("     a literal join key answers on the composed route (fixed in alpha.22)",
                StaticJoinProbes.literalJoinKeyAdmits());
        capability("#83 a FILTER sees a BIND target beside a static pattern (fixed in alpha.22)",
                StaticJoinProbes.filterSeesBindTargetBesideStaticPattern());
        capability("#85 a FILTER sees an OPTIONAL variable on a stream-only query (fixed in alpha.22)",
                QueryProbes.filterSeesOptionalVariableOnStreamOnlyQuery());
        capability("#74 a CEP SEQ variable that is never a subject is refused (fixed in alpha.22)",
                CepProbes.seqVariableMustBeASubject());
        capability("     FROM <graph> scopes a static lookup on a one-stream query (fixed in alpha.22)",
                StaticJoinProbes.fromGraphScopesStaticLookup());
        capability("     REPLACE() is evaluated (fixed in alpha.22)", QueryProbes.replaceEvaluates());
        capability("stream-static enrichment binds static variables", StaticJoinProbes.staticEnrichmentBinds());
        capability("FILTER/BIND builtins: ABS, CONCAT, IRI, SUBSTR, IF", QueryProbes.builtinsWork());
        capability("atomic multi-statement element joins in a query", QueryProbes.atomicElementJoins());
        capability("GROUP BY with aggregates", AggregateProbes.groupByWorks());
        capability("single-pattern [RANGE] aggregates at window close",
                AggregateProbes.singlePatternRangeAggregatesAtWindowClose());
        capability("two-pattern [RANGE] aggregate reports on every arrival (rolling)",
                AggregateProbes.twoPatternRangeAggregateRolls());
        capability("CypherQL: FROM STREAM before MATCH registers; MATCH first is a syntax error",
                QueryProbes.cypherClauseOrder());
        capability("warm parse-cache ASP backend reachable via registerQuery", ReasoningProbes.warmBackendReachable());
        capability("RdfMessageCodec: empty messages, per-message blank nodes, quad graph names kept",
                MessageProbes.codecMessageSemantics());
        capability("BIND arithmetic over two static joins feeds a windowed MIN (TrailerHealthMonitor unit conversion)",
                StaticJoinProbes.convertedValueFeedsWindowedMin());
        capability("OPTIONAL over static data with FILTER(!BOUND) keeps only unmatched elements (TrailerHealthMonitor UnitMismatch)",
                StaticJoinProbes.optionalStaticUnboundFilter());

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
}
