#!/usr/bin/env bash
# Stdio smoke test for the embedded CQELS MCP server + the V2G fleet seed.
#
# Drives one JSON-RPC session through: initialize -> tools/list -> resources/list ->
# prompts/list -> query (SPARQL over the SEEDED fleet world) -> recall_memory (pattern
# recall over the seed) -> store_memory -> create_stream -> register_stream_query
# (a [NOW] speeding monitor) -> push_stream_events (three typed speed readings as
# RDF-Messages nquads) -> recall_memory(queryId) drain -> forget_stream_query.
# Then asserts expected substrings, and that stdout carried ONLY JSON-RPC frames.
#
# Usage: scripts/smoke.sh   (builds target/cqels-mcp-server.jar if missing)
#        SMOKE_TRANSCRIPT=/path/to/file scripts/smoke.sh   keeps the raw JSON-RPC transcript

set -u
cd "$(dirname "$0")/.."

JAR=target/cqels-mcp-server.jar
if [ ! -f "$JAR" ]; then
  echo "building $JAR ..."
  mvn -q package || exit 1
fi

OUT=$(mktemp)
if [ -n "${SMOKE_TRANSCRIPT:-}" ]; then
  trap 'cp "$OUT" "$SMOKE_TRANSCRIPT" 2>/dev/null; rm -f "$OUT"' EXIT
else
  trap 'rm -f "$OUT"' EXIT
fi

EV=https://example.org/fleet/vehicle/EV-7Q2
VSS_SPEED=https://covesa.global/vss#Speed
XSD_INT=http://www.w3.org/2001/XMLSchema#integer

{
  emit() { printf '%s\n' "$1"; sleep 0.6; }
  emit '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"1.0"}}}'
  emit '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  # -- discovery: the full published surface ---------------------------------------------------
  emit '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
  emit '{"jsonrpc":"2.0","id":3,"method":"resources/list"}'
  emit '{"jsonrpc":"2.0","id":4,"method":"prompts/list"}'
  # -- the seeded V2G world: one-shot SPARQL sees it… ------------------------------------------
  emit '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"query","arguments":{"query":"SELECT ?v ?d WHERE { ?v <https://covesa.global/fleet#assignedDriver> ?d }","language":"sparql"}}}'
  # …and so does recall_memory pattern recall (the seed lives in the long-term memory graph) ----
  emit '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"recall_memory","arguments":{"pattern":{"subject":"'"$EV"'"}}}}'
  # -- store_memory: add a fact on top of the seed ----------------------------------------------
  emit '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"store_memory","arguments":{"facts":[{"subject":"'"$EV"'","predicate":"https://covesa.global/fleet#status","object":"in-service"}]}}}'
  emit '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"recall_memory","arguments":{"pattern":{"subject":"'"$EV"'","predicate":"https://covesa.global/fleet#status"}}}}'
  # -- streaming: create -> register -> push (streams are hot, no replay) -----------------------
  emit '{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"create_stream","arguments":{"stream":"telemetry"}}}'
  emit '{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"register_stream_query","arguments":{"queryId":"speeding","query":"PREFIX vss: <https://covesa.global/vss#> SELECT ?vehicle ?kmh FROM STREAM telemetry [NOW] WHERE { STREAM telemetry { ?vehicle vss:Speed ?kmh } FILTER(?kmh > 120) }"}}}'
  # three speed readings as one call — typed integers via the RDF-Messages nquads body ---------
  emit '{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"push_stream_events","arguments":{"stream":"telemetry","events":[{"nquads":"<'"$EV"'> <'"$VSS_SPEED"'> \"135\"^^<'"$XSD_INT"'> ."},{"nquads":"<https://example.org/fleet/vehicle/EV-3K8> <'"$VSS_SPEED"'> \"90\"^^<'"$XSD_INT"'> ."},{"nquads":"<https://example.org/fleet/vehicle/EV-9TZ> <'"$VSS_SPEED"'> \"128\"^^<'"$XSD_INT"'> ."}]}}}'
  sleep 1.5
  # -- drain the standing query's buffered matches, then stop it --------------------------------
  emit '{"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"recall_memory","arguments":{"queryId":"speeding"}}}'
  emit '{"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"forget_stream_query","arguments":{"queryId":"speeding"}}}'
  sleep 2
} | java -jar "$JAR" > "$OUT" 2>/dev/null &

# Wait for the last response (id 13), then stop the long-running server.
for _ in $(seq 1 90); do
  grep -q '"id":13' "$OUT" && break
  sleep 1
done
kill %1 2>/dev/null
wait 2>/dev/null

fail=0
assert_contains() { # desc, needle [, line-filter]
  local desc=$1 needle=$2 filter=${3:-}
  if [ -n "$filter" ]; then
    if grep -F -- "$filter" "$OUT" | grep -qF -- "$needle"; then echo "ok:   $desc"; else echo "FAIL: $desc (missing: $needle)"; fail=1; fi
  else
    if grep -qF -- "$needle" "$OUT"; then echo "ok:   $desc"; else echo "FAIL: $desc (missing: $needle)"; fail=1; fi
  fi
}

assert_contains "initialize returned the server info"          "cqels-fleet-mcp"                '"id":1'
assert_contains "tools/list includes store_memory"             '"name":"store_memory"'          '"id":2'
assert_contains "tools/list includes register_stream_query"    '"name":"register_stream_query"' '"id":2'
assert_contains "tools/list includes watch_invariant"          '"name":"watch_invariant"'       '"id":2'
assert_contains "resources/list includes engine status"        "cqels://engine/status"          '"id":3'
assert_contains "prompts/list is non-empty"                    '"name"'                         '"id":4'
assert_contains "query sees the seeded drivers (alice)"        "alice"                          '"id":5'
assert_contains "query sees the seeded drivers (carol)"        "carol"                          '"id":5'
assert_contains "pattern recall sees the seeded vehicle"       "assignedDriver"                 '"id":6'
assert_contains "store_memory stored the new fact"             "Stored 1 facts"                 '"id":7'
assert_contains "pattern recall sees the stored fact"          "in-service"                     '"id":8'
assert_contains "create_stream created the stream"             "Created stream 'telemetry'"     '"id":9'
assert_contains "register_stream_query registered 'speeding'"  '\"queryId\":\"speeding\"'       '"id":10'
assert_contains "push_stream_events pushed the readings"       "3"                              '"id":11'
assert_contains "drain surfaced the 135 km/h speeder"          "135"                            '"id":12'
assert_contains "drain surfaced the 128 km/h speeder"          "128"                            '"id":12'
if grep -- '"id":12' "$OUT" | grep -qF 'EV-3K8'; then
  echo "FAIL: 90 km/h reading (EV-3K8) leaked past FILTER(?kmh > 120)"; fail=1
else
  echo "ok:   90 km/h reading filtered out"
fi
assert_contains "forget_stream_query stopped the query"        '\"status\":\"forgotten\"'       '"id":13'
# stdout purity: every stdout line must be a JSON-RPC frame — logs belong on stderr.
if grep -qv '^{' "$OUT"; then
  echo "FAIL: stdout carried non-JSON bytes (protocol corruption)"; fail=1
else
  echo "ok:   stdout carried only JSON-RPC frames"
fi

[ "$fail" -eq 0 ] && echo "SMOKE OK" || echo "SMOKE FAILED"
exit "$fail"
