# Supply chain: verifying CQELS releases

CQELS **Maven** artifacts are published to an anonymous Maven repository and are covered by a
cosign-signed manifest. (The runnable shaded server jar is the one exception — it has no
anonymous route today; see [The shaded server jar](#the-shaded-server-jar) below.) This page
exists so you can obtain the **verification key from a different origin than the artifacts it
verifies**.

## Why the key lives here

Every release publishes `SHA256SUMS` (a digest of every file the release produced) and
`SHA256SUMS.bundle` (its cosign signature) alongside the artifacts themselves.

If you also fetch the *public key* from that same location, the verification proves nothing
useful: anyone who could tamper with the artifacts could replace the manifest, the signature and
the key together, and your `cosign verify-blob` would still print `Verified OK`. A signature is
only as trustworthy as the independence of the key you check it against.

So: **take `cosign.pub` from this repository, not from the artifact repository.**

    cosign.pub  SHA-256: 36dd8daa9988f23eb40c4f3550fa7bdfa3796e5e58cce8d23b9cc6a99f47f30b

Pin that fingerprint (or vendor the key file itself) in your build. If it ever changes without
an announcement here, stop and ask.

## Verifying a release

`set -e` on the first line is load-bearing, not boilerplate. Without it a failing fingerprint
check only sets a non-zero status for that one command; the script would carry on and run
`cosign verify-blob`, which succeeds against a valid-but-wrong key and its matching manifest —
so the block as a whole would exit 0 and a swapped key would pass unnoticed. Making the
individual command fail is not enough; the sequence has to stop.

```bash
#!/usr/bin/env bash
set -euo pipefail

VERSION=2.0.0-alpha.16
BASE=https://maven.cqels.org/releases/supply-chain/$VERSION

# 1. Key from THIS repository — a different origin from the artifacts.
#    --check FAILS on a mismatch; printing the digest to compare by eye does
#    not, so a swapped key would pass unnoticed.
curl -fsSLO https://raw.githubusercontent.com/cqels/CQELS4J/master/cosign.pub
echo "36dd8daa9988f23eb40c4f3550fa7bdfa3796e5e58cce8d23b9cc6a99f47f30b  cosign.pub" \
  | shasum -a 256 --check -

# 2. Manifest and signature from the artifact repository.
curl -fsSLO "$BASE/SHA256SUMS" -O "$BASE/SHA256SUMS.bundle"

# 3. Verify the manifest was signed by the key you just pinned.
cosign verify-blob --key cosign.pub --bundle SHA256SUMS.bundle \
  --new-bundle-format=false --insecure-ignore-tlog SHA256SUMS
```

### Verifying an older release after a key rotation

The URL above tracks the **current** key. When the signing key is rotated, that URL serves the
new key and the fingerprint above stops matching — which is correct for new releases and useless
for old ones.

Each release therefore publishes its own `supply-chain/<version>/VERIFY.md` containing a URL
**pinned to the exact commit that held the key when that release was published**. To verify a
historical release, use that release's `VERIFY.md`, not this page. Retired key fingerprints are
published here at rotation time so an old release stays independently checkable.

`--insecure-ignore-tlog` is expected here: these signatures are deliberately made without a
transparency-log entry, so the pinned key is the whole trust anchor. That is exactly why step 1
matters.

## Checking a jar you downloaded

`SHA256SUMS` lists repository-relative paths for every file the release produced, so a bare
`--check` will fail on everything you did not download. Check the specific artifact instead:

```bash
# path as it appears in the manifest
REL=org/cqels/cqels-engine/2.0.0-alpha.16/cqels-engine-2.0.0-alpha.16.jar
want=$(grep "  $REL$" SHA256SUMS | cut -d' ' -f1)
got=$(shasum -a 256 ~/.m2/repository/$REL | cut -d' ' -f1)
if [ -n "$want" ] && [ "$want" = "$got" ]; then
  echo "OK   $REL"
else
  echo "MISMATCH $REL (signed ${want:-<absent from manifest>}, local $got)" >&2
  exit 1
fi
```

This compares rather than printing two digests for you to eyeball — the printing form succeeds
even when they differ.

To check everything Maven resolved, run the comparison from your local repository root so the
manifest's relative paths line up. This script **exits non-zero** on any mismatch, and also when it checked nothing at all — a
verification step that cannot fail is worse than no verification, because it looks like a pass.
Note the `< <(...)` redirect rather than a pipe: a piped `while` runs in a subshell, so the
failure flag set inside it would be discarded and the script would always succeed.

```bash
#!/usr/bin/env bash
cd ~/.m2/repository || exit 1
rc=0
checked=0
while read -r want rel; do
  [ -f "$rel" ] || continue                       # not resolved locally; skip
  checked=$((checked + 1))
  got=$(shasum -a 256 "$rel" | cut -d' ' -f1)
  if [ "$got" = "$want" ]; then
    echo "OK   $rel"
  else
    echo "FAIL $rel (signed $want, local $got)"
    rc=1
  fi
done < <(grep -E '^[0-9a-f]{64}  org/cqels/' /path/to/SHA256SUMS | grep -vE -- '-shaded\.jar$')

if [ "$checked" -eq 0 ]; then
  echo "checked nothing — wrong directory, or no org.cqels artifacts resolved yet" >&2
  rc=1
fi

# Reverse direction: the loop above walks the MANIFEST, so a local org.cqels jar
# that appears in no manifest entry would never be looked at — an injected or
# stale artifact hides precisely by not being listed. Walk the local tree too
# and require every jar to be accounted for.
while IFS= read -r rel; do
  case "$rel" in *-shaded.jar) continue ;; esac        # listed but not mirrored
  grep -q "  $rel\$" /path/to/SHA256SUMS || {
    echo "UNLISTED $rel (present locally, absent from the signed manifest)" >&2
    rc=1
  }
done < <(find org/cqels -name '*.jar' 2>/dev/null)

exit $rc
```

The two directions answer different questions: the first asks "does everything the release signed
still match?", the second asks "is anything sitting in my repository that the release never
signed?". A verification that only walks the manifest silently tolerates the second case.

## The shaded server jar

The runnable shaded jar (`cqels-mcp-<version>-shaded.jar`, ~62 MB) is **not** served from the
Maven repository — it is most of a release's bytes and the repository is size-bounded.

**As of `2.0.0-alpha.16` it has no anonymous download route.** Every channel that carries it
today needs credentials: the `shaded` classifier on the GitHub Packages Maven registry
(HTTP 401 unauthenticated), the container image (currently a private package), and the release
asset (attached to a repository that is not publicly readable). If you are reading this expecting
to `curl` the jar, you cannot yet — that is a gap, not an oversight in these instructions.

Everything else a release produces *is* anonymously available from the Maven repository.

It *is* listed in `SHA256SUMS` — which is why the bulk loop above skips it — so however you
obtained it, verify it against its manifest entry:

```bash
VERSION=2.0.0-alpha.16
REL=org/cqels/cqels-mcp/$VERSION/cqels-mcp-$VERSION-shaded.jar
want=$(grep "  $REL$" SHA256SUMS | cut -d' ' -f1)
got=$(shasum -a 256 cqels-mcp-$VERSION-shaded.jar | cut -d' ' -f1)
if [ -n "$want" ] && [ "$want" = "$got" ]; then
  echo "OK   shaded jar"
else
  echo "MISMATCH shaded jar (signed ${want:-<absent from manifest>}, local $got)" >&2
  exit 1
fi
```

Verify the manifest signature first (above) — a manifest entry is only meaningful once you have
established that the manifest itself is authentic.

## Reproducibility — and its limits

From `2.0.0-alpha.16` onward `project.build.outputTimestamp` is pinned, so builds are
reproducible from source: rebuilding the exact tag with Temurin 17 and
`-Drevision=<version>` reproduces the **plain Maven artifacts** byte-for-byte, and they can be
compared against the signed manifest.

**The shaded jar is not covered by that.** Rebuilding it has been observed to produce an archive
whose *contents* are identical but whose bytes differ, so its digest will not match the manifest
even though nothing is wrong with your build. Exclude it when reproducing a release; verify it
by digest against the manifest instead, as above.
