#!/usr/bin/env bash
#
# Behaviour-truth check — the companion to version-truth-gate.sh.
#
# The version gate exists because a version claim in the prose can go stale without anything
# failing to compile. Capability claims are the same hazard, one category over, and this repository
# now carries a lot of them: CQELS-QL_SPEC.md §6 and §9, CDSP_MAPPING.md §4-5, and several example
# Javadocs all say some variant of "X does not work on this engine build, work around it like
# this".
#
# Those statements rot in a direction the version gate cannot see. When upstream FIXES one of them,
# nothing here breaks — the workarounds keep working, the build stays green, and the documentation
# quietly starts lying to readers who then apply a workaround they no longer need. The failure is
# silent and open-ended, which is precisely the shape of defect this repo was set up to prevent.
#
# So org.cqels.examples.CapabilityProbe asserts both directions:
#   - caveats     documented as broken. Passing now => the docs are stale, and the probe names
#                 the files to edit.
#   - capabilities documented as working and relied on by the examples. Failing => a regression
#                 in a new engine release.
#
# Exit codes follow the version gate's convention, so CI can treat them the same way:
#   0  documentation and engine behaviour agree
#   1  they diverge — the probe output names what changed and which files to fix
#   3  INCONCLUSIVE: could not run (missing toolchain, or dependencies unreachable). Not a defect;
#      re-run. Fail-closed like the gate's --online tier: still non-zero, but the log says so.
#
# Run it whenever the pin moves (RELEASING.md step 4) — that is the moment caveats most often
# become false, because the whole point of a bump is that upstream changed.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EXAMPLES_DIR="${REPO_ROOT}/examples"
PROBE_CLASS="org.cqels.examples.CapabilityProbe"

if ! git -C "${REPO_ROOT}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "usage error: not inside a git work tree" >&2
    exit 2
fi

# Prerequisites. Named individually so the log says which one is missing rather than just
# "build failed" — the same courtesy the gate's --deep tier extends for python3.
for tool in java mvn; do
    if ! command -v "${tool}" >/dev/null 2>&1; then
        echo "INCONCLUSIVE: ${tool} is not on PATH — cannot probe engine behaviour." >&2
        echo "  This is not a documentation defect. Install ${tool} and re-run." >&2
        exit 3
    fi
done

echo "-- capability probe: building examples --"
if ! mvn -q -B -f "${EXAMPLES_DIR}/pom.xml" compile 2>&1 | grep -vE '^WARNING|final field mutation|--enable-final-field-mutation'; then
    :   # grep -v returns 1 on no output, which is the normal quiet-build case
fi

# A build failure here is almost always the artifact mirror being unreachable, not a defect in
# the repository — distinguish the two rather than reporting a red for someone's flaky network.
if [ ! -d "${EXAMPLES_DIR}/target/classes" ]; then
    echo "INCONCLUSIVE: examples did not compile — dependencies may be unreachable." >&2
    echo "  Check network access to https://raw.githubusercontent.com/cqels/maven/main/releases and re-run." >&2
    exit 3
fi

echo "-- capability probe: asserting documented behaviour --"
OUTPUT="$(mvn -q -B -f "${EXAMPLES_DIR}/pom.xml" exec:java -Dexec.mainClass="${PROBE_CLASS}" 2>&1)"
STATUS=$?

# Drop Maven's own JDK-26 reflection warnings; they are Maven's, not this repo's.
echo "${OUTPUT}" | grep -vE '^WARNING|final field mutation|--enable-final-field-mutation|^\[INFO\]|^\[WARNING\]'

if [ ${STATUS} -eq 0 ]; then
    exit 0
fi

# The probe throws on divergence, so Maven exits non-zero. Separate that from a run that never
# got as far as the assertions (engine could not start, dependency missing at runtime, …).
if echo "${OUTPUT}" | grep -q 'DIVERGENCE:'; then
    echo
    echo "Capability claims in this repository are no longer true. Update the files named above." >&2
    exit 1
fi

echo "INCONCLUSIVE: the probe did not complete — see the output above." >&2
exit 3
