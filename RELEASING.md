# Releasing a CQELS4J version bump

This repository ships documentation and runnable examples for a CQELS engine
release published elsewhere. "Releasing" here means moving the pinned
`<cqels.version>` — and every claim that depends on it — to a new engine
version, truthfully.

The version-truth gate (`scripts/ci/version-truth-gate.sh`) enforces most of
this automatically. This checklist covers the parts it cannot.

## Checklist

1. **Confirm the engine release is real before touching this repo.**
   The GitHub release page for the target version must exist, and the shaded
   MCP server jar must be downloadable from it. A bump merged before those
   exist puts dead links on the public landing page — the gate's `--online`
   tier checks exactly this, so run it first:

   ```bash
   scripts/ci/version-truth-gate.sh --online
   ```

2. **Bump the pin** in `examples/pom.xml` and `mcp-server/pom.xml` (the gate
   fails if they disagree), then update the current-version stamps the offline
   gate reports. Do **not** touch lines the gate classifies as provenance
   ("since `X`", "from `X` onward", "`X` is the first release …") — those are
   history, and bumping them makes them false.

3. **Run the demos.** Every example must run with 0 exceptions and its expected
   emissions on the new version, not merely compile. Capture output before and
   after the bump and account for every difference.

4. **Run all three gate tiers locally:**

   ```bash
   scripts/ci/version-truth-gate.test.sh   # the gate's own tests
   scripts/ci/version-truth-gate.sh        # offline claims
   scripts/ci/version-truth-gate.sh --online
   scripts/ci/version-truth-gate.sh --deep # docs vs the real server surface
   ```

5. **Open the PR** and let CI repeat the offline + online tiers.

6. **After the merge, dispatch the `links` job once by hand**
   (Actions → version-truth → Run workflow). This is the durability step the
   workflow's own comments reference: GitHub disables scheduled workflows in
   public repositories after 60 days of repository inactivity, so the nightly
   world-drift check cannot be assumed alive between releases. The
   schedule-staleness step in CI will fail the next PR if the schedule has gone
   quiet — this dispatch is how you both verify the release channels and
   confirm the workflow is enabled.
