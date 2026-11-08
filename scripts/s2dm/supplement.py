"""Write what `s2dm export rdf` leaves out of a schema as a supplementary Turtle graph.

This is CQELS4J's own extraction, not an s2dm exporter. It reads the same GraphQL schema the
export reads and writes, per field, the four things the RDF export drops:

  - the field's description           -> skos:definition "..."@en
  - the default of its `unit` argument -> s2x:defaultUnit <ns>Unit_Enum.VALUE
  - its @range(min, max)               -> s2x:min / s2x:max
  - an @instanceTag on the field       -> s2x:instanceTag true

Field and enum-value IRIs follow the export's scheme (<ns>Type.field, <ns>Enum.VALUE), so the
supplement joins the exported skos.ttl and data_graph.ttl directly. Only object and interface
types are read. Output is sorted, so a re-run on the same schema gives the same file.

Usage (inside the s2dm image, where graphql-core is installed):
    python -I supplement.py <schema.graphql> <namespace> <out.ttl>
"""

import sys

from graphql import parse
from graphql.language import (
    EnumValueNode,
    FloatValueNode,
    IntValueNode,
    InterfaceTypeDefinitionNode,
    ObjectTypeDefinitionNode,
)

S2X = "https://example.org/s2dm/supplement#"


def literal(text):
    escaped = text.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\r", "")
    return f'"{escaped}"@en'


def number(node):
    if isinstance(node, (IntValueNode, FloatValueNode)):
        return node.value
    return None


def main(schema_path, ns, out_path):
    with open(schema_path, encoding="utf-8") as f:
        document = parse(f.read())

    lines = []
    for definition in document.definitions:
        if not isinstance(definition, (ObjectTypeDefinitionNode, InterfaceTypeDefinitionNode)):
            continue
        type_name = definition.name.value
        for field in definition.fields or ():
            subject = f"<{ns}{type_name}.{field.name.value}>"
            if field.description and field.description.value.strip():
                lines.append(f"{subject} skos:definition {literal(field.description.value.strip())} .")
            for arg in field.arguments or ():
                if arg.name.value == "unit" and isinstance(arg.default_value, EnumValueNode):
                    unit_enum = arg.type.name.value if hasattr(arg.type, "name") else arg.type.type.name.value
                    lines.append(f"{subject} s2x:defaultUnit <{ns}{unit_enum}.{arg.default_value.value}> .")
            for directive in field.directives or ():
                name = directive.name.value
                if name == "instanceTag":
                    lines.append(f"{subject} s2x:instanceTag true .")
                elif name == "range":
                    for arg in directive.arguments or ():
                        value = number(arg.value)
                        if arg.name.value in ("min", "max") and value is not None:
                            lines.append(f"{subject} s2x:{arg.name.value} {float(value)!r} .")

    with open(out_path, "w", encoding="utf-8") as out:
        out.write("# What `s2dm export rdf` leaves out of the schema, extracted by CQELS4J's\n")
        out.write("# scripts/s2dm/supplement.py. Not s2dm output; see S2DM_MAPPING.md.\n")
        out.write("@prefix skos: <http://www.w3.org/2004/02/skos/core#> .\n")
        out.write(f"@prefix s2x: <{S2X}> .\n\n")
        for line in sorted(lines):
            out.write(line + "\n")
    print(f"wrote {out_path}: {len(lines)} triples")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit("usage: supplement.py <schema.graphql> <namespace> <out.ttl>")
    main(*sys.argv[1:])
