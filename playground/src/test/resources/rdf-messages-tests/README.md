# RDF Messages Tests, copied

These files are the tests of the W3C RSP Community Group's
[RDF Messages Tests](https://w3c-cg.github.io/rsp/spec/messages-tests) (Living Document, 22 October 2026).
The editors waived copyright under CC0 and also offer the document under the Open Web Foundation
Agreement 1.0.

Each `<pre>` block of the page was copied byte for byte by a script, not retyped:

- `<id>.txt`, `<id>.ndjson` or `<id>.rdf`: a test's input;
- `<id>.expected`: the expected result as the page states it;
- `1.4-<n>.input` and `1.4-<n>.expected`: the two round trips of §1.4;
- `INDEX.tsv`: each test's id and title.

`RdfMessagesTestSuiteTest` reads these files. Where an expectation is a list of N-Quads lines, the
test parses that list. Where it is prose ("the two blank nodes must not be the same RDF term"),
the test encodes the check by hand.
