#!/usr/bin/env bash
# Regenerate examples/src/main/resources/s2dm/trailer/{skos,data_graph}.ttl with COVESA s2dm,
# supplement.ttl with this repository's own scripts/s2dm/supplement.py (what the RDF export
# leaves out of the same schema: field descriptions, units, @range, instance-tag fields), and
# units.ttl with scripts/s2dm/units.py (the schema's unit enum values linked to QUDT units, with
# the conversions between them).
#
# Fetching the pinned s2dm commit and the pinned QUDT release from GitHub, and building the Docker
# image (s2dm's locked dependencies), need network access. The export itself runs in a throwaway container with no
# network, a read-only root filesystem, your uid, and only the inputs (the schema and, for
# units.py, the QUDT units file; read-only), the output folder and, for the supplement and the
# units, this script's folder (read-only) mounted.
#
# Input: s2dm's own trailer example, examples/trailer/full_schema.graphl, at the pinned commit.
# Usage: scripts/s2dm/export-trailer.sh        (needs git and docker)
set -euo pipefail

S2DM_COMMIT=d97b449a7cd4bee9298eb9868545eded68f975d2
QUDT_TAG=v3.5.3
# a tag can be moved; the file's checksum pins what the tag held when units.ttl was generated
QUDT_SHA256=5e84f3e10ae6a3c535e2e7616dc2affee09dd52bea5229822188730493fd4418
NAMESPACE='https://example.org/s2dm/trailer#'
IMAGE="cqels4j-s2dm:${S2DM_COMMIT:0:7}"

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
dest="$repo/examples/src/main/resources/s2dm/trailer"
work=$(mktemp -d "${TMPDIR:-/tmp}/s2dm-export.XXXXXX")
trap 'rm -rf -- "$work"' EXIT

git init -q "$work/s2dm"
git -C "$work/s2dm" fetch -q --depth 1 https://github.com/COVESA/s2dm.git "$S2DM_COMMIT"
git -C "$work/s2dm" checkout -q FETCH_HEAD
printf '.git\nplayground\ndocs-gen\n' > "$work/s2dm/.dockerignore"

docker build -q -t "$IMAGE" -f "$here/Dockerfile" "$work/s2dm" >/dev/null

mkdir -p "$work/in" "$work/out"
cp "$work/s2dm/examples/trailer/full_schema.graphl" "$work/in/trailer.graphql"
curl -sSfL -o "$work/in/qudt-units.ttl" \
  "https://raw.githubusercontent.com/qudt/qudt-public-repo/$QUDT_TAG/src/main/rdf/vocab/unit/VOCAB_QUDT-UNITS-ALL.ttl"
echo "$QUDT_SHA256  $work/in/qudt-units.ttl" | shasum -a 256 -c - >/dev/null \
  || { echo "QUDT $QUDT_TAG units file does not match QUDT_SHA256" >&2; exit 1; }
docker run --rm --network none --read-only --tmpfs /tmp --user "$(id -u):$(id -g)" \
  -v "$work/in:/in:ro" -v "$work/out:/out" "$IMAGE" \
  export rdf -s /in/trailer.graphql -o /out \
  --namespace "$NAMESPACE" --prefix trailer --output-formats turtle

docker run --rm --network none --read-only --tmpfs /tmp --user "$(id -u):$(id -g)" \
  -v "$work/in:/in:ro" -v "$here:/tool:ro" -v "$work/out:/out" --entrypoint python "$IMAGE" \
  -I /tool/supplement.py /in/trailer.graphql "$NAMESPACE" /out/supplement.ttl

docker run --rm --network none --read-only --tmpfs /tmp --user "$(id -u):$(id -g)" \
  -v "$work/in:/in:ro" -v "$here:/tool:ro" -v "$work/out:/out" --entrypoint python "$IMAGE" \
  -I /tool/units.py /in/trailer.graphql "$NAMESPACE" /in/qudt-units.ttl "$QUDT_TAG" /out/units.ttl

mkdir -p "$dest"
cp "$work/out/skos.ttl" "$work/out/data_graph.ttl" "$work/out/supplement.ttl" "$work/out/units.ttl" "$dest/"
echo "wrote $dest/{skos,data_graph,supplement,units}.ttl (s2dm $S2DM_COMMIT, QUDT $QUDT_TAG)"
echo "remove the image when done: docker rmi $IMAGE"
