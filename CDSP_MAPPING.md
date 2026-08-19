# Mapping the COVESA CDSP knowledge layer onto CQELS

This document maps the [COVESA Central Data Service Playground](https://github.com/COVESA/cdsp)
(CDSP) knowledge layer onto CQELS 2.0, rule by rule and query by query. It is written against
what CDSP **actually runs today**, not a planned configuration, and every capability claim about
CQELS below was verified by running it on `2.0.0-alpha.18`.

> **Scope note.** A natural question is how S2DM-on-MongoDB use cases map here. They do not,
> because they do not exist yet: [S2DM](https://github.com/COVESA/s2dm) has no MongoDB binding —
> its exporters target schema languages and RDF (`avro`, `jsonschema`, `linkml`, `protobuf`,
> `shacl`, `skos`, `vspec`), not databases — and CDSP's own implementation concept
> records that "for the application database archetype MongoDB Realm was selected. Support for it
> is currently a work in progress as part of the playground project backlog." The incumbent CQELS
> would actually displace is **RDFox**, not a document store. For the S2DM concept layer itself,
> see the three demos under [`examples/`](examples/README.md#s2dm-concept-layer-orgcqelsexamplescdsp).

---

## 1. What CDSP runs today

| Layer | Backend | Evidence |
|-------|---------|----------|
| Information layer (data store) | **Apache IoTDB** | `handlers/src/HandlerCreator.ts` has a single `case "iotdb"`; every other value throws `Unsupported handler type`. `handlers/src/` contains one handler directory. |
| Knowledge layer (reasoning) | **RDFox** | `model_config.json` → `"reasoner_settings": { "inference_engine": "RDFox", … }`, backed by `symbolic-reasoner/rdfox/src/rdfox_adapter.cpp`. No other engine appears anywhere in the repository. |

The pipeline is:

```
vehicle JSON  --WebSocket-->  triple assembler  -->  RDFox materialisation  -->  output SPARQL
                              (helper .rq)           (.dlog rules + SHACL)       (select_*.rq)
```

Every stage is batch-shaped: assemble triples, materialise consequences, then ask. CQELS
registers the question once and answers it as data arrives, which is what collapses the pipeline
below.

---

## 2. The use case: aggressive driving / driving style

CDSP's worked example detects aggressive driving from steering-angle swings at speed, and reports
driving *segments*. Inputs (`inputs/vehicle_data_required.txt`) are seven plain VSS paths. The *detection rules*
(4, 5, 7) use `Vehicle.Chassis.SteeringWheel.Angle` and `Vehicle.Speed`; rule 6 and the output
query additionally need `Vehicle.CurrentLocation.Latitude` and `.Longitude` for the fix points.

It is implemented as eight Datalog rules plus one output query.

### 2.1 Rule-by-rule

| CDSP rule (`driving_style_inference_rules.dlog`) | What it does | CQELS-QL equivalent |
|---|---|---|
| **2. `CurrentObservation`** | `AGGREGATE … BIND MAX(?pt)` per observed property, to find the most recent reading | **Not reproducible as a query** — see the note below. A window bounds *how old* a reading may be; it does not pick the *latest* one. Handled in the data model instead. |
| **3. `within3s`** | Pairs observations whose timestamps differ by less than a window size read from an ontology individual (`car:hasWindowSize "PT3S"^^xsd:duration`) | **Not needed as a rule** — becomes the window itself, `[RANGE 3s]`. Window semantics are syntax the planner understands, not data to be interpreted. |
| **4. `LargeAngleChange`** | Self-join on two angle observations in the window; `ABS(angle1 − angle2) > 210`; ordered by trimmed timestamp; `SKOLEM` mints an individual | Two angle patterns + `FILTER(STR(?f1) < STR(?f2))` + `BIND(ABS(…))` + `FILTER(?angleDiff > 210)`. No individual is minted. Note `<`, not `!=`: `!=` admits both orderings of the pair and double-counts every swing. |
| **5. `HighSpeedObservation`** | Current speed reading with `speed > 10`; `SKOLEM` mints an individual | One speed pattern + `FILTER(?speed > 10)`. |
| **6. `FixPoint`** | Correlates a latitude and a longitude observation by equal trimmed timestamp | Push lat+long as **one atomic element** (`DataStream.push(List<Statement>)`), and the correlation is structural — no timestamp-equality join at all. Same technique as `CorrelatedFaultCascade`. |
| **7. `AggressiveDriving`** | Joins rules 4 and 5 on equal trimmed timestamp | Co-occurrence in one window is a *weaker* join: it accepts any speed reading still in the window. Restored by frame co-location — see the note below. |
| **8. `Segment`** | Assembles start/end fix points, times, angle diff, window size; mints a segment IRI via `BIND(IRI(CONCAT(…)))` | Only partly. `GROUP BY` aggregates over the window and `BIND(IRI(CONCAT(…)))` does mint an IRI (verified) — but the start/end fix points and their timestamps need the pair *ordered in time*, which this release cannot do. See §2.2. |
| **9. `avgAngleChange`** | `AGGREGATE … BIND AVG(?angle_diff)` per segment | `(AVG(?angleDiff) AS ?avgAngleChange)` with `GROUP BY`. |

> **Two things a window does not give you, found in review.**
>
> **Rule 2 is not redundant.** A window says "no older than N seconds"; rule 2 says "the most
> recent". Those differ exactly when a signal changes inside the window — a vehicle at 64 km/h that
> slows to 4 and *then* swerves still has the 64 reading in scope, and a naive translation alerts on
> it. CQELS-QL cannot express "the latest value of X" on `2.0.0-alpha.18`: there is no event-time
> comparison (`xsd:dateTime` arithmetic is not evaluated — see §4) and no argmax. A second stream
> with its own `[TRIPLES 1]` window would say it, but multi-source joins with count windows are
> rejected: *"not yet supported … tracked as a follow-up to issue #185"*.
>
> The fix is in the **data model**, not the query. A telemetry *frame* carries angle and speed in one
> atomic element, as a vehicle actually samples them, so the speed guard reads the speed at the
> moment of the swing. `CdspDrivingStyle` scenario 4 is that case, and it stays silent.
>
> **Rule 4's ordering is only partly reproduced.** `STR(?f1) < STR(?f2)` de-duplicates the
> unordered pair; it does not order the two readings in time. That is fine for the symmetric `ABS`
> difference, and not fine for anything that labels a segment's *start* and *end* — which is exactly
> what §2.2 needs.

### 2.2 The output query

`select_driving_style.rq` projects segment id, average angle change, speed, duration and
start/end coordinates, closing with:

```sparql
BIND ((xsd:dateTime(?end) - xsd:dateTime(?start)) as ?time_diff)
FILTER (?time_diff < "PT4S"^^xsd:duration)
```

That final filter is a duration bound restated by hand because RDFox has no window operator. It is
subsumed by the window rather than translated: the shipped query pairs readings inside
`[RANGE 3s]`, and any pair it joins is therefore already less than 4 s apart. Note the two
intervals are not the same thing — rule 3's 3 s bounds the *pairing*, while this 4 s bounds a
*segment's* start-to-end span — which is why the subsumption is worth stating rather than assuming. This matters beyond
tidiness: as a `FILTER` it can only be applied *after* materialising every candidate segment,
whereas as a window it bounds the state the engine keeps in the first place.

The `REPLACE(REPLACE(?s, "^.*#", ""), "[^a-zA-Z0-9]", "")` segment-id cleanup is presentation, and
`REPLACE` is not supported (see §4) — do it in the result listener.

**What this mapping does not reproduce.** The output query projects a *segment*: an id, a start and
end fix point with their coordinates, and start and end timestamps. The continuous query in §3
projects per-vehicle aggregates over a window instead. Getting from one to the other needs the
start/end **ordering** of the angle pair, and the fix points correlated to those two instants — both
resting on the event-time comparison this release does not have. So the honest summary is: the
*detection* collapses to one query; the *segment assembly* does not, and would need either the
missing time comparison or a post-processing step in the listener holding the two frames.

---

## 3. The result: one query

The eight rules' **detection** collapses into a single registration — not the output query's
segment assembly, which §2.2 explains this cannot reproduce. That detection is
[`CdspDrivingStyle`](examples/src/main/java/org/cqels/examples/cdsp/CdspDrivingStyle.java),
which runs today:

```sparql
REGISTER QUERY AggressiveDriving AS
SELECT ?vehicle (MAX(?angleDiff) AS ?peakAngleChange)
                (AVG(?angleDiff) AS ?avgAngleChange)
                (COUNT(*) AS ?swings)
FROM STREAM Telemetry [RANGE 3s]
WHERE {
  STREAM Telemetry {
    ?f1 sosa:hasFeatureOfInterest ?vehicle .
    ?f1 vss:Chassis.SteeringWheel.Angle ?angle1 .
    ?f2 sosa:hasFeatureOfInterest ?vehicle .
    ?f2 vss:Chassis.SteeringWheel.Angle ?angle2 .
    ?f2 vss:Speed ?speed .
  }
  FILTER(STR(?f1) < STR(?f2))
  BIND(ABS(?angle1 - ?angle2) AS ?angleDiff)
  FILTER(?angleDiff > 210)
  FILTER(?speed > 10)
}
GROUP BY ?vehicle
```

`?f1` and `?f2` are telemetry **frames**, each carrying angle and speed together, which is what
makes `?speed` the speed *at* the swing rather than any speed still in the window.

Observed output, including both discriminating negatives:

```
Scenario 1 — EV-7Q2 swings 20 -> 250 deg at 64 km/h (should fire):
  AGGRESSIVE DRIVING -> {avgAngleChange=230.0, peakAngleChange=230.0, swings=1, vehicle=…/EV-7Q2}

Scenario 2 — EV-3K8 swings 15 -> 245 deg at 4 km/h (should stay quiet):
  (no alert: the swing is real but the vehicle is barely moving)

Scenario 3 — EV-9TZ swings 30 -> 55 deg at 80 km/h (should stay quiet):
  (no alert: fast, but only a 25 deg correction)

Scenario 4 — EV-7Q2 was at 64, SLOWED to 4, then swings 20 -> 250 (should stay quiet):
  (no alert: the swing happened at 4 km/h, though 64 is still in the window)
```

`swings=1`, not 2: `<` admits each unordered pair once. Scenario 4 is the stale-speed case.

The intermediate individuals RDFox mints — `LargeAngleChange`, `HighSpeedObservation`,
`AggressiveDriving`, `FixPoint` — exist to carry state from one Datalog rule to the next. A
continuous query has no such handoff, so neither the individuals nor the `SKOLEM(…)` calls that
name them are needed. That is the substance of the reduction, not a syntactic trick.

---

## 4. Function support, verified

Probed on `2.0.0-alpha.18` by registering one query per function and pushing a value.

| Function | Status | Consequence for this mapping |
|---|---|---|
| `ABS`, `CONCAT`, `IRI`, `SUBSTR`, `IF` | works | Rules 4, 8 port directly, IRI minting included |
| `SELECT DISTINCT`, `GROUP BY`, `AVG`/`MAX`/`COUNT`, `HAVING` | works | Rules 8, 9 and the output projection port directly |
| `REPLACE` | **silently unsupported** — the row is emitted with the variable unbound | Cosmetic segment-id cleanup moves to the listener |
| `xsd:dateTime` subtraction | **silently unsupported** — variable unbound | Not needed: the window replaces duration arithmetic |
| `xsd:duration` comparison | **silently unsupported** — filter never matches | Not needed, as above |

None of these raises at registration, which is what makes them expensive to find: the query goes
live and simply returns nothing for that expression. That is the same shape as
[#54](https://github.com/cqels/CQELS4J/issues/54), [#55](https://github.com/cqels/CQELS4J/issues/55)
and [#57](https://github.com/cqels/CQELS4J/issues/57). [#56](https://github.com/cqels/CQELS4J/issues/56)
is the exception worth knowing: it *does* print a parser diagnostic to stderr — but registration
succeeds anyway, so the query still runs and the diagnostic is easy to scroll past.

---

## 5. What this port needs that is not ready

| Need | Status | Issue |
|---|---|---|
| Guard on a static pattern (e.g. restrict to vehicles in a fleet, or to a modelled signal set) | **Broken** — a non-matching static pattern fails to eliminate the row, so guards over-report | [#54](https://github.com/cqels/CQELS4J/issues/54) |
| Filter on an enumerated IRI using a prefix, e.g. a VSS/S2DM enum value | Works only with full IRIs; prefixed names silently never match | [#55](https://github.com/cqels/CQELS4J/issues/55) |
| Transitive traversal of a vocabulary hierarchy (`skos:broader+`, `rdfs:subClassOf+`) | Property paths parse-error yet still register and evaluate something else | [#56](https://github.com/cqels/CQELS4J/issues/56) |
| Rules over atomically pushed multi-statement observations — the natural shape for SOSA, and required by rule 6's fix-point correlation | Reasoner silently skips multi-statement elements | [#57](https://github.com/cqels/CQELS4J/issues/57) |
| Rules matching a background ontology (CDSP loads `.ttl` ontologies alongside its rules) | Reasoner sees stream elements only; ontology must be pushed onto the stream | [#58](https://github.com/cqels/CQELS4J/issues/58) |

The driving-style query above needs **none** of these, which is why it runs today. They bite as
soon as the port moves past this one use case: #57 and #58 together are what stop CDSP's
`.dlog` rules from being carried over as rules rather than rewritten as queries, and #54 is
required before any static guard can be trusted.

Also relevant: CDSP validates with SHACL (`vehicle_shacl.ttl`, `observation_shacl.ttl`), and
`cqels-shacl` is published, so that stage has a counterpart. There is no published GraphQL surface
([#59](https://github.com/cqels/CQELS4J/issues/59)), which is what an S2DM-native consumer would
want.

---

## 6. Ingestion

CDSP already exposes the data CQELS needs, in two places:

- **IoTDB.** CDSP upstreamed IoTDB support into VISSR, and CQELS has two IoTDB storage modules.
  `cqels-storage-iotdb-tsfile` **is** published at `2.0.0-alpha.18`; `cqels-storage-iotdb-session`
  is **not** (404 on the release mirror), so that half means building from the engine repository.
- **VISS over WebSocket.** The information layer speaks VISS; the unpublished `cqels-cdsp` module
  contains a `CdspWebSocketClient` and a `VssToRdfMapper` that already do the JSON→RDF step CDSP's
  C++ triple assembler performs. Publishing it would remove the hand-written mapping layer from
  this port entirely.

Either way the destination is the same: `DataStream.push(…)` with observations shaped as SOSA.
Note which helper: the driving-style query reads angle and speed from the SAME
frame, so it consumes [`Fleet.pushFrame`](examples/src/main/java/org/cqels/examples/Fleet.java),
not the one-signal-per-observation `Fleet.pushObservation`. A CDSP feed delivering each signal as
its own observation would need grouping into frames first — which is where the "latest value"
problem of §2.1 reappears, and worth designing for before wiring a real feed.

---

## 7. What stays in CDSP

This is not a replacement for CDSP. The information layer, the VISS interface, the data-store
handlers and the SHACL shapes are all orthogonal to how inference is evaluated. What changes is
the knowledge layer's engine: from materialise-then-query to register-then-stream. The `.rq`
output queries are already SPARQL and port largely as written; the `.dlog` rules are where the
reduction happens, because most of them encode temporal bookkeeping that a streaming engine
performs natively.
