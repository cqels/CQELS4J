#!/usr/bin/env bash
# Regenerate examples/src/main/resources/s2dm/trailer/{skos,data_graph}.ttl with COVESA s2dm.
#
# s2dm runs in Docker. The image build is the only step with network access (it fetches s2dm's
# locked dependencies). The export itself runs with no network, a read-only root filesystem, a
# throwaway container, your uid, and only two host folders mounted: the input schema (read-only)
# and the output folder.
#
# Input: s2dm's own trailer example, examples/trailer/full_schema.graphl, at the pinned commit.
# Usage: scripts/s2dm/export-trailer.sh        (needs git and docker)
set -euo pipefail

S2DM_COMMIT=d97b449a7cd4bee9298eb9868545eded68f975d2
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
docker run --rm --network none --read-only --tmpfs /tmp --user "$(id -u):$(id -g)" \
  -v "$work/in:/in:ro" -v "$work/out:/out" "$IMAGE" \
  export rdf -s /in/trailer.graphql -o /out \
  --namespace "$NAMESPACE" --prefix trailer --output-formats turtle

mkdir -p "$dest"
cp "$work/out/skos.ttl" "$work/out/data_graph.ttl" "$dest/"
echo "wrote $dest/skos.ttl and $dest/data_graph.ttl (s2dm $S2DM_COMMIT)"
echo "remove the image when done: docker rmi $IMAGE"
