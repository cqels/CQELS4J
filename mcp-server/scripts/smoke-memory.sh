#!/usr/bin/env bash
# Stdio smoke test for the alpha.7 agent-memory demo tools + event-time detect_sequence.
#
# Drives one JSON-RPC session through: store_fact -> record_event x2 -> recall_episodes ->
# save_procedure -> list_procedures -> run_procedure -> assemble_context -> detect_sequence
# (fail-fast without lateness_ms; event-time mode; arrival-order mode) -> out-of-order
# push_event fixture -> poll_results for both CEP queries -> unregister-CEP proof (a stopped
# detect_sequence matcher must not surface later would-be matches) -> wrong-type event_time
# rejection. Then asserts expected substrings.
#
# The out-of-order fixture is the #429-F3 story: the REAL order was a speed DROP (t=1000ms)
# then a speed SPIKE (t=2000ms), but the spike ARRIVES first. Arrival-order matching misses
# the sequence; event-time matching (lateness budget 3000ms) reorders and matches it.
#
# Usage: scripts/smoke-memory.sh   (builds target/cqels-mcp-server.jar if missing)

set -u
cd "$(dirname "$0")/.."

JAR=target/cqels-mcp-server.jar
if [ ! -f "$JAR" ]; then
  echo "building $JAR ..."
  mvn -q package || exit 1
fi

OUT=$(mktemp)
trap 'rm -f "$OUT"' EXIT

DROP=https://covesa.global/fleet#SpeedDropEvent
SPIKE=https://covesa.global/fleet#SpeedSpikeEvent
HEARTBEAT=https://covesa.global/fleet#HeartbeatEvent
EV=https://example.org/fleet/vehicle/EV-7Q2

{
  emit() { printf '%s\n' "$1"; sleep 0.6; }
  emit '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"1.0"}}}'
  emit '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  # -- semantic/static memory: a fact the procedure + context bundle will surface -------------
  emit '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"store_fact","arguments":{"subject":"'"$EV"'","predicate":"https://covesa.global/fleet#assignedDriver","object":"https://example.org/fleet/driver/alice"}}}'
  # -- episodic memory: two timestamped episodes, then a time-ordered recall ------------------
  emit '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"record_event","arguments":{"event_type":"https://covesa.global/fleet#ChargeStartEvent","entity":"'"$EV"'","data":"station=depot-north","timestamp_ms":1000000}}}'
  emit '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"record_event","arguments":{"event_type":"https://covesa.global/fleet#ChargeStopEvent","entity":"'"$EV"'","timestamp_ms":2000000}}}'
  emit '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"recall_episodes","arguments":{"entity":"'"$EV"'"}}}'
  # -- procedural memory: save, list, run ------------------------------------------------------
  emit '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"save_procedure","arguments":{"name":"vehicle_profile","description":"Everything known about EV-7Q2","sparql":"SELECT ?p ?o WHERE { <'"$EV"'> ?p ?o }"}}}'
  emit '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"list_procedures","arguments":{}}}'
  emit '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"run_procedure","arguments":{"name":"vehicle_profile"}}}'
  # -- working memory: one facts+episodes+procedures bundle ------------------------------------
  emit '{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"assemble_context","arguments":{"entity":"'"$EV"'","limit":5}}}'
  # -- event-time CEP (#429 F3): engine fails fast when event_time=true has no budget ----------
  emit '{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"detect_sequence","arguments":{"stream":"Incidents","steps":["'"$DROP"'","'"$SPIKE"'"],"event_time":true}}}'
  # seq id note: the failed registration above still consumed seq_1, so the two below get
  # seq_2 (event-time) and seq_3 (arrival order).
  emit '{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"detect_sequence","arguments":{"stream":"Incidents","steps":["'"$DROP"'","'"$SPIKE"'"],"withinSeconds":60,"event_time":true,"lateness_ms":3000}}}'
  emit '{"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"detect_sequence","arguments":{"stream":"Incidents","steps":["'"$DROP"'","'"$SPIKE"'"],"withinSeconds":60}}}'
  # -- the out-of-order fixture: spike (t=2000) ARRIVES before drop (t=1000); the heartbeat
  #    (t=10000) advances the event-time watermark so the reorder releases the buffered pair ---
  emit '{"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"push_event","arguments":{"stream":"Incidents","subject":"https://example.org/fleet/event/e-spike","predicate":"a","object":"'"$SPIKE"'","timestamp_ms":2000}}}'
  emit '{"jsonrpc":"2.0","id":14,"method":"tools/call","params":{"name":"push_event","arguments":{"stream":"Incidents","subject":"https://example.org/fleet/event/e-drop","predicate":"a","object":"'"$DROP"'","timestamp_ms":1000}}}'
  emit '{"jsonrpc":"2.0","id":15,"method":"tools/call","params":{"name":"push_event","arguments":{"stream":"Incidents","subject":"https://example.org/fleet/event/e-heartbeat","predicate":"a","object":"'"$HEARTBEAT"'","timestamp_ms":10000}}}'
  sleep 1
  emit '{"jsonrpc":"2.0","id":16,"method":"tools/call","params":{"name":"poll_results","arguments":{"queryId":"seq_2"}}}'
  emit '{"jsonrpc":"2.0","id":17,"method":"tools/call","params":{"name":"poll_results","arguments":{"queryId":"seq_3"}}}'
  # -- unregister-CEP proof: register a matcher (seq_4), STOP it via unregister_stream_query
  #    (routes to the engine's unregisterCepQuery), then push events that WOULD match in
  #    arrival order — nothing may surface: the matcher is disposed, not merely muted --------
  emit '{"jsonrpc":"2.0","id":18,"method":"tools/call","params":{"name":"detect_sequence","arguments":{"stream":"Unreg","steps":["'"$DROP"'","'"$SPIKE"'"],"withinSeconds":60}}}'
  emit '{"jsonrpc":"2.0","id":19,"method":"tools/call","params":{"name":"unregister_stream_query","arguments":{"queryId":"seq_4"}}}'
  emit '{"jsonrpc":"2.0","id":20,"method":"tools/call","params":{"name":"push_event","arguments":{"stream":"Unreg","subject":"https://example.org/fleet/event/u-drop","predicate":"a","object":"'"$DROP"'"}}}'
  emit '{"jsonrpc":"2.0","id":21,"method":"tools/call","params":{"name":"push_event","arguments":{"stream":"Unreg","subject":"https://example.org/fleet/event/u-spike","predicate":"a","object":"'"$SPIKE"'"}}}'
  sleep 1
  emit '{"jsonrpc":"2.0","id":22,"method":"tools/call","params":{"name":"poll_results","arguments":{"queryId":"seq_4"}}}'
  # -- strict arg typing: a STRING "true" for event_time must be rejected, not coerced/ignored --
  emit '{"jsonrpc":"2.0","id":23,"method":"tools/call","params":{"name":"detect_sequence","arguments":{"stream":"Incidents","steps":["'"$DROP"'","'"$SPIKE"'"],"event_time":"true","lateness_ms":3000}}}'
  sleep 3
} | java -jar "$JAR" > "$OUT" 2>/dev/null &

# Wait for the last response (id 23), then stop the long-running server.
for _ in $(seq 1 90); do
  grep -q '"id":23' "$OUT" && break
  sleep 1
done
kill %1 2>/dev/null
wait 2>/dev/null

fail=0
assert_contains() { # desc, needle [, line-filter]
  local desc=$1 needle=$2 filter=${3:-}
  local haystack="$OUT"
  if [ -n "$filter" ]; then
    if grep -F -- "$filter" "$OUT" | grep -qF -- "$needle"; then echo "ok:   $desc"; else echo "FAIL: $desc (missing: $needle)"; fail=1; fi
  else
    if grep -qF -- "$needle" "$haystack"; then echo "ok:   $desc"; else echo "FAIL: $desc (missing: $needle)"; fail=1; fi
  fi
}

assert_contains "store_fact stored the driver fact"            "Stored:"                                   '"id":2'
assert_contains "record_event stored episode 1"                "recorded episode"                          '"id":3'
assert_contains "record_event stored episode 2"                "ChargeStopEvent"                           '"id":4'
assert_contains "recall_episodes returned the charge episodes" "ChargeStartEvent"                          '"id":5'
if grep -- '"id":5' "$OUT" | grep -qE 'ChargeStopEvent.*ChargeStartEvent'; then
  echo "ok:   recall_episodes is newest-first (stop before start)"
else
  echo "FAIL: recall_episodes ordering (expected ChargeStopEvent before ChargeStartEvent)"; fail=1
fi
assert_contains "save_procedure saved it"                      "saved procedure 'vehicle_profile'"         '"id":6'
assert_contains "list_procedures lists it"                     "vehicle_profile"                           '"id":7'
assert_contains "run_procedure ran the stored SPARQL"          "alice"                                     '"id":8'
assert_contains "assemble_context: facts section"              "assignedDriver"                            '"id":9'
assert_contains "assemble_context: episodes section"           "ChargeStopEvent"                           '"id":9'
assert_contains "assemble_context: procedures section"         "vehicle_profile"                           '"id":9'
assert_contains "event_time without lateness_ms fails FAST"    "lateness budget"                           '"id":10'
assert_contains "event-time detect_sequence registered"        "EVENT-TIME order"                          '"id":11'
assert_contains "arrival-order detect_sequence registered"     "arrival order"                             '"id":12'
# The match proves reordering: startTime=1000 (drop) < endTime=2000 (spike) although the
# spike arrived first. Arrival order sees spike-then-drop and correctly finds nothing.
assert_contains "event-time mode MATCHED the out-of-order SEQ" "PatternMatch{events=2"                     '"id":16'
assert_contains "event-time match is drop(1000)->spike(2000)"  "startTime=1000, endTime=2000"              '"id":16'
assert_contains "arrival-order mode missed the same sequence"  "(no new results)"                          '"id":17'
# Unregister-CEP proof: the response confirms the ENGINE disposed the matcher (unregisterCepQuery
# returned true), and polling the stopped id surfaces nothing for the would-match pushes.
assert_contains "detect_sequence registered seq_4 for unregister test" "seq_4"                             '"id":18'
assert_contains "unregister stopped the CEP matcher"           "(CEP matcher stopped)"                     '"id":19'
assert_contains "stopped matcher surfaced no rows for would-match events" "unknown query id"               '"id":22'
if grep -- '"id":22' "$OUT" | grep -qF 'PatternMatch'; then
  echo "FAIL: stopped seq_4 still surfaced a match"; fail=1
else
  echo "ok:   stopped seq_4 surfaced no match"
fi
assert_contains "string \"true\" for event_time is rejected"   "'event_time' must be a boolean"            '"id":23'

if [ "$fail" -eq 0 ]; then
  echo "SMOKE PASSED"
else
  echo "SMOKE FAILED — full transcript:"
  cat "$OUT"
  exit 1
fi
