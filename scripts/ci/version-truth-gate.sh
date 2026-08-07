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
#     It is the residual of a marker that GOVERNS the token; a marker governing
#     a different noun ("tested before release: `X`") is not exempt — see P1.
#   * The same rule cuts the other way, and that direction is deliberate: a
#     marker reaches its token through filler only, so an old version several
#     ordinary words downstream — "Before `2.0.0-alpha.16`, the manifest listed
#     cqels-engine-2.0.0-alpha.13.jar" — is judged on its own and fires as a
#     current claim. Reaching it needs a plain word-distance window, which is
#     exactly what let "**Tested before release:** `X`" pass as provenance. The
#     remedy for a real sentence of that shape is the BARE form ("the alpha.13
#     jar"), which is checked against the upper bound only. Pinned both ways in
#     the self-test.
#   * A reference to a WITHDRAWN version ("since 2.0.0-alpha.17", a version that
#     was never publicly consumable) passes the offline tier, which cannot know
#     the withdrawn set. The online tier only probes channels derived from the
#     pin.
#   * Tokens are matched CASE-INSENSITIVELY and reported as written. A stamp
#     spelled `2.0.0-Alpha.13` used to match nothing at all — not current, not
#     bare, not malformed — so a stale stamp in that spelling passed silently
#     while the vacuity guard stayed satisfied by the other stamps in the file.
#   * A6 reads README.md's surface counts in their EMPHASISED form only —
#     **25 tools**, **9 resources**, **10 prompt templates**. That is a doc
#     convention the gate depends on, and it is deliberate: an unanchored scan
#     turned every number-plus-noun in running prose into a competing
#     landing-page claim and blocked merge with "the two landing pages disagree"
#     about docs that were correct. Unbolding a claim does not disable the
#     check — the vacuity guard fires instead.
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

# Every rule in this gate is ASCII — the version grammar, the marker words, the
# markdown block syntax — but the LOCALE is not, and on the platform RELEASING.md
# tells maintainers to run all three tiers on (macOS) it changes awk's behaviour
# in two silent ways (round 5):
#
#   * ONE non-UTF-8 byte in a tracked guide ABORTS the whole file. macOS awk
#     dies with "towc: multibyte conversion failure" on the first gsub/match of
#     a CP1252 line, so the file contributed ZERO records — not one line, all of
#     them — and a stale current stamp inside it passed with exit 0 and a stamp
#     count byte-identical to a clean run. CI never saw it: ubuntu's mawk is
#     byte-oriented, so the local pre-tag run was the one checking nothing.
#   * tolower() there is multibyte-aware and NOT length-preserving: "İ" (U+0130)
#     lowercases to one byte. The scanner matches on a lowercased copy and takes
#     its offsets from it — a documented invariant ("same length, so every offset
#     still indexes the original") that simply is not true in a UTF-8 locale — so
#     a line holding one such character reported its CORRECT stamp as a malformed
#     token, and no edit to the version could clear it.
#
# C makes every tool here byte-oriented, which is what the rules already assume.
# The exit-status check on scan_file below is the backstop for anything else
# that can kill an awk pass.
export LC_ALL=C

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
  # An unreadable value must NOT fall through to the "$PIN is still empty"
  # first-iteration sentinel: an element split across lines passes the count
  # guard above, yields nothing here, and the SECOND pom then silently became
  # the pin — after which the gate printed "pin is X, identical in both poms"
  # about two poms that were not (codex round 3).
  if [ -z "$v" ]; then
    echo "::error::$pom declares <cqels.version> but no value could be read from it — the element must open, hold the version and close on ONE line. Refusing to guess the pin." >&2
    exit 2
  fi
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

# The scanned path reaches awk through the ENVIRONMENT, not through `-v`. POSIX
# requires a `-v` assignment to be processed for ESCAPE SEQUENCES, so a tracked
# path holding a literal backslash — `a\174b.md` — arrived inside awk as
# `a|b.md` and synthesised the record delimiter AFTER the raw-path guard below
# had passed it: the record mis-split, `cls` matched no arm, and a stale stamp
# was dropped in total silence (round 4). A `\n` spelling of the same trick made
# the gate report a defect against a filename that does not exist. ENVIRON is
# handed the bytes as they are.
scan_file() {
  VTG_SCAN_FILE="$1" awk '
    BEGIN { FILE = ENVIRON["VTG_SCAN_FILE"] }
    function norm(s) { gsub(/[^A-Za-z0-9]+/, " ", s); sub(/^ +/, "", s); sub(/ +$/, "", s); return s }

    # One referent = one word. A URL is a link TARGET, not prose: its dots are
    # not clause ends and its path segments are not words of the sentence. A
    # version token is one word, not the six fragments norm() makes of
    # "2 0 0 alpha 13". Both are collapsed before any clause or window rule,
    # because the repo house style for a version mention is a link to its tag —
    # "since [`X`](.../tag/vX)" — and without this the URL copy of the token
    # was classified CURRENT and a TRUE provenance line fired (codex round 3).
    #
    # The punctuation that ENDS the sentence a URL sits in is NOT part of the
    # URL. `[^ ]+` runs to the next space, so the ").", "," or ";" that closes a
    # house-style link — "…since [`X`](…/tag/vX). CQELS `Y` is required" — was
    # deleted along with the target. The clause truncation below then found no
    # boundary and the previous sentence marker governed the stamp in the NEXT
    # sentence: the gate reported OK on the exact stale-landing-page defect it
    # exists to catch (round 2). The trailing punctuation run is therefore
    # handed back to the prose.
    #
    # A RELATIVE markdown target is a link target too, and this recogniser only
    # knew absolute ones — so "From [`X`](CHANGELOG.md) onward" left the path in
    # the prose, the "onward" was no longer adjacent to the token, and a TRUE
    # P2 line fired as a stale current stamp with no rewrite of that wording
    # clearing it (round 4). inurl() learned the same lesson one round earlier;
    # this is the same rule for the prose side. A `](…)` match is delimited by
    # its own closing paren, so nothing is handed back for it — the "." or ","
    # that follows the link is outside the match and stays in the sentence.
    function stripurls(s,   out, url, tail) {
      out = ""
      while (match(s, /\]\([^ )]*\)|https?:\/\/[^ ]+/)) {
        url = substr(s, RSTART, RLENGTH)
        tail = ""
        if (url !~ /^\]\(/) {
          while (url ~ /[.,;:!?)\]}]$/) {
            tail = substr(url, length(url), 1) tail
            url = substr(url, 1, length(url) - 1)
          }
        }
        out = out substr(s, 1, RSTART - 1) " " tail
        s = substr(s, RSTART + RLENGTH)
      }
      return out s
    }

    # A masked token keeps its IDENTITY: "2.0.0-alpha.13" becomes the single
    # word "v200alpha13". The old mask was the literal " VERSION ", which
    # tolower()s to "version" — a member of the P1 filler set — so EVERY version
    # token was transparent to the marker walk and one marker exempted an
    # unrelated stamp later in the same clause ("Stable since `X`, `Y` remains
    # the current release", or a "| Introduced | Current |" matrix row). The
    # two shapes that legitimately put a token between a marker and its object
    # are handled explicitly in classify(): the same token repeated as a link
    # target, and "since `X` and `Y`" (round 2).
    function flat(s) { gsub(/[^A-Za-z0-9]/, "", s); return tolower(s) }

    # An HTML COMMENT is invisible in the rendered page, so nothing inside one
    # may govern a token — the same rule continuous() applies to the line ABOVE
    # (round 3), which is where it was applied and ONLY where. On its own line a
    # comment was ordinary prose: norm() flattens "<!-- since -->" to the word
    # "since", and the in-line clause truncation cannot sever it because the only
    # [.!?;] in the span is the "!" of "<!--", which sits in FRONT of the marker
    # — so truncating there DISCARDS the real label and KEEPS the invisible word.
    # "**Latest release:** <!-- since --> `2.0.0-alpha.13`" was reported OK with
    # the rendered badge reading alpha.13 under an alpha.16 pin (round 5). The
    # same truncation cut the other way too, on ordinary documentation: a comment
    # between a genuine marker and its token ("supported since <!-- see CHANGELOG
    # --> `X`") threw the "since" away and fired on a true provenance line.
    # An unterminated "<!--" runs to the end of the string, which is how markdown
    # reads it.
    function stripcomments(s,   out, p) {
      out = ""
      while ((p = index(s, "<!--")) > 0) {
        out = out substr(s, 1, p - 1) " "
        s = substr(s, p + 4)
        p = index(s, "-->")
        if (p == 0) return out
        s = substr(s, p + 3)
      }
      return out s
    }

    function prep(s,   out, tok) {
      s = stripcomments(s)
      s = stripurls(s)
      out = ""
      while (match(s, /[0-9]+\.[0-9]+\.[0-9]+-[A-Za-z]+\.[0-9]+/)) {
        tok = substr(s, RSTART, RLENGTH)
        out = out substr(s, 1, RSTART - 1) " v" flat(tok) " "
        s = substr(s, RSTART + RLENGTH)
      }
      return out s
    }

    # Is the token at absolute offset ABS inside a LINK TARGET on this line?
    #
    # A markdown target `](…)` counts whether or not it is an absolute URL. Both
    # halves of the recogniser used to be anchored on https?://, so the target
    # copy of a token in a RELATIVE link — "since [`X`](CHANGELOG-X.md)", the
    # natural shape for a per-version changelog — was neither masked out of the
    # prose nor eligible for the same-token inheritance below. Judged as prose,
    # the path word ("CHANGELOG") breaks the marker walk, so a TRUE provenance
    # line fired and no rewrite short of renaming the linked file cleared it
    # (round 3).
    function inurl(line, abs,   p, st, en) {
      p = 1
      while (match(substr(line, p), /https?:\/\/[^ ]+|\]\([^ )]*\)/)) {
        st = p + RSTART - 1
        en = st + RLENGTH - 1
        if (abs >= st && abs <= en) return 1
        p = en + 1
      }
      return 0
    }

    # PROVENANCE iff a marker word governs the token.
    #   P1  since | before | prior to   <version-qualifying filler>  TOKEN
    #   P2  from  <filler>  TOKEN  onward(s)
    #   P3  TOKEN  is|was the first release
    function classify(before, after, tok,   b, a, n, w, i, lw, self, coord, nounobj, frm) {
      before = prep(before); after = prep(after)

      # A marker only governs a token in ITS OWN clause. norm() strips every
      # punctuation mark, so without this truncation a marker in a NEIGHBOURING
      # clause of the same line exempts a stale stamp. Both directions were
      # reported OK by the gate on a false claim:
      #     Nothing changed since launch; release `2.0.0-alpha.13`      (before)
      #     The current release is `2.0.0-alpha.13`. Moving onwards, …  (after)
      # (codex round 2 for the before side, round 3 for the after side, which
      # the round-2 fix left untouched.) Everything before the LAST mark, and
      # everything after the FIRST one, is discarded. This is safe only because
      # prep() ran first: the dots inside a version token and a URL are not
      # clause ends, and truncating on them cut the marker away from its OWN
      # token. A colon is deliberately NOT a truncator: it introduces what
      # follows ("Available since: `X`") rather than ending a clause.
      sub(/.*[.!?;]/, "", before)
      sub(/[.!?;].*/, "", after)
      # A colon or comma does not END the clause, but it does end a LABEL or a
      # lead-in, and norm() strips both marks before the walk can see them. That
      # left the walk with position alone to tell the label shape "**Tested
      # before release:** `X`" (current) from the historical opener "Before
      # release `X`, …" (provenance) — so putting anything at all in front of
      # the opener made it look like the label and fired on true history:
      # "**History:** Before release `X`, …", "Note: Before release `X`, …",
      # "In CQELS, before release `X`, …", and every opener under a lead-in line
      # ending in a colon, which continuous() joins (round 4). Bold labels and
      # colon-introduced paragraphs are ordinary changelog structure. The mark
      # is kept as the word "vtgsep" so the walk can ask what sits immediately
      # in front of the marker rather than merely whether ANYTHING does.
      # (any literal spelling of the sentinel in the prose is defaced first, so
      # it cannot be typed into a document to forge a label boundary)
      gsub(/[Vv][Tt][Gg][Ss][Ee][Pp]/, "vtgsepx", before)
      gsub(/[:,]/, " vtgsep ", before)
      # The forward markers are matched on a LOWERCASED copy, exactly as the
      # backward ones already were (lw = tolower). Case-sensitive P2/P3 fired on
      # the sanctioned wording of this very gate in title case — "## Breaking
      # Changes From `X` Onward" — and the remedy it printed was to reword to
      # "from `X` onward", which the heading already said (round 2).
      b = norm(before); a = tolower(norm(after))
      self = "v" flat(tok)

      # A token whose OWN clause predicates it as the current one IS a current
      # claim, whatever marker precedes it. Without this, the two transparencies
      # the backward walk needs for legitimate shapes — a coordination ("since
      # `X` and `Y` respectively") and a repeated token — carried a marker across
      # a clause that says the opposite in words: "Stable since `X` and `Y` is
      # the current release" was reported OK on a stale `Y` (round 3). Neither
      # transparency can see what FOLLOWS the token; this rule can, and it is the
      # one wording no provenance line ever uses about itself.
      #
      # PRESENT tense only. "was|were the latest" is not a claim about now, it is
      # how history describes a SUPERSEDED release, so the past-tense arm fired
      # on the plainly true "Before `X` was the latest release, checkpoint files
      # carried no version header" and the only edit that silenced it — bumping
      # the token, which is what the error message asks for — made the sentence
      # false (round 4). The arm was never load-bearing: all four cases that
      # exercise this rule are present tense.
      if (a ~ /^(is|are|remains?|stays?) the (current|latest|newest)( |$)/) return "current"

      # P1: the marker must GOVERN the token — only version-qualifying filler
      # may sit between the two. A plain word-distance window instead of this
      # classified "**Tested before release:** `2.0.0-alpha.13`" as provenance,
      # where `before` governs "release" and the stamp is an ordinary current
      # claim (codex round 3). The filler set is measured, not guessed: every
      # provenance line in this repo has 0-2 intervening words, and all of them
      # are "CQELS" or the javadoc `{@code X}` wrap.
      #
      # Two kinds of filler are conditional, because unconditional they let a
      # marker reach a token it does not govern (round 2):
      #   * ANOTHER version token is transparent only when it is the SAME token
      #     repeated as a link target ("since [`X`](…/tag/vX)", the house style)
      #     or when a coordination carries it ("since `X` and `Y`", one marker
      #     governing two). A bare comma, pipe or em-dash between two DIFFERENT
      #     tokens does not: "Stable since `X`, `Y` remains the current release"
      #     and "| since `X` | `Y` |" are a marked token plus an unmarked one.
      #   * "release" is the object of `before` in the label shape "**Tested
      #     before release:** `X`" — a current stamp — but is transparent for
      #     `since`, where "since release `X`" names the release itself. That
      #     poison must not swallow the standard historical opener "Before
      #     release `X`, <old behaviour>" / "Prior to CQELS release `X`, …",
      #     which fired on a true sentence and printed a remedy the author had
      #     already written (round 3). The two are told apart by position: the
      #     label shape has a subject in front of the marker ("**Tested** before
      #     release:"), the opener starts the clause. So `release` only poisons a
      #     marker that something else precedes — and "precedes" means a WORD of
      #     the same clause, not a label or lead-in that ended at a colon or a
      #     comma, which is the `vtgsep` test (round 4).
      n = split(b, w, " ")
      coord = 0; nounobj = 0; frm = 0
      for (i = n; i >= 1; i--) {
        lw = tolower(w[i])
        if (lw == "since") return "provenance"
        if (lw == "before") { if (nounobj && i > 1 && w[i-1] != "vtgsep") break; return "provenance" }
        if (lw == "to" && i > 1 && tolower(w[i-1]) == "prior") {
          if (nounobj && i > 2 && w[i-2] != "vtgsep") break
          return "provenance"
        }
        if (lw == "from") { frm = 1; break }
        if (lw == "and" || lw == "or") { coord = 1; continue }
        # A masked version token is transparent ONLY when a coordination carries
        # it ("since `X` and `Y`"). The same token repeated used to be
        # transparent too, for the house-style link "since [`X`](…/tag/vX) — but
        # that copy is a link TARGET and is handled by the same-token inheritance
        # below, which is clause-scoped where this walk-through was not: the
        # repetition path let a marker reach a stamp in a LATER clause of the
        # same sentence ("Documented since `X`, `X` is the current release" was
        # reported OK, round 3).
        if (lw ~ /^v[0-9]/) {                       # a masked version token
          if (coord) { coord = 0; continue }
          break
        }
        # The article is filler like any other version-qualifying word. Without
        # it the walk broke on "the" and the most natural English spelling of
        # every marker — "since the `X` release", "since the release `X`",
        # "before the `X` release", "prior to the `X` release" — fired as a
        # stale current stamp, while the article-free spelling of the same
        # sentence passed (round 4). It defended nothing: the whole suite stays
        # green with it added.
        if (lw == "cqels" || lw == "code" || lw == "version" || lw == "v" || lw == "the") { coord = 0; continue }
        if (lw == "release" && !nounobj) { nounobj = 1; continue }
        # The label/lead-in mark itself is transparent to the walk — it is only
        # consulted at the marker, above. Opaque, it broke "Available since: `X`"
        # (a must-not-fire case) and every other marker introduced by a colon.
        #
        # It clears `coord` on the way through, exactly as the filler arm does.
        # Preserving it let a coordination reach BACK ACROSS the comma the
        # round-2 fix made opaque: in "stable since `X`, and `Y` is the version
        # to install", the walk read "and" (coord=1), stepped over the comma with
        # coord intact, spent it on the masked `X`, and reached "since" — so
        # comma-plus-conjunction, the ordinary English spelling of a NEW
        # independent clause, exempted an unmarked current claim that the bare
        # comma correctly fires on (round 5). A coordination that really does
        # share one marker ("since `X` and `Y`") has no clause mark in it.
        if (lw == "vtgsep") { coord = 0; continue }
        break
      }
      # P2 / P3 look forward. NOTHING may sit between the token and "onward":
      # a one-word slot let any next-line sentence that opened "Moving onwards,"
      # exempt a stale line-final stamp — and a stamp/badge line ends with the
      # token, so continuous() allowed that join (codex round 3).
      #
      # P2 also requires the "from" HALF of "from X onward" to be present. The
      # marker word is the pair, not "onward" alone: without the backward half,
      # any stale stamp on a line that does not end in a clause mark was
      # exempted by a next line merely BEGINNING with the word — "Current
      # release: `2.0.0-alpha.13`:" / "onward compatibility notes follow."
      # (round 2). Every true P2 site in this repo is written "from `X` onward".
      if (frm && a ~ /^onwards?( |$)/) return "provenance"
      # The MIRROR of the current-predicate rule above, at the one place it is
      # needed. P3 consults only what FOLLOWS the token, so a clause that names
      # the token in words as the current one was exempted by appending a P3
      # tail: "**Current release:** `X` is the first release with the MCP
      # server" was reported OK on a stale `X` — and that label is the house
      # stamp convention here (README.md:8, GETTING_STARTED.md:7), so the evasion
      # is reachable by ordinary release-note prose, not only by gaming (round
      # 4). It sits here rather than beside its mirror because the backward walk
      # already breaks on "current"/"latest"/"newest" — they are not filler — so
      # P3 is the only rule this nominal ever has to beat.
      # (the trailing `vtgsep` run is the colon of the label itself)
      #
      # The nominal does not have to ABUT the token. End-anchored on the noun,
      # one word of the SAME filler the backward walk steps over — the product
      # name — put the guard and the walk in disagreement about the same
      # vocabulary and reopened the evasion verbatim: "**Current release:** CQELS
      # `2.0.0-alpha.13` is the first release with the MCP server" was reported OK
      # (round 5). The repo writes exactly that shape ("**Applies to:** CQELS
      # `X`", " * CQELS {@code X}"), so the filler run is allowed here too.
      if (tolower(b) ~ /(^| )(current|latest|newest)( release| version)?( (cqels|code|version|v|the|vtgsep))*$/) return "current"
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
      # A trailing colon INTRODUCES the next line ("Available since:") — it is
      # the one clause mark that connects rather than separates, so it does not
      # refuse the join (codex round 2: treating it as a boundary classified a
      # legitimate wrapped historical claim as a current stamp).
      if (p ~ /[.!?;][ \t]*$/) return 0                  # sentence/clause ended
      # An HTML comment is INVISIBLE in the rendered page, and norm() flattens
      # its delimiters to nothing, so "<!-- introduced since CQELS -->" above a
      # stamp governed it as ordinary prose: the published landing page carried a
      # naked stale stamp exempted by text no reader can see (round 3). That also
      # defeats the remedy this gate exists to force, which is to resolve WHERE
      # THE NEXT READER SEES IT. Not markdown-gated — pom.xml puts a comment
      # directly above <cqels.version> today.
      if (p ~ /-->[ \t]*$/) return 0                     # prev is an HTML comment
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

    # What makes a matched token NOT the token it looks like. Returns the
    # offending suffix, or "" when the match stands on its own.
    #
    # A trailing alphanumeric was the only case checked, so "2.0.0-alpha.16.1"
    # and "2.0.0-alpha.16-SNAPSHOT" — neither of which resolves anywhere — were
    # silently truncated to the pin and reported as a correct current stamp,
    # the exact failure the check was written to prevent (codex round 3).
    # `.` and `-` cannot be rejected wholesale: SUPPLY_CHAIN.md carries
    # `cqels-engine-2.0.0-alpha.16.jar` and `…-shaded.jar`, both true. A dot is
    # only suspect when a DIGIT follows it (a sentence period or a file
    # extension never does), and a dash only in front of a literal SNAPSHOT.
    function badsuffix(rest,   n, c, out) {
      if (rest ~ /^[0-9A-Za-z]/) return substr(rest, 1, 1)
      if (toupper(substr(rest, 1, 9)) == "-SNAPSHOT") return "-SNAPSHOT"
      if (rest ~ /^\.[0-9]/) {
        out = ""
        for (n = 1; n <= length(rest); n++) {
          c = substr(rest, n, 1)
          if (c !~ /[0-9.]/) break
          out = out c
        }
        sub(/\.$/, "", out)
        return out
      }
      return ""
    }

    # awk strips the newline, never the CR before it, and every structural rule
    # in continuous() is END-anchored — so on a CRLF-saved file the blank-line,
    # the sentence-end and the HTML-comment refusals all silently stopped
    # matching. The comment one has no backstop (the other two are carried by
    # the in-line clause truncation), so the round-3 defect reopened verbatim:
    # an invisible `<!-- … since … -->` governed the stamp below it and the gate
    # reported OK on a stale landing stamp. CRLF only ever LOOSENS the rules, so
    # the failure direction is always a false negative. There is no
    # .gitattributes, so one guide saved on Windows commits as CRLF and turns
    # the classifier permissive (round 4). The A6 extractors already did this;
    # the scanner did not.
    { sub(/\r$/, ""); L[FNR] = $0 }

    END {
      for (i = 1; i <= FNR; i++) {
        line = L[i]
        prev = (i > 1 && continuous(L[i-1], line)) ? L[i-1] : ""
        nxt  = (i < FNR && continuous(line, L[i+1])) ? L[i+1] : ""
        masked = line
        # Matching is done on a LOWERCASED copy, and every offset taken from it
        # indexes the original — which holds only because LC_ALL=C is forced at
        # the top of this script. In a UTF-8 locale macOS awk lowercases "İ" to a
        # SHORTER byte string, the offsets shift, and a correct stamp downstream
        # of one was reported as a malformed token (round 5). The token is
        # reported as written.
        # A case-sensitive grammar made `2.0.0-Alpha.13` match nothing at all:
        # not current, not bare, not malformed, so a stale stamp in that
        # spelling passed in total silence (codex round 3).
        lline = tolower(line)
        lmasked = lline

        # -- full-form tokens ------------------------------------------------
        nt = 0
        pos = 1
        while (match(substr(lline, pos), /[0-9]+\.[0-9]+\.[0-9]+-(alpha|beta|rc)\.[0-9]+/)) {
          abs = pos + RSTART - 1
          len = RLENGTH
          tok = substr(line, abs, len)
          nt++

          S[nt] = abs; E[nt] = abs + len - 1
          bad = badsuffix(substr(line, abs + len))
          if (bad != "") {
            T[nt] = tok bad; C[nt] = "malformed"; U[nt] = 0
          } else {
            before = prev " " substr(line, 1, abs - 1)
            after  = substr(line, abs + len) " " nxt
            T[nt] = tok; C[nt] = classify(before, after, tok); U[nt] = inurl(line, abs)
          }

          # Blank the token (same length, so positions stay valid) before the
          # bare-form sweep, so "2.0.0-alpha.16" never also registers as a bare
          # "alpha.16".
          masked  = substr(masked,  1, abs - 1) sprintf("%" len "s", "") substr(masked,  abs + len)
          lmasked = substr(lmasked, 1, abs - 1) sprintf("%" len "s", "") substr(lmasked, abs + len)
          pos = abs + len
        }

        # A URL is a link TARGET. When the SAME token also appears on the line
        # as prose and that copy is provenance, the target is that same claim
        # written twice, so it inherits — prose in between must not change the
        # verdict. "…fixed since `X` — see [the release notes](…/tag/vX)." fired
        # on true history because "see the release notes" broke the marker walk
        # for the URL copy only, and no rewrite short of deleting the link
        # cleared it (round 2). Inheritance is same-token only: a bumped display
        # token beside a stale URL ("[`alpha.18`](…/tag/valpha.16)") is two
        # different claims and the stale one still fires.
        #
        # Inheritance is also CLAUSE-scoped, exactly like the marker walk it
        # overrides. Line-scoped, it reached across a sentence boundary and
        # silenced an independent claim: "…fixed since `X`. Download the latest
        # jar from the [release page](…/tag/vX)." was reported OK — a stale
        # release-tag link on the landing page, which is defect 2 above. The
        # clause truncation had already classified that link correctly; only the
        # inheritance undid it. The gap is measured on the PREPPED text, because
        # the dots inside the link target and inside a version token are not
        # clause ends.
        for (j = 1; j <= nt; j++) {
          if (U[j] && C[j] == "current") {
            for (k = 1; k <= nt; k++) {
              if (U[k] || C[k] != "provenance" || tolower(T[k]) != tolower(T[j])) continue
              if (S[j] > S[k]) { gs = E[k] + 1; gl = S[j] - gs }
              else             { gs = E[j] + 1; gl = S[k] - gs }
              gap = (gl > 0) ? prep(substr(line, gs, gl)) : ""
              if (gap ~ /[.!?;]/) continue
              # A comma is not a clause end, but a comma plus a COORDINATING
              # CONJUNCTION is: it is the ordinary spelling of a second
              # independent clause, and the marker no longer governs anything
              # past it, exactly as at a period. Scoped to [.!?;] alone, "**Since `X` it
              # is attached to the release**, so fetch [the latest shaded jar]
              # (…/download/vX/…)" inherited provenance — a stale landing-page
              # download link, defect 2 above, reported OK (round 5). The comma
              # by itself must NOT break the inheritance: "since `X`, see [the
              # release notes](…/tag/vX)" is one claim written twice, which is
              # the shape this whole rule exists for.
              if (tolower(gap) ~ /,[ \t]*(and|or|but|so|yet|nor)[ \t]/) continue
              C[j] = "provenance"
              break
            }
          }
        }
        for (j = 1; j <= nt; j++) print FILE "|" i "|" C[j] "|" T[j]

        # -- bare tokens (alpha.7) -------------------------------------------
        pos = 1
        while (match(substr(lmasked, pos), /(alpha|beta|rc)\.[0-9]+/)) {
          abs = pos + RSTART - 1
          len = RLENGTH
          bch = (abs > 1) ? substr(lmasked, abs - 1, 1) : " "
          ach = substr(lmasked, abs + len, 1)
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
#
# NUL-delimited, and never the quoted form. `git ls-files` quotes any path
# holding a non-ASCII byte, a control character, a quote or a backslash
# (core.quotePath defaults to ON), emitting the literal text
# "GU\303\215A.md" — for which `[ -f ]` is false, so a translated guide with an
# accent in its name was dropped IN SILENCE and a stale stamp inside it passed
# with exit 0 and an unchanged stamp count (round 2). Quoting a DIRECTORY
# component drops every file beneath it. -z suppresses quoting outright, so the
# bytes of the path arrive here exactly as git holds them.
#
# A record is `<file>|<line>|<class>|<token>` and the records are NEWLINE-
# delimited, so BOTH of those characters are structural: a path holding a `|`
# mis-splits the record, and a path holding a `\n` (or a `\r`, which the reader
# strips the same way) splits ONE record into two physical lines — the orphan
# head matches no `case` arm and is dropped in silence, while the tail line
# starts with whatever followed the newline and is attributed to THAT file. Both
# directions were reproduced (round 5): a `decoy\nREADME.md` satisfied A5's
# per-required-file vacuity guard for a README that had been reworded out of
# scope, and the same trick with a stale stamp reported a defect against a line
# of README.md that holds no version at all. Refused loudly rather than parsed.
SELF="scripts/ci/version-truth-gate.sh|scripts/ci/version-truth-gate.test.sh"
while IFS= read -r -d '' f; do
  case "$f" in
    *"|"*|*$'\n'*|*$'\r'*)
      err "tracked path '$f' contains '|', a newline or a carriage return — the characters this gate's own records are delimited by — so it cannot be scanned. Rename it."
      continue ;;
  esac
  # A tracked path with no file behind it is a submodule gitlink or a
  # working-tree deletion, not a document; the required-file guard in A5 is what
  # notices a rename of something that matters.
  [ -f "$f" ] || continue
  case "|$SELF|" in *"|$f|"*) continue ;; esac
  # `--` because a path is an OPERAND, not an option. Without it grep parsed a
  # top-level `-RELEASE-NOTES.md` as an option bundle, failed with exit 2, and
  # `|| continue` dropped the file with no record, no counter change and no err
  # — a stale stamp inside it passed with "every version claim in this
  # repository is true" (round 5). This is the only place a git-derived path
  # reaches a command in option position: awk and sed take theirs after the
  # program text, where a leading dash is already a filename, and adding `--`
  # to those would break them on BSD.
  grep -Iq . -- "$f" 2>/dev/null || continue     # skip binary
  # awk's exit status was discarded, and awk's own diagnostic goes to stderr
  # without touching `fail` — so any pass that DIES (a non-UTF-8 byte under a
  # UTF-8 locale, a read error) contributed zero records and the gate reported
  # OK over an unscanned file (round 5). LC_ALL=C above removes the known
  # trigger; this is what notices the next one.
  if ! scan_file "$f" >> "$RECORDS"; then
    err "scanning '$f' failed — that file contributed no version records, so every claim in it would have been checked vacuously."
  fi
done < <(git -c core.quotePath=off ls-files -z)

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
      # Tokens are scanned case-insensitively, so the channel must be ranked
      # that way too — otherwise "since 2.0.0-Alpha.11" ranks unknown (9) and
      # true history is reported as impossible.
      t = tolower(t); pin = tolower(pin)
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
      err "$f:$ln documents '$tok', which is not a usable version token — a stray character or suffix next to a version. Nothing resolves for that string."
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
#
# The README claim is read in its BOLD form only. `[0-9]+ tools` scanned the
# whole file, so every number-plus-noun in ordinary prose became a competing
# landing-page claim and any of them that differed raised "the two landing pages
# disagree" — a false statement, about correct docs, in a job that blocks merge
# ("Since `X` the server gained 2 tools" was enough, round 3). The advertised
# surface is the emphasised claim; a count in running prose is not a claim. If it
# is reworded away the vacuity guard below fires, so tightening cannot hide it.
#
# A declaration that appears TWICE is refused rather than compared: `sed` prints
# one line per match, "3\n9" is not an integer, `[ -ne ]` then exits 2 and `if`
# reads that as false — so the heading-vs-rows assertion silently no-opped while
# a differently-shaped error blamed the wrong file (round 3).
MCPDOC=mcp-server/README.md
count_once() { # count_once <label> <value>
  case "$2" in
    "")  return 1 ;;
    *[!0-9]*)
      err "$MCPDOC declares '$1' more than once — the declared count is ambiguous, and comparing against a list of counts silently skips the comparison."
      return 1 ;;
  esac
  return 0
}
if [ -f "$MCPDOC" ] && [ -f README.md ]; then
  # Declared count in the section heading.
  head_n=$(sed -n 's/^## Tools exposed (\([0-9][0-9]*\)).*/\1/p' "$MCPDOC")
  if [ -z "$head_n" ]; then
    err "$MCPDOC has no '## Tools exposed (N)' heading — the declared tool count has been reworded away, so the count check would pass vacuously."
  elif ! count_once '## Tools exposed (N)' "$head_n"; then
    :
  else
    # Rows in the tables under that heading, up to the next '## '. Anchored at
    # the line start so a backticked name inside a description cell is not a row.
    rows=$(awk '/^## Tools exposed \(/ { on=1; next } on && /^## / { on=0 } on && /^\| `/ { n++ } END { print n+0 }' "$MCPDOC")
    if [ "$rows" -ne "$head_n" ]; then
      err "$MCPDOC says 'Tools exposed ($head_n)' but the tables under it list $rows tools. A tool was added or removed and the heading was not updated."
    fi
    # Cross-file: README.md's emphasised claim.
    claims=$(grep -oE '\*\*[0-9]+ tools\*\*' README.md | tr -d '*' | awk '{print $1}' | sort -u)
    if [ -z "$claims" ]; then
      err "README.md makes no '**N tools**' claim any more — the cross-file half of this check would pass vacuously. Restore the claim or delete this assertion deliberately."
    else
      for c in $claims; do
        [ "$c" -eq "$head_n" ] || err "README.md advertises '$c tools' but $MCPDOC enumerates $head_n. The two landing pages disagree."
      done
    fi
  fi

  # Same shape for resources and prompts — the same defect class, one section
  # further down, and previously covered by nothing at all.
  # The section extractors stop at the first blank line. `/^$/` cannot match a
  # CRLF blank line — awk strips only the \n — so on a CRLF-saved README the
  # extraction ran to EOF, swallowed every later section, and the gate reported
  # counts that cannot be reconciled ("declares 9 resources but lists 12") about
  # a document in which no claim had changed at all. Nothing normalises line
  # endings on the way in, so the CR is handled here (round 3).
  res_n=$(sed -n 's/^\*\*Resources (\([0-9][0-9]*\) + 1 template).*/\1/p' "$MCPDOC")
  if [ -z "$res_n" ]; then
    err "$MCPDOC has no '**Resources (N + 1 template)**' declaration — the resource count check would pass vacuously."
  elif ! count_once '**Resources (N + 1 template)**' "$res_n"; then
    :
  else
    listed=$(awk '{ sub(/\r$/, "") } /^\*\*Resources \(/ { on=1 } on { print; if (/^$/ && seen) exit; seen=1 } ' "$MCPDOC" \
      | grep -oE '`cqels://[^`]+`' | grep -v '{' | sort -u | wc -l | tr -d ' ')
    [ "$listed" -eq "$res_n" ] || err "$MCPDOC declares $res_n resources but lists $listed concrete \`cqels://\` URIs."
    rclaim=$(grep -oE '\*\*[0-9]+ resources\*\*' README.md | tr -d '*' | awk '{print $1}' | sort -u)
    if [ -z "$rclaim" ]; then
      err "README.md makes no '**N resources**' claim any more — cross-file resource check would pass vacuously."
    else
      for c in $rclaim; do
        [ "$c" -eq "$res_n" ] || err "README.md advertises '$c resources' but $MCPDOC declares $res_n."
      done
    fi
  fi

  pr_n=$(sed -n 's/^\*\*Prompts (\([0-9][0-9]*\)).*/\1/p' "$MCPDOC")
  if [ -z "$pr_n" ]; then
    err "$MCPDOC has no '**Prompts (N)**' declaration — the prompt count check would pass vacuously."
  elif ! count_once '**Prompts (N)**' "$pr_n"; then
    :
  else
    plisted=$(awk '{ sub(/\r$/, "") } /^\*\*Prompts \(/ { on=1 } on { print; if (/^$/ && seen) exit; seen=1 }' "$MCPDOC" \
      | grep -oE '`[a-z_]+`' | sort -u | wc -l | tr -d ' ')
    [ "$plisted" -eq "$pr_n" ] || err "$MCPDOC declares $pr_n prompts but lists $plisted names."
    pclaim=$(grep -oE '\*\*[0-9]+ prompt templates\*\*' README.md | tr -d '*' | awk '{print $1}' | sort -u)
    if [ -z "$pclaim" ]; then
      err "README.md makes no '**N prompt templates**' claim any more — cross-file prompt check would pass vacuously."
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
        # The CR is stripped here for the same reason the scanner and the A6
        # extractors strip it: one guide saved on Windows commits as CRLF (there
        # is no .gitattributes). This was the only reader that did not, and a
        # bare autolink at the END of a current-stamp line has nothing — no `)`,
        # no `>` — to stop the match before the CR, so the probe URL carried it,
        # curl refused it with exit 3 and the links job reported INCONCLUSIVE
        # "re-run" forever on a link that returns 200 (round 5). That inverts
        # what exit 3 promises, and it hides a genuinely dead link as flake.
        sed -n "${ln}p" "$f" \
          | tr -d '\r' \
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

  awk '{ sub(/\r$/, "") } /^\*\*Resources \(/ { on=1 } on { print; if (/^$/ && seen) exit; seen=1 }' "$MCPDOC" \
    | grep -oE 'cqels://[^`]+' | grep -v '{' | sort -u > "$WORK/doc-resources"
  compare_set resources "$WORK/adv-resources" "$WORK/doc-resources"

  # The '{'-bearing URIs in the SAME documented section are the templates —
  # compared against resources/templates/list, never silently dropped.
  awk '{ sub(/\r$/, "") } /^\*\*Resources \(/ { on=1 } on { print; if (/^$/ && seen) exit; seen=1 }' "$MCPDOC" \
    | grep -oE 'cqels://[^`]+' | grep '{' | sort -u > "$WORK/doc-resource-templates"
  compare_set "resource templates" "$WORK/adv-resource-templates" "$WORK/doc-resource-templates"

  awk '{ sub(/\r$/, "") } /^\*\*Prompts \(/ { on=1 } on { print; if (/^$/ && seen) exit; seen=1 }' "$MCPDOC" \
    | grep -oE '`[a-z_]+`' | tr -d '`' | sort -u > "$WORK/doc-prompts"
  compare_set prompts "$WORK/adv-prompts" "$WORK/doc-prompts"

  [ "$fail" -eq 0 ] || exit 1
fi

echo "OK: every version claim in this repository is true$([ "$ONLINE" -eq 1 ] && echo ", and every channel it names resolves")$([ "$DEEP" -eq 1 ] && echo ", and the documented surface is the advertised one")."
