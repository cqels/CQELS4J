#!/usr/bin/env bash
# Container smoke test: builds the image from ../Dockerfile and checks what a container
# changes about the endpoint, which scripts/smoke.sh (the bare jar on loopback) cannot see.
# Every run command README.md § "Run in Docker" recommends is exercised here:
#
#   1. `docker run` with no options refuses to start (exit 2). The image binds 0.0.0.0, so
#      the launcher's non-loopback rule applies; the image must fail closed, not serve.
#   2. With a token: 401 without it, 200 with it, over the seeded fleet; the process is not
#      root; `docker stop` ends it with exit 143 (SIGTERM reached the JVM as PID 1 and its
#      shutdown hook ran) rather than 137 (killed after the grace period).
#   3. The loopback-only, no-token mode: answers through a port published on 127.0.0.1, and
#      still refuses a foreign Host header (the DNS-rebinding check survives the container).
#   4. A named volume at /data with CQELS_SPARQL_STORE_PATH=/data is writable by the
#      non-root user, and an update survives a stop and a fresh container.
#
# Usage: scripts/docker-smoke.sh   (needs docker and curl; IMAGE overrides the tag)

set -u
cd "$(dirname "$0")/.." || exit 1

IMAGE=${IMAGE:-cqels-sparql-endpoint:smoke}
TOKEN="smoke-$$-$RANDOM"
VOL="cqels-sparql-smoke-$$"
CIDS=""   # a plain string, not an array: "${a[@]}" on an empty array trips set -u in bash 3.2

cleanup() {
  for c in $CIDS; do docker rm -f "$c" >/dev/null 2>&1; done
  docker volume rm -f "$VOL" >/dev/null 2>&1
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

echo "building $IMAGE ..."
docker build -q -t "$IMAGE" . >/dev/null || { echo "FAIL: docker build"; exit 1; }

fail=0
ok()  { echo "ok:   $1"; }
bad() { echo "FAIL: $1"; fail=1; }

req() { # url, [curl args...] -> sets STATUS and BODY
  local tmp; tmp=$(mktemp)
  STATUS=$(curl -s -o "$tmp" -w '%{http_code}' --connect-timeout 10 --max-time 60 "$@")
  BODY=$(cat "$tmp"); rm -f "$tmp"
}

# start [docker run options...] -> sets CID and URL (the published loopback port), or fails.
start() {
  CID=$(docker run -d -p 127.0.0.1::8089 "$@" "$IMAGE") || { bad "docker run $*"; return 1; }
  CIDS="$CIDS $CID"
  for _ in $(seq 1 120); do
    docker logs "$CID" 2>/dev/null | grep -q '^listening on ' && break
    [ "$(docker inspect -f '{{.State.Running}}' "$CID")" = true ] || {
      bad "container exited before listening:"; docker logs "$CID" 2>&1 | tail -5; return 1; }
    sleep 1
  done
  docker logs "$CID" 2>/dev/null | grep -q '^listening on ' || { bad "no 'listening on' line within 120 s"; return 1; }
  local port; port=$(docker port "$CID" 8089/tcp | sed -n 's/^127\.0\.0\.1://p' | head -1)
  URL="http://127.0.0.1:$port/sparql"
}

# stop_graceful <desc>: docker stop, then require exit 143 (SIGTERM handled by the JVM).
stop_graceful() {
  docker stop -t 30 "$CID" >/dev/null
  local rc; rc=$(docker inspect -f '{{.State.ExitCode}}' "$CID")
  [ "$rc" = 143 ] && ok "$1: docker stop ends the JVM by SIGTERM (exit 143)" \
                  || bad "$1: docker stop exit code $rc, wanted 143 (137 = killed, shutdown hook skipped)"
}

ASK_FLEET='ASK { <https://example.org/fleet/vehicle/EV-7Q2> <https://covesa.global/fleet#assignedDriver> ?d }'

# 1. fail closed. Detached with a deadline, not `docker run --rm` in the foreground: if the
#    image ever regressed to serving, a foreground run would never return and the job would
#    hang until CI's timeout instead of failing here.
CID=$(docker run -d "$IMAGE"); CIDS="$CIDS $CID"
for _ in $(seq 1 60); do
  [ "$(docker inspect -f '{{.State.Running}}' "$CID")" = true ] || break
  sleep 1
done
if [ "$(docker inspect -f '{{.State.Running}}' "$CID")" = true ]; then
  bad "no token and no opt-in: still running after 60 s, wanted a refusal"
else
  rc=$(docker inspect -f '{{.State.ExitCode}}' "$CID")
  if [ "$rc" = 2 ] && docker logs "$CID" 2>&1 | grep -q 'refusing to start'; then
    ok "no token and no opt-in: refuses to start (exit 2)"
  else
    bad "no token and no opt-in: exit $rc, wanted 2 with 'refusing to start'"
  fi
fi

# 2. token mode
if start -e CQELS_SPARQL_AUTH_TOKEN="$TOKEN" -e CQELS_SPARQL_SEED_FLEET=true; then
  req "$URL" -G --data-urlencode "query=$ASK_FLEET"
  [ "$STATUS" = 401 ] && ok "token mode: no token -> 401" || bad "token mode: no token -> $STATUS, wanted 401"
  req "$URL" -G -H "Authorization: Bearer $TOKEN" --data-urlencode "query=$ASK_FLEET"
  { [ "$STATUS" = 200 ] && printf '%s' "$BODY" | grep -qF '"boolean" : true'; } \
    && ok "token mode: ASK over the seeded fleet -> true" || bad "token mode: ASK -> $STATUS $BODY"
  uid=$(docker exec "$CID" id -u)
  [ "$uid" != 0 ] && ok "runs as uid $uid, not root" || bad "runs as root"
  stop_graceful "token mode"
fi

# 3. loopback-only, no token
if start -e CQELS_SPARQL_ALLOW_UNAUTHENTICATED_REMOTE=true -e CQELS_SPARQL_SEED_FLEET=true; then
  req "$URL" -G --data-urlencode "query=$ASK_FLEET"
  { [ "$STATUS" = 200 ] && printf '%s' "$BODY" | grep -qF '"boolean" : true'; } \
    && ok "loopback mode: ASK through 127.0.0.1 -> true" || bad "loopback mode: ASK -> $STATUS $BODY"
  req "$URL" -G -H 'Host: rebind.example' --data-urlencode "query=$ASK_FLEET"
  [ "$STATUS" = 403 ] && ok "loopback mode: foreign Host -> 403" || bad "loopback mode: foreign Host -> $STATUS, wanted 403"
fi

# 4. durable store on a named volume
docker volume create "$VOL" >/dev/null
DURABLE="-e CQELS_SPARQL_AUTH_TOKEN=$TOKEN -e CQELS_SPARQL_UPDATE_ENABLED=true -e CQELS_SPARQL_STORE_PATH=/data -v $VOL:/data"
# shellcheck disable=SC2086  # DURABLE is a word list on purpose; none of its words contain spaces
if start $DURABLE; then
  req "$URL" -H "Authorization: Bearer $TOKEN" --data-urlencode 'update=INSERT DATA { <urn:cqels:smoke> <urn:cqels:p> "persisted" }'
  [ "$STATUS" = 204 ] && ok "durable store: update as the non-root user, on the volume -> 204" \
                      || bad "durable store: update -> $STATUS, wanted 204: $BODY"
  stop_graceful "durable store"
  # shellcheck disable=SC2086
  if start $DURABLE; then
    req "$URL" -G -H "Authorization: Bearer $TOKEN" --data-urlencode 'query=ASK { <urn:cqels:smoke> <urn:cqels:p> "persisted" }'
    { [ "$STATUS" = 200 ] && printf '%s' "$BODY" | grep -qF '"boolean" : true'; } \
      && ok "durable store: the update survives a new container" || bad "durable store: after restart -> $STATUS $BODY"
  fi
fi

[ $fail -eq 0 ] && echo "docker-smoke: all checks passed" || { echo "docker-smoke: FAILED"; exit 1; }
