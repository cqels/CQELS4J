#!/usr/bin/env bash
#
# Self-test for version-truth-gate.sh.
#
# Both directions are pinned. A gate that stops detecting is worse than no gate,
# because the green check reads as proof; a gate that fires on correct
# documentation gets ignored, which comes to the same thing.
#
# The must-fire cases are the three defects that were found by hand in the live
# public repo, plus every way the classification rule was observed to be
# gameable while it was being designed. The must-not-fire cases are lines copied
# VERBATIM out of the real guides — all ten full-form provenance sites and all
# four bare-form shapes — because the expensive failure mode here is not missing
# a defect, it is firing on "since 2.0.0-alpha.11" until somebody deletes the
# gate.
#
# Run: scripts/ci/version-truth-gate.test.sh
# No network and no JVM: the online tier runs against a PATH-shimmed curl, and
# the deep tier against a recorded JSON-RPC transcript.

set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SUT="$HERE/version-truth-gate.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

pass=0
fail=0
PIN=2.0.0-alpha.18

# ---------------------------------------------------------------------------
# A correct fixture repo at $1 (default $PIN). Deliberately small — the counts
# are 3 tools / 2 resources / 2 prompts, because A6 checks self-consistency, not
# the real numbers. Every shape that mattered in the real repo is reproduced:
# the display-token-plus-URL line, the "Download it from the" wrap, the
# "**Since X…**" prose three lines above a `VERSION=` fence, and a v-prefixed
# release URL next to an unprefixed raw one.
# ---------------------------------------------------------------------------
newfix() {
  local v=${1:-$PIN} d
  d=$(mktemp -d "$WORK/fix.XXXXXX")
  mkdir -p "$d/examples" "$d/mcp-server"

  for p in examples mcp-server; do
    cat > "$d/$p/pom.xml" <<EOF
<project>
    <properties>
        <cqels.version>$v</cqels.version>
    </properties>
</project>
EOF
  done

  cat > "$d/README.md" <<EOF
# CQELS

> **Latest release:** [\`$v\`](https://github.com/cqels/CQELS4J/releases/tag/v$v) · **License:** MIT

    <version>$v</version>

An MCP client gets the full production tool surface — **3 tools** covering the
agent-memory types — plus 2 resources and 2 prompt templates.
EOF

  cat > "$d/GETTING_STARTED.md" <<EOF
# Getting started

> **Current release:** \`$v\` — coordinates \`org.cqels:cqels-*\`.

    <url>https://raw.githubusercontent.com/cqels/maven/main/releases</url>
    <version>$v</version>

- **Release verification:** [$v](https://raw.githubusercontent.com/cqels/maven/main/releases/supply-chain/$v/VERIFY.md)
EOF

  cat > "$d/CQELS-QL_SPEC.md" <<EOF
# CQELS-QL

**Applies to:** CQELS \`$v\` · **Based on:** SPARQL 1.1.
EOF

  cat > "$d/SUPPLY_CHAIN.md" <<EOF
# Supply chain

\`\`\`bash
VERSION=$v
BASE=https://raw.githubusercontent.com/cqels/maven/main/releases/supply-chain/\$VERSION
\`\`\`

## The shaded server jar

**Since \`$v\` it is attached to the GitHub release**, which needs no credentials:

\`\`\`bash
VERSION=$v
curl -fsSLO https://github.com/cqels/CQELS4J/releases/download/v\$VERSION/cqels-mcp-\$VERSION-shaded.jar
\`\`\`
EOF

  cat > "$d/mcp-server/README.md" <<EOF
# MCP server

## Tools exposed (3)

| Tool | What it does |
|------|--------------|
| \`store_memory\` | Store facts into long-term memory. |
| \`recall_memory\` | Retrieve knowledge via SPARQL. |
| \`query\` | One-shot query over the knowledge graph. |

**Resources (2 + 1 template):** \`cqels://engine/status\`, \`cqels://kg/stats\`. Per-query
buffered results are readable at the \`cqels://queries/{queryId}/results\` template.

**Prompts (2):** \`recall_about\`, \`store_knowledge\`.

## Remote clients

- **The published server, natively.** Download it from the
  [$v release](https://github.com/cqels/CQELS4J/releases/tag/v$v)
  (no credentials needed).
EOF

  ( cd "$d" && git init -q . && git add -A ) >/dev/null 2>&1
  printf '%s' "$d"
}

reindex() { ( cd "$1" && git add -A ) >/dev/null 2>&1; }

# check <want-exit> <name> <dir> [gate-args...]
check() {
  local want=$1 name=$2 d=$3; shift 3
  local out rc
  reindex "$d"
  out=$( cd "$d" && "$SUT" "$@" 2>&1 ); rc=$?
  if [ "$rc" -eq "$want" ]; then
    printf '  ok    %s\n' "$name"; pass=$((pass + 1))
  else
    printf '  FAIL  %s (wanted exit %s, got %s)\n' "$name" "$want" "$rc"
    printf '%s\n' "$out" | sed 's/^/          /'
    fail=$((fail + 1))
  fi
}

# checkmsg <want-exit> <name> <needle> <dir> [gate-args...] — also asserts the
# message names the thing that is wrong. An exit code alone does not tell a
# maintainer which file to open.
checkmsg() {
  local want=$1 name=$2 needle=$3 d=$4; shift 4
  local out rc
  reindex "$d"
  out=$( cd "$d" && "$SUT" "$@" 2>&1 ); rc=$?
  if [ "$rc" -eq "$want" ] && printf '%s' "$out" | grep -qF -- "$needle"; then
    printf '  ok    %s\n' "$name"; pass=$((pass + 1))
  else
    printf '  FAIL  %s (wanted exit %s naming "%s", got %s)\n' "$name" "$want" "$needle" "$rc"
    printf '%s\n' "$out" | sed 's/^/          /'
    fail=$((fail + 1))
  fi
}

echo "the correct state must be silent"

d=$(newfix); check 0 "a correctly bumped repo passes" "$d"

d=$(newfix 2.0.0-beta.1)
check 0 "a beta pin passes (grammar admits beta/rc)" "$d"

echo
echo "defect 1 — a stale current stamp (docs and pom drifted apart)"

d=$(newfix); sed -i.bak 's|Latest release:.*|Latest release: `2.0.0-alpha.13`|' "$d/README.md"
checkmsg 1 "a stale 'Latest release' stamp fires" "README.md:3" "$d"

d=$(newfix); sed -i.bak "s|<cqels.version>$PIN|<cqels.version>2.0.0-alpha.16|" "$d/mcp-server/pom.xml"
checkmsg 2 "the two poms disagreeing is a usage error, not a doc report" "disagree about the pin" "$d"

# The house lesson: match() returns only the FIRST occurrence, so a line whose
# display token was bumped can hide a stale URL behind it.
d=$(newfix)
printf 'See the [`%s`](https://github.com/cqels/CQELS4J/releases/tag/v2.0.0-alpha.16) notes.\n' "$PIN" >> "$d/README.md"
checkmsg 1 "display token bumped, URL left behind — the SECOND token fires" "2.0.0-alpha.16" "$d"

d=$(newfix)
printf '\nThe RDF Messages envelope (CQELS 2.0.0-alpha.16).\n' >> "$d/CQELS-QL_SPEC.md"
checkmsg 1 "an UNMARKED historical token fires (the RdfMessageIngestion shape)" "reword it as provenance" "$d"

# alpha.9 vs alpha.18: a lexical compare says "9" > "18" and passes it.
d=$(newfix); sed -i.bak 's|Latest release:.*|Latest release: `2.0.0-alpha.9`|' "$d/README.md"
check 1 "numeric, not lexical: a stale alpha.9 under an alpha.18 pin fires" "$d"

echo
echo "defect 1, the other direction — impossible history"

d=$(newfix); printf '\nA guard honored since `2.0.0-alpha.99`.\n' >> "$d/CQELS-QL_SPEC.md"
checkmsg 1 "provenance NEWER than the pin fires" "impossible history" "$d"

d=$(newfix); printf '\nAn opt-in path (alpha.19).\n' >> "$d/README.md"
check 1 "a BARE reference to a future version fires" "$d"

echo
echo "the classification rule — where it must NOT let a stale stamp through"

# 'Since' at the head of a sentence must not exempt a stamp further along it.
d=$(newfix)
printf '\nSince then much has changed; the engine is now 2.0.0-alpha.13.\n' >> "$d/README.md"
check 1 "a marker more than 3 words away does not exempt" "$d"

# The mcp-server/README.md:194-195 shape. 'from' alone is not a marker.
d=$(newfix)
sed -i.bak "s|\[$PIN release\]|[2.0.0-alpha.16 release]|" "$d/mcp-server/README.md"
checkmsg 1 "'…Download it from the' wrapping above a stale link does NOT exempt it" "mcp-server/README.md" "$d"

# The SUPPLY_CHAIN.md:163-166 shape: provenance prose, blank line, fence, then a
# CURRENT stamp a consumer pastes. A deeper lookback would exempt it.
d=$(newfix)
awk -v p="$PIN" '/^VERSION=/ && ++n == 2 { print "VERSION=2.0.0-alpha.16"; next } { print }' \
  "$d/SUPPLY_CHAIN.md" > "$d/x" && mv "$d/x" "$d/SUPPLY_CHAIN.md"
checkmsg 1 "lookback is bounded to ONE line: a fence under '**Since X…**' still fires" "SUPPLY_CHAIN.md" "$d"

echo
echo "the classification rule — every real provenance line, verbatim, must be silent"

prov() { # prov <name> <line>
  local d; d=$(newfix)
  printf '%s\n' "$2" >> "$d/CQELS-QL_SPEC.md"
  check 0 "$1" "$d"
}
prov "CQELS-QL_SPEC.md:332  '— since \`X\`, a \`FILTER\`…'" \
  '- **Extension functions (SPARQL 1.1 §17.6)** — since `2.0.0-alpha.11`, a `FILTER` / `BIND` /'
prov "CQELS-QL_SPEC.md:346  '  since \`X\` registering … fails loud'" \
  '  since `2.0.0-alpha.13` registering a query containing `MINUS` **fails loud** with a hint to rewrite it'
prov "README.md:155         '(honored since CQELS X)' inside a table cell" \
  '| [`DriverAttentionWatchdog`](x.java) | guards, including on the negated step (honored since CQELS 2.0.0-alpha.13) | Driver-attention watchdog. |'
prov "examples/README.md:129 '(guards on negated steps are honored since CQELS X)'" \
  '| [`DriverAttentionWatchdog`](x.java) | cross-event `STR()` guards (guards on negated steps are honored since CQELS 2.0.0-alpha.13) | watchdog. |'
prov "ChargerRangeFilter.java:9  ' * <p>Since {@code X} the engine…'" \
  ' * <p>Since {@code 2.0.0-alpha.11} the engine resolves an explicit function-IRI call it does not'
prov "DriverAttentionWatchdog.java:132 'Before CQELS X, cross-event guards on'" \
  '            // and the alert STILL fires. Before CQELS 2.0.0-alpha.13, cross-event guards on'
prov "SUPPLY_CHAIN.md:67    'X is the first release' (phrase ends at the line break)" \
  'Be aware of the current state rather than assuming it: `2.0.0-alpha.16` is the first release
with a deployed `VERIFY.md`, and it does pin the key to a commit. Earlier releases publish none'
prov "SUPPLY_CHAIN.md:163   '**Since \`X\` it is attached to the GitHub release**'" \
  '**Since `2.0.0-alpha.16` it is attached to the GitHub release**, which needs no credentials:'
prov "SUPPLY_CHAIN.md:207   'From \`X\` onward … is pinned'" \
  'From `2.0.0-alpha.16` onward `project.build.outputTimestamp` is pinned, so builds are'

# The one that needs the lookback: the marker ends the PREVIOUS line.
#
# This case must be exercised in a .java file, because that is where it really
# lives (DriverAttentionWatchdog.java:32-33) and because the file TYPE decides
# the rule. An earlier revision of this fixture appended these javadoc lines to
# CQELS-QL_SPEC.md, where a leading `* ` is a markdown bullet, not a comment
# continuation — so it exercised a shape that does not exist in the repo, and
# broke the moment block-continuity was enforced. A fixture that tests the wrong
# file type is not testing the case it names.
d=$(newfix)
mkdir -p "$d/examples/src/main/java/org/cqels/examples/cdsp"
cat > "$d/examples/src/main/java/org/cqels/examples/cdsp/DriverAttentionWatchdog.java" <<'EOF'
/**
 * <em>other</em> vehicle braking elsewhere in the fleet no longer suppresses the alert. Since
 * CQELS {@code 2.0.0-alpha.13}, cross-event FILTER guards on negated steps are honored; before
 * that they were ignored on the negated step.
 */
class DriverAttentionWatchdog {}
EOF
check 0 "DriverAttentionWatchdog.java:33 — javadoc wrap, marker ends the PREVIOUS line" "$d"

# The markdown counterpart of the same shape: a soft-wrapped PROSE paragraph
# (no bullet marker), which is how it would really appear in a .md file.
d=$(newfix)
cat >> "$d/CQELS-QL_SPEC.md" <<'EOF'

Guards on negated steps behave as documented. Since
CQELS `2.0.0-alpha.13`, cross-event FILTER guards on negated steps are honored.
EOF
check 0 "markdown soft-wrap — marker ends the PREVIOUS prose line" "$d"

# ...and the evasion that motivated block-continuity: the SAME marker shape, but
# the token sits in a NEW block (a blockquote badge) that the prose above does
# not govern. Found by codex review; verified against the real README, where it
# silently exempted the landing-page stamp.
d=$(newfix)
python3 - "$d/README.md" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read().split("\n")
for i, l in enumerate(s):
    if l.startswith("> **Latest release:**"):
        s[i] = l.replace("2.0.0-alpha.18", "2.0.0-alpha.13")
        s.insert(i, "Documentation has remained unchanged since")
        break
open(p, "w").write("\n".join(s))
PY
check 1 "a marker on the previous line must NOT exempt a stamp in a new block" "$d"

echo
echo "bare-form provenance must be silent"

d=$(newfix)
cat >> "$d/README.md" <<'EOF'
| `[FUTURE …]` + 2-pattern star join (opt-in, alpha.7) |
> alpha.7 also ships an opt-in *warm parse-cache* ASP solver backend,
| `RdfMessageIngestion` | N-Quads envelope (`RdfMessageCodec`, alpha.9) |
| `ChargerRangeFilter` | user-defined function by IRI (SPARQL 1.1 §17.6, alpha.11) |
EOF
check 0 "all four real bare-form shapes under a newer pin" "$d"

d=$(newfix 2.0.0-beta.1)
printf '| star join (opt-in, alpha.7) — since `2.0.0-alpha.13` the guard is honored |\n' >> "$d/README.md"
checkmsg 0 "channel rank: alpha provenance under a BETA pin is valid history" "OK:" "$d"

# The other three cells of the channel matrix, previously untested — a regression
# in the rank compare (e.g. falling back to numeric-suffix-only, where alpha.13
# "beats" beta.1) would have passed the suite (found by codex review).
d=$(newfix)   # pin = alpha
printf 'Star joins landed. Since `2.0.0-beta.1` the guard is honored.\n' >> "$d/README.md"
checkmsg 1 "channel rank: BETA provenance under an ALPHA pin is impossible history" "NEWER than" "$d"

d=$(newfix 2.0.0-beta.2)
printf 'Star joins landed. Since `2.0.0-rc.1` the guard is honored.\n' >> "$d/README.md"
checkmsg 1 "channel rank: RC provenance under a BETA pin is impossible history" "NEWER than" "$d"

d=$(newfix 2.0.0-rc.2)
printf 'Star joins landed. Since `2.0.0-beta.9` the guard is honored.\n' >> "$d/README.md"
checkmsg 0 "channel rank: beta provenance under an RC pin is valid history" "OK:" "$d"

# Multi-digit ordering must be numeric, not lexical: alpha.9 < alpha.18 even
# though "9" > "1" lexically, and alpha.113 > alpha.16 even though "113" < "16"
# lexically. Both directions pinned.
d=$(newfix)   # pin = alpha.18-era fixture default
printf 'Star joins landed. Since `2.0.0-alpha.9` the guard is honored.\n' >> "$d/README.md"
checkmsg 0 "numeric suffix: alpha.9 under a later alpha pin is valid history (not lexical)" "OK:" "$d"

d=$(newfix 2.0.0-alpha.16)
printf 'Star joins landed. Since `2.0.0-alpha.113` the guard is honored.\n' >> "$d/README.md"
checkmsg 1 "numeric suffix: alpha.113 under alpha.16 is impossible history (not lexical)" "NEWER than" "$d"

echo
echo "the fence that is CURRENT passes when it is current (boundary, both sides)"

d=$(newfix)
check 0 "the same '**Since X…**' + fence shape, fence at the pin" "$d"

echo
echo "defect 3, offline half — self-consistency of the documented surface"

d=$(newfix); sed -i.bak 's|## Tools exposed (3)|## Tools exposed (4)|' "$d/mcp-server/README.md"
checkmsg 1 "heading count vs table rows" "the tables under it list 3" "$d"

d=$(newfix); sed -i.bak 's|\*\*3 tools\*\*|**4 tools**|' "$d/README.md"
checkmsg 1 "cross-file drift: README.md vs mcp-server/README.md" "The two landing pages disagree" "$d"

d=$(newfix); sed -i.bak 's|Resources (2 + 1 template)|Resources (3 + 1 template)|' "$d/mcp-server/README.md"
check 1 "resource count vs the listed cqels:// URIs" "$d"

d=$(newfix); sed -i.bak 's|\*\*Prompts (2)|**Prompts (3)|' "$d/mcp-server/README.md"
check 1 "prompt count vs the listed names" "$d"

echo
echo "vacuity — the gate must never pass having checked nothing"

d=$(newfix); rm "$d/GETTING_STARTED.md"
checkmsg 1 "a missing required guide FAILS, never skips" "refusing to skip it silently" "$d"

d=$(newfix); printf '# Getting started\n\nNo version here.\n' > "$d/GETTING_STARTED.md"
checkmsg 1 "a guide reworded out of reach fails ('pass vacuously')" "pass vacuously" "$d"

d=$(newfix); sed -i.bak 's|\*\*3 tools\*\* covering the|tool set covering the|' "$d/README.md"
checkmsg 1 "README's tool claim reworded away fails rather than silently unchecking" "pass vacuously" "$d"

echo
echo "the pin itself — a usage error (exit 2), not a report of 19 stale docs"

for bad in '${revision}' '2.0.0-alpha-SNAPSHOT' '2.0.0' 'not a version'; do
  d=$(newfix); sed -i.bak "s|<cqels.version>$PIN|<cqels.version>$bad|" "$d/examples/pom.xml" "$d/mcp-server/pom.xml"
  check 2 "pin '$bad' is rejected" "$d"
done

d=$(newfix)
sed -i.bak "s|<cqels.version>$PIN</cqels.version>|<cqels.version>$PIN</cqels.version><cqels.version>$PIN</cqels.version>|" "$d/examples/pom.xml"
check 2 "two <cqels.version> elements is an ambiguous pin" "$d"

echo
echo "scan universe — the index, not the working tree"

d=$(newfix)
printf 'dependency-reduced-pom.xml\n' > "$d/.gitignore"
printf '<project><properties><cqels.version>2.0.0-alpha.13</cqels.version></properties></project>\n' \
  > "$d/mcp-server/dependency-reduced-pom.xml"
check 0 "a .gitignored shade byproduct carrying a stale pin is invisible" "$d"

# The gate and this test quote versions to explain themselves, so they exclude
# themselves. Found the hard way: run against the real repo, the gate reported
# eight defects in its own header. The exclusion is two exact paths...
d=$(newfix); mkdir -p "$d/scripts/ci"
cp "$SUT" "$HERE/version-truth-gate.test.sh" "$d/scripts/ci/"
check 0 "the gate and its own test do not fire on their worked examples" "$d"

# ...NOT the directory, so a sibling script that really does state a version is
# still checked.
d=$(newfix); mkdir -p "$d/scripts/ci"
printf '#!/bin/sh\nVERSION=2.0.0-alpha.13\n' > "$d/scripts/ci/release.sh"
checkmsg 1 "a SIBLING script in scripts/ci is still scanned" "scripts/ci/release.sh" "$d"

echo
echo "defect 2 — the online tier (PATH-shimmed curl, no network)"

mkdir -p "$WORK/shim"
cat > "$WORK/shim/curl" <<'SHIM'
#!/usr/bin/env bash
url=${@: -1}
case "${VTG_TEST_CURL:-all200}" in
  all200) echo 200 ;;
  timeout) echo 000; exit 28 ;;
  down)   echo 503 ;;
  404:*)  if [[ "$url" == *"${VTG_TEST_CURL#404:}"* ]]; then echo 404; else echo 200; fi ;;
esac
SHIM
chmod +x "$WORK/shim/curl"
export PATH="$WORK/shim:$PATH"

d=$(newfix); VTG_TEST_CURL=all200 check 0 "every channel resolving passes" "$d" --online

d=$(newfix)
VTG_TEST_CURL="404:/releases/tag/" checkmsg 1 "a dead release-tag link fires (README + mcp-server both link it)" \
  "releases/tag/v$PIN is HTTP 404" "$d" --online

d=$(newfix)
VTG_TEST_CURL="404:-shaded.jar" checkmsg 1 "the shaded jar 404 on the only credential-free route fires" \
  "shaded.jar is HTTP 404" "$d" --online

d=$(newfix)
VTG_TEST_CURL="404:/VERIFY.md" checkmsg 1 "the unprefixed raw VERIFY.md URL fires (no 'v' required)" \
  "VERIFY.md is HTTP 404" "$d" --online

d=$(newfix)
VTG_TEST_CURL="404:cqels-engine" checkmsg 1 "the documented <dependency> not resolving fires" \
  "cqels-engine-$PIN.pom is HTTP 404" "$d" --online

# Anti-drift: the gate must not keep probing a channel the docs stopped naming.
d=$(newfix)
sed -i.bak 's|https://github.com/cqels/CQELS4J/releases/download/|https://elsewhere.example/dl/|' "$d/SUPPLY_CHAIN.md"
checkmsg 1 "a doc migrating to a new host fails loud rather than probing a dead template" \
  "has stopped naming" "$d" --online

echo
echo "…and a network hiccup is INCONCLUSIVE (exit 3), never a defect"

for mode in timeout down; do
  d=$(newfix)
  out=$( cd "$d" && VTG_TEST_CURL=$mode "$SUT" --online 2>&1 ); rc=$?
  if [ "$rc" -eq 3 ] && ! printf '%s' "$out" | grep -qi 'documentation defect\.'; then
    printf '  ok    curl %s => exit 3, not worded as a defect\n' "$mode"; pass=$((pass + 1))
  else
    printf '  FAIL  curl %s should be exit 3 and not called a defect (got %s)\n' "$mode" "$rc"
    printf '%s\n' "$out" | sed 's/^/          /'
    fail=$((fail + 1))
  fi
done

echo
echo "defect 3, the real one — docs self-consistent, the ARTIFACT disagrees"

# Real MCP frame shapes, including the nested `arguments[].name` that made the
# first (grep-based) implementation report eleven argument names as
# undocumented prompts. That false positive is now a regression case.
cat > "$WORK/t-good" <<'EOF'
{"jsonrpc":"2.0","id":1,"result":{"serverInfo":{"name":"cqels-fleet-mcp"}}}
{"jsonrpc":"2.0","id":3,"result":{"tools":[{"name":"store_memory","inputSchema":{"type":"object","properties":{"name":{"type":"string"}}}},{"name":"recall_memory"},{"name":"query"}]}}
{"jsonrpc":"2.0","id":4,"result":{"resources":[{"uri":"cqels://engine/status"},{"uri":"cqels://kg/stats"},{"uri":"cqels://queries/{queryId}/results"}]}}
{"jsonrpc":"2.0","id":5,"result":{"prompts":[{"name":"recall_about","arguments":[{"name":"topic","required":true},{"name":"window"}]},{"name":"store_knowledge","arguments":[{"name":"graph"}]}]}}
{"jsonrpc":"2.0","id":6,"result":{"resourceTemplates":[{"uriTemplate":"cqels://queries/{queryId}/results"}]}}
EOF
d=$(newfix); VTG_DEEP_CAPTURE="$WORK/t-good" check 0 \
  "documented surface == advertised surface (nested argument names ignored)" "$d" --deep

# The live shape on master: one extra advertised tool the table never gained a
# row for. This is defect 3, at the seam only --deep can reach.
sed 's|{"name":"query"}|{"name":"query"},{"name":"remove_stream"}|' "$WORK/t-good" > "$WORK/t-extra"
d=$(newfix); VTG_DEEP_CAPTURE="$WORK/t-extra" checkmsg 1 "an UNDOCUMENTED advertised tool fires, naming it" \
  "ADVERTISED BUT UNDOCUMENTED: remove_stream" "$d" --deep

# A rename that keeps the COUNT stable — invisible to any count-based check.
sed 's|"recall_memory"|"recall_memories"|' "$WORK/t-good" > "$WORK/t-renamed"
d=$(newfix); VTG_DEEP_CAPTURE="$WORK/t-renamed" checkmsg 1 "a rename at a STABLE count fires (set equality, not counts)" \
  "recall_memories" "$d" --deep

sed 's|,{"name":"store_knowledge","arguments":\[{"name":"graph"}\]}||' "$WORK/t-good" > "$WORK/t-prompt"
d=$(newfix); VTG_DEEP_CAPTURE="$WORK/t-prompt" checkmsg 1 "prompt drift fires too (previously covered by nothing)" \
  "prompts list does not match" "$d" --deep

sed 's|,{"uri":"cqels://kg/stats"}||' "$WORK/t-good" > "$WORK/t-res"
d=$(newfix); VTG_DEEP_CAPTURE="$WORK/t-res" checkmsg 1 "resource drift fires too" \
  "resources list does not match" "$d" --deep

# Template drift — the surface an earlier revision compared against NOTHING.
# resources/list filtered every '{'-bearing URI and no other check existed, so a
# server that dropped the documented cqels://queries/{queryId}/results template
# still reported the surface as matching (found by codex review). The template
# now comes from resources/templates/list and is diffed like the rest.
sed 's|{"resourceTemplates":\[{"uriTemplate":"cqels://queries/{queryId}/results"}\]}|{"resourceTemplates":[]}|' \
  "$WORK/t-good" > "$WORK/t-tmpl"
d=$(newfix); VTG_DEEP_CAPTURE="$WORK/t-tmpl" checkmsg 1 "a dropped resource TEMPLATE fires (previously compared against nothing)" \
  "resource templates list does not match" "$d" --deep

# ...and a renamed template placeholder fires too — set equality again.
sed 's|{queryId}|{qid}|; s|cqels://queries/{qid}/results" template|cqels://queries/{queryId}/results" template|' \
  "$WORK/t-good" > "$WORK/t-tmpl2"
d=$(newfix); VTG_DEEP_CAPTURE="$WORK/t-tmpl2" checkmsg 1 "a renamed template placeholder fires" \
  "resource templates list does not match" "$d" --deep

# A truncated session must be INCONCLUSIVE, not "every prompt is undocumented".
head -3 "$WORK/t-good" > "$WORK/t-cut"
d=$(newfix); VTG_DEEP_CAPTURE="$WORK/t-cut" checkmsg 3 "a truncated session is INCONCLUSIVE, not a defect report" \
  "Not a documentation defect" "$d" --deep

echo
echo "arguments"

d=$(newfix)
check 2 "an unknown flag is a usage error" "$d" --nope
if ( cd "$WORK" && "$SUT" ) >/dev/null 2>&1; then
  echo "  FAIL  running outside a git work tree should be a usage error"; fail=$((fail + 1))
else
  echo "  ok    running outside a git work tree is a usage error"; pass=$((pass + 1))
fi

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
