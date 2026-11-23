# RDF Messages in CQELS

The W3C RSP Community Group's [RDF Messages](https://w3c-cg.github.io/rsp/spec/messages) draft
defines how RDF is cut into discrete messages for streaming, and how a stream of messages is
written to a file and read back. A continuous query engine is exactly the consumer that draft has
in mind, and CQELS already treats a stream element as one atomic unit. This document records how
the two fit, what the engine provides, what this repository adds, and what is still missing.

Everything below was measured against `2.0.0-alpha.22` and the draft dated **22 October 2026**
(a Living Document; its profiles section is still a TODO, and its JSON-LD and YAML-LD parts are
marked preliminary).

The worked examples are
[`MessageLogReplay`](examples/src/main/java/org/cqels/examples/messages/MessageLogReplay.java)
(a message log replayed from a file) and
[`MessageStreamOverSse`](examples/src/main/java/org/cqels/examples/messages/MessageStreamOverSse.java)
(the same messages over Server-Sent Events).

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
  adds none. `PREFIX` and `BASE` carry over and may be redefined. The draft also defines
  NDJSON-LD (§2.2), YAML-LD (§2.3, marked as still under discussion) and an RDF/XML wrapper
  (§2.4).
- **Version labels (§1.7):** `1.2-messages` is RDF 1.2 *with triple terms*; `1.2-basic-messages`
  and `1.1-messages` have none.
- **Carrying a stream (§4.1, illustrative):** Server-Sent Events with an `RDF-Message-Content-Type`
  header and one event per message; WebSocket subprotocols; the MQTT 5 content type.

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
| **an RDF 1.2 triple term** `<<( <a> <b> <c> )>>` after `VERSION "1.2-messages"` | **rejected** with a parse error, although that label announces triple terms ([#133](https://github.com/cqels/CQELS4J/issues/133)) |

What it does not cover: Turtle, TriG, and the JSON-LD, YAML-LD and RDF/XML forms. RDF4J 4.3.8, the
version the engine ships, rejects both `VERSION` and `MESSAGE` in Turtle and N-Triples, so a Turtle
log cannot simply be handed to RDF4J.

RDF4J 4.3.8 also predates RDF 1.2, and some RDF 1.2 syntax it reads *as different data*, without
an error (#133): in Turtle, `<< s p o >>` becomes an RDF-star quoted triple rather than a reifier,
`{| |}` an RDF-star annotation, and `"x"@ar--rtl` the language tag `ar--rtl`; in RDF/XML,
`rdf:parseType="Triple"` becomes an XML literal and `rdf:annotation` an extra triple. Its JSON-LD
parser (jsonld-java 0.13.4) implements JSON-LD 1.0, so it drops `@direction` and ignores a term's
scoped `@context`. The readers below refuse all of these rather than pass the misreading on.
Where JSON-LD 1.0 and 1.1 read the same input differently, the readers cannot tell, and the 1.0
reading applies (§4.3).

## 4. What this repository adds

All in `org.cqels.examples.messages`. Every reader returns one `List<Statement>` per message, which
is one `push`; every writer's output reads back to the same messages (the RDF/XML writer checks
each message, since RDF/XML cannot hold every graph).

| Format | Reader and writer | Built on |
|---|---|---|
| N-Triples, N-Quads (§2.1) | [`RdfMessageLog`](examples/src/main/java/org/cqels/examples/messages/RdfMessageLog.java) `parse`, `writeNQuads` | the engine codec, with workarounds for #126 |
| Turtle, TriG (§2.1) | `RdfMessageLog` `parse`, `writeTurtle`, `writeTriG` | a tokeniser here, RDF4J per message |
| NDJSON-LD (§2.2) | [`JsonLdMessageLog`](examples/src/main/java/org/cqels/examples/messages/JsonLdMessageLog.java) `parseNdjson`, `writeNdjson` | Jackson per line, `rdf4j-rio-jsonld` 4.3.8 per message |
| YAML-LD (§2.3) | `JsonLdMessageLog` `parseYaml`, `writeYaml` | snakeyaml-engine (YAML 1.2), `rdf4j-rio-jsonld` per document |
| RDF/XML (§2.4) | [`RdfXmlMessageLog`](examples/src/main/java/org/cqels/examples/messages/RdfXmlMessageLog.java) `parse`, `write` | StAX, `rdf4j-rio-rdfxml` 4.3.8 per message |
| Server-Sent Events (§4.1.1) | [`SseMessageChannel`](examples/src/main/java/org/cqels/examples/messages/SseMessageChannel.java) `Producer`, `consume` | the JDK's HTTP server and client |

### 4.1 N-Triples and N-Quads

These go to the engine codec, with three workarounds for #126. Before parsing, a comment after
`MESSAGE` (with or without a space) is removed, and so is every comment-only line. When the last
message is empty, the writer adds one more delimiter so that the message survives re-reading. The
probe (§7) says when each workaround can go.

### 4.2 Turtle and TriG

These are tokenised here, and each message is parsed by RDF4J's ordinary parser.

- **Tokens.** Strings (including long strings spanning lines), IRIs, numbers, language tags and
  names are read by Turtle's own token rules.
  - `MESSAGE` inside any of them is not a delimiter: `ex:MESSAGE`, `ex:x..MESSAGE`,
    `MESSAGE.foo:s`, and escaped names such as `ex:x\.MESSAGE`.
  - A dot right after a token still ends the statement. `1.MESSAGE`, `1.e2.MESSAGE`,
    `true.MESSAGE`, `"x"@en.@message .` and `ex:.MESSAGE` all cut there, and a `PREFIX` glued to
    such a dot is carried forward.
  - Name characters include non-ASCII ones, and only ASCII digits start a number, so a prefix
    such as `١:` is a prefix.
- **Where a delimiter counts.** Only where a statement may start: not inside `[ ]`, `( )` or a
  graph block. A `MESSAGE` inside an open graph block is a parse error, as the companion test
  1.5.3 requires.
- **Comments** may stand wherever whitespace may, including inside directives.
- **`VERSION` / `@version`** is removed before parsing. Its label:
  - must be a short quoted string with valid escapes (Turtle's VersionSpecifier);
  - is decoded, so a hyphen written as `\u002D` is a hyphen;
  - applies from where it stands.
- **`PREFIX` / `BASE`** (and their `@` forms) are carried into later messages. A redefinition
  applies from that point on.
- **Blank nodes** are scoped to their message, because each message is parsed separately.
- **Errors.** An unterminated string, IRI or label is an error.
- **Stricter than the codec.** A delimiter is accepted only while the most recently announced
  label ends in `-messages`.
- **RDF 1.2 syntax** that RDF4J 4.3.8 would misread or cannot read is refused with an error that
  names #133: `<<( )>>`, `<< >>`, `{| |}`, and a base direction such as `@ar--rtl`.

The writers terminate every message with `MESSAGE`, so an empty last message round-trips. Each
message carries its own `@prefix` lines, which §2.1.3 allows. Numbers are written as typed
literals, because RDF4J's short form would turn `"01"^^xsd:integer` into `1`. The Turtle writer
refuses a statement with a graph name; the TriG writer keeps it.

Every writer refuses two things rather than change them: a triple term (#133), and an IRI
containing a character that no syntax can write unescaped (a space, a
backslash, or one of `` < > " { } | ^ ` ``).

### 4.3 NDJSON-LD and YAML-LD

**NDJSON-LD** follows §2.2's algorithm as written:
- Every line is one JSON object. Blank lines, duplicate keys and trailing tokens are errors. CRLF
  line ends and a byte-order mark before the first line are accepted.
- `{}` is an empty message.
- A line whose only key is `@context` is a context message: it emits nothing and becomes the
  active context C.
- Any other line L is converted as `{"@context": C, "@graph": L}` while C is set, and as itself
  otherwise. Its own `@context` applies to it alone.

RDF4J 4.3.8 ships its own NDJSON-LD parser (`application/x-ld+ndjson`). It reads all lines as one
document, so it shares blank nodes across lines and knows no context messages. A test pins that
behaviour, because it is the reason the parser is not used here.

**YAML-LD** follows §2.3's proposal: each YAML document is one message, read as a JSON-LD
document. The draft leaves some questions open; the choices made here are:
- **YAML 1.2 with the Core schema.** `yes` stays a string and `0o17` is 15. Jackson's YAML
  reader would make `yes` the boolean true, which is why it is not used. A `%YAML 1.1` directive
  is refused.
- **Aliases are expanded,** up to snakeyaml-engine's guard against alias bombs (50 aliases to
  collections per document). Refused, because they have no JSON form or would lose a value:
  - a recursive alias;
  - a tag the Core schema lacks (`!!binary`, `!!set`, a custom tag);
  - `!!bool` or `!!null` on a value not spelled that way (the library would make `!!bool yes`
    null, which JSON-LD then drops);
  - a key that is not a string;
  - `.inf` or `.nan`;
  - an untagged plain `<<` key, which the library merges as YAML 1.1 did, already while reading;
    in YAML 1.2 it is an ordinary key (`"<<"` or `!!str <<` is accepted). A plain `<<` *value*,
    a valid YAML 1.2 string, is refused by the library itself (it has no constructor for the
    merge tag); quote it.
- **An empty document is refused** rather than guessed at. An empty message is written `{}`.
- **Library limit:** a bare document after a `...` end marker (valid YAML 1.2) is refused by
  snakeyaml-engine; start the next document with `---`.
- **No context messages,** because §2.3 defines none.

**Both formats:**
- Blank nodes are scoped per message.
- Remote contexts are never fetched. A context URL is served from a map the caller passes, and
  any other URL is an error.
- **JSON-LD 1.0, not 1.1.** The processor implements JSON-LD 1.0 (#133).
  - **Refused:** what it would drop or reject: JSON-LD 1.1 keywords, a term's scoped `@context`,
    and a property-valued `@index`. The check reads keys without the context. It therefore also
    refuses such a keyword when it is the key of an index map, where JSON-LD 1.0 would read it as
    data.
  - **Read with the 1.0 meaning, without an error.** Examples of input that 1.0 and 1.1 read
    differently, measured against Titanium, a JSON-LD 1.1 processor (the list is not complete):
    - any term works as a prefix of a compact IRI. In 1.1, a term does so only if it is
      defined as a plain string ending in a gen-delim such as `/` or `#`, or has
      `"@prefix": true`. So `"ex": "http://example.org/ns"`, or an expanded definition
      `"ex": {"@id": "http://example.org/ns#"}`, makes `ex:foo` `http://example.org/nsfoo`
      or `http://example.org/ns#foo` here, and leaves it `ex:foo` in 1.1;
    - redefining a term that is itself a compact IRI, to an IRI other than its own expansion, is
      accepted (1.1: an error);
    - an `@id` of `"@foo"` (keyword-like) becomes an IRI (1.1: dropped);
    - a JSON number written with a fraction or an exponent but with a whole value below
      10²¹ (`1.0`, `1e2`, `1.5e2`) becomes `xsd:double` (1.1: `xsd:integer`);
    - an integer of 10²¹ or more (`123456789012345678901234567890`) becomes `xsd:integer`
      (1.1: `xsd:double`);
    - a number that is not whole but rounds to a whole double (`1.000000000000000000001`,
      `1e-400`) becomes `xsd:double` (1.1: `xsd:integer`);
    - a double's lexical form: `0.30000000000000004` becomes `"3.0000000000000004E-1"` (1.1:
      `"3.0E-1"`, 16 digits, a different value);
    - a typed number keeps its JSON spelling: `{"@value": 1.0, "@type": "xsd:decimal"}` gives
      `"1.0"` (1.1: `"1"`).
  - **Numbers.** Otherwise every number is typed exactly as RDF4J types it read directly. A
    floating-point number beyond `xsd:double` (`1e400`) is refused rather than read as the string
    "Infinity"; an integer is kept exactly, up to Jackson's 1000 digits (see below).
- **Invalid IRIs.** An `@id` with characters IRIs do not allow (`s t`) is percent-encoded, as RDF4J
  does. The writers refuse such IRIs instead.
- **Line size.** Jackson's limits apply to a line: 20 million characters in a string, nesting depth
  1000, 1000 digits in a number. Direct RDF4J has the same limits.
- **A question for the draft.** While a context C is set, a data line with its own `@graph`
  becomes a named graph with a blank-node name, because §2.2 wraps it in another `@graph`.
  JSON-LD 1.1 reads the wrapped document the same way. The code follows the draft as written.
- The writers emit expanded JSON-LD, one `{"@graph": [...]}` object per message, with named
  graphs kept.

### 4.4 RDF/XML

The `<rdfm:MessageLog>` is read with StAX, one `<rdf:RDF>` element at a time. Each element is
copied out as a standalone document and parsed by RDF4J. The copy writes a carriage return, and a
tab or newline in an attribute, as character references, so XML's line-end and attribute
normalisation leave them as they were.

- **Scope.** `rdf:nodeID` is scoped to its message (§2.4.1).
- **Inheritance (§2.4.2).** Namespace declarations, `xml:base` and `xml:lang` are inherited from
  the log element unless the message overrides them. A relative `xml:base` is resolved against
  the base in force.
- **Refused, as §2.4 requires:** a root other than `rdfm:MessageLog`, a child other than
  `rdf:RDF`, and text between messages. Comments and processing instructions between messages
  are ignored. Anything after `</rdfm:MessageLog>` other than comments, processing instructions
  and whitespace is refused.
- **Also refused:**
  - an `rdf:version` without `-messages`;
  - a DOCTYPE (DTDs and external entities are not processed);
  - `rdf:parseType="Triple"`, `rdf:annotation` and `rdf:annotationNodeID` (#133), except inside
    the content of an XML literal, where they are just XML.
- **XML 1.0 only.** Each message is copied out as an XML 1.0 document, so a character that only
  XML 1.1 allows (`&#1;`) is refused.
- **The writer** wraps RDF4J's `<rdf:RDF>` output for each message in an `<rdfm:MessageLog>`
  carrying `rdf:version="1.2-messages"`. It refuses graph names, since RDF/XML has none.
- **Read-back check.** RDF/XML cannot hold every graph, and RDF4J writes some of these anyway:
  - characters XML 1.0 forbids;
  - an ill-formed `rdf:XMLLiteral`;
  - `rdf:li`, which reads back as `rdf:_1`;
  - `rdf:nodeID` as a predicate;
  - a predicate with no XML name.

  So the writer reads each message back and refuses it unless it is the same. The comparison runs
  in linear time: the statements with blank nodes masked, the number of blank nodes, and each
  blank node's masked neighbourhood. A full graph-isomorphism check would take factorial time on
  alike blank nodes (eight took 20 s). The comparison is not an isomorphism test: it would miss a
  rewiring that leaves each blank node's one-step neighbourhood the same. The RDF4J 4.3.8 writer's
  only change to blank-node structure is merging two blank nodes whose IDs it mangles to the same
  name, which lowers the blank-node count, and the comparison refuses that. A review fuzzed
  100,000 messages against an exact isomorphism check and found no wrong acceptance.
- **`rdf:version`** is refused as a predicate (writer), and by the reader, outside the content of
  an XML literal, as an element and as an attribute anywhere but `<rdf:RDF>`. RDF 1.2 XML makes it a syntax term; RDF4J 4.3.8 would read
  `<rdf:Description rdf:version="1.2">` as an extra `rdf:version` triple, and write and read the
  predicate as an ordinary property.

### 4.5 Server-Sent Events

`SseMessageChannel` carries a message stream as §4.1.1 illustrates.
- **Producer:** a `text/event-stream` response, the header
  `RDF-Message-Content-Type: application/ld+json` (or `application/n-quads`), and one
  `event: rdf-message` per message. With JSON-LD, the payload is one line, and an empty message is
  `{}`.
- **Consumer:** follows the WHATWG event-stream rules: UTF-8 whatever charset the response names;
  CR, LF or CRLF line ends; `:` comments;
  one leading space dropped; several `data:` lines joined; dispatch at a blank line, and none for
  empty data. It skips other event types and decodes each payload as one standalone message.
- **Media types** are compared case-insensitively.
- **Scope:** a demonstration transport. One fixed list of messages per client (clients are served
  in parallel), loopback only, no `Last-Event-ID` resumption.

### 4.6 Tests

[`RdfMessagesTestSuiteTest`](examples/src/test/java/org/cqels/examples/messages/RdfMessagesTestSuiteTest.java)
runs **every test of the draft's companion
[RDF Messages Tests](https://w3c-cg.github.io/rsp/spec/messages-tests)** (22 October 2026). It
runs each in every format the test applies to: 75 test runs, all passing. The inputs and
expectations are copied byte for byte from the page into
[`src/test/resources/rdf-messages-tests/`](examples/src/test/resources/rdf-messages-tests/README.md).
Two adaptations, both stated in the test:
- The NDJSON-LD tests name `https://schema.org/` and `https://example.org/other-vocab/` as
  contexts. Since nothing is fetched, each is served as a stand-in context with that `@vocab`.
- §1.4's round trips also run for NDJSON-LD, YAML-LD and RDF/XML. RDF/XML, Turtle and N-Triples
  have no named graphs, so the round trip with named graphs is refused in those formats rather
  than run.

Beside the suite, there are four more test classes:

| Test class | Tests | What it covers |
|---|---|---|
| [`RdfMessageLogTest`](examples/src/test/java/org/cqels/examples/messages/RdfMessageLogTest.java) | 30 | Turtle/TriG token boundaries checked against RDF4J's own parse, prefix carry, version labels, the RDF 1.2 refusals, writer round trips |
| [`JsonLdMessageLogTest`](examples/src/test/java/org/cqels/examples/messages/JsonLdMessageLogTest.java) | 20 | NDJSON line rules, context messages, offline contexts, JSON-LD 1.1 refusals (including a property-valued `@index`), numbers typed as direct RDF4J types them and those beyond `xsd:double`, YAML 1.2 scalars, tags, merge keys, versions and document rules, writer round trips (number spellings, unwritable IRIs) |
| [`RdfXmlMessageLogTest`](examples/src/test/java/org/cqels/examples/messages/RdfXmlMessageLogTest.java) | 13 | inheritance and overrides, `xmlns=""`, character references, XML literals, content after the log, `rdf:version`, refusals, round trips, and the writer's read-back check (including its speed on 200 alike blank nodes and a ring of 200) |
| [`SseMessageChannelTest`](examples/src/test/java/org/cqels/examples/messages/SseMessageChannelTest.java) | 4 | the event-stream rules, UTF-8 and CR line ends, case-insensitive media types, two clients in parallel, and a loopback run with both payload types |

## 5. The worked examples

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

[`MessageStreamOverSse`](examples/src/main/java/org/cqels/examples/messages/MessageStreamOverSse.java)
serves the same six messages from a producer over Server-Sent Events, with JSON-LD payloads. A
consumer in the same process reads them as they arrive and pushes each into the engine. The result
is 6 events, the empty heartbeat among them, and 4 `Reading` rows, the same as the replay.

## 6. Not done yet

- **RDF 1.2 triple terms, reifiers, annotations and base direction.** These are refused, not
  read, until the engine can parse RDF 1.2
  ([#133](https://github.com/cqels/CQELS4J/issues/133)). For the same reason the JSON-LD
  readers are JSON-LD 1.0 processors: what 1.0 would drop is refused, and where 1.0 and 1.1 differ
  in meaning, the 1.0 meaning applies (§4.3).
- **Other transports.** The draft's WebSocket and MQTT examples (§4.1) are not built.
- **Binary formats.** Jelly (§2.5) is not built.
- **Profiles.** Once the draft's §3 is written, check this demo's reading in §2 (a window wider
  than one message is how messages are combined) against it.
- **YAML-LD context messages.** §2.3 defines none, and the format itself is still under discussion.

## 7. Keeping this true

[`CapabilityProbe`](examples/src/main/java/org/cqels/examples/CapabilityProbe.java)
(`scripts/ci/capability-probe.sh`, run on every PR) checks the six engine gaps above in both
directions, so this document and the workarounds cannot outlive a fix unnoticed:

- #126, `MESSAGE # comment` rejected by the codec. A control checks that the same log without the
  comment reads as two messages.
- #126, a comment-only line after the last delimiter opening an empty message.
- #126, a trailing empty message lost by `writeNQuads`.
- #125, `GRAPH` inside `STREAM` refused.
- #133, the codec rejecting a triple term under `VERSION "1.2-messages"`. The control is the same
  log with an IRI object.
- #133, the engine's Turtle parser reading `<< s p o >>` as an RDF-star quoted triple, not an RDF
  1.2 reifier.

As a capability the demo relies on, the probe also checks that the codec keeps empty messages,
scopes blank nodes per message and keeps a quad's graph name. The test classes in §4.6 run in the
same CI job.
