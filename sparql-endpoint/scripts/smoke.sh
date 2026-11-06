#!/usr/bin/env bash
# HTTP smoke test for the SPARQL 1.1 Protocol endpoint over the seeded V2G fleet world.
#
# Starts the shaded jar on an ephemeral port with the fleet seed, waits for its one stdout
# line ("listening on http://…"), then drives the protocol with curl: ASK over the seeded
# fleet (the same fleet:assignedDriver fact mcp-server/scripts/smoke.sh queries first), SELECT
# as JSON, CONSTRUCT as Turtle, direct-POST query, an Update (must be 403: read-only by
# default), a malformed query (400), an unmatchable Accept (406), and /health. Then stops it.
#
# Usage: scripts/smoke.sh   (builds target/cqels-sparql-endpoint.jar if missing)

set -u
cd "$(dirname "$0")/.."

JAR=target/cqels-sparql-endpoint.jar
if [ ! -f "$JAR" ]; then
  echo "building $JAR ..."
  mvn -q -DskipTests package || exit 1
fi

OUT=$(mktemp)
trap 'kill $PID 2>/dev/null; wait $PID 2>/dev/null; rm -f "$OUT"' EXIT

CQELS_SPARQL_PORT=0 CQELS_SPARQL_SEED_FLEET=true java -jar "$JAR" > "$OUT" 2>/dev/null &
PID=$!

# The launcher prints exactly one stdout line once the engine is started and Jetty is bound.
URL=
for _ in $(seq 1 60); do
  URL=$(sed -n 's/^listening on //p' "$OUT")
  [ -n "$URL" ] && break
  kill -0 $PID 2>/dev/null || { echo "FAIL: the endpoint exited before listening"; exit 1; }
  sleep 1
done
[ -n "$URL" ] || { echo "FAIL: no 'listening on' line within 60 s"; exit 1; }
echo "endpoint: $URL"

fail=0
check() { # desc, expected-status, actual-status, body, needle
  local desc=$1 want=$2 got=$3 body=$4 needle=${5:-}
  if [ "$got" = "$want" ] && { [ -z "$needle" ] || printf '%s' "$body" | grep -qF -- "$needle"; }; then
    echo "ok:   $desc"
  else
    echo "FAIL: $desc (status $got, wanted $want${needle:+, needle '$needle'})"; fail=1
  fi
}
req() { # method, url, [curl args...] -> sets STATUS and BODY
  local tmp; tmp=$(mktemp)
  STATUS=$(curl -s -o "$tmp" -w '%{http_code}' "$@")
  BODY=$(cat "$tmp"); rm -f "$tmp"
}

Q_DRIVER='ASK { <https://example.org/fleet/vehicle/EV-7Q2> <https://covesa.global/fleet#assignedDriver> ?d }'
Q_SELECT='SELECT ?v ?name WHERE { ?v <https://covesa.global/fleet#assignedDriver> ?d . ?d <https://covesa.global/fleet#name> ?name } ORDER BY ?v'
Q_CONSTRUCT='CONSTRUCT WHERE { ?z <https://covesa.global/zone#speedLimit> ?l }'

req -G "$URL" --data-urlencode "query=$Q_DRIVER";                                   check "ASK over the seeded fleet"         200 "$STATUS" "$BODY" '"boolean" : true'
req -G "$URL" --data-urlencode "query=$Q_SELECT";                                   check "SELECT as JSON (default)"          200 "$STATUS" "$BODY" 'Alice'
req -G "$URL" -H 'Accept: text/csv' --data-urlencode "query=$Q_SELECT";             check "SELECT as CSV"                     200 "$STATUS" "$BODY" 'EV-9TZ'
req -G "$URL" --data-urlencode "query=$Q_CONSTRUCT";                                check "CONSTRUCT as Turtle (default)"     200 "$STATUS" "$BODY" 'speedLimit'
req -X POST "$URL" -H 'Content-Type: application/sparql-query' --data "$Q_DRIVER";  check "direct-POST query"                 200 "$STATUS" "$BODY" '"boolean" : true'
req -X POST "$URL" --data-urlencode 'update=INSERT DATA { <urn:x> <urn:y> <urn:z> }'; check "Update is refused (read-only)"   403 "$STATUS" "$BODY"
req -G "$URL" --data-urlencode 'query=SELEKT';                                      check "malformed query"                   400 "$STATUS" "$BODY" 'malformed'
req -G "$URL" -H 'Accept: application/vnd.nothing' --data-urlencode "query=$Q_SELECT"; check "unmatchable Accept"            406 "$STATUS" "$BODY"
req "${URL%/sparql}/health";                                                        check "/health"                           200 "$STATUS" "$BODY" 'ok'

[ $fail -eq 0 ] && echo "smoke: all checks passed" || { echo "smoke: FAILED"; exit 1; }
