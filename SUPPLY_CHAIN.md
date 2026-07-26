# Supply chain: verifying CQELS releases

CQELS release artifacts are published to an anonymous Maven repository and are covered by a
cosign-signed manifest. This page exists so you can obtain the **verification key from a
different origin than the artifacts it verifies**.

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

```bash
VERSION=2.0.0-alpha.16
BASE=https://maven.cqels.org/releases/supply-chain/$VERSION

# 1. Key from THIS repository — a different origin from the artifacts.
curl -fsSLO https://raw.githubusercontent.com/cqels/CQELS4J/master/cosign.pub
shasum -a 256 cosign.pub   # must equal the fingerprint above

# 2. Manifest and signature from the artifact repository.
curl -fsSLO "$BASE/SHA256SUMS" -O "$BASE/SHA256SUMS.bundle"

# 3. Verify the manifest was signed by the key you just pinned.
cosign verify-blob --key cosign.pub --bundle SHA256SUMS.bundle \
  --new-bundle-format=false --insecure-ignore-tlog SHA256SUMS
```

`--insecure-ignore-tlog` is expected here: these signatures are deliberately made without a
transparency-log entry, so the pinned key is the whole trust anchor. That is exactly why step 1
matters.

## Checking a jar you downloaded

`SHA256SUMS` lists repository-relative paths for every file the release produced, so a bare
`--check` will fail on everything you did not download. Check the specific artifact instead:

```bash
# path as it appears in the manifest
REL=org/cqels/cqels-engine/2.0.0-alpha.16/cqels-engine-2.0.0-alpha.16.jar
grep " $REL\$" SHA256SUMS
shasum -a 256 ~/.m2/repository/$REL
```

The two digests must match. To check everything Maven resolved, run the comparison from your
local repository root so the manifest's relative paths line up:

```bash
cd ~/.m2/repository
grep -E '^[0-9a-f]{64}  org/cqels/' /path/to/SHA256SUMS \
  | grep -vE -- '-shaded\.jar$' \
  | while read -r want rel; do
      [ -f "$rel" ] || continue
      got=$(shasum -a 256 "$rel" | cut -d' ' -f1)
      [ "$got" = "$want" ] && echo "OK   $rel" || echo "FAIL $rel"
    done
```

## Notes

- The runnable shaded server jar is not served from the Maven repository (size); it ships as a
  container image. It is still listed in `SHA256SUMS`, which is why the loop above skips it.
- Builds are reproducible from source from `2.0.0-alpha.16` onward
  (`project.build.outputTimestamp` is pinned), so a release can be independently reconstructed
  and compared against the signed digests.
