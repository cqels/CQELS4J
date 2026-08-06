#!/usr/bin/env bash
#
# version-truth-gate.sh [--online] [--deep]
#
# Assert that every version this repository *states* is true: the release it
# tells a consumer to depend on is the one the poms pin, that release actually
# exists on the channels the docs name, and the tool surface the docs enumerate
# is the one the server advertises.
#
# Why this is a gate and not a convention
# ---------------------------------------
# This is a documentation repository. Its entire product is claims about
# something else — a release published from a different repo, on channels this
# repo does not control. Nothing here compiles, so nothing here fails when a
# claim goes stale. Three defects were found by hand in one week, each of which
# had been sitting in the public landing page:
#
#   1. A STALE PIN READ AS CURRENT. `origin/master` pinned 2.0.0-alpha.16 while
#      a branch carried alpha.13, and there was no way to tell which was the
#      truth without reading both. Docs and pom drifting apart was invisible.
#   2. VERSION CLAIMS THAT DO NOT RESOLVE. A bump to alpha.18 would have put
#      three dead links on the front page — README.md, mcp-server/README.md and
#      SUPPLY_CHAIN.md all link a release tag that did not exist. Separately the
#      shaded jar was 404 on every channel while listed in the signed manifest.
#   3. DOCUMENTED OUTPUT THAT DISAGREES WITH REALITY. README.md advertised
#      "24 tools"; the server advertises 25. The docs were internally
#      self-consistent — heading, table and prose all said 24 — so no amount of
#      cross-referencing inside the repo could find it. Only asking the server
#      could.
#
# The naive rule is wrong in BOTH directions
# ------------------------------------------
# "Bump every version string on release" falsifies history. This repo carries
# nineteen deliberate references to OLD versions — "since 2.0.0-alpha.11 the
# engine resolves an explicit function-IRI", "Before CQELS 2.0.0-alpha.13
# cross-event guards on negated steps were ignored". Those are provenance. They
# are true, they are useful, and bumping them makes them lies.
#
# "Never touch old versions" is the failure we started from: the landing page
# rots and every consumer who follows it fails against a perfectly good release.
#
# So the gate classifies each token by the words around it. A token marked as
# provenance ("since X", "before X", "from X onward", "X is the first release
# with") is only required to be <= the pin — impossible history still fails. An
# unmarked token is a claim about *now* and must equal the pin exactly.
#
# The remedy is symmetric, and that is the point: when the gate fires on a line
# you meant historically, you do not silence it — you REWORD the line into
# marked provenance. The ambiguity gets resolved once, in the text, where the
# next reader sees it too.
#
# Scope, and what is deliberately outside it
# ------------------------------------------
#   * Only `git ls-files` is scanned. mcp-server/dependency-reduced-pom.xml is a
#     shade byproduct that carries a <cqels.version> and is .gitignored; scanning
#     the working tree instead of the index would fail on a file nobody edits.
#   * Only FULL-FORM tokens (2.0.0-alpha.16) are required to equal the pin. Bare
#     mentions (alpha.7) are checked only against the upper bound. This is
#     measured, not assumed: all nine bare mentions in this repo are provenance,
#     and no current stamp has ever been written bare. The residual is real — a
#     future "Latest release: alpha.16" written bare would not be caught by the
#     equality rule — and is bounded by the vacuity guard, which requires every
#     required file to still carry a full-form current stamp.
#   * Marker proximity is a text rule and can be gamed: "unchanged since
#     `2.0.0-alpha.16`" reads as provenance and is exempted from equality. No
#     such line exists today. That is the inherent residual of any text rule.
#   * A reference to a WITHDRAWN version ("since 2.0.0-alpha.17", a version that
#     was never publicly consumable) passes the offline tier, which cannot know
#     the withdrawn set. The online tier only probes channels derived from the
#     pin.
#   * The version grammar is pinned to `-(alpha|beta|rc).N`. The day this
#     project ships a final `2.0.0`, A1 will fail loudly rather than silently
#     un-scoping every token in the repo. That is intentional; the fix is a
#     one-line grammar extension, and it is better found here than mid-release.
#
# Tiers
# -----
#   (default)   A1-A7  offline, pure git/awk, < 5s, no network. Every PR.
#   --online    + B1-B2  ~6 HEAD requests. Separate CI check, and a daily run so
#                        world-drift (a deleted release, a dead mirror) surfaces
#                        without waiting for a commit.
#   --deep      + C1     builds mcp-server and drives one stdio JSON-RPC session
#                        (needs java, maven and python3).
#                        ~62 MB + a JVM, minutes. Schedule/dispatch only.
#
# Exit codes
# ----------
#   0  every claim checked and true
#   1  a documentation defect — a claim in this repo is false
#   2  usage error, or the pin itself is unusable (see A1)
#   3  INCONCLUSIVE — the network could not be reached. Never mapped to pass
#      (a gate that can pass having checked nothing is not a gate) and never
#      worded as a defect (a gate that cries wolf on a GitHub hiccup gets
#      ignored, which comes to the same thing). Re-run.
#
# Local use
# ---------
#   scripts/ci/version-truth-gate.sh             before opening a bump PR
#   scripts/ci/version-truth-gate.sh --online    before requesting review
#
# Testing
# -------
# scripts/ci/version-truth-gate.test.sh pins both directions and runs on every
# PR, ahead of the gate itself. Every must-not-fire case is a line copied
# verbatim out of the real guides.

set -uo pipefail

ONLINE=0
DEEP=0
for arg in "$@"; do
  case "$arg" in
    --online) ONLINE=1 ;;
    --deep)   DEEP=1 ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) echo "usage: version-truth-gate.sh [--online] [--deep]" >&2; exit 2 ;;
  esac
done

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || {
  echo "::error::not inside a git work tree — this gate scans \`git ls-files\`, not the filesystem." >&2
  exit 2
}
cd "$ROOT" || exit 2

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

fail=0
err() { echo "::error::$*"; fail=1; }
ok()  { echo "ok:   $*"; }

# ---------------------------------------------------------------------------
# A1 — THE PIN.
#
# Everything else is measured against this one value, so a malformed pin is a
# usage failure (exit 2), not a doc failure. Comparing every documented stamp
# against a typo and reporting them all stale is how a gate teaches people to
# ignore it.
# ---------------------------------------------------------------------------
POMS="examples/pom.xml mcp-server/pom.xml"
PIN=""
for pom in $POMS; do
  if [ ! -f "$pom" ]; then
    echo "::error::$pom not found — refusing to guess the pin." >&2
    exit 2
  fi
  n=$(grep -o '<cqels\.version>' "$pom" | wc -l | tr -d ' ')
  if [ "$n" -ne 1 ]; then
    echo "::error::$pom declares <cqels.version> $n times, expected exactly 1 — the pin is ambiguous." >&2
    exit 2
  fi
  v=$(sed -n 's|.*<cqels\.version>\(.*\)</cqels\.version>.*|\1|p' "$pom")
  if [ -z "$PIN" ]; then
    PIN="$v"
  elif [ "$PIN" != "$v" ]; then
    echo "::error::the two poms disagree about the pin: examples/pom.xml says '$PIN', $pom says '$v'. Half a bump landed." >&2
    exit 2
  fi
done

case "$PIN" in
  *SNAPSHOT*|*'${'*)
    echo "::error::pin is '$PIN' — a SNAPSHOT or unresolved property is not something a consumer can depend on." >&2
    exit 2 ;;
esac
if ! printf '%s' "$PIN" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+-(alpha|beta|rc)\.[0-9]+$'; then
  echo "::error::pin '$PIN' does not match the documented version grammar N.N.N-(alpha|beta|rc).N." >&2
  echo "::error::If the scheme changed on purpose (a final release, say), extend the grammar in A1 — do NOT delete the check, which would silently un-scope every version token in the repo." >&2
  exit 2
fi
ok "pin is $PIN, identical in both poms"

# ---------------------------------------------------------------------------
# The scanner.
#
# One awk pass per tracked text file. Emits one record per version token:
#
#   <file>|<line>|<class>|<token>
#
# class ∈ current | provenance | bare | malformed
#
# ALL tokens on a line are emitted, not just the first: match() returns only the
# leading occurrence, so a line reading
#   [`2.0.0-alpha.18`](https://.../tag/v2.0.0-alpha.16)
# would show a correctly-bumped display token and hide the stale URL behind it.
#
# Classification context = the tail of the PREVIOUS physical line plus the part
# of this line before the token, with all non-alphanumerics flattened to spaces
# so that ` * <p>Since {@code X}` and `**Since \`X\`**` and `| honored since X |`
# all reduce to the same word sequence.
#
# Exactly ONE line of lookback, and one of lookahead. Both bounds are measured:
#
#   * The lookback is load-bearing. In DriverAttentionWatchdog.java the marker
#     "Since" ends line 32 and the token starts line 33; with no lookback, true
#     provenance would be reported as a stale current stamp.
#   * A DEEPER lookback would be wrong in the other direction. SUPPLY_CHAIN.md
#     has "**Since `2.0.0-alpha.16` it is attached…**", a blank line, a
#     "```bash" fence, and then `VERSION=2.0.0-alpha.16` — a CURRENT stamp that
#     a consumer pastes. Three lines of lookback would exempt it and the whole
#     verification snippet could rot unnoticed.
#   * The lookahead is symmetric and equally load-bearing: SUPPLY_CHAIN.md's
#     "`2.0.0-alpha.16` is the first release" happens to end exactly at the line
#     break today, so a future re-wrap that splits "is the first release" would
#     flip true provenance to CURRENT and fire on correct history.
#
# `from` alone is NOT a marker, only "from X onward". mcp-server/README.md wraps
# "…Download it from the" directly above a CURRENT release link; a bare-`from`
# marker would exempt the one link most likely to 404.
# ---------------------------------------------------------------------------
RECORDS="$WORK/records"
: > "$RECORDS"

scan_file() {
  awk -v FILE="$1" '
    function norm(s) { gsub(/[^A-Za-z0-9]+/, " ", s); sub(/^ +/, "", s); sub(/ +$/, "", s); return s }

    # PROVENANCE iff a marker word governs the token.
    #   P1  since | before | prior to   ..<=3 words..  TOKEN
    #   P2  TOKEN  ..<=1 word..  onward(s)
    #   P3  TOKEN  is|was the first release
    function classify(before, after,   b, a, n, w, i, tail) {
      b = norm(before); a = norm(after)

      # P1: the marker must sit within 3 words of the token. The bound stops a
      # sentence-initial "Since" from exempting a current stamp further along
      # the same line; the measured maximum in this repo is 2 ("Since CQELS
      # {@code X" across a javadoc wrap), so 3 is one word of slack.
      n = split(b, w, " ")
      for (i = n; i >= 1 && i > n - 4; i--) {
        if (tolower(w[i]) == "since" || tolower(w[i]) == "before") return "provenance"
        if (tolower(w[i]) == "to" && i > 1 && tolower(w[i-1]) == "prior") return "provenance"
      }
      # P2 / P3 look forward.
      if (a ~ /^([A-Za-z0-9]+ )?onwards?( |$)/) return "provenance"
      if (a ~ /^(is|was) the first release( |$)/) return "provenance"
      return "current"
    }

    # Is the previous line part of the SAME construct as this one?
    #
    # The lookback exists for one real shape — a marker and its token split by a
    # comment wrap, e.g. DriverAttentionWatchdog.java:32-33:
    #     * ... no longer suppresses the alert. Since
    #     * CQELS {@code 2.0.0-alpha.13}, cross-event FILTER guards ...
    # Without a continuity test it also joins two lines that have nothing to do
    # with each other, and `norm()` cannot see the difference because it strips
    # every punctuation and block marker before classify() runs. Codex broke the
    # gate with exactly that (verified — the gate reported OK on a README whose
    # landing badge read alpha.13 under an alpha.16 pin):
    #     Documentation has remained unchanged since
    #     the release `2.0.0-alpha.13` shipped.
    # Two ordinary prose lines, no marker abuse, and the central invariant is
    # gone. So the join is now allowed ONLY when nothing between the two lines
    # says "new construct":
    #   - prev must not END a sentence or clause (. ! ? : ;) — that sentence is
    #     over, so a "since" inside it cannot govern the next sentence token
    #     (NOTE: no apostrophes anywhere in this awk block — a stray one closes
    #     the single-quoted program and bash then parses the awk source)
    #   - prev must not be blank (paragraph break)
    #   - prev must not be a heading, table row, or fence
    #   - THIS line must not START a new block (heading, blockquote, list item,
    #     table row, fence) — a token inside a fresh block is never governed by
    #     the prose above it
    # A rejected join is not a downgrade to "unknown": it means the token is
    # judged on its own line, which is the safe direction (CURRENT, hence
    # equality-checked).
    # Markdown block syntax is only meaningful in markdown. A javadoc
    # continuation line is ` * CQELS {@code X}` — character-identical to a
    # markdown bullet, and applying the bullet rule to .java false-positived on
    # DriverAttentionWatchdog.java:33, the exact wrap the lookback exists for.
    # So: block rules for .md, sentence/paragraph rules everywhere.
    function continuous(p, c,   md) {
      if (p ~ /^[ \t]*$/) return 0                       # paragraph break
      if (p ~ /[.!?:;][ \t]*$/) return 0                 # sentence/clause ended
      md = (FILE ~ /\.md$/)
      if (md) {
        if (p ~ /^[ \t]*#/) return 0                     # prev is a heading
        if (p ~ /^[ \t]*\|/) return 0                    # prev is a table row
        if (p ~ /^[ \t]*(```|~~~)/) return 0             # prev is a fence
        if (c ~ /^[ \t]*#/) return 0                     # new heading
        if (c ~ /^[ \t]*>/) return 0                     # new blockquote
        if (c ~ /^[ \t]*([-*+]|[0-9]+\.)[ \t]/) return 0 # new list item
        if (c ~ /^[ \t]*\|/) return 0                    # new table row
        if (c ~ /^[ \t]*(```|~~~)/) return 0             # new fence
      }
      return 1
    }

    { L[FNR] = $0 }

    END {
      for (i = 1; i <= FNR; i++) {
        line = L[i]
        prev = (i > 1 && continuous(L[i-1], line)) ? L[i-1] : ""
        nxt  = (i < FNR && continuous(line, L[i+1])) ? L[i+1] : ""
        masked = line

        # -- full-form tokens ------------------------------------------------
        pos = 1
        while (match(substr(line, pos), /[0-9]+\.[0-9]+\.[0-9]+-(alpha|beta|rc)\.[0-9]+/)) {
          abs = pos + RSTART - 1
          len = RLENGTH
          tok = substr(line, abs, len)

          # A trailing alphanumeric means the token is not what it looks like
          # ("2.0.0-alpha.16x"): report it rather than silently truncating to
          # something that happens to match the pin.
          after_ch = substr(line, abs + len, 1)
          if (after_ch ~ /[0-9A-Za-z]/) {
            print FILE "|" i "|malformed|" tok after_ch
          } else {
            before = prev " " substr(line, 1, abs - 1)
            after  = substr(line, abs + len) " " nxt
            print FILE "|" i "|" classify(before, after) "|" tok
          }

          # Blank the token (same length, so positions stay valid) before the
          # bare-form sweep, so "2.0.0-alpha.16" never also registers as a bare
          # "alpha.16".
          masked = substr(masked, 1, abs - 1) sprintf("%" len "s", "") substr(masked, abs + len)
          pos = abs + len
        }

        # -- bare tokens (alpha.7) -------------------------------------------
        pos = 1
        while (match(substr(masked, pos), /(alpha|beta|rc)\.[0-9]+/)) {
          abs = pos + RSTART - 1
          len = RLENGTH
          bch = (abs > 1) ? substr(masked, abs - 1, 1) : " "
          ach = substr(masked, abs + len, 1)
          if (bch !~ /[0-9A-Za-z]/ && ach !~ /[0-9A-Za-z]/)
            print FILE "|" i "|bare|" substr(masked, abs, len)
          pos = abs + len
        }
      }
    }
  ' "$1"
}

# A7 — the scan universe is the index, never the working tree.
#
# Two files are excluded, and only two: this gate and its self-test. Both must
# quote version tokens to do their job — the header explains the rule with
# "since 2.0.0-alpha.11", the test fixtures are built out of them — and none of
# those are claims about the current release. This was not foreseen; the gate
# was run against real history, reported eight defects in its own prose, and the
# exclusion was added afterwards.
#
# It is a list of exact paths rather than the directory, so a future
# scripts/ci/ script that DOES state a version is still checked. The self-test
# pins that.
SELF="scripts/ci/version-truth-gate.sh|scripts/ci/version-truth-gate.test.sh"
while IFS= read -r f; do
  [ -f "$f" ] || continue
  case "|$SELF|" in *"|$f|"*) continue ;; esac
  grep -Iq . "$f" 2>/dev/null || continue     # skip binary
  scan_file "$f" >> "$RECORDS"
done < <(git ls-files)

if [ ! -s "$RECORDS" ]; then
  err "no version token was found in ANY tracked file — the scanner matched nothing, so this gate would pass vacuously."
fi

# When the gate fires, the first question is always "why is THAT classified
# current?". VTG_DUMP=1 answers it without adding a second tool to maintain.
[ -n "${VTG_DUMP:-}" ] && sort -t'|' -k3,3 -k1,1 "$RECORDS" | sed 's/^/      /'

# ---------------------------------------------------------------------------
# Version ordering: (major, minor, patch, channel-rank, N).
#
# Numeric on N, not lexical: alpha.9 must sort BELOW alpha.18, which a string
# compare gets backwards — and getting it backwards makes A3 pass everything.
#
# Channel-ranked, not N alone: the grammar admits beta and rc, so on the day the
# pin becomes 2.0.0-beta.1 an N-only comparison would fire on all nineteen
# alpha.7/9/11/13/16 provenance mentions at once. Any alpha under a beta pin is
# valid history.
#
# A bare token has no base version; it is compared against the pin's base, i.e.
# on (channel, N) only.
# ---------------------------------------------------------------------------
vkey() {
  awk -v t="$1" -v pin="$2" '
    function rank(c) { return (c == "alpha") ? 1 : (c == "beta") ? 2 : (c == "rc") ? 3 : 9 }
    BEGIN {
      if (t ~ /^[0-9]/) { split(t, p, "-"); split(p[1], b, ".") }
      else              { split(pin, q, "-"); split(q[1], b, ".") }   # bare: borrow the pin base
      sub(/^[^-]*-/, "", t)
      split(t, s, ".")
      printf "%06d.%06d.%06d.%d.%08d\n", b[1], b[2], b[3], rank(s[1]), s[2]
    }'
}
PIN_KEY=$(vkey "$PIN" "$PIN")

# ---------------------------------------------------------------------------
# A2 / A3 / A4
# ---------------------------------------------------------------------------
while IFS='|' read -r f ln cls tok; do
  case "$cls" in
    malformed)
      err "$f:$ln documents '$tok', which is not a usable version token — a stray character next to a version. Nothing resolves for that string."
      ;;
    current)
      # A2 — an unmarked token is a claim about NOW.
      if [ "$tok" != "$PIN" ]; then
        err "$f:$ln claims version \`$tok\` as current, but the poms pin \`$PIN\`. Either bump it, or — if it marks the version a behaviour was INTRODUCED in — reword it as provenance (\"since \`$tok\`\", \"before \`$tok\`\", \"from \`$tok\` onward\") so the next release does not have to guess."
      fi
      ;;
    provenance|bare)
      # A3 / A4 — history may be old, never newer than the pin.
      k=$(vkey "$tok" "$PIN")
      if [ "$k" \> "$PIN_KEY" ]; then
        err "$f:$ln refers to \`$tok\`, which is NEWER than the pinned \`$PIN\`. That is impossible history — nothing can have been introduced in a release this repo does not depend on."
      fi
      ;;
  esac
done < "$RECORDS"

# ---------------------------------------------------------------------------
# A5 — vacuity guards.
#
# A renamed file must FAIL, not be skipped: the silent skip is how a gate stops
# detecting while its green check still reads as proof. And a guide that has
# been reworded until it no longer states any current version has stopped being
# checked, which is the same failure wearing a different hat.
# ---------------------------------------------------------------------------
# examples/README.md is deliberately absent: it is provenance-only today, and
# demanding a current stamp there would be demanding a stamp nobody wants.
REQUIRED="README.md GETTING_STARTED.md CQELS-QL_SPEC.md SUPPLY_CHAIN.md mcp-server/README.md examples/pom.xml mcp-server/pom.xml"
for f in $REQUIRED; do
  if [ ! -f "$f" ]; then
    err "$f is required to state the current version and is missing — refusing to skip it silently."
    continue
  fi
  c=$(awk -F'|' -v f="$f" '$1 == f && $3 == "current"' "$RECORDS" | wc -l | tr -d ' ')
  if [ "$c" -eq 0 ]; then
    err "$f states no current version at all — it has been reworded out of this gate's reach, so the check would pass vacuously."
  fi
done

# ---------------------------------------------------------------------------
# A6 — tool / resource / prompt surface, self-consistency.
#
# This half is OFFLINE and cannot catch the defect that motivated it: the docs
# were 24/24/24 self-consistent against a 25-tool server. It is still worth
# running, because it catches the cheaper drift — a row added without bumping
# the heading, or mcp-server/README.md updated while README.md is not. The
# reality half is C1, under --deep.
# ---------------------------------------------------------------------------
MCPDOC=mcp-server/README.md
if [ -f "$MCPDOC" ] && [ -f README.md ]; then
  # Declared count in the section heading.
  head_n=$(sed -n 's/^## Tools exposed (\([0-9][0-9]*\)).*/\1/p' "$MCPDOC")
  if [ -z "$head_n" ]; then
    err "$MCPDOC has no '## Tools exposed (N)' heading — the declared tool count has been reworded away, so the count check would pass vacuously."
  else
    # Rows in the tables under that heading, up to the next '## '. Anchored at
    # the line start so a backticked name inside a description cell is not a row.
    rows=$(awk '/^## Tools exposed \(/ { on=1; next } on && /^## / { on=0 } on && /^\| `/ { n++ } END { print n+0 }' "$MCPDOC")
    if [ "$rows" -ne "$head_n" ]; then
      err "$MCPDOC says 'Tools exposed ($head_n)' but the tables under it list $rows tools. A tool was added or removed and the heading was not updated."
    fi
    # Cross-file: README.md's prose claim.
    claims=$(grep -oE '[0-9]+ tools' README.md | awk '{print $1}' | sort -u)
    if [ -z "$claims" ]; then
      err "README.md makes no 'N tools' claim any more — the cross-file half of this check would pass vacuously. Restore the claim or delete this assertion deliberately."
    else
      for c in $claims; do
        [ "$c" -eq "$head_n" ] || err "README.md advertises '$c tools' but $MCPDOC enumerates $head_n. The two landing pages disagree."
      done
    fi
  fi

  # Same shape for resources and prompts — the same defect class, one section
  # further down, and previously covered by nothing at all.
  res_n=$(sed -n 's/^\*\*Resources (\([0-9][0-9]*\) + 1 template).*/\1/p' "$MCPDOC")
  if [ -z "$res_n" ]; then
    err "$MCPDOC has no '**Resources (N + 1 template)**' declaration — the resource count check would pass vacuously."
  else
    listed=$(awk '/^\*\*Resources \(/ { on=1 } on { print; if (/^$/ && seen) exit; seen=1 } ' "$MCPDOC" \
      | grep -oE '`cqels://[^`]+`' | grep -v '{' | sort -u | wc -l | tr -d ' ')
    [ "$listed" -eq "$res_n" ] || err "$MCPDOC declares $res_n resources but lists $listed concrete \`cqels://\` URIs."
    rclaim=$(grep -oE '[0-9]+ resources' README.md | awk '{print $1}' | sort -u)
    if [ -z "$rclaim" ]; then
      err "README.md makes no 'N resources' claim any more — cross-file resource check would pass vacuously."
    else
      for c in $rclaim; do
        [ "$c" -eq "$res_n" ] || err "README.md advertises '$c resources' but $MCPDOC declares $res_n."
      done
    fi
  fi

  pr_n=$(sed -n 's/^\*\*Prompts (\([0-9][0-9]*\)).*/\1/p' "$MCPDOC")
  if [ -z "$pr_n" ]; then
    err "$MCPDOC has no '**Prompts (N)**' declaration — the prompt count check would pass vacuously."
  else
    plisted=$(awk '/^\*\*Prompts \(/ { on=1 } on { print; if (/^$/ && seen) exit; seen=1 }' "$MCPDOC" \
      | grep -oE '`[a-z_]+`' | sort -u | wc -l | tr -d ' ')
    [ "$plisted" -eq "$pr_n" ] || err "$MCPDOC declares $pr_n prompts but lists $plisted names."
    pclaim=$(grep -oE '[0-9]+ prompt' README.md | awk '{print $1}' | sort -u)
    if [ -z "$pclaim" ]; then
      err "README.md makes no 'N prompt' claim any more — cross-file prompt check would pass vacuously."
    else
      for c in $pclaim; do
        [ "$c" -eq "$pr_n" ] || err "README.md advertises '$c prompt templates' but $MCPDOC declares $pr_n."
      done
    fi
  fi
fi

if [ "$fail" -eq 0 ]; then
  cur=$(awk -F'|' '$3 == "current"' "$RECORDS" | wc -l | tr -d ' ')
  pro=$(awk -F'|' '$3 == "provenance" || $3 == "bare"' "$RECORDS" | wc -l | tr -d ' ')
  ok "$cur current version stamps all equal $PIN; $pro provenance references all <= $PIN"
else
  exit 1
fi

# ---------------------------------------------------------------------------
# B — the online tier.
#
# Three-way outcome, deliberately. 404 is a documentation defect. 2xx/3xx is a
# pass. Anything else — 403, 429, 5xx, DNS, timeout — is INCONCLUSIVE, because
# treating a GitHub hiccup as a doc defect teaches people to re-run until green,
# and treating it as a pass means the check can succeed having verified nothing.
#
# curl, not the GitHub API: the API is 60 requests/hour per IP unauthenticated
# and shared across every job on a hosted runner, which is a flake generator.
# The HTML and raw endpoints are not API-rate-limited.
# ---------------------------------------------------------------------------
inconclusive=0

probe() { # probe <url> <why>
  local url=$1 why=$2 code rc
  code=$(curl -sIL -o /dev/null -w '%{http_code}' --max-time 20 --retry 3 --retry-all-errors "$url" 2>/dev/null)
  rc=$?
  if [ "$rc" -ne 0 ] || [ -z "$code" ] || [ "$code" = "000" ]; then
    echo "??:   INCONCLUSIVE $url ($why) — could not reach it (curl exit $rc). Transient network trouble is not evidence of a documentation defect; re-run."
    inconclusive=1
    return
  fi
  case "$code" in
    2??|3??) ok "$code $url ($why)" ;;
    404|410)
      err "$url is HTTP $code — $why. This repository publishes that link and it does not resolve. If the release has not been published yet, publish it first and bump afterwards."
      ;;
    *)
      echo "??:   INCONCLUSIVE $url ($why) — HTTP $code after retries. Transient network trouble is not evidence of a documentation defect; re-run."
      inconclusive=1
      ;;
  esac
}

if [ "$ONLINE" -eq 1 ]; then
  echo "-- online: doc-embedded links --"
  # B1 — every absolute URL that sits on a line carrying a CURRENT token. Found
  # by the same scanner that enforces A2, so a new doc link is covered the day
  # it is written, with no list to keep in sync.
  awk -F'|' -v pin="$PIN" '$3 == "current" && $1 ~ /\.md$/ { print $1 "|" $2 }' "$RECORDS" \
    | sort -u \
    | while IFS='|' read -r f ln; do
        sed -n "${ln}p" "$f" \
          | grep -oE 'https?://[^ )>"'"'"'`]+' \
          | sed 's/[.,;:]*$//' \
          | grep -F "$PIN" || true
      done | sort -u > "$WORK/urls"

  if [ ! -s "$WORK/urls" ]; then
    err "no version-bearing URL was found on any current-stamp line — the online tier would check nothing."
  fi
  while IFS= read -r u; do probe "$u" "linked from a current-version line"; done < "$WORK/urls"

  echo "-- online: pin-derived channels --"
  # B2 — channels the docs BUILD from a shell variable, which B1 cannot see
  # because no literal token appears in them. Each carries an anti-drift guard:
  # the base literal must still be present in the document it stands for, so a
  # doc that migrates to a new host fails loud instead of leaving this gate
  # cheerfully probing a channel nobody reads any more.
  derived() { # derived <doc> <base-literal> <full-url> <why>
    if ! grep -qF "$2" "$1"; then
      err "$1 no longer contains '$2' — this gate was probing a channel that document has stopped naming. Update the template in B2 to match the doc."
      return
    fi
    probe "$3" "$4"
  }
  GH=https://github.com/cqels/CQELS4J/releases
  MAVEN=https://raw.githubusercontent.com/cqels/maven/main/releases
  derived README.md        "$GH/"          "$GH/tag/v$PIN"                                  "the release this repo tells consumers to use"
  derived SUPPLY_CHAIN.md  "$GH/download/" "$GH/download/v$PIN/cqels-mcp-$PIN-shaded.jar"   "the shaded server jar, the only credential-free route documented"
  derived SUPPLY_CHAIN.md  "$MAVEN/supply-chain/" "$MAVEN/supply-chain/$PIN/SHA256SUMS"     "the signed manifest the verification snippet fetches"
  derived GETTING_STARTED.md "$MAVEN"      "$MAVEN/org/cqels/cqels-engine/$PIN/cqels-engine-$PIN.pom" "the artifact the documented <dependency> resolves to"

  [ "$fail" -eq 0 ] || exit 1
  if [ "$inconclusive" -eq 1 ]; then
    echo "::warning::at least one channel could not be reached. Nothing here is evidence of a documentation defect — re-run."
    exit 3
  fi
fi

# ---------------------------------------------------------------------------
# C1 — the deep tier: what the server actually advertises.
#
# The only check that can catch defect 3. Builds mcp-server against the pinned
# org.cqels:cqels-mcp — its single dependency — and drives one stdio JSON-RPC
# session (initialize -> tools/list -> resources/list -> prompts/list), the same
# seam mcp-server/scripts/smoke.sh already exercises.
#
# Compares NAME SETS, not counts: a rename that keeps the count stable is
# exactly as wrong as a missing row, and only the set difference names the
# culprit.
#
# Caveat worth knowing: this measures the LAUNCHER-embedded surface. The docs
# claim it is "the full production tool surface", which holds only while the
# launcher stays a thin pass-through over the published jar. If it ever starts
# filtering or adding tools, this measurement and that claim quietly diverge.
#
# The advertised surface is read with a real JSON parser, not grep. Grep was
# tried first and produced a FALSE POSITIVE: an MCP prompts/list frame carries
# `"name"` twice — once for the prompt and once for every argument it declares —
# so eleven argument names were reported as undocumented prompts. A gate that
# cries wolf on correct documentation gets ignored. The deep tier therefore
# needs python3 in addition to java and maven; it already needs both.
#
# VTG_DEEP_CAPTURE exists so the self-test can drive this comparison against a
# recorded transcript without a network or a JVM. CI never sets it.
# ---------------------------------------------------------------------------
if [ "$DEEP" -eq 1 ]; then
  echo "-- deep: advertised surface --"
  TRANSCRIPT="$WORK/transcript"
  if [ -n "${VTG_DEEP_CAPTURE:-}" ]; then
    cp "$VTG_DEEP_CAPTURE" "$TRANSCRIPT"
    echo "note: using recorded transcript $VTG_DEEP_CAPTURE (self-test path)"
  else
    ( cd mcp-server && mvn -q -B package ) || {
      echo "??:   INCONCLUSIVE — mcp-server would not build against $PIN. That is a build or artifact-resolution problem, not a documentation defect. Re-run."
      exit 3
    }
    # Driving the session needs BOTH properties, and each was learned by getting
    # it wrong:
    #
    #   * stdin must stay OPEN. Feeding the requests from a file EOFs stdin
    #     immediately and the server shuts down before answering anything — the
    #     transcript came back empty.
    #   * the server must be killable BY PID. It does not exit when stdin
    #     finally closes, so `{ … } | java …` never returns and the gate hangs
    #     forever.
    #
    # A FIFO gives both: a background writer holds the pipe open, and java is a
    # plain background job whose pid we own.
    FIFO="$WORK/rpc.in"
    mkfifo "$FIFO"
    {
      printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"version-truth-gate","version":"1.0"}}}'
      sleep 3
      printf '%s\n' '{"jsonrpc":"2.0","method":"notifications/initialized"}'
      printf '%s\n' '{"jsonrpc":"2.0","id":3,"method":"tools/list"}'
      printf '%s\n' '{"jsonrpc":"2.0","id":4,"method":"resources/list"}'
      printf '%s\n' '{"jsonrpc":"2.0","id":5,"method":"prompts/list"}'
      printf '%s\n' '{"jsonrpc":"2.0","id":6,"method":"resources/templates/list"}'
      sleep 60
    } > "$FIFO" &
    WRITER=$!
    : > "$TRANSCRIPT"
    java -jar mcp-server/target/cqels-mcp-server.jar < "$FIFO" > "$TRANSCRIPT" 2>/dev/null &
    SRV=$!
    for _ in $(seq 1 90); do grep -q '"id":6' "$TRANSCRIPT" 2>/dev/null && break; sleep 1; done
    kill "$SRV" "$WRITER" 2>/dev/null
    wait "$SRV" 2>/dev/null
    wait "$WRITER" 2>/dev/null
  fi

  if ! python3 - "$TRANSCRIPT" "$WORK" <<'PY'
import json, os, sys
transcript, out = sys.argv[1], sys.argv[2]
found = {}
for line in open(transcript, encoding='utf-8', errors='replace'):
    line = line.strip()
    if not line.startswith('{'):
        continue
    try:
        frame = json.loads(line)
    except ValueError:
        continue
    result = frame.get('result') or {}
    for key, field in (('tools', 'name'), ('prompts', 'name'), ('resources', 'uri'),
                       ('resourceTemplates', 'uriTemplate')):
        entries = result.get(key)
        if isinstance(entries, list):
            # Only the TOP-LEVEL name/uri of each entry. Nested argument and
            # property names are not part of the advertised surface.
            found[key] = sorted({e[field] for e in entries
                                 if isinstance(e, dict) and isinstance(e.get(field), str)})
for key in ('tools', 'prompts', 'resources'):
    with open(os.path.join(out, 'adv-' + key), 'w') as fh:
        for value in found.get(key, []):
            if '{' not in value:          # URI templates are counted separately
                fh.write(value + '\n')
# Templates are their own surface, discovered by their own method. An earlier
# revision filtered every '{'-bearing URI out of resources/list and compared
# templates against NOTHING, so deleting the documented
# cqels://queries/{queryId}/results template still reported the surface as
# matching (found by codex review).
with open(os.path.join(out, 'adv-resource-templates'), 'w') as fh:
    for value in found.get('resourceTemplates', []):
        fh.write(value + '\n')
absent = [k for k in ('tools', 'prompts', 'resources', 'resourceTemplates') if k not in found]
if absent:
    sys.stderr.write('no %s frame in the transcript\n' % ', '.join(absent))
    sys.exit(9)
PY
  then
    echo "??:   INCONCLUSIVE — the server did not answer every discovery call. Not a documentation defect; re-run."
    exit 3
  fi

  # Advertised vs documented, as SETS. A rename that keeps the count stable is
  # exactly as wrong as a missing row, and only a set difference names the
  # culprit.
  compare_set() { # compare_set <label> <advertised-file> <documented-file>
    local label=$1 adv=$2 doc=$3 undocumented unadvertised
    undocumented=$(comm -13 "$doc" "$adv" | tr '\n' ' ')
    unadvertised=$(comm -23 "$doc" "$adv" | tr '\n' ' ')
    if [ -n "$undocumented" ] || [ -n "$unadvertised" ]; then
      err "$MCPDOC's $label list does not match what the server advertises.${undocumented:+ ADVERTISED BUT UNDOCUMENTED: $undocumented}${unadvertised:+ DOCUMENTED BUT NOT ADVERTISED: $unadvertised}"
    else
      ok "$label: $(wc -l < "$adv" | tr -d ' ') advertised, all documented"
    fi
  }

  awk '/^## Tools exposed \(/ { on=1; next } on && /^## / { on=0 } on && /^\| `/ { gsub(/`/,"",$2); print $2 }' "$MCPDOC" | sort -u > "$WORK/doc-tools"
  compare_set tools "$WORK/adv-tools" "$WORK/doc-tools"

  awk '/^\*\*Resources \(/ { on=1 } on { print; if (/^$/ && seen) exit; seen=1 }' "$MCPDOC" \
    | grep -oE 'cqels://[^`]+' | grep -v '{' | sort -u > "$WORK/doc-resources"
  compare_set resources "$WORK/adv-resources" "$WORK/doc-resources"

  # The '{'-bearing URIs in the SAME documented section are the templates —
  # compared against resources/templates/list, never silently dropped.
  awk '/^\*\*Resources \(/ { on=1 } on { print; if (/^$/ && seen) exit; seen=1 }' "$MCPDOC" \
    | grep -oE 'cqels://[^`]+' | grep '{' | sort -u > "$WORK/doc-resource-templates"
  compare_set "resource templates" "$WORK/adv-resource-templates" "$WORK/doc-resource-templates"

  awk '/^\*\*Prompts \(/ { on=1 } on { print; if (/^$/ && seen) exit; seen=1 }' "$MCPDOC" \
    | grep -oE '`[a-z_]+`' | tr -d '`' | sort -u > "$WORK/doc-prompts"
  compare_set prompts "$WORK/adv-prompts" "$WORK/doc-prompts"

  [ "$fail" -eq 0 ] || exit 1
fi

echo "OK: every version claim in this repository is true$([ "$ONLINE" -eq 1 ] && echo ", and every channel it names resolves")$([ "$DEEP" -eq 1 ] && echo ", and the documented surface is the advertised one")."
