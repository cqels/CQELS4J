# RDF Messages in CQELS

The W3C RSP Community Group's [RDF Messages](https://w3c-cg.github.io/rsp/spec/messages) draft
defines how RDF is cut into discrete messages for streaming, and how a stream of messages is
written to a file and read back. A continuous query engine is exactly the consumer that draft has
in mind, and CQELS already treats a stream element as one atomic unit. This document records how
the two fit, what the engine provides, what this repository adds, and what is still missing.

Everything below was measured against `2.0.0-alpha.22` and the draft dated **22 October 2026**
(a Living Document; its profiles section is still a TODO, and its JSON-LD and YAML-LD parts are
marked preliminary).

The worked example is
[`MessageLogReplay`](examples/src/main/java/org/cqels/examples/messages/MessageLogReplay.java).

## 1. What the draft defines

- **RDF Message:** an RDF dataset "intended to be interpreted atomically as a single communicative
  act". It may be empty.
- **RDF Message Stream:** an ordered, possibly unbounded sequence of messages. The transport
  (WebSockets, Server-Sent Events, MQTT, Kafka, a programming-language stream) is out of scope.
- **Scope:** by default each message is its own "world": what one message asserts is not asserted
  by the others. A *stream profile* may say otherwise (§3, unwritten). Blank nodes are scoped to
  their message.
- **RDF Message Log:** a stream written to a file. In N-Triples, N-Quads, Turtle and TriG a
  version label with a `-messages` suffix (`VERSION "1.2-messages"`, also `1.1-messages` and
  `1.2-basic-messages`) announces it, and `MESSAGE` (Turtle/TriG also `@message .`) separates
  messages. A delimiter before any statement makes the first message empty; a trailing delimiter
  adds none. `PREFIX` and `BASE` carry over and may be redefined. The draft also proposes
  NDJSON-LD, YAML-LD and an RDF/XML wrapper.

## 2. How it maps onto CQELS

| Draft | CQELS (measured on `2.0.0-alpha.22`) |
|---|---|
| RDF Message | One stream element: one `DataStream.push(Collection<Statement>, long)`. `[TRIPLES n]` counts elements, not statements (CQELS-QL_SPEC.md §3) |
| Empty message | `push(List.of(), t)` is accepted and takes a window slot: under `[TRIPLES 2]`, pushing a two-statement element, an empty one, then a one-statement element gives counts 2, 2, 1, because the empty element pushed the first one out |
| RDF Message Stream | A `DataStream`. Elements are delivered in arrival order (pushed at event times 3000, 1000, 2000, they arrive in that order); time windows use each element's event time |
| Each message its own world | `[NOW]` and `[TRIPLES 1]` see one message at a time |
| A profile that combines messages | A wider window (`[RANGE]`, `[TRIPLES n]`) joins and aggregates across messages. The draft allows combining when a profile says so (§1.3) but has not yet defined profiles (§3), so treating the window as that choice is this demo's reading, not the draft's |
| Blank nodes scoped to a message | Holds when each message is parsed on its own, which both readers below do |
| A message is a dataset | The engine keeps a quad's graph name, but a query cannot read it: `GRAPH` inside `STREAM` is refused ([#125](https://github.com/cqels/CQELS4J/issues/125)) |
| Producing messages (§4.4, `CONSTRUCT` results) | No continuous `CONSTRUCT` ([#45](https://github.com/cqels/CQELS4J/issues/45)); results are `SELECT` rows |

## 3. What the engine provides

`org.cqels.stream.codec.RdfMessageCodec` (cqels-core) reads and writes N-Quads message logs, which
covers N-Triples too. The existing
[`RdfMessageIngestion`](examples/src/main/java/org/cqels/examples/RdfMessageIngestion.java) demo
uses it. Measured against the draft's own §2.1.2 examples and some edge cases:

| Case | Codec |
|---|---|
| two messages; empty first message; trailing delimiter adds nothing; three trailing delimiters | correct |
| `1.1-messages`, `1.2-basic-messages`, `1.2-messages` labels | accepted |
| blank nodes: the same `_:x` in two messages | two different blank nodes |
| a quad's graph name | kept |
| CRLF line endings; a comment after a triple or after `VERSION`; a comment on its own line between two messages | correct |
| **`MESSAGE # comment`** (the draft's own example uses it), and `MESSAGE#comment` | **rejected**: `RDFParseException: Expected '<' or '_', found: M` ([#126](https://github.com/cqels/CQELS4J/issues/126)) |
| **a comment-only line after the last delimiter**, or as the only line after `VERSION` | **opens an empty message**: triple · `MESSAGE` · `# tail` reads as `[1, 0]`, not `[1]`; `VERSION` · `# c` as `[0]`, not `[]` (#126) |
| **writing a log whose last message is empty** | **lost on re-reading**: `[1, 0]` comes back as `[1]`, `[0]` as `[]` (#126) |
| `MESSAGE` with no version label, or a label without `-messages` | split into messages rather than rejected (lenient; noted in #126) |

What it does not cover: Turtle, TriG, and the JSON-LD, YAML-LD and RDF/XML forms. RDF4J 4.3.8, the
version the engine ships, rejects both `VERSION` and `MESSAGE` in Turtle and N-Triples, so a Turtle
log cannot simply be handed to RDF4J.

## 4. What this repository adds

[`RdfMessageLog`](examples/src/main/java/org/cqels/examples/messages/RdfMessageLog.java)
(`org.cqels.examples.messages`):

- **N-Triples and N-Quads** go to the engine codec, with three workarounds for #126: before
  parsing, a comment after `MESSAGE` (with or without a space) is removed, and so is every
  comment-only line; and when the last message is empty the writer adds one more delimiter so it
  survives re-reading. The probe below says when each can go.
- **Turtle and TriG** are tokenised here and each message is parsed by RDF4J's ordinary parser:
  - strings (including long strings spanning lines), IRIs, numbers, language tags and names are
    read by Turtle's own token rules, so `MESSAGE` inside any of them is not a delimiter
    (`ex:MESSAGE`, `ex:x..MESSAGE`, `MESSAGE.foo:s`, escaped names such as `ex:x\.MESSAGE`), and a
    dot right after a token still ends the statement (`1.MESSAGE`, `1.e2.MESSAGE`, `true.MESSAGE`,
    `"x"@en.@message .`, `ex:.MESSAGE` all cut, and a `PREFIX` glued to such a dot is carried);
  - name characters include non-ASCII ones, and only ASCII digits start a number, so a prefix
    such as `١:` is a prefix;
  - a delimiter counts only where a statement may start, not inside `[ ]`, `( )` or a graph block;
  - comments may stand wherever whitespace may, including inside directives;
  - `VERSION` / `@version` is removed before parsing; its label must be a short quoted string
    with valid escapes (Turtle's VersionSpecifier), is decoded (a hyphen written as `\u002D` is a
    hyphen) and applies from where it stands;
  - `PREFIX` / `BASE` (and the `@` forms) are carried into later messages, with a redefinition
    applying from that point on;
  - each message is parsed separately, so blank nodes are scoped to it;
  - an unterminated string, IRI or label is an error.
- **Strict where the codec is lenient:** a Turtle/TriG delimiter is accepted only while the label
  most recently announced ends in `-messages`.
- **A Turtle writer** that terminates every message with `MESSAGE`, so an empty last message
  round-trips. Each message carries its own `@prefix` lines, which §2.1.3 allows. Turtle has no
  named graphs, so a statement with a graph name is refused (use the N-Quads writer, which keeps it).

Tested by [`RdfMessageLogTest`](examples/src/test/java/org/cqels/examples/messages/RdfMessageLogTest.java)
(29 tests: the draft's §2.1.2 and §2.1.3 examples in N-Triples, Turtle and TriG, the old-style
directives, blank-node scoping, names, numbers and tags next to `MESSAGE` or dots checked against
RDF4J's own parse, prefix carry after such tokens, non-ASCII prefixes, comments inside directives,
version labels in order and malformed ones, unterminated tokens, TriG graph blocks, RDF-star
quoted triples, the N-Triples comment forms, and writer round-trips with empty messages first,
last and in between).

Not handled: RDF 1.2 triple terms (`<<( s p o )>>`), because RDF4J 4.3.8 does not parse them (a
`1.2-messages` label is accepted, triple terms are not); RDF-star quoted triples (`<< s p o >>`)
pass through, since RDF4J 4.3.8 reads those. NDJSON-LD, YAML-LD and the RDF/XML wrapper are not
handled.

## 5. The worked example

[`MessageLogReplay`](examples/src/main/java/org/cqels/examples/messages/MessageLogReplay.java)
replays [`cabin-temperatures.ttl`](examples/src/main/resources/messages/cabin-temperatures.ttl), a
Turtle message log: a message describing the stream, four `sosa:Observation` messages whose result
is the blank node `_:r` (the same label in each), an empty heartbeat, a `MESSAGE # comment`, and a
`PREFIX ex:` redefined for the second bus. Event time is each message's `sosa:resultTime`.

| Step | Measured |
|---|---|
| Read the log | 6 messages, sizes `[2, 6, 6, 0, 6, 6]` (the trailing delimiter adds none) |
| `Reading` over `[TRIPLES 1]`: one message at a time | 4 rows, each observation with its own value |
| `CabinAverage` over `[RANGE 30s]`: messages combined | `bus-17-front` averages 22.0 over 2 readings; the heartbeat adds no reading but re-reports the groups, as a windowed `GROUP BY` does on every arrival |
| The same log as one plain Turtle document | the four `_:r` become **1** blank node, and `Reading` returns **16** rows: every observation joined to every value |
| Each `Reading` row written as one message of a new Turtle log | 4 messages of 5 statements, read back as `[5, 5, 5, 5]` |

The fourth row is the draft's case for per-message blank-node scoping, measured.

## 6. Not done yet

- **NDJSON-LD.** `rdf4j-rio-jsonld` 4.3.8 would parse each line; the draft's context-message rule
  (a line with only `@context` sets the context for later lines) is the part to write.
- **Transport.** The draft's §4.1 examples (Server-Sent Events with an `RDF-Message-Content-Type`
  header, WebSocket subprotocols, MQTT content type) are out of its scope and out of this demo's.
  The Jetty 12 in [`sparql-endpoint/`](sparql-endpoint/) could serve query results as
  Server-Sent Events, one message per event.
- **Profiles.** When the draft's §3 is written, this demo's reading in §2 (a window wider than
  one message is how messages are combined) should be checked against it.
- **The draft's companion "RDF Messages Tests"** suite has not been run against `RdfMessageLog`.

## 7. Keeping this true

[`CapabilityProbe`](examples/src/main/java/org/cqels/examples/CapabilityProbe.java)
(`scripts/ci/capability-probe.sh`, run on every PR) asserts the four gaps above in both
directions, so this document and the workarounds cannot outlive a fix unnoticed:

- #126, `MESSAGE # comment` rejected by the codec (with a control: the same log without the
  comment must read as two messages);
- #126, a comment-only line after the last delimiter opening an empty message;
- #126, a trailing empty message lost by `writeNQuads`;
- #125, `GRAPH` inside `STREAM` refused;

and, as a capability the demo relies on, that the codec keeps empty messages, scopes blank nodes per
message and keeps a quad's graph name.
