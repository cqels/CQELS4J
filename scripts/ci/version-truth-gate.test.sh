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
# the deep tier against a recorded JSON-RPC transcript. It does need python3 —
# both to build a few fixtures and because the deep tier parses the transcript
# with it. Without it the deep cases are INCONCLUSIVE (exit 3), which is a
# missing prerequisite, not a finding; see RELEASING.md step 4.

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
agent-memory types — plus **2 resources** and **2 prompt templates**.
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

# checkutf8 <want-exit> <name> <needle|-> <dir> — checkmsg, run with a UTF-8
# locale FORCED into the environment.
#
# The gate exports LC_ALL=C precisely so its awk stays byte-oriented, and these
# cases are the reason: in a UTF-8 locale macOS awk aborts a whole file on one
# non-UTF-8 byte (the file then contributes ZERO records and a stale stamp in it
# passes) and its tolower() is not length-preserving (the scanner offsets shift
# and a CORRECT stamp is reported malformed). Both are round-5, both are
# invisible on CI — ubuntu's mawk is byte-oriented either way — so the assertion
# has to name the locale rather than inherit it.
checkutf8() {
  local want=$1 name=$2 needle=$3 d=$4
  local out rc
  reindex "$d"
  out=$( cd "$d" && LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8 "$SUT" 2>&1 ); rc=$?
  if [ "$rc" -eq "$want" ] && { [ "$needle" = "-" ] || printf '%s' "$out" | grep -qF -- "$needle"; }; then
    printf '  ok    %s\n' "$name"; pass=$((pass + 1))
  else
    printf '  FAIL  %s (wanted exit %s naming "%s", got %s)\n' "$name" "$want" "$needle" "$rc"
    printf '%s\n' "$out" | sed 's/^/          /'
    fail=$((fail + 1))
  fi
}

# checkrecords <want-exit> <name> <dir> <record>... — asserts the CLASSIFICATION
# of named tokens, via VTG_DUMP, not merely the exit code.
#
# At token == pin an exit code cannot witness the class at all: A2 accepts a
# `current` token equal to the pin and A3 accepts a `provenance` token <= the
# pin, so both arms are silent and a case that only reads the exit code cannot
# tell a correct classification from a lucky one. That is how the pass-side
# boundary case below came to be a byte-identical duplicate of the first smoke
# test (round 2).
checkrecords() {
  local want=$1 name=$2 d=$3; shift 3
  local out rc r missing=""
  reindex "$d"
  out=$( cd "$d" && VTG_DUMP=1 "$SUT" 2>&1 ); rc=$?
  for r in "$@"; do
    printf '%s' "$out" | grep -qF -- "$r" || missing="$missing $r"
  done
  if [ "$rc" -eq "$want" ] && [ -z "$missing" ]; then
    printf '  ok    %s\n' "$name"; pass=$((pass + 1))
  else
    printf '  FAIL  %s (wanted exit %s with records%s, got %s)\n' \
      "$name" "$want" "${missing:- —}" "$rc"
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

# A single-digit suffix under a two-digit pin. This case used to claim it
# pinned vkey()'s numeric-vs-lexical ordering; it does not and never did — an
# unmarked token is compared with a plain string INEQUALITY (A2), no ordering
# involved, so it passed for the same reason as the alpha.13 case above. The
# real numeric-vs-lexical coverage is the provenance pair further down, which
# does route through vkey(); a lexical regression inside vkey() turns those red
# and leaves this one green (round 2).
d=$(newfix); sed -i.bak 's|Latest release:.*|Latest release: `2.0.0-alpha.9`|' "$d/README.md"
check 1 "a stale stamp whose suffix is SHORTER than the pin's still fires" "$d"

echo
echo "defect 1, the other direction — impossible history"

d=$(newfix); printf '\nA guard honored since `2.0.0-alpha.99`.\n' >> "$d/CQELS-QL_SPEC.md"
checkmsg 1 "provenance NEWER than the pin fires" "impossible history" "$d"

d=$(newfix); printf '\nAn opt-in path (alpha.19).\n' >> "$d/README.md"
check 1 "a BARE reference to a future version fires" "$d"

echo
echo "the classification rule — where it must NOT let a stale stamp through"

# 'Since' at the head of a sentence must not exempt a stamp further along it.
# This one is carried by the GOVERNING rule, not by the clause rule the ';'
# suggests: the walk stops at "now", four words before it could ever reach
# "since", so deleting the in-line truncation leaves it green (round 3). The
# case that really pins the before-side truncation is below, marked as such.
# Widening the old 3-word window to 100 left the whole suite green — the bound
# this case used to name was never exercised by anything (codex round 3).
d=$(newfix)
printf '\nSince then much has changed; the engine is now 2.0.0-alpha.13.\n' >> "$d/README.md"
check 1 "a marker in an earlier clause does not exempt (governing rule)" "$d"

d=$(newfix)
printf '\nSince version numbering the current build is 2.0.0-alpha.13.\n' >> "$d/README.md"
checkmsg 1 "a marker whose object is NOT the token does not exempt (no clause mark to help)" \
  "claims version" "$d"

# The label shape: `before` governs "release", not the version. The colon is
# deliberately not a clause mark (see 'Available since:' below), so nothing but
# the governing rule can catch this.
d=$(newfix)
printf '\n**Tested before release:** `2.0.0-alpha.13` on JDK 17 and 21.\n' >> "$d/README.md"
check 1 "'Tested before release: X' is a current stamp, not provenance" "$d"

d=$(newfix)
printf '\n**Tested before release** — `2.0.0-alpha.13` on JDK 17 and 21.\n' >> "$d/README.md"
check 1 "...and the em-dash spelling of it too (no colon involved)" "$d"

# ...and the label boundary cannot be TYPED into a document: the sentinel the
# classifier leaves where a colon or comma was is defaced wherever it occurs in
# the prose itself, so writing it out does not turn a label into an opener.
d=$(newfix)
printf '\n**Tested vtgsep before release:** `2.0.0-alpha.13` on JDK 17 and 21.\n' >> "$d/README.md"
check 1 "...and the label boundary sentinel cannot be forged in prose" "$d"

# ...and the label defence must know the NOUN, not one spelling of it. "version"
# was consumed by the filler arm one line before the noun-object arm could see
# it, so the walk sailed through and returned provenance unconditionally: the
# identical label shape with one word changed exempted a stale stamp, in both
# the `before` and the `prior to` spelling (round 6).
d=$(newfix)
printf '\n**Tested before version:** `2.0.0-alpha.13` on JDK 17 and 21.\n' >> "$d/README.md"
checkmsg 1 "'Tested before VERSION: X' is a current stamp too — the synonym of the noun" \
  "claims version \`2.0.0-alpha.13\`" "$d"

d=$(newfix)
printf '\n**Verified prior to version:** `2.0.0-alpha.13` on JDK 17 and 21.\n' >> "$d/README.md"
checkmsg 1 "...and the 'prior to' spelling of it" "claims version \`2.0.0-alpha.13\`" "$d"

d=$(newfix)
printf '\n**Tested before version** — `2.0.0-alpha.13` on JDK 17 and 21.\n' >> "$d/README.md"
check 1 "...and the em-dash spelling, which needs no colon" "$d"

# ...and the same word must not poison the OPENER, exactly as "release" must
# not: these are true history and the walk has to reach the marker through it.
prov_md "'Before version \`X\`, <old behaviour>' is provenance" \
  'Before version `2.0.0-alpha.11`, the checkpoint manifest listed no header.'
prov_md "...and under a lead-in, 'In CQELS, before version \`X\`, …'" \
  'In CQELS, before version `2.0.0-alpha.11`, cross-event guards were ignored.'
prov_md "...and 'since version \`X\`' stays untouched (since never consults the noun)" \
  'The engine resolves an explicit function IRI since version `2.0.0-alpha.11`.'

# ...but the noun must not poison the standard historical OPENER, which is the
# same three words with nothing in front of them. Both fired on true, naturally
# worded provenance, and the remedy the error printed ("reword it as provenance,
# \"before \`X\`\"") was to delete the word "release" — with no hint that the noun
# was what broke it (round 3). The repo's own sentence is
# DriverAttentionWatchdog.java:132, "Before CQELS 2.0.0-alpha.13, …".
prov_md() { # prov_md <name> <line>
  local d; d=$(newfix)
  printf '%s\n' "$2" >> "$d/README.md"
  check 0 "$1" "$d"
}
prov_md "'Before CQELS release \`X\`, <old behaviour>' is provenance" \
  'Before CQELS release `2.0.0-alpha.13`, cross-event FILTER guards on negated steps were ignored.'
prov_md "'Before release \`X\`, …' — the noun alone must not poison the opener" \
  'Before release `2.0.0-alpha.13`, cross-event FILTER guards on negated steps were ignored.'
prov_md "'Prior to release \`X\`, …' — the same for the two-word marker" \
  'Prior to release `2.0.0-alpha.13`, cross-event FILTER guards on negated steps were ignored.'

# ...and the opener stays an opener when a LABEL or a lead-in sits in front of
# it. "Something precedes the marker" was the whole test, so a bold label, a
# "Note:", a "In CQELS," or any previous line ending in a colon (which
# continuous() joins) made the opener look like the label shape and fired on
# true history — with a remedy the author had already written (round 4). Bold
# labels are this repo's own house style (CQELS-QL_SPEC.md:3).
prov_md "'**History:** Before release \`X\`, …' — a label in front is still an opener" \
  '**History:** Before release `2.0.0-alpha.13`, cross-event guards on negated steps were ignored.'
prov_md "'Note: Prior to release \`X\`, …' — the two-word marker too" \
  'Note: Prior to release `2.0.0-alpha.13`, cross-event guards on negated steps were ignored.'
prov_md "'In CQELS, before release \`X\`, …' — a comma lead-in is not a subject" \
  'In CQELS, before release `2.0.0-alpha.13`, cross-event guards on negated steps were ignored.'
d=$(newfix)
printf '\nChangelog highlights:\nBefore release `2.0.0-alpha.13`, journal entries carried no version header.\n' \
  >> "$d/README.md"
check 0 "...and a colon LEAD-IN on the previous line, which continuous() joins" "$d"

# The AFTER side of the clause rule. The round-2 fix truncated `before` only,
# so a marker in a LATER clause still exempted a stale stamp.
d=$(newfix)
printf '\nThe current release is `2.0.0-alpha.13`; moving onward requires migration.\n' >> "$d/README.md"
check 1 "an 'onward' in a LATER clause does not exempt (after-side clause rule)" "$d"

d=$(newfix)
printf '\nThe current release is `2.0.0-alpha.13`. Moving onwards, each demo is listed below.\n' >> "$d/README.md"
check 1 "...nor one in the next SENTENCE of the same line" "$d"

# ...and the P2-specific pin. Both cases above have no `from` in their before
# context, so P2's `frm` half is 0 whatever the truncation does and they pass
# with the after-side rule deleted — only the P3 case below noticed it missing
# (round 3). This one supplies the `from` half, so it is the P2 marker pair that
# is being clause-scoped and nothing else.
d=$(newfix)
printf '\nDownloadable from `2.0.0-alpha.13`; onward compatibility notes follow.\n' >> "$d/README.md"
check 1 "...and with the 'from' half present, so it is P2 that is clause-scoped" "$d"

d=$(newfix)
printf '\nThe pin is `2.0.0-alpha.13`; is the first release of a new era, they said.\n' >> "$d/README.md"
check 1 "...and P3 is clause-scoped as well, not just P2" "$d"

# A stamp line ends with the token and no punctuation — the characteristic
# shape of a badge — so continuous() allows the join to the next line. Nothing
# may sit between the token and 'onward'; a one-word slot let any next-line
# sentence opening 'Read onwards…' exempt the badge.
#
# The 'from' half has to be PRESENT for this case to mean anything. With a
# '**Current release:**' badge in front of the token there is no `from`, so P2
# short-circuits on `frm` whatever the anchor does: the case passed for a reason
# other than the one it names, and the whole suite stayed green with the anchor
# deleted — including with the exact one-word slot the comment describes
# reintroduced (round 4). Supplying 'from' is what makes the anchor load-bearing
# here.
d=$(newfix)
printf '\nGuards on negated steps are honored from `2.0.0-alpha.13`\nRead onwards for the full guide.\n' >> "$d/README.md"
check 1 "a next-line sentence merely CONTAINING 'onwards' does not exempt a stale badge" "$d"

# ...and the other direction: the real wrap, where 'onward' opens the next line.
d=$(newfix)
printf '\nGuards on negated steps are honored from `2.0.0-alpha.13`\nonward, on every stream.\n' >> "$d/CQELS-QL_SPEC.md"
check 0 "'from X' wrapping onto a line that OPENS with 'onward' is provenance" "$d"

# ...but 'onward' is only HALF the marker: "from X onward" is a pair, and
# without the backward half any stale stamp on a line that does not end in a
# clause mark was exempted by a next line merely BEGINNING with the word. A
# badge line ends with the token, and a trailing colon does not refuse the join
# either, so both spellings of the real README shape leaked (round 2).
d=$(newfix)
printf '\nCurrent release: `2.0.0-alpha.13`:\nonward compatibility notes follow.\n' >> "$d/README.md"
checkmsg 1 "a stale stamp before a line OPENING with 'onward' is not provenance without 'from'" \
  "claims version" "$d"

d=$(newfix)
printf '\n**Current release:** `2.0.0-alpha.13`\nonwards the API is stable.\n' >> "$d/README.md"
check 1 "...and the no-colon badge spelling of the same evasion" "$d"

d=$(newfix)
printf '\nThe pin is `2.0.0-alpha.13` onwards, they said.\n' >> "$d/README.md"
check 1 "...and the same-line spelling: 'X onwards' with no 'from' is a current claim" "$d"

# Marker matching is case-insensitive on BOTH sides. The backward walk always
# lowercased; P2/P3 did not, so title case — the normal register for a markdown
# heading — fired on the gate's own sanctioned provenance wording and the
# remedy it printed was the wording the author already had (round 2).
d=$(newfix)
printf '\n## Breaking Changes From `2.0.0-alpha.13` Onward\n' >> "$d/CQELS-QL_SPEC.md"
check 0 "P2 in title case ('From X Onward') is provenance, not a stale stamp" "$d"

d=$(newfix)
printf '\n`2.0.0-alpha.13` Is the first release with the MCP server.\n' >> "$d/CQELS-QL_SPEC.md"
check 0 "P3 in title case ('X Is the first release') is provenance too" "$d"

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

# Provenance written in this repo's own house style — the version LINKS to its
# tag (README.md:8), so the token appears twice on the line. Every one of these
# fired as a stale current stamp: the first token exploded into six words under
# norm() and its dots truncated the clause, pushing the marker out of reach of
# the second (codex round 3). The remedy the error message offered was
# "reword it as provenance", which the line already said.
prov "link house style   'since [\`X\`](…/tag/vX).'" \
  'Negated-step guards are honored since [`2.0.0-alpha.13`](https://github.com/cqels/CQELS4J/releases/tag/v2.0.0-alpha.13).'
prov "link house style   'From [\`X\`](…) onward, …'" \
  'From [`2.0.0-alpha.13`](https://github.com/cqels/CQELS4J/releases/tag/v2.0.0-alpha.13) onward, guards are honored.'
prov "one marker, two tokens  'since \`X\` and \`Y\`'" \
  'Guards are honored since `2.0.0-alpha.11` and `2.0.0-alpha.13` respectively.'
prov "since + a noun object  'since release \`X\`'" \
  'The engine resolves an explicit function IRI since release `2.0.0-alpha.11`.'

# ...and the ARTICLE in front of that noun. The filler set had no articles, so
# the walk broke on "the" and the most natural English spelling of every marker
# fired as a stale current stamp, while the article-free spelling of the same
# sentence passed. It defended nothing: adding it leaves the suite green
# (round 4). All four marker families are pinned, because one article broke all
# four.
prov "an article between marker and token  'since the \`X\` release'" \
  'Cross-event guards have been honored since the `2.0.0-alpha.11` release.'
prov "...and 'since the release \`X\`'" \
  'Cross-event guards have been honored since the release `2.0.0-alpha.11`.'
prov "...and 'Before the \`X\` release, …'" \
  'Before the `2.0.0-alpha.11` release, cross-event guards were ignored.'
prov "...and 'Prior to the \`X\` release, …'" \
  'Prior to the `2.0.0-alpha.11` release, cross-event guards were ignored.'
prov "...and 'From the \`X\` onward, …' (P2 keeps its adjacency)" \
  'From the `2.0.0-alpha.11` onward, cross-event guards are honored.'

# ...and the article inside the COORDINATION path, which the five cases above do
# not reach: "since `X` and the `Y`" spends the coordination on the masked token
# and then still has to step over the article to reach the marker.
#
# This replaces a case that read "Stable since `X`, the `Y` jar is the one to
# install." and was named for the article. It was vacuous (round 7): the walk
# breaks unconditionally at the masked `X` long before the article could matter,
# so it passed byte-identically with the article fix reverted, and it was
# behaviourally indistinguishable from its article-free sibling below, which
# pins that masked-token boundary already. The article arm can only ever extend
# the walk's reach TOWARD a marker, so its failure mode is a false EXEMPTION —
# it is pinnable in the must-not-fire direction and nowhere else, and every case
# here is verified to fire once the arm is reverted.
prov "...and the article inside a coordination  'since \`X\` and the \`Y\`'" \
  'Stable since `2.0.0-alpha.11` and the `2.0.0-alpha.13` line were both shipped.'
# The link target of a token whose PROSE copy on the same line is already
# provenance is that same claim written twice. Judged on its own it fired,
# because four ordinary words ("see the release notes") sit between the marker
# and the URL copy — and no rewrite short of deleting the link cleared it
# (round 2). The must-fire counterpart is the "display token bumped, URL left
# behind" case above: two DIFFERENT tokens are two different claims.
prov "link target behind intervening prose  'since \`X\` — see [notes](…/tag/vX).'" \
  'Correlated guards were fixed since `2.0.0-alpha.13` — see [the release notes](https://github.com/cqels/CQELS4J/releases/tag/v2.0.0-alpha.13).'

# ...and a RELATIVE target is a link target too. Both halves of the recogniser
# were anchored on https?://, so the target copy in "since [`X`](CHANGELOG-X.md)"
# was judged as prose, the path word broke the marker walk, and a true
# provenance line fired with no rewrite short of renaming the linked file
# (round 3).
prov "relative link target  'since [\`X\`](CHANGELOG-X.md).'" \
  'Negated-step guards are honored since [`2.0.0-alpha.13`](CHANGELOG-2.0.0-alpha.13.md).'

# ...and the P2 half of that lesson, which the round-3 fix left behind: only
# inurl() learned to recognise a relative target, so the PROSE side still had
# the path sitting between the token and "onward". P2 requires adjacency, so
# "From [`X`](CHANGELOG.md) onward" — the natural shape for a per-version
# changelog, which this gate's own commentary blesses — fired as a stale
# current stamp, and the remedy it printed was the wording already on the line
# (round 4). The dot in ".md" is not the mechanism: a fragment target with no
# dot at all broke it too.
prov "relative link target, P2  'From [\`X\`](CHANGELOG.md) onward, …'" \
  'From [`2.0.0-alpha.13`](CHANGELOG.md) onward, cross-event guards are honored.'
prov "...and a dotless fragment target, which broke it for the same reason" \
  'From [`2.0.0-alpha.13`](#changelog) onward, cross-event guards are honored.'

# ...and stripping the target from the prose must not exempt a stamp that has no
# marker at all.
d=$(newfix)
printf 'Read the [`2.0.0-alpha.13`](CHANGELOG.md) notes before upgrading.\n' >> "$d/README.md"
checkmsg 1 "a relative link around an UNMARKED stamp is still a current claim" \
  "claims version \`2.0.0-alpha.13\`" "$d"

# ...and inheritance stays same-token only for relative targets as well: a
# bumped display token beside a stale relative target is two claims.
d=$(newfix)
printf 'See the [`%s`](CHANGELOG-2.0.0-alpha.13.md) notes.\n' "$PIN" >> "$d/README.md"
checkmsg 1 "a stale RELATIVE link target beside a bumped display token still fires" \
  "claims version \`2.0.0-alpha.13\`" "$d"

# Inheritance is CLAUSE-scoped, exactly like the marker walk it overrides.
# Line-scoped, an unrelated earlier sentence silenced an independent claim: a
# stale release-tag link on the landing page — defect 2 — passed with exit 0,
# even though the clause truncation had already classified it correctly (round
# 3). The must-not-fire counterpart is the em-dash case just above: one
# sentence, so the inheritance still applies.
d=$(newfix)
printf 'Correlated guards were fixed since `2.0.0-alpha.13`. Download the latest jar from the [release page](https://github.com/cqels/CQELS4J/releases/tag/v2.0.0-alpha.13).\n' >> "$d/README.md"
checkmsg 1 "a stale link does NOT inherit provenance across a sentence boundary" \
  "claims version \`2.0.0-alpha.13\`" "$d"

d=$(newfix)
printf 'The shaded jar ships since `2.0.0-alpha.13`; grab the current build from the [release page](https://github.com/cqels/CQELS4J/releases/tag/v2.0.0-alpha.13).\n' >> "$d/README.md"
check 1 "...nor across a semicolon, which the clause rule already treats as a boundary" "$d"

# ...nor across a comma plus a CONJUNCTION, which is a second independent clause
# wearing the one punctuation mark this rule used to step over. The real
# SUPPLY_CHAIN.md sentence shape with ", so fetch …" attached passed with exit 0
# — a stale landing-page download link, defect 2, reported OK (round 5).
d=$(newfix)
printf '**Since `2.0.0-alpha.13` it is attached to the GitHub release**, so fetch [the latest shaded jar](https://github.com/cqels/CQELS4J/releases/download/v2.0.0-alpha.13/cqels-mcp-2.0.0-alpha.13-shaded.jar) directly.\n' >> "$d/SUPPLY_CHAIN.md"
checkmsg 1 "...nor across a comma plus a conjunction, which opens a new clause" \
  "claims version \`2.0.0-alpha.13\`" "$d"

# ...but a BARE comma still inherits: that is one claim written twice, which is
# the shape the inheritance exists for, and the em-dash case above is its twin.
prov "a plain comma still inherits  'since \`X\`, see [notes](…/tag/vX).'" \
  'Correlated guards were fixed since `2.0.0-alpha.13`, see [the release notes](https://github.com/cqels/CQELS4J/releases/tag/v2.0.0-alpha.13).'

# A marker cannot reach past a clause that predicates the token as current. The
# two transparencies the walk needs for legitimate shapes — a coordination and a
# repeated token — carried it there, and the gate reported OK on a stamp that
# says in words that it is the current release (round 3). The must-not-fire
# counterpart is "since `X` and `Y` respectively" above.
d=$(newfix)
printf 'Stable since `2.0.0-alpha.11` and `2.0.0-alpha.13` is the current release.\n' >> "$d/README.md"
checkmsg 1 "a coordination does not carry a marker into a clause declaring the token current" \
  "claims version \`2.0.0-alpha.13\`" "$d"

d=$(newfix)
printf 'Documented since `2.0.0-alpha.13`, `2.0.0-alpha.13` is the current release.\n' >> "$d/README.md"
checkmsg 1 "...nor does the SAME token repeated in prose (only a link target inherits)" \
  "claims version \`2.0.0-alpha.13\`" "$d"

# ...and the verb is not glued to the nominal. Anchored as if it were, ONE
# ordinary adverb between them missed the rule and the coordination carried the
# marker across the masked first token again — a clause that says in words it is
# the current release was classified provenance (round 6). Same mechanism class
# as the round-5 filler word that reopened the mirror guard.
d=$(newfix)
printf 'Stable since `2.0.0-alpha.11` and `2.0.0-alpha.13` is now the latest release.\n' >> "$d/README.md"
checkmsg 1 "an adverb between the verb and the nominal does not reopen the coordination" \
  "claims version \`2.0.0-alpha.13\`" "$d"

d=$(newfix)
printf 'Stable since `2.0.0-alpha.11` and `2.0.0-alpha.13` is still the current release.\n' >> "$d/README.md"
check 1 "...nor does 'is still the current release'" "$d"

d=$(newfix)
printf 'Stable since `2.0.0-alpha.11` and `2.0.0-alpha.13`, which is the latest release, ships today.\n' >> "$d/README.md"
check 1 "...nor the relative-pronoun spelling ', which is the latest release'" "$d"

# ...and the adverb slot must stay an ALLOW-LIST. A wildcard there would match
# the NEGATION, which is a true statement about a superseded release — the same
# trap the past-tense arm fell into in round 4.
prov_md "'is NOT the latest release' under a marker is history, not a stale stamp" \
  'Guards are honored since `2.0.0-alpha.11` and `2.0.0-alpha.13` is not the latest release.'

# ...and the MIRROR of that rule, which was missing: the clause that names the
# token as the current one may sit BEFORE it. P3 looks only forward, so
# appending "is the first release …" to the repo's own stamp label exempted a
# stale landing stamp and the gate reported OK (round 4). Every spelling of the
# nominal is pinned, including the two the real guides use verbatim
# (README.md:8 '**Latest release:**', GETTING_STARTED.md:7 '**Current
# release:**').
d=$(newfix)
printf '\n**Current release:** `2.0.0-alpha.13` is the first release with the MCP server.\n' >> "$d/README.md"
checkmsg 1 "a P3 tail does not exempt a stamp its own label calls the current release" \
  "claims version \`2.0.0-alpha.13\`" "$d"

d=$(newfix)
printf '\nThe latest release `2.0.0-alpha.13` was the first release to bundle the MCP server.\n' >> "$d/README.md"
checkmsg 1 "...nor in running prose, in the past tense" \
  "claims version \`2.0.0-alpha.13\`" "$d"

# ...and the nominal does not have to ABUT the token. The guard was end-anchored
# on the noun while the backward walk steps over a fixed set of filler words, so
# the two disagreed about the same vocabulary and ONE of them — the product name,
# which this repo writes in front of a token routinely ("**Applies to:** CQELS
# `X`") — reopened the evasion verbatim (round 5).
d=$(newfix)
printf '\n**Current release:** CQELS `2.0.0-alpha.13` is the first release with the MCP server.\n' >> "$d/README.md"
checkmsg 1 "...and one word of filler between the label and the stamp does not reopen it" \
  "claims version \`2.0.0-alpha.13\`" "$d"

d=$(newfix)
printf '\n**Latest version:** the `2.0.0-alpha.13` is the first release with the MCP server.\n' >> "$d/README.md"
checkmsg 1 "...nor does an article, on the other spelling of the nominal" \
  "claims version \`2.0.0-alpha.13\`" "$d"

# ...and the filler may sit BETWEEN the adjective and its noun, which is where
# English usually puts the product name. Tolerated only AFTER the noun, the noun
# itself landed in the trailing-run position — a set that does not contain
# "release" — so one word REORDERED from the round-5 shape above reopened the
# same evasion (round 6).
d=$(newfix)
printf '\n**Current CQELS release:** `2.0.0-alpha.13` is the first release with the MCP server.\n' >> "$d/README.md"
checkmsg 1 "filler BETWEEN the adjective and the noun does not reopen the P3 evasion" \
  "claims version \`2.0.0-alpha.13\`" "$d"

d=$(newfix)
printf '\nThe current CQELS release `2.0.0-alpha.13` is the first release with the MCP server.\n' >> "$d/README.md"
checkmsg 1 "...and the same sentence with no label at all — plain release-note prose" \
  "claims version \`2.0.0-alpha.13\`" "$d"

# ...including when the filler is a BARE DECIMAL, which is how this project
# writes its own name. prep() masked only full-grammar tokens, so the dot in
# "CQELS 2.0" was read as a clause end and severed the label from its stamp
# (round 6).
d=$(newfix)
printf '\n**Current CQELS 2.0 release:** `2.0.0-alpha.13` is the first release with the MCP server.\n' >> "$d/README.md"
checkmsg 1 "...and a bare decimal in the label does not sever it from the stamp" \
  "claims version \`2.0.0-alpha.13\`" "$d"

# The other direction of that same severing, which is the expensive one: the
# marker is TRUE history, the dot cut `before` down to "0 release", the walk
# never reached "since", and the remedy the error printed was the wording
# already on the line.
prov_md "'Since CQELS 2.0 release \`X\`, …' — a bare decimal is not a clause end" \
  'Since CQELS 2.0 release `2.0.0-alpha.11`, the engine resolves an explicit function IRI.'
prov_md "...and the same with 'Before CQELS 2.0 release \`X\`, …'" \
  'Before CQELS 2.0 release `2.0.0-alpha.11`, cross-event guards were ignored.'

# ...but a REAL sentence end still ends the clause: masking the decimal must not
# cost the before-side truncation its only job.
d=$(newfix)
printf '\nNothing has moved since CQELS 2.0. CQELS `2.0.0-alpha.13` powers the demos.\n' >> "$d/README.md"
checkmsg 1 "...and a real period after a bare decimal still ends the clause" \
  "claims version \`2.0.0-alpha.13\`" "$d"

# ...and the other direction: P3 without that nominal is still provenance, in
# the lower-case register too (the title-case case is above).
prov "P3 lower case  '\`X\` is the first release with…'" \
  '`2.0.0-alpha.13` is the first release with the MCP server bundled.'

# ...and the filler the mirror guard now tolerates must not swallow P3 itself:
# the same sentence with the product name and no nominal in front of it is
# ordinary release-note prose, and provenance.
prov "P3 behind filler, with no nominal  'CQELS \`X\` is the first release…'" \
  'CQELS `2.0.0-alpha.13` is the first release with the MCP server bundled.'

# The current-predicate rule is PRESENT tense. "was the latest release" is how
# history describes a superseded release, not a claim about now, so the
# past-tense arm fired on a plainly true subordinate clause and the only edit
# that silenced it — the bump the message asks for — made the sentence false
# (round 4). The must-fire counterparts are the two present-tense cases above.
prov_md "'Before \`X\` was the latest release, …' is history, not a stale stamp" \
  'Before `2.0.0-alpha.13` was the latest release, checkpoint files carried no version header.'

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

# ...and the previous line must not be an HTML COMMENT, which is invisible in
# the rendered page. norm() flattens `<!-- -->` to nothing, so the comment
# governed the stamp below it as ordinary prose and the published landing page
# carried a naked stale stamp exempted by text no reader can see (round 3).
d=$(newfix)
printf '\n<!-- introduced since CQELS -->\n`2.0.0-alpha.13` is the release to install.\n' >> "$d/README.md"
checkmsg 1 "an invisible HTML comment does not govern the stamp below it" \
  "claims version \`2.0.0-alpha.13\`" "$d"

# The pass-side boundary of the same shape. It must assert the RECORD, not the
# exit code: at token == pin A2 accepts `current` and A3 accepts `provenance`,
# so the exit code is silent whichever way the comment is resolved, and with the
# refusal in continuous() deleted this case stayed green while both must-fire
# siblings around it turned red — it was passing on the A2/A3 boundary identity,
# not on the rule its name claims (round 5). Same lesson as the fence case
# further down, which is what checkrecords() was written for.
d=$(newfix)
printf '\n<!-- introduced since CQELS -->\n`%s` is the release to install.\n' "$PIN" >> "$d/README.md"
checkrecords 0 "...and the same shape at the pin stays silent" "$d" \
  "README.md|11|current|$PIN"

# ...and the same case with CRLF terminators. Every structural refusal in
# continuous() is END-anchored and awk leaves the CR on the record, so on a
# CRLF-saved file all three stopped matching — and the HTML-comment one is the
# one with no backstop, so this exact defect reopened verbatim and the gate
# reported OK on the stale stamp (round 4). CRLF only ever loosens the rules.
d=$(newfix)
printf '\n<!-- introduced since CQELS -->\n`2.0.0-alpha.13` is the release to install.\n' >> "$d/README.md"
python3 - "$d/README.md" <<'PY'
import sys
p = sys.argv[1]
b = open(p, 'rb').read().replace(b'\r\n', b'\n').replace(b'\n', b'\r\n')
open(p, 'wb').write(b)
PY
checkmsg 1 "...and CRLF does not turn that invisible comment back into a governing marker" \
  "claims version \`2.0.0-alpha.13\`" "$d"

# ...and the shape that motivated tracking the comment state ACROSS lines: a
# comment that OPENS on an earlier line, so the line above the stamp holds the
# tail of it and no "<!--" for the in-line stripper to find. (continuous()
# refuses this one on the trailing "-->" as well; the case below is the same
# construct with that refusal stepped around.)
d=$(newfix)
printf '\n<!-- introduced\nsince CQELS -->\n`2.0.0-alpha.13` is the release to install.\n' >> "$d/README.md"
checkmsg 1 "...and a comment whose OPENING is on an earlier line does not govern it either" \
  "claims version \`2.0.0-alpha.13\`" "$d"

# ...and the same construct with the comment CLOSING MID-LINE. The refusal in
# continuous() is end-anchored on "-->", and stripcomments() cannot see an
# opener two lines up, so one word of filler after the closer walked past both
# and an invisible "since" governed a stale landing stamp with the gate
# reporting OK (round 6). Any of {CQELS, the, version, release} does it.
d=$(newfix)
printf '\n<!--\nsince --> CQELS\n`2.0.0-alpha.13` is the release to install.\n' >> "$d/README.md"
checkmsg 1 "...and a comment that CLOSES MID-LINE cannot smuggle a marker onto the stamp below" \
  "claims version \`2.0.0-alpha.13\`" "$d"

# ...and the other direction of the same rule: what follows the closer IS
# visible, so a real marker there still governs the token on the next line.
d=$(newfix)
printf '\n<!-- parked\nnote --> Cross-event guards have been supported since\nCQELS `2.0.0-alpha.11` in the engine core.\n' >> "$d/CQELS-QL_SPEC.md"
check 0 "...but visible prose AFTER the closer still governs the line below it" "$d"

# A TOKEN INSIDE a comment is not a rendered claim at all. It used to be scanned
# as one while every word that could mark it was stripped, so parked release
# notes fired as a stale current stamp — and the remedy the message printed
# ("reword it as provenance, since `X`") was already written inside the comment,
# where it was stripped too. Nothing but deleting the parked text cleared it.
d=$(newfix)
printf '\n<!-- Before `2.0.0-alpha.13`, cross-event guards on negated steps were ignored. -->\n' >> "$d/README.md"
check 0 "a version token PARKED INSIDE a comment is not a claim about anything" "$d"

d=$(newfix)
printf '\n<!--\nParked release notes.\nBefore `2.0.0-alpha.13`, cross-event guards were ignored.\n-->\n' >> "$d/README.md"
check 0 "...and the same parked text spread over a multi-line comment" "$d"

# CODE IS NOT MARKUP, and this is where the cross-line comment state bit back
# (round 7). Inside a fence `<!--` is literal rendered TEXT: it opens nothing.
# The lexer opened a span on it anyway, never found a closer, and blanked every
# line to EOF — so a stale stamp below an ordinary documentation fence produced
# no record, no error and exit 0. One sentence of prose about hiding a block is
# enough to arm it; the round-5 gate, whose stripper worked one line at a time,
# fired correctly on the identical document.
d=$(newfix)
printf '\n```text\nstrip the marker <!-- like this\n```\n\nLatest release: `2.0.0-alpha.13`\n' >> "$d/README.md"
checkmsg 1 "an opener shown INSIDE A FENCE does not blank the rest of the file" \
  "claims version \`2.0.0-alpha.13\`" "$d"

d=$(newfix)
printf '\nTo hide a paragraph, prefix it with `<!--`.\n\nLatest release: `2.0.0-alpha.13`\n' >> "$d/CQELS-QL_SPEC.md"
checkmsg 1 "...and an opener quoted in an INLINE CODE SPAN does not either" \
  "claims version \`2.0.0-alpha.13\`" "$d"

# ...in a file A5 does not cover, where there is no vacuity net at all: the
# required-file guard only notices the blanking when the opener sits ABOVE the
# guide's stamps, so line order alone decided the direction.
d=$(newfix)
printf 'Use `<!--` to comment out a block.\n\nLatest release: `2.0.0-alpha.13`\n' > "$d/MIGRATION.md"
checkmsg 1 "...and in a file no vacuity guard covers" "MIGRATION.md:3 claims version" "$d"

# The mirror: a CLOSED comment displayed inside a fence is rendered text a
# reader sees, so it is a claim and must be scanned — the lexer used to strip it.
d=$(newfix)
printf '\n```xml\n<!-- pin the current release: 2.0.0-alpha.13 -->\n```\n' >> "$d/README.md"
checkmsg 1 "a comment RENDERED inside a fence is a visible claim, not a stripped one" \
  "claims version \`2.0.0-alpha.13\`" "$d"

# ...and an opener with no closer anywhere is malformed input, not a span. It
# must fail CLOSED — the opener blanks its own line and the rest of the file
# stays visible — because the alternative (blank to EOF) is the answer that
# makes the gate quieter, which is the one answer it must never give.
d=$(newfix)
printf '\n<!-- parked note\n\nLatest release: `2.0.0-alpha.13`\n' >> "$d/README.md"
checkmsg 1 "an UNTERMINATED opener does not hide the claims below it" \
  "claims version \`2.0.0-alpha.13\`" "$d"

# ...and the fence exemption must not cost the round-6 fix: a real multi-line
# comment OUTSIDE code still hides both its marker and its token.
d=$(newfix)
printf '\n<!-- Parked.\nBefore `2.0.0-alpha.13` guards were ignored.\n-->\n\n```text\n<!-- shown, not parsed\n```\n' >> "$d/README.md"
check 0 "...while a real multi-line comment outside code is still invisible" "$d"

# ...and a close is a close only on CommonMark's terms (codex, round 7): same
# character, a run AT LEAST AS LONG as the opener, nothing after it but
# whitespace. Without the length check, the ``` shown inside this four-backtick
# fence "closed" it, the rendered comment below sat in prose, and the stripper
# hid a stale version the reader plainly sees.
d=$(newfix)
printf '\n````markdown\n```\n<!-- pin the current release: 2.0.0-alpha.13 -->\n````\n' >> "$d/README.md"
checkmsg 1 "a \`\`\` inside a four-backtick fence is content, not a closer" \
  "claims version \`2.0.0-alpha.13\`" "$d"

# ...same purity rule for info strings: an opener may carry one ("```xml"), a
# closer may not, so an info-string line inside an open fence is content too.
d=$(newfix)
printf '\n```text\nfirst example\n```xml\n<!-- pin the current release: 2.0.0-alpha.13 -->\n```\n' >> "$d/README.md"
checkmsg 1 "...and an info-string line inside an open fence is not a closer" \
  "claims version \`2.0.0-alpha.13\`" "$d"

# ...and the strictness must not orphan the legitimate shape it exists for: a
# four-backtick fence quoting a three-backtick example still closes on its own
# marker, and what follows is prose again. The parked comment after the fence is
# the part that makes this case able to fail: if the closer logic ever turns
# over-strict and the fence never closes, the comment markers become literal
# fence content, the stale token inside them turns visible, and this fires.
d=$(newfix)
printf '\n````markdown\nA fence example:\n```bash\necho hi\n```\n````\n\n<!-- doc-rev 2.0.0-alpha.13, parked -->\n' >> "$d/README.md"
check 0 "a four-backtick fence quoting a three-backtick example still closes" "$d"

# ...and indentation decides whether the marks are a fence at all (codex,
# round 8): CommonMark allows an opener at most THREE leading spaces — four
# turn the line into indented code and the marks into literal text. Treating
# an indented example as a real opener suspended the comment lexer, so an
# invisible since-comment below it survived into the lookback and exempted a
# stale stamp the reader plainly sees.
d=$(newfix)
printf '\n    ```text\n<!--\nsince --> CQELS\n`2.0.0-alpha.13` is the release to install.\n```\n' >> "$d/README.md"
checkmsg 1 "a four-space-indented fence example is code, not an opener" \
  "claims version \`2.0.0-alpha.13\`" "$d"

# ...and three spaces is still a fence, so the comment inside it is rendered
# text and its stale token is a visible claim. This is the boundary's other
# half: if the indent rule ever turns over-strict, this comment gets stripped
# as invisible markup and the case goes silent.
d=$(newfix)
printf '\n   ```text\n<!-- pin the current release: 2.0.0-alpha.13 -->\n   ```\n' >> "$d/README.md"
checkmsg 1 "a three-space-indented fence is still a fence" \
  "claims version \`2.0.0-alpha.13\`" "$d"

# ...and the mirror of that, which is the direction that lets a gate certify
# nothing: an in-comment token used to count as a CURRENT stamp, so one
# invisible line satisfied A5 for a guide whose rendered text states no version
# at all — the precise failure the vacuity guard exists to prevent (round 6).
d=$(newfix)
sed -i.bak "s|$PIN|THE-LATEST-RELEASE|g" "$d/GETTING_STARTED.md"
printf '\n<!-- doc-rev marker: %s -->\n' "$PIN" >> "$d/GETTING_STARTED.md"
checkmsg 1 "an invisible comment does not satisfy the current-stamp vacuity guard" \
  "pass vacuously" "$d"

# ...and the SAME-LINE spelling, which was defended nowhere. continuous() refuses
# the join from a comment on the line ABOVE; nothing stripped a comment from the
# line ITSELF, and the in-line clause truncation cannot help — it cuts at the
# LAST [.!?;], which here is the "!" of "<!--", in FRONT of the marker, so it
# discarded the real label and KEPT the invisible word. The rendered badge read
# alpha.13 under an alpha.18 pin and the gate reported OK (round 5).
d=$(newfix)
sed -i.bak 's|Latest release:.*|Latest release: <!-- since --> `2.0.0-alpha.13`|' "$d/README.md"
checkmsg 1 "an invisible HTML comment on the SAME LINE does not exempt the stamp beside it" \
  "claims version \`2.0.0-alpha.13\`" "$d"

# ...and the other direction, which the same truncation broke on ordinary
# documentation: a comment sitting between a REAL marker and its token threw the
# marker away and fired on true history, with the printed remedy already written.
d=$(newfix)
printf 'Cross-event guards have been supported since <!-- see CHANGELOG --> `2.0.0-alpha.11` in the engine core.\n' >> "$d/CQELS-QL_SPEC.md"
check 0 "...and a comment BETWEEN a marker and its token does not break the marker" "$d"

# The SAME-LINE version of that evasion (codex round 2): the marker sits in an
# EARLIER clause of the same line, separated by a semicolon. norm() strips the
# ';' before the marker scan, so without in-line clause truncation this
# classified provenance and the gate reported OK on a stale landing stamp.
d=$(newfix)
printf 'Nothing changed since launch; release `2.0.0-alpha.13` remains the pin here.\n' >> "$d/README.md"
checkmsg 1 "a marker in an EARLIER clause of the same line must not exempt a stale stamp" \
  "claims version" "$d"

# ...and the case that actually PINS that truncation. The one above is carried
# by the governing rule instead — the walk breaks on "launch" — so deleting the
# before-side truncation left the whole suite green and reopened a false OK on a
# stale landing stamp with nothing to notice (round 3). Here only filler ("CQELS")
# sits between the clause mark and the token, so the marker WOULD reach it.
d=$(newfix)
printf 'Nothing has moved since. CQELS `2.0.0-alpha.13` powers the demos.\n' >> "$d/README.md"
checkmsg 1 "a marker in an earlier SENTENCE reaches the token through filler alone — the before-side clause rule is what stops it" \
  "claims version \`2.0.0-alpha.13\`" "$d"

# The sentence a link ENDS is still a sentence. `[^ ]+` ran to the next space,
# so the ")." closing a house-style link was deleted with the URL, the clause
# truncation found no boundary left, and the since-sentence governed the stamp
# in the NEXT sentence — the gate reported OK on exactly the stale-landing-page
# defect it exists to catch (round 2). The repo links every version mention, so
# this is the natural shape, not a contrived one.
d=$(newfix)
printf 'Correlated FILTER guards have been fixed since [`2.0.0-alpha.11`](https://github.com/cqels/CQELS4J/releases/tag/v2.0.0-alpha.11). CQELS `2.0.0-alpha.13` is required to run the examples below.\n' >> "$d/README.md"
checkmsg 1 "a since-sentence ENDING IN A LINK does not exempt the next sentence's stamp" \
  "claims version \`2.0.0-alpha.13\`" "$d"

# The mask that makes a version token one word must not ALSO be a filler word.
# It was the literal " VERSION ", which lowercases to a member of the filler
# set, so every token was transparent to the marker walk and one marker exempted
# an unrelated stamp later in the same clause — comma, pipe and em-dash are not
# clause marks (round 2). The must-not-fire counterparts are the house-style
# link and "since `X` and `Y`" above: the same token repeated, and a
# coordination, are the only two ways a token may sit between marker and object.
#
# The tail of these two fixtures matters. Both used to end "…is/remains the
# current release", which trips the FORWARD current-predicate rule and returns
# before the backward walk ever runs — so they reported ok under the exact
# masked-token regression they are named for, and only their table-cell and
# comma-plus-conjunction siblings were holding the line (round 6). The tail is
# now neutral, so the classification can only come from the walk.
d=$(newfix)
printf 'Stable since `2.0.0-alpha.11`, `2.0.0-alpha.13` ships today.\n' >> "$d/README.md"
checkmsg 1 "a marker governing token A does not exempt an unrelated token B after a comma" \
  "claims version \`2.0.0-alpha.13\`" "$d"

d=$(newfix)
printf '\n| Introduced | Current |\n|---|---|\n| since `2.0.0-alpha.11` | `2.0.0-alpha.13` |\n' >> "$d/README.md"
check 1 "...nor across a table cell boundary ('| Introduced | Current |' matrix row)" "$d"

d=$(newfix)
printf 'Supported since `2.0.0-alpha.11` — `2.0.0-alpha.13` ships today.\n' >> "$d/README.md"
check 1 "...nor across an em-dash" "$d"

# ...and not across a comma plus a CONJUNCTION either, which is the ordinary
# English spelling of a second independent clause. The clause mark was
# transparent to the walk (it has to be — "Available since: `X`" is a
# must-not-fire case) but it did not clear the coordination flag, so "and"
# reached back over it, spent itself on the masked first token and arrived at
# the marker: the bare comma fired and the comma-plus-conjunction spelling of
# the SAME sentence passed (round 5).
d=$(newfix)
printf 'The journal format is stable since `2.0.0-alpha.11`, and `2.0.0-alpha.13` is the version consumers should install.\n' >> "$d/README.md"
checkmsg 1 "...nor across a comma plus 'and'" "claims version \`2.0.0-alpha.13\`" "$d"

d=$(newfix)
printf 'The journal format is stable since `2.0.0-alpha.11`, or `2.0.0-alpha.13` for older engines.\n' >> "$d/README.md"
checkmsg 1 "...nor across a comma plus 'or'" "claims version \`2.0.0-alpha.13\`" "$d"

# ...but a trailing colon INTRODUCES the next line rather than ending a clause.
# Refusing that join classified this legitimate wrapped historical claim as a
# current stamp — a false positive that would fire on every future bump (codex
# round 2, the other direction).
d=$(newfix)
printf 'Available since:\n`2.0.0-alpha.13` for the negated-step guards.\n' >> "$d/README.md"
checkmsg 0 "'Available since:' wrapping onto a historical version is provenance, not a stale stamp" \
  "OK:" "$d"

# ...and the colon inside a single line never truncates the marker away.
d=$(newfix)
printf 'Available since: `2.0.0-alpha.13` for the negated-step guards.\n' >> "$d/README.md"
checkmsg 0 "'Available since: X' on one line is provenance" "OK:" "$d"

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
echo "token spelling — a string that is not a version must not be read as one"

# A stale stamp spelled `2.0.0-Alpha.13` used to match NOTHING — not current,
# not bare, not malformed — so it passed in silence while the vacuity guard
# stayed satisfied by the other stamps in the same file (codex round 3).
d=$(newfix); sed -i.bak 's|Latest release:.*|Latest release: `2.0.0-Alpha.13`|' "$d/README.md"
checkmsg 1 "a stale stamp in mixed case is not invisible" "2.0.0-Alpha.13" "$d"

d=$(newfix); printf '\nAn opt-in path (Alpha.99).\n' >> "$d/README.md"
check 1 "a BARE mixed-case reference to a future version fires" "$d"

# ...and the other direction: reading tokens case-insensitively must not turn
# true history into "impossible history" via an unranked channel.
d=$(newfix); printf '\nGuards are honored since `2.0.0-Alpha.11`.\n' >> "$d/CQELS-QL_SPEC.md"
checkmsg 0 "mixed-case provenance older than the pin stays valid history" "OK:" "$d"

# Suffixes. Only a trailing ALPHANUMERIC was rejected, so `X.1` and
# `X-SNAPSHOT` — neither of which resolves anywhere — were truncated to the pin
# and reported as a correct current stamp, which is the silent truncation this
# check was written to prevent.
for suffix in x .1 .9.9 -SNAPSHOT; do
  d=$(newfix)
  sed -i.bak "s|Latest release:.*|Latest release: \`$PIN$suffix\`|" "$d/README.md"
  checkmsg 1 "'$PIN$suffix' is reported, not truncated to the pin" "not a usable version token" "$d"
done

# ...and the shapes that legitimately carry a '.' or '-' next to a token stay
# silent. SUPPLY_CHAIN.md:87 documents exactly this path, so a blanket rule on
# either character would fire on a true claim.
d=$(newfix)
printf '\n    REL=org/cqels/cqels-engine/%s/cqels-engine-%s.jar\n' "$PIN" "$PIN" >> "$d/SUPPLY_CHAIN.md"
check 0 "a real artifact path ('…/X/cqels-engine-X.jar') is not malformed" "$d"

# An OLD version inside a jar NAME, several ordinary words downstream of the
# marker, is judged on its own and fires. That is the deliberate safe direction:
# reaching it would need a plain word-distance window, which is what let
# "**Tested before release:** `X`" pass as provenance (round 3). Both halves are
# pinned so the trade-off cannot be changed silently — the remedy for a real
# sentence of this shape is the BARE form, which the gate accepts as history.
d=$(newfix)
printf '\nBefore `%s`, the signed manifest listed cqels-engine-2.0.0-alpha.13.jar.\n' "$PIN" >> "$d/SUPPLY_CHAIN.md"
checkmsg 1 "a stale version inside a jar NAME is a current claim, marker or no marker" \
  "2.0.0-alpha.13" "$d"

d=$(newfix)
printf '\nBefore `%s`, the signed manifest listed the alpha.13 jar.\n' "$PIN" >> "$d/SUPPLY_CHAIN.md"
check 0 "...and the documented remedy — the bare form — is accepted as history" "$d"

# Spellings the RENDERER decodes and the byte grammar does not. Case folding
# closed one of these; the rest arrive through transports folding cannot see
# (round 7), and both failure modes are silent:
#
#   * a Cyrillic "а" in "alpha" renders identically and matched NOTHING — not
#     current, not bare, not malformed — exactly the silence the case fix
#     removed, reopened one byte lower down;
#   * "&#45;" and "%2D" render as a hyphen, so the reader sees a full current
#     stamp while the byte grammar sees no full form and the bare sweep DEMOTES
#     the stamp to `alpha.13` — provenance, which need only be <= the pin;
#   * U+2011 is what a paste out of Word or a PDF produces and is visually a
#     hyphen. Same demotion, and the only observable was one extra provenance
#     count.
#
# Confusables cannot be enumerated, so the rule flags the SHAPE and refuses to
# guess. All four are pinned because each is a different transport.
d=$(newfix)
printf '\nLatest release: `2.0.0-\320\260lpha.13`\n' >> "$d/README.md"
checkmsg 1 "a HOMOGLYPH inside the channel word is refused, not silently unmatched" \
  "the rendered page may show an ordinary version" "$d"

d=$(newfix)
printf '\nLatest release: 2.0.0&#45;alpha.13\n' >> "$d/README.md"
checkmsg 1 "an HTML character reference for the dash is refused, not demoted to bare" \
  "the rendered page may show an ordinary version" "$d"

d=$(newfix)
printf '\nLatest release: 2.0.0\342\200\221alpha.13\n' >> "$d/README.md"
checkmsg 1 "a non-breaking hyphen (Word/PDF paste) is refused, not demoted to bare" \
  "the rendered page may show an ordinary version" "$d"

d=$(newfix)
printf '\nSee [notes](https://github.com/cqels/CQELS4J/releases/tag/v2.0.0%%2Dalpha.13).\n' >> "$d/README.md"
checkmsg 1 "a percent-escaped dash in a link target is refused" \
  "the rendered page may show an ordinary version" "$d"

# ...and the rule must not fire on ordinary documentation. A non-ASCII byte NEAR
# a version is everywhere in these guides (an em dash, a section sign, an accent
# in a translated line); what makes a token unreadable is non-ASCII GLUED INTO
# it, with no space, between the base and another dotted number.
d=$(newfix)
printf '\nCQELS 2.0.0 — see §3.1. Publi\303\251e pour 2.0.0 — rien \303\240 signaler.\nBound to 192.168.0.1:8080 and 127.0.0.1, build 1.2.3.4.5.\n' >> "$d/CQELS-QL_SPEC.md"
check 0 "...and non-ASCII prose merely NEAR a version is not a spoofed token" "$d"

d=$(newfix)
printf '\n    REL=org/cqels/cqels-mcp/%s/cqels-mcp-%s-shaded.jar\n' "$PIN" "$PIN" >> "$d/SUPPLY_CHAIN.md"
check 0 "...nor is a plain-ASCII artifact path with a percent-free URL" "$d"

echo
echo "the fence that is CURRENT passes when it is current (boundary, both sides)"

# At token == pin the exit code is silent whichever way the fence is classified
# — A2 accepts `current` == pin, A3 accepts `provenance` <= pin — so this case
# was a byte-identical duplicate of "a correctly bumped repo passes" and could
# not tell the two apart. It now asserts the RECORDS: the prose above the fence
# is provenance, the pasted `VERSION=` inside it is current, which is the exact
# boundary the must-fire case above defends from the other side. A widened
# lookback flips SUPPLY_CHAIN.md:13 to provenance with the exit code unchanged.
d=$(newfix)
checkrecords 0 "the same '**Since X…**' + fence shape, fence at the pin" "$d" \
  "SUPPLY_CHAIN.md|10|provenance|$PIN" \
  "SUPPLY_CHAIN.md|13|current|$PIN"

echo
echo "defect 3, offline half — self-consistency of the documented surface"

d=$(newfix); sed -i.bak 's|## Tools exposed (3)|## Tools exposed (4)|' "$d/mcp-server/README.md"
checkmsg 1 "heading count vs table rows" "the tables under it list 3" "$d"

d=$(newfix); sed -i.bak 's|\*\*3 tools\*\*|**4 tools**|' "$d/README.md"
checkmsg 1 "cross-file drift: README.md vs mcp-server/README.md" "The two landing pages disagree" "$d"

# These two must name the INTRA-FILE assertion. Bumping only the declaration
# also breaks the cross-file claim in README.md, so both errors fire on the one
# mutation and a bare exit code cannot tell them apart: the whole suite stayed
# green with the "declares N but lists M" comparisons deleted outright (round
# 6). The tools pair two cases up already does it this way.
d=$(newfix); sed -i.bak 's|Resources (2 + 1 template)|Resources (3 + 1 template)|' "$d/mcp-server/README.md"
checkmsg 1 "resource count vs the listed cqels:// URIs" \
  "declares 3 resources but lists 2 concrete" "$d"

d=$(newfix); sed -i.bak 's|\*\*Prompts (2)|**Prompts (3)|' "$d/mcp-server/README.md"
checkmsg 1 "prompt count vs the listed names" "declares 3 prompts but lists 2 names" "$d"

# ...and the other direction, which is the expensive one here. The claim greps
# were unanchored whole-file scans, so every number-plus-noun in ordinary prose
# became a competing landing-page claim and raised "the two landing pages
# disagree" — a false statement, about correct docs, in a job that blocks merge
# (round 3). Only the EMPHASISED claim is the advertised surface.
d=$(newfix)
printf '\nMost integrations only ever call 2 tools: store and recall.\n' >> "$d/README.md"
check 0 "an ordinary sentence counting tools is not a landing-page claim" "$d"

d=$(newfix)
printf '\nThe quickstart uses 1 resources and 1 prompt template.\n' >> "$d/README.md"
check 0 "...nor one counting resources and prompts" "$d"

d=$(newfix)
printf '\n> **Since 2.0.0-alpha.9** the server gained 1 tools.\n' >> "$d/README.md"
check 0 "...nor a release note in this repo's own provenance idiom" "$d"

# A declaration that appears TWICE must be refused, not compared. `sed` prints
# one line per match, so head_n became "3\n9", `[ -ne ]` exited 2 with
# "integer expression expected", `if` read that as false — and the
# heading-vs-rows assertion silently no-opped even though the first section's
# count was genuinely wrong. The surviving error came from the `||`-guarded arm
# and blamed the wrong file, with an embedded newline that GitHub renders
# truncated (round 3).
d=$(newfix)
printf '\n## Tools exposed (9)\n\n| Tool | What it does |\n|------|--------------|\n| `other` | Something else. |\n' \
  >> "$d/mcp-server/README.md"
checkmsg 1 "a DUPLICATED count declaration is refused, not silently skipped" \
  "more than once" "$d"

# CRLF. The section extractors stop at the first blank line, and `/^$/` cannot
# match "\r" — awk strips only the newline — so the extraction ran to EOF,
# swallowed every later section, and the gate reported two defects
# ("declares 2 resources but lists 3") about a document in which no claim had
# changed at all. There is no .gitattributes, so a CRLF file commits as CRLF.
d=$(newfix)
python3 - "$d/mcp-server/README.md" <<'PY'
import sys
p = sys.argv[1]
b = open(p, 'rb').read().replace(b'\r\n', b'\n').replace(b'\n', b'\r\n')
open(p, 'wb').write(b)
PY
check 0 "a CRLF-saved mcp-server/README.md reports no defect (its claims are unchanged)" "$d"

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

# A value the extractor cannot read must be a usage error, not a silent
# "unset". An element split across lines passes the count guard (which sees
# only the OPEN tag), and the empty value then fell through to the
# "$PIN is still empty" first-iteration sentinel — so the SECOND pom quietly
# became the pin and the gate printed "identical in both poms" about two poms
# that were not (codex round 3). It matters which pom: only the first one hits
# the sentinel, so both are pinned.
for pom in examples mcp-server; do
  d=$(newfix)
  python3 - "$d/$pom/pom.xml" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
s = s.replace("<cqels.version>", "<cqels.version>\n            ")
s = s.replace("</cqels.version>", "\n        </cqels.version>")
open(p, "w").write(s)
PY
  checkmsg 2 "a <cqels.version> split across lines in $pom/pom.xml is a usage error" \
    "no value could be read" "$d"
done

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

# ...and a path git would QUOTE is still scanned. `git ls-files` renders a
# non-ASCII byte as the literal text "GU\303\215A.md" (core.quotePath is on by
# default), for which `[ -f ]` is false — so a translated guide was dropped in
# silence, the stale stamp inside it passed with exit 0, and the summary still
# printed the same stamp count as a clean run (round 2). A quoted DIRECTORY
# component would drop every file beneath it.
NONASCII=$(printf 'GU\303\215A.md')
d=$(newfix); printf '# Guia\n\nLatest release: `2.0.0-alpha.13`\n' > "$d/$NONASCII"
checkmsg 1 "a stale stamp in a NON-ASCII filename is scanned, not silently skipped" \
  "2.0.0-alpha.13" "$d"

d=$(newfix); printf '# Guia\n\nBuilt against `%s`.\n' "$PIN" > "$d/$NONASCII"
check 0 "...and the same file with a CORRECT stamp stays silent" "$d"

# A path containing the record delimiter mis-splits the scanner's own output —
# 'a|b.md|3|current|X' read as f=a ln=b.md cls=3, which matches no arm and is
# dropped. Refused loudly instead of parsed.
d=$(newfix); printf '# ab\n\nLatest release: `2.0.0-alpha.13`\n' > "$d/a|b.md"
checkmsg 1 "a tracked path containing '|' is refused loudly, not silently mis-parsed" \
  "cannot be scanned" "$d"

# ...and a path that SYNTHESISES that delimiter after the guard has passed it.
# The scanned name used to reach awk through `-v`, which POSIX processes for
# escape sequences, so the raw bytes `a\174b.md` — a literal backslash, which
# the guard above sees and allows — arrived inside awk as `a|b.md`, mis-split
# the record, matched no arm, and dropped the stale stamp in total silence
# (round 4). The name now travels through the environment, which is not
# escape-processed, and the stamp fires under the real filename.
d=$(newfix); printf '# ab\n\nLatest release: `2.0.0-alpha.13`\n' > "$d/a\\174b.md"
checkmsg 1 "a backslash escape in a tracked path cannot forge the record delimiter" \
  'a\174b.md:3 claims version' "$d"

# ...and a path holding a real NEWLINE, which is the OTHER delimiter: the records
# are newline-delimited, so one record split into two physical lines. The orphan
# head matched no arm and vanished; the tail was read as a record belonging to
# whatever followed the newline — `decoy<LF>README.md` satisfied A5's
# per-required-file vacuity guard for a README that had been reworded out of
# scope, and the same trick with a stale stamp blamed a line of README.md that
# holds no version at all (round 5). The `-z` read that makes such a path
# survive git is exactly what delivers it here, so it is refused, like `|`.
d=$(newfix); printf '# decoy\n\nLatest release: `2.0.0-alpha.13`\n' > "$d/decoy"$'\n'"README.md"
checkmsg 1 "a tracked path containing a NEWLINE is refused loudly, not split into two records" \
  "cannot be scanned" "$d"

# ...and a path that begins with '-'. A path is an OPERAND, but grep parses a
# leading dash as options wherever it sits, so the binary-skip test failed with
# exit 2 and `|| continue` dropped the file with no record, no counter change and
# no err — "every version claim in this repository is true" over a guide saying
# the current release is alpha.13 (round 5).
d=$(newfix); printf '# Release notes\n\nLatest release: `2.0.0-alpha.13`\n' > "$d/-RELEASE-NOTES.md"
checkmsg 1 "a tracked path beginning with '-' is scanned, not eaten as an option bundle" \
  '-RELEASE-NOTES.md:3 claims version' "$d"

d=$(newfix); printf '# Release notes\n\nBuilt against `%s`.\n' "$PIN" > "$d/-RELEASE-NOTES.md"
check 0 "...and the same file with a CORRECT stamp stays silent" "$d"

# ...and a file that grep calls BINARY. `grep -I` says binary the moment it sees
# a NUL, and the skip was unconditional, so a guide saved as UTF-16 (legacy
# Windows "Unicode") — which GitHub reads by its BOM and renders as perfectly
# ordinary markdown — was dropped entirely: no record, no counter change, no
# diagnostic, exit 0 over every stale stamp in it. The skip is also UPSTREAM of
# the awk-status backstop below, so that net could never catch it (round 7).
d=$(newfix)
printf '# Notes\n\nLatest release: `2.0.0-alpha.13`\n' > "$WORK/u8.md"
iconv -f UTF-8 -t UTF-16LE "$WORK/u8.md" > "$WORK/u16.md"
{ printf '\377\376'; cat "$WORK/u16.md"; } > "$d/NOTES.md"
checkmsg 1 "a UTF-16 guide is refused, not silently dropped as binary" \
  "holds NUL bytes" "$d"

# ...and the same drop with no size or diff signal whatever: ONE stray NUL
# appended to an ordinary UTF-8 guide.
d=$(newfix)
printf '# Notes\n\nLatest release: `2.0.0-alpha.13`\n\000' > "$d/NOTES.md"
checkmsg 1 "...and one stray NUL byte cannot silence a guide either" \
  "holds NUL bytes" "$d"

# ...while a real binary ASSET stays silent. No renderer shows prose in a .png,
# so its extension is the honest reason to skip it — and the only one.
d=$(newfix)
printf '\211PNG\r\n\032\n\000\000\000\rIHDR\000\000' > "$d/logo.png"
check 0 "...but a genuine binary asset is still skipped without complaint" "$d"

# grep's OTHER non-zero status. 1 is "no match", 2 is "could not read this
# file", and `|| continue` gave them the same arm — so an unreadable tracked
# file contributed nothing, moved no counter, and the gate printed "every
# version claim in this repository is true" (round 7). awk exits 2 on the same
# input, so the backstop below would have caught it had it ever been reached.
d=$(newfix)
printf 'Ships `2.0.0-alpha.13` today.\n' > "$d/NOTES.md"
reindex "$d"; chmod 000 "$d/NOTES.md"
checkmsg 1 "an UNREADABLE tracked file is reported, not counted as 'no match'" \
  "could not be read" "$d"
chmod 644 "$d/NOTES.md"

echo
echo "the locale — every rule here is ASCII, and awk is not (macOS)"

# One non-UTF-8 byte (a CP1252 accent in a translated guide) made macOS awk
# abort the WHOLE file with "towc: multibyte conversion failure": zero records,
# an unchanged stamp count, and a stale stamp inside it passing with exit 0.
# Only the local run is exposed — ubuntu's mawk is byte-oriented — and the local
# run is the pre-tag decision point, so the gate forces LC_ALL=C rather than
# inheriting whatever the maintainer has set.
d=$(newfix); printf 'Publi\351e: the current release is 2.0.0-alpha.13.\n' > "$d/fr.md"
checkutf8 1 "a non-UTF-8 byte does not silently drop the file it sits in" \
  "fr.md:1 claims version" "$d"

# ...and the mirror. The scanner matches on a lowercased copy and indexes the
# ORIGINAL with the offsets — sound only while tolower() is length-preserving,
# which in a UTF-8 locale it is not ("İ" U+0130 lowercases to one byte). The
# offsets shifted, substr() returned a shifted token, and a stamp that EQUALS
# the pin was reported as "not a usable version token" — an error no edit to the
# version could clear (round 5).
d=$(newfix); printf 'Kurulum \304\260\303\247in: the current release is %s.\n' "$PIN" > "$d/tr.md"
checkutf8 0 "a multibyte character does not turn a CORRECT stamp into a malformed token" "-" "$d"

# ...and the same file with a STALE stamp still fires, under the same locale, so
# the case above is silent for the right reason.
d=$(newfix); printf 'Kurulum \304\260\303\247in: the current release is 2.0.0-alpha.13.\n' > "$d/tr.md"
checkutf8 1 "...and a STALE stamp behind one still fires, naming the token as written" \
  "claims version \`2.0.0-alpha.13\`" "$d"

echo
echo "defect 2 — the online tier (PATH-shimmed curl, no network)"

mkdir -p "$WORK/shim"
cat > "$WORK/shim/curl" <<'SHIM'
#!/usr/bin/env bash
url=${@: -1}
# Real curl refuses a URL carrying a control character outright — exit 3, "URL
# using bad/illegal format" — which probe() maps to INCONCLUSIVE. The shim has
# to model that, because the one reader that did not strip the CR is B1 and a
# shim that answers 200 to anything cannot see the difference.
case "$url" in *$'\r'*) echo 000; exit 3 ;; esac
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

# CRLF, in the one reader that did not strip it. The scanner and all four A6
# extractors strip the CR; B1 harvested the URL straight off the raw line, and a
# BARE autolink at the end of a current-stamp line has no ')' or '>' to stop the
# match before it — so curl got a URL ending in \r, refused it with exit 3, and
# the links job reported INCONCLUSIVE "re-run" forever on a link that resolves
# (round 5). Exit 3 is supposed to mean the network could not be reached.
d=$(newfix)
printf '\n- **Release notes:** https://github.com/cqels/CQELS4J/releases/tag/v%s\n' "$PIN" >> "$d/GETTING_STARTED.md"
python3 - "$d/GETTING_STARTED.md" <<'PY'
import sys
p = sys.argv[1]
b = open(p, 'rb').read().replace(b'\r\n', b'\n').replace(b'\n', b'\r\n')
open(p, 'wb').write(b)
PY
VTG_TEST_CURL=all200 check 0 "a CRLF guide does not hand curl a URL with a CR (a permanent, unclearable exit 3)" \
  "$d" --online

# ...and B1 must actually READ every current-stamp line, including one in a file
# whose name starts with a dash. Round 5 gave grep its `--` and recorded that
# "sed takes its path after the program text, where a leading dash is already a
# filename" — true of BSD sed, FALSE of GNU sed, which permutes options past the
# script. So on ubuntu — where the links job runs — `sed -n Np -RELEASE-NOTES.md`
# died with "invalid option -- 'R'", `|| true` swallowed it, that file's links
# were never probed, and the run still printed "every channel it names resolves"
# (round 6). B1 reads through a redirect now, which is an operand on both.
# NOTE: this case can only turn red where the defect was reachable — under GNU
# sed, i.e. in CI. On a BSD/macOS sed it passed before the fix as well.
d=$(newfix)
printf '# Release notes\n\nBuilt against `%s` — notes at https://raw.githubusercontent.com/cqels/maven/main/releases/notes/%s/NOTES.md\n' \
  "$PIN" "$PIN" > "$d/-RELEASE-NOTES.md"
VTG_TEST_CURL=all200 checkmsg 0 "the online tier harvests links from a file whose name starts with '-'" \
  "releases/notes/$PIN/NOTES.md" "$d" --online

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

# ...and a MISSING INTERPRETER is not a missing answer. The parse ran inside
# `if ! …`, so "python3: command not found" took the truncated-session arm and
# the gate reported "the server did not answer every discovery call … re-run" —
# every clause of which is false, and no re-run can clear it. RELEASING.md sends
# maintainers to run this tier locally, and this same suite drives it eight
# times, so one absent interpreter turned all of that red with a diagnosis of
# the wrong machine (round 6). Both directions are asserted: the new message
# must name the interpreter, and it must NOT blame the server.
d=$(newfix)
reindex "$d"
out=$( cd "$d" && VTG_DEEP_CAPTURE="$WORK/t-good" VTG_PYTHON3=python3-does-not-exist "$SUT" --deep 2>&1 ); rc=$?
if [ "$rc" -eq 3 ] \
   && printf '%s' "$out" | grep -qF 'is not on PATH' \
   && ! printf '%s' "$out" | grep -qF 'the server did not answer'; then
  printf '  ok    a missing python3 is named as such, not blamed on the server\n'; pass=$((pass + 1))
else
  printf '  FAIL  a missing python3 should exit 3 naming the interpreter (got %s)\n' "$rc"
  printf '%s\n' "$out" | sed 's/^/          /'
  fail=$((fail + 1))
fi

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
