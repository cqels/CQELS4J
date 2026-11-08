# Mapping COVESA S2DM onto CQELS

[S2DM](https://github.com/COVESA/s2dm) (Simplified Semantic Data Modeling) is COVESA's approach
to modelling vehicle data: subject-matter experts write the model as a GraphQL schema, and s2dm's
tools turn it into whatever a consuming system needs. One of those outputs is RDF, and RDF is
what CQELS can load as background data and join live streams against.

This document records what s2dm produces, how this repository obtains real s2dm output, what that
output lets a continuous query do, and what it does not carry. The worked example is
[`TrailerHealthMonitor`](examples/src/main/java/org/cqels/examples/s2dm/TrailerHealthMonitor.java).

Everything below was measured against s2dm commit
[`d97b449`](https://github.com/COVESA/s2dm/tree/d97b449a7cd4bee9298eb9868545eded68f975d2)
(3 November 2026, s2dm version 0.33.0).

## 1. What s2dm provides

- **Modelling conventions.** The model is GraphQL. Custom directives add what GraphQL lacks:
  `@instanceTag` (a field that says *which* instance a value belongs to, such as which axle),
  `@range(min, max)`, `@cardinality`, `@noDuplicates`, `@metadata` and `@reference`. Units are
  enums, written as a field argument with a default, e.g.
  `pressure(unit: Pressure_Unit_Enum = KILOPASCAL): UInt16`.
- **A command-line tool**, `s2dm`, which can:
  - merge schema files (`compose`) and resolve dependencies between models (`deps`);
  - check a model (`check constraints`, `check version-bump`, `diff`);
  - export a model to SHACL, VSPEC (the YAML format of COVESA VSS), JSON Schema, LinkML, Avro,
    Protobuf and RDF (`export …`);
  - run three built-in SPARQL queries over the RDF (`query`), and search the SKOS labels
    (`search skos`);
  - keep concept identifiers and a specification history (`registry`), sync unit enums from QUDT
    (`units sync`), and run similarity search, model insights, a web playground and a REST API.

## 2. Getting real output

[`scripts/s2dm/export-trailer.sh`](scripts/s2dm/export-trailer.sh) runs
`s2dm export rdf` on s2dm's own trailer example schema
(`examples/trailer/full_schema.graphl`) and writes the result to
[`examples/src/main/resources/s2dm/trailer/`](examples/src/main/resources/s2dm/trailer/). The
two files there are committed exactly as s2dm wrote them.

s2dm runs in Docker ([`scripts/s2dm/Dockerfile`](scripts/s2dm/Dockerfile)). Fetching the pinned
s2dm commit and building the image need network access. The export itself runs in a throwaway
container with no network, a read-only root filesystem, the caller's uid, and only the input
schema (read-only) and the output folder mounted.

What it took to get there, so the next person does not repeat it:

1. **Dependencies must come from s2dm's lockfile.** `pip install` of s2dm 0.33.0 resolves
   graphql-core 3.3.2, and s2dm then crashes with `'NoneType' object is not iterable` when it
   merges schema files or exports VSPEC (the two cases tried). The lockfile pins graphql-core 3.2.6, and with it the
   crash is gone. The Dockerfile installs with `uv sync --frozen`. The RDF export itself gave
   triple-for-triple identical output under both installs.
2. **Not every example schema exports.** Of the s2dm schemas tried at this commit:
   - `examples/trailer/full_schema.graphl` (the one used here) and
     `docs-gen/static/examples/seat-to-vspec/full_sdl.graphql` export;
   - the modular `examples/trailer/modular_trailer_example/` and
     `examples/seat-to-vspec/modular_seat_spec/` fail with `Unknown directive '@instanceTag'` and
     `Unknown directive '@metadata'`: they use s2dm's directives without declaring them;
   - `docs-gen/static/examples/seat-capabilities/full_schema.graphql` has a GraphQL syntax error
     (an unquoted description at line 69);
   - `examples/graphql-to-skos/sample.graphql` and `examples/schema-rdf/sample.graphql` fail with
     `Unknown type 'VelocityUnit'` and `Unknown directive '@instanceTag'`.
3. **The exporters that carry units and ranges reject the trailer schema.** Reading the s2dm
   source: the VSPEC exporter writes units (through its own symbol table), `@range` min/max and
   instances; the LinkML exporter writes units and `@range` min/max; the JSON Schema exporter
   writes `@range` as `minimum`/`maximum` but no units. All three were run on the trailer schema,
   and all three stop at s2dm's current `@instanceTag` check with the same three errors
   (`VehicleTrailer.trailer must reference an object or enum type with @instanceTag`, and the same
   for the axle and wheel tags). Fixing the tags would not be enough for VSPEC's units either: its
   symbol table has `KILOPA`, not the trailer schema's `KILOPASCAL`.

None of these has been reported to COVESA/s2dm.

## 3. What the RDF export contains

`s2dm export rdf --namespace <ns>` writes two graphs, each in every format asked for (by
default N-Triples and Turtle, so four files; the script asks for Turtle only).

**`skos.ttl`: concepts and labels.**

| GraphQL | RDF |
|---|---|
| object, interface, input or union type | `skos:Concept`, member of `ObjectConcepts` (or `InterfaceConcepts`, `InputObjectConcepts`, `UnionConcepts`) |
| field | `skos:Concept` named `Type.field`, member of `FieldConcepts` |
| enum | `skos:Collection` whose members are its values |
| enum value | `skos:Concept` named `Enum.VALUE`, member of its enum's collection and of `FieldConcepts` |

Every concept has a language-tagged `skos:prefLabel`. A **type** with a description in the schema
also gets a `skos:definition` and a `skos:note` saying where the definition came from; a described
**enum** gets the `skos:definition` without the note. A **field** never gets a `skos:definition`, even when the schema describes it: the exporter
passes an empty description for every field (`rdf_materializer.py`, `_skos_field_container`).

**`data_graph.ttl`: the schema's structure.** The `s2dm:` types live here only; `skos.ttl` has
no `s2dm:ObjectType` or `s2dm:Field` assertions.

| GraphQL | RDF |
|---|---|
| object, interface or input type | `s2dm:ObjectType` (or `InterfaceType`, `InputObjectType`), with `s2dm:hasField` to each field |
| union | `s2dm:UnionType`, with `s2dm:hasUnionMember` to each member type |
| field | `s2dm:Field`, `s2dm:hasOutputType` (a type, an enum, `s2dm:Boolean`/`Int`/`Float`/`String`/`ID`, or a custom scalar such as `ns:UInt16`), and `s2dm:usesTypeWrapperPattern` (`bare`, `nonNull`, `list`, …) |
| enum | `s2dm:EnumType`, `s2dm:hasEnumValue` to each value |
| enum value | `s2dm:EnumValue` |

For the trailer schema: 181 concepts in 44 collections, 6 definitions (all on types); 12 object
types, 3 input types, 55 fields, 39 enums and 111 enum values; 1,250 triples in all.

The `s2dm:` terms live at `https://covesa.global/models/s2dm#`. The concepts live under whatever
`--namespace` the export was given; this repository uses `https://example.org/s2dm/trailer#`.

## 4. The worked example

[`TrailerHealthMonitor`](examples/src/main/java/org/cqels/examples/s2dm/TrailerHealthMonitor.java)
loads both files as background data and streams wheel readings from a truck with trailers. Each
reading is one atomic element: the s2dm field it reports, the vehicle, the value, and the four
instance tags the schema declares for a wheel (trailer; and axle, side and position from the
outside in, which tells twin tires apart), using the export's own enum-value IRIs.
That encoding is the demo's own: s2dm defines the model, not an RDF form for instance data.

| Query | What it reads from the model | Measured result |
|---|---|---|
| `PlacedReading` | the field's owning type, the slot that type fills, the slot's owner, and the owning type's definition | a tire pressure reading is placed as `VehicleTrailerWheel.tire` → `TrailerWheelTire`, "Tire signals for wheel."; a brake reading as `VehicleTrailerWheel.brake` → `TrailerWheelBrake` |
| `BooleanAlarm` | which fields return `s2dm:Boolean` | `isPressureLow = true` and `isBrakesWorn = true` raise alarms; `isBrakesWorn = false` and numeric readings do not; none of the three boolean fields is named in the query. This treats every `true` boolean as an alarm, which holds for this schema's three boolean fields; a boolean where `true` is good news (an `isHealthy`) would raise a false alarm |
| `LowestPressure` | nothing (instance tags only) | lowest tire pressure per trailer and axle over 10 s |

One reading is deliberately wrong: `TrailerWheelTire.isPresureLow = true`, a misspelled field. It
produces neither a placement nor an alarm, because the model does not contain that field. With
the `s2dm:Boolean` guard removed, the same reading does raise an alarm (measured), so the guard is
what rejects it, not the filter on the value.

## 5. What the export does not carry

- **Units.** The export drops field arguments, so `unit: Pressure_Unit_Enum = KILOPASCAL` is lost.
  A query cannot convert or check units from the RDF.
- **`@range`.** Not exported. A range check (reading outside min/max) has nothing to join against.
- **Which fields are instance tags.** `@instanceTag` is not exported, so a query cannot ask the
  model which parts of a reading identify the instance. The demo knows it from the schema.
- **Field descriptions.** See §3. A query that wants to explain a field has to use its owning
  type's definition, as `PlacedReading` does.
  [`S2dmConceptCatalog`](examples/src/main/java/org/cqels/examples/cdsp/S2dmConceptCatalog.java)
  joins on field definitions, which s2dm's older SKOS exporter (`s2dm generate skos-skeleton`)
  wrote and the current one does not, so over a real export its first query returns no rows for a
  field observation. (An observation tagged with a described type still matches; the query does
  not restrict its concept to fields.)
- **`s2dm:` typing in `skos.ttl`.** The older exporter typed each concept `s2dm:ObjectType` or
  `s2dm:Field` in the SKOS file; the current one puts that typing only in `data_graph.ttl`.
  `S2dmConceptCatalog`'s second query guards on `s2dm:Field`, so it needs both files loaded.
- **`skos:broader`.** Not written anywhere. The hierarchy that
  [`SkosConceptRollup`](examples/src/main/java/org/cqels/examples/cdsp/SkosConceptRollup.java)
  rolls up is asserted by that demo; the structure s2dm does export is `s2dm:hasField` and
  `s2dm:hasOutputType`.

The `org.cqels.examples.cdsp.S2dm*` demos predate this mapping. Their concept graph is hand-built
in the shape of the older SKOS exporter, over a vocabulary partly taken from s2dm's examples.

Units, `@range` and instances are written by other s2dm exporters (§2, item 3), but none of
those accepts the trailer schema at this commit.

## 6. CQELS limits this runs into

- **No property paths over background data**
  ([#46](https://github.com/cqels/CQELS4J/issues/46)). Walking the model to any depth would be
  `?owner (s2dm:hasField/s2dm:hasOutputType)+ ?component`, which is refused at registration when
  the path is outside the `STREAM` block. The demo walks a fixed number of steps with plain joins
  instead; the trailer model is shallow enough for that.
- **Rules cannot read background data** ([#58](https://github.com/cqels/CQELS4J/issues/58)). A
  rule that derived, say, "every field under a wheel" from `data_graph.ttl` would have to have the
  model pushed into the stream first.
- **A windowed `GROUP BY` re-reports on every arrival.** `LowestPressure` prints its current
  groups each time any reading arrives, including readings it filters out. See "Reading the
  output" in [examples/README.md](examples/README.md).

## 7. Regenerating

```bash
scripts/s2dm/export-trailer.sh
```

```bash
docker rmi cqels4j-s2dm:d97b449
```

The first command needs `git` and `docker` and rewrites the two files in
`examples/src/main/resources/s2dm/trailer/`. The second removes the image afterwards. To move to a
newer s2dm, change `S2DM_COMMIT` in the script, re-run it, and re-check §2–§5 against the new
output.
