# Releasing a CQELS4J version bump

This repository ships documentation and runnable examples for a CQELS engine
release published elsewhere. "Releasing" here means moving the pinned
`<cqels.version>` — and every claim that depends on it — to a new engine
version, truthfully.

The version-truth gate (`scripts/ci/version-truth-gate.sh`) enforces most of
this automatically. This checklist covers the parts it cannot.

## Checklist

1. **Confirm the engine release is real before touching this repo — by hand.**
   The GitHub release page for the target version must exist, and the shaded
   MCP server jar must be downloadable from it. A bump merged before those
   exist puts dead links on the public landing page. Open them yourself:

   ```
   https://github.com/cqels/CQELS4J/releases/tag/v<TARGET>
   ```

   The gate cannot do this step. Every URL its `--online` tier probes is
   derived from the pin it reads out of the poms, so run before the bump it
   checks the release you are moving *away* from and says nothing about the
   target. It becomes the real check at step 4, after the pin has moved.

2. **Bump the pin** in `examples/pom.xml` and `mcp-server/pom.xml` (the gate
   fails if they disagree), then update the current-version stamps the offline
   gate reports. Do **not** touch lines the gate classifies as provenance
   ("since `X`", "from `X` onward", "`X` is the first release …") — those are
   history, and bumping them makes them false.

3. **Run the demos.** Every example must run with 0 exceptions and its expected
   emissions on the new version, not merely compile. Capture output before and
   after the bump and account for every difference.

4. **Run all three gate tiers locally, then the capability probe:**

   ```bash
   scripts/ci/version-truth-gate.test.sh   # the gate's own tests
   scripts/ci/version-truth-gate.sh        # offline claims
   scripts/ci/version-truth-gate.sh --online
   scripts/ci/version-truth-gate.sh --deep # docs vs the real server surface
   ```

   Prerequisites: the offline and `--online` tiers need nothing but git, awk,
   sed, grep and curl. `--deep` builds `mcp-server` and parses one JSON-RPC
   session, so it needs **java, maven and python3** — and so does the self-test
   above, which drives `--deep` against recorded transcripts. Without python3
   both report `INCONCLUSIVE … is not on PATH` (exit 3), not a documentation
   defect.

   Then re-check the documented engine limitations, which the version tiers
   cannot see:

   ```bash
   scripts/ci/capability-probe.sh   # needs java + maven
   ```

   This repository documents behaviour it works around — `CQELS-QL_SPEC.md` §6
   and §9, `CDSP_MAPPING.md` §4–5, and several example Javadocs. A bump is the
   moment those claims most often become false, and they rot **silently**: when
   upstream fixes one, the workarounds keep working and nothing else fails. The
   probe asserts both directions — caveats that are no longer true, and
   capabilities that have regressed — and names the files to edit. Exit 3 again
   means it could not run, not that the docs are wrong.

5. **Open the PR** and let CI repeat the offline + online tiers.

6. **After the merge, run the workflow once by hand**
   (Actions → version-truth → Run workflow; a dispatch runs the whole
   workflow, `links` included). This is the durability step the workflow's own
   comments reference: GitHub disables scheduled workflows in public
   repositories after 60 days of repository inactivity, so the nightly
   world-drift check cannot be assumed alive between releases. The
   `schedule-health` job will fail the next PR if the schedule has gone quiet;
   it is a job of its own precisely so that it never blocks this dispatch.
