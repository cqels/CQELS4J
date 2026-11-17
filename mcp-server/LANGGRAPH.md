# Using CQELS-MCP as your LangGraph memory

The CQELS MCP server exposes an RDF knowledge graph, a reasoner and a stream-processing engine as
MCP tools. Because it speaks MCP, **any LangGraph / LangChain agent can use it with zero
server-side code**: LangChain's built-in MCP support (`langchain.mcp`) turns the server's tools
into ordinary LangChain tools.

This is the "just plug it in" path. A drop-in LangGraph `BaseStore` adapter is planned separately;
this guide needs none of it.

## When to reach for it

Use CQELS-MCP as your agent's memory when the memory has to do more than store and fetch:

- **Reason** — recall a concept *and what is entailed about it* (RDFS/OWL subsumption, type
  propagation, transitivity), plus SHACL validation and ASP solving.
- **Remember *when*** — per-statement valid-time, provenance and confidence, and point-in-time recall.
- **Watch** — standing queries, SHACL invariants and rule programs that fire as observations arrive,
  instead of polling with search.
- **Enforce access** — fail-closed, role-scoped recall.

For a plain scratchpad, LangGraph's built-in store is simpler.

---

## 1. Prerequisites

- **JDK 17+**.
- The **CQELS-MCP shaded jar**, `cqels-mcp-<version>-shaded.jar`, attached to each release at
  <https://github.com/cqels/CQELS4J/releases>. `SUPPLY_CHAIN.md` in that repository explains how to
  verify the download.
- Python 3.11+ and:
  ```bash
  pip install "langchain[mcp]>=1.5" langgraph "langchain[anthropic]"
  ```

`langchain.mcp` is in beta (importing it emits a `LangChainBetaWarning`) and replaces the standalone
`langchain-mcp-adapters` package, which is no longer maintained. If you are still on the old
package, read the note at the end of section 2 first.

Check that the server starts. It speaks MCP over stdio, so it simply waits:

```bash
java -jar /abs/path/to/cqels-mcp-<version>-shaded.jar
```

Logs go to stderr; stdout is reserved for the protocol. Stop it with Ctrl-C.

---

## 2. Connect from LangGraph

```python
import asyncio

from fastmcp.client import Client
from fastmcp.client.transports import StdioTransport
from langchain.agents import create_agent
from langchain.mcp import MCPAdapter

JAR = "/abs/path/to/cqels-mcp-<version>-shaded.jar"


async def main():
    cqels = Client(StdioTransport(command="java", args=["-jar", JAR]))
    try:
        async with MCPAdapter(cqels) as adapter:
            tools = await adapter.list_tools()  # the server's tools as LangChain tools
            agent = create_agent("anthropic:claude-sonnet-5-5", tools)

            result = await agent.ainvoke({
                "messages": [{"role": "user",
                              "content": "Remember that Tweety is a canary. Then tell me what Tweety is."}]
            })
            print(result["messages"][-1].content)
    finally:
        await cqels.close()  # stops the Java process


asyncio.run(main())
```

The model now sees the CQELS tools and calls `store_memory` / `recall_memory` on its own.

**The server process is the memory.** By default everything lives in that one Java process, so the
lifetime of the process is the lifetime of the memory:

- Build the client once and reuse it. The stdio transport keeps the subprocess alive between tool
  calls, which is what lets a `recall_memory` see an earlier `store_memory`. Keep the default
  `keep_alive=True`: with `keep_alive=False` the subprocess stops whenever the last open
  `async with` on the client exits, so memory would not carry over from one block to the next.
- Call `await cqels.close()` when you are done; it stops the Java process. Leaving the `async with`
  block does not, because the transport keeps the subprocess for reuse.
- A plain config dict also works (`MCPAdapter({"mcpServers": {"cqels": {"command": "java", "args":
  ["-jar", JAR]}}})`); stop its server with `await adapter.client.close()`. With several servers in
  one config, tool names are prefixed with the server name (`cqels_store_memory`).
- This hands the model every tool, including administrative ones such as `set_access_policy` and
  `forget_memory`. That is fine for a trusted, single-user agent; before other people use it, filter
  the list as shown in section 9.
- To share one memory between several agents or processes, or to keep it running independently of
  your Python code, run the server over HTTP (section 8).

> **Still on `langchain-mcp-adapters`?** `MultiServerMCPClient.get_tools()` opens a new MCP session
> for every tool call. Over stdio that is a new server process per call, so a `recall_memory` never
> sees the preceding `store_memory`. Hold one session instead:
>
> ```python
> from langchain_mcp_adapters.tools import load_mcp_tools
>
> async with client.session("cqels") as session:
>     tools = await load_mcp_tools(session)
>     ...  # build and run the agent inside this block
> ```

---

## 3. The tool surface

25 tools, grouped by the kind of memory they serve:

| Group | Tools |
|---|---|
| **Core memory** | `store_memory`, `recall_memory`, `forget_memory` |
| **Semantic (inference)** | `register_reasoning`, then `recall_memory` with `entail: true` |
| **Episodic** | `record_event`, `recall_episodes` |
| **Procedural** | `save_procedure`, `list_procedures`, `run_procedure` |
| **Working / GraphRAG** | `assemble_context` |
| **Decision lineage** | `explain_decision`, `recall_decisions` |
| **Governance** | `set_access_policy` |
| **Streams and watches** | `create_stream`, `remove_stream`, `push_stream_events`, `register_stream_query`, `validate_stream_query`, `forget_stream_query`, `watch_invariant`, `register_rules` |
| **One-shot analysis** | `query`, `reason`, `validate`, `solve` |

The server also offers 9 resources, one resource template (a watch's results) and 10 prompts. `langchain.mcp` wraps tools only, so read those
through the FastMCP client: `async with cqels: await cqels.read_resource("cqels://docs/memory-profile")`.
That resource is the server's own routing guide — which tool for which memory operation — and is
worth putting in your system prompt.

Arguments you will use most:

- `store_memory({facts: [{subject, predicate, object, objectType?}], graph?, meta?})` —
  `objectType` is `"uri"` or `"literal"` (the default). `turtle` can be passed instead of `facts`.
  `meta` attaches `source`, `confidence`, `validFrom`, `validTo`, `spatialScope`, `accessLabel`,
  `decisionScope` to the statements.
- `recall_memory` has four modes; pass one. If you pass several, `queryId` wins, then `sparql`,
  then `text`, then `pattern`:
  - `pattern: {subject?, predicate?, object?}` — omitted positions are wildcards, but give at least
    one; add `entail: true` for inferred triples or `role` for governed recall.
  - `text` — text search. The published server searches lexically: `mode: "vector"` is refused
    because no embedding provider is enabled, and `"hybrid"` ranks by the lexical match alone.
  - `sparql` — a full SELECT or CONSTRUCT.
  - `queryId` — drain the results of a watch (section 6).
  - The metadata filters `asOf`, `minConfidence`, `source` and `includeMeta` work with `pattern`
    (or on their own), not with `text`, `sparql` or `queryId`, and not together with `entail`.
    `limit` bounds the result in every mode.
- `assemble_context({task, mode?, limit?, neighbors?, budget?})` — a bundle for a task: facts and
  procedures, ranked and cut to fit `budget`, plus the most recent events, which the budget does not
  limit.

The snippets below run inside the `async with` block from section 2 and call tools directly, which
is also a convenient way to seed or inspect memory without a model:

```python
t = {tool.name: tool for tool in tools}
```

A direct `ainvoke` returns a list of content blocks; the server's answer (usually JSON) is in the
`text` field of the first one.

---

## 4. Ontology-aware recall

This is what a key-value or vector store cannot do: recall what the facts *entail*, not only what
was asserted.

```python
# 1. a fact, and a small ontology in the schema graph
await t["store_memory"].ainvoke({"facts": [
    {"subject": "ex:Tweety", "predicate": "rdf:type", "object": "ex:Canary", "objectType": "uri"},
]})
await t["store_memory"].ainvoke({"graph": "cqels://memory/schema", "facts": [
    {"subject": "ex:Canary", "predicate": "rdfs:subClassOf", "object": "ex:Bird", "objectType": "uri"},
    {"subject": "ex:Bird", "predicate": "rdfs:subClassOf", "object": "ex:Animal", "objectType": "uri"},
]})

# 2. turn reasoning on (once)
await t["register_reasoning"].ainvoke({})

# 3. recall with entailment
print(await t["recall_memory"].ainvoke({"pattern": {"subject": "ex:Tweety"}, "entail": True}))
# -> Tweety is a Canary, a Bird and an Animal (and an rdfs:Resource, under the default RDFS_FULL)
```

`ex:Bird` and `ex:Animal` were never stored for Tweety. The closure always reflects the current
store: a `rdfs:subClassOf` edge added later shows up on the next `entail` recall.

---

## 5. Remembering when

Attach a validity window and provenance when you store, then ask what was true at a point in time.

```python
await t["store_memory"].ainvoke({
    "facts": [{"subject": "ex:Alice", "predicate": "ex:worksFor", "object": "ex:Acme", "objectType": "uri"}],
    "meta": {"validFrom": "2024-01-01", "validTo": "2025-06-30", "source": "hr-system", "confidence": 0.9},
})

print(await t["recall_memory"].ainvoke({"pattern": {"subject": "ex:Alice"}, "asOf": "2024-09-01"}))
# -> the worksFor fact, with its metadata
print(await t["recall_memory"].ainvoke({"pattern": {"subject": "ex:Alice"}, "asOf": "2026-01-01"}))
# -> []
```

---

## 6. Memory that watches

Register a standing condition, then feed observations. A query registered later still sees the
stream's recent history (up to its last 8192 observations), but a CEP pattern (`cep: true`) sees only
what arrives after it, so **register before you push** when you need every observation.

```python
SHAPES = """
@prefix sh: <http://www.w3.org/ns/shacl#> .
@prefix ex: <http://example.org/> .
ex:TempShape a sh:NodeShape ; sh:targetSubjectsOf ex:temperature ;
  sh:property [ sh:path ex:temperature ; sh:maxInclusive 30 ] .
"""
QUERY = ("PREFIX ex: <http://example.org/> SELECT ?s ?t FROM STREAM sensors [RANGE 10s] "
         "WHERE { STREAM sensors { ?s ex:temperature ?t } FILTER(?t > 30) }")


def reading(value):
    return ('VERSION "1.2-messages"\n<http://example.org/s1> <http://example.org/temperature> '
            f'"{value}"^^<http://www.w3.org/2001/XMLSchema#decimal> .')


await t["create_stream"].ainvoke({"stream": "sensors"})
await t["watch_invariant"].ainvoke({"stream": "sensors", "shapes": SHAPES, "queryId": "temp"})
await t["register_stream_query"].ainvoke({"query": QUERY, "queryId": "hot"})

await t["push_stream_events"].ainvoke({"stream": "sensors", "events": [
    {"eventTime": "2026-10-02T10:00:00Z", "nquads": reading("22.5")},
    {"eventTime": "2026-10-02T10:00:01Z", "nquads": reading("41.0")},
]})
await asyncio.sleep(1)

print(await t["recall_memory"].ainvoke({"queryId": "wi-temp"}))  # one SHACL violation, for 41.0
print(await t["recall_memory"].ainvoke({"queryId": "hot"}))      # [{"s": ".../s1", "t": 41.0}]

await t["forget_stream_query"].ainvoke({"queryId": "wi-temp"})
await t["forget_stream_query"].ainvoke({"queryId": "hot"})
```

- `watch_invariant` ids are returned with a `wi-` prefix, `register_rules` ids with `rl-`.
- Reading a watch with `recall_memory({queryId})` removes the rows it returns: up to `limit`
  (default 50) and a response size budget, so call it again until it comes back empty. The buffer is
  bounded by `bufferSize` (default 100) and by size; when full, the oldest rows are dropped.
- Always set `eventTime`. Without it the server uses arrival time, which is unsafe for windows.
- Event `facts` objects are plain string literals unless `objectType` is `"uri"`. For numbers, dates
  or other typed values use `nquads` as above; a string `"41.0"` does not satisfy `?t > 30`.
- Check a draft query with `validate_stream_query` before registering it. `FILTER(SEQ(...))`
  patterns need `cep: true`. The syntax references are the `cqels://docs/cqelsql` and
  `cqels://docs/cep` resources.
- `register_rules` does the same with an ASP rule program instead of a query or a shape.

The server can also push a notification per result (`notify: true`), but the LangChain adapter does
not surface MCP notifications, so poll from a graph node or a scheduled task.

**Over HTTP (section 8), a watch belongs to the MCP session that registered it** and is removed when
that session ends. With the default adapter lifecycle every tool call is its own session, so a watch
registered in one call is already gone when the next call tries to drain it. Keep the `MCPAdapter`
context open for the whole run that registers, drains and forgets a watch, as section 2 does. Over
stdio there is one session per server process, so this does not arise.

---

## 7. Long-term memory in a StateGraph

Tell the model to persist and recall, and wire the tools with a `ToolNode`:

```python
from langchain.chat_models import init_chat_model
from langgraph.graph import START, MessagesState, StateGraph
from langgraph.prebuilt import ToolNode, tools_condition

model = init_chat_model("anthropic:claude-sonnet-5-5")
SYSTEM = ("You have a persistent knowledge graph through the cqels memory tools. "
          "Save durable facts with store_memory. Before answering, use recall_memory "
          "(with entail: true for conceptual questions).")


async def call_model(state: MessagesState):
    messages = [{"role": "system", "content": SYSTEM}] + state["messages"]
    return {"messages": await model.bind_tools(tools).ainvoke(messages)}


graph = StateGraph(MessagesState)
graph.add_node("model", call_model)
graph.add_node("tools", ToolNode(tools))
graph.add_edge(START, "model")
graph.add_conditional_edges("model", tools_condition)
graph.add_edge("tools", "model")
app = graph.compile()

result = await app.ainvoke({"messages": [{"role": "user", "content": "What is Tweety?"}]})
```

The tools are async, so run the graph with `ainvoke` / `astream`.

**Separation of concerns:** LangGraph owns the loop, the short-term thread state (checkpointer) and
the decision of when to consolidate. CQELS-MCP owns the durable, reasoned, governed knowledge.

---

## 8. Durable and shared memory

**Persist facts across restarts** by giving the server a directory for its RDF store:

```python
cqels = Client(StdioTransport(
    command="java", args=["-jar", JAR],
    env={"CQELS_MCP_RDF_STORE_PATH": "/var/lib/cqels/rdf"},
))
```

Stored facts with their metadata, access labels and policies, saved procedures, recorded events and
decision traces then survive a restart. Watches and `register_reasoning` do not; register them again
on startup. A stored access policy survives too, and it denies `register_reasoning` (section 9), so a
governed store has no `entail` recall after a restart. The text-search index is rebuilt only as new facts are stored, so after a restart use
`pattern` or `sparql` recall for older facts.

`CQELS_MCP_STORAGE_BACKEND` / `CQELS_MCP_STORAGE_PATH` are a different setting: they journal
*stream* data and do not persist stored facts.

**Share one memory** between agents, processes or machines by running the server over HTTP:

```bash
CQELS_MCP_TRANSPORT=http CQELS_MCP_HTTP_AUTH_TOKEN=<token> \
  java -jar /abs/path/to/cqels-mcp-<version>-shaded.jar
# listens on http://127.0.0.1:3000/mcp
```

```python
cqels = Client("http://127.0.0.1:3000/mcp", auth="<token>")
async with MCPAdapter(cqels) as adapter:
    tools = await adapter.list_tools()
```

The server binds plain HTTP on loopback by default. To reach it from another machine, put a TLS
reverse proxy in front and keep the bearer token; do not expose the port directly. Watches over HTTP
are per session — see the note at the end of section 6.

Prefer HTTP for long-lived agents even on one machine. Over stdio, a client that stops reading the
server's output makes the server buffer without bound; over HTTP a write completes only when the
client reads it.

---

## 9. Per-user memory and governance

**One graph per user.** Map your LangGraph `user_id` to a named graph:

```python
await t["store_memory"].ainvoke({"graph": "cqels://memory/user/alice", "facts": [
    {"subject": "ex:alice", "predicate": "ex:likes", "object": "sushi"}]})

# read one user's graph
print(await t["recall_memory"].ainvoke({"sparql":
    "SELECT ?s ?o WHERE { GRAPH <cqels://memory/user/alice> { ?s <ex:likes> ?o } }"}))

# erase one user's memory
await t["forget_memory"].ainvoke({"graph": "cqels://memory/user/alice"})
```

Clearing a graph removes its facts but not the metadata recorded about them (`source`,
`confidence`, validity, access label). That metadata is kept per fact in a separate annotations graph
and stays readable through `query`. When the facts were stored with `meta`, forget them by `facts`
instead, which removes a fact's metadata together with its last copy. On a governed server metadata
is never removed, so that an access label cannot be stripped.

Graphs organise; they do not isolate. A `pattern` recall reads `cqels://memory/longterm` (where facts
go when you give no `graph`) plus *all* `cqels://memory/user/…` graphs, so scope reads with SPARQL as
above, or enforce isolation with a policy.

**Statement-level access control.** Label statements when you store them, grant labels to roles,
and recall as a role:

```python
await t["store_memory"].ainvoke({
    "facts": [{"subject": "ex:Alice", "predicate": "ex:salary", "object": "120000"}],
    "meta": {"accessLabel": "hr"}})
await t["set_access_policy"].ainvoke({"role": "hr", "labels": ["hr", "*"]})

await t["recall_memory"].ainvoke({"pattern": {"subject": "ex:Alice"}, "role": "hr"})   # sees the salary
await t["recall_memory"].ainvoke({"pattern": {"subject": "ex:Alice"}, "role": "eng"})  # sees nothing
```

> **Setting a policy locks the server down, and it cannot be undone.** Once any policy exists, the
> only read left is `recall_memory` with a `pattern` and a `role`. Writes still work (`store_memory`,
> `record_event`, `save_procedure`, and `forget_memory` by `facts` or `graph` but not by `sparql`),
> as do `validate_stream_query` and `forget_stream_query`. Everything else is denied rather than allowed to leak: text, SPARQL and
> metadata recall, `query`, `assemble_context`, `reason`, `validate`, `solve`, episodic and decision
> recall, listing and running procedures, `register_reasoning`, `register_rules`, `watch_invariant`,
> and creating, feeding, removing or querying streams. `entail` with a `role` works only if
> `register_reasoning` ran before the first policy, in the same server process. There is no tool to
> remove a policy. Run governed memory on its own server instance, separate from one you use for
> watches.

The `role` must be set by your application from an authenticated identity, never taken from the
model or the end user: a caller that can choose its `role` can read as anyone. Writes need the same
care. A label belongs to a fact (its subject, predicate and object), not to the graph the fact is
stored in, and once a policy exists the first label written to a fact is the one that stays. A model that can store facts
can therefore claim any fact it can name: an unlabelled one already stored, or one your application
stores later under another label, which then stays readable to the model's role and hidden from the
intended one. So on a governed server give the model only a recall tool that fixes its role, and
store from your application, where you decide both the facts and their label:

```python
from langchain_core.tools import tool


def recall_as(role):  # role from your authenticated session
    @tool
    async def recall(subject: str):
        """Recall what is known about a subject."""
        return await t["recall_memory"].ainvoke({"pattern": {"subject": subject}, "role": role})
    return recall


async def remember(user, label, facts):  # called by your application, never by the model
    return await t["store_memory"].ainvoke({
        "graph": f"cqels://memory/user/{user}", "facts": facts, "meta": {"accessLabel": label}})


agent_tools = [recall_as("hr")]
```

Pass `agent_tools` instead of `tools` to `create_agent` (section 2), or to both `bind_tools` and
`ToolNode` (section 7), and change the system prompt to match: the model now has one tool,
`recall(subject)`, without `entail`. Store every fact with a label, and each fact under one label
only: an unlabelled fact is visible only to roles granted `"*"`, and under a policy a second label is ignored.

---

## 10. Caveats in the current alpha

- **Prefixes.** Well-known prefixes such as `rdf:` and `rdfs:` are expanded. Anything else, such as
  `ex:Tweety` in these examples, is kept verbatim as the IRI `ex:Tweety`. Use full IRIs in real
  data.
- **Typed values.** A fact's `object` is a plain string literal unless `objectType` is `"uri"`. For
  typed literals use `turtle` in `store_memory` or `nquads` in `push_stream_events`. To forget a typed
  or language-tagged literal by `facts`, pass its `datatype` or `language` so the object matches.
- **Forgetting and text search.** `forget_memory` by `facts` or `graph` also removes the facts from
  text search. A raw `sparql` delete does not, so deleted facts can still surface in `text` recall.
- **Entailment scope.** `entail` recall reasons over one data graph (`cqels://memory/longterm` unless
  you pass `dataGraph` to `register_reasoning`) plus the schema graph. Other user graphs are not
  included.
- **Tool errors** come back to the model as an error tool message it can react to; they do not
  raise in your code.

---

*Last verified on 2026-11-17 with the alpha.22 server, `langchain` 1.5.0, `langgraph` 1.2.22 and
`fastmcp` 4.2.0: every snippet was run against the server, with the model calls replaced by a
scripted model. Re-check the snippets when you upgrade either side.*
