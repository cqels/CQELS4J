"""Link a schema's unit enum values to QUDT units, with QUDT's conversion factors.

This is CQELS4J's own extraction, not s2dm's. Current s2dm generates its unit enums from QUDT and
marks every value with `@reference(uri: "http://qudt.org/vocab/unit/...")`. The trailer example
schema predates that: its unit enums (`Pressure_Unit_Enum { KILOPASCAL ... }`) carry no reference.
This script supplies the link for the enums that some field uses as the type of its `unit`
argument, and copies from QUDT what converting between two such units takes.

For each value V of such an enum:
  - an `@reference(uri: ...)` on the value, if the schema has one, is used as it is;
  - else, if V is in OVERRIDES, the QUDT unit named there;
  - else V is matched against the labels of the QUDT units, upper-cased with every run of other
    characters turned into "_" ("Kilopascal" -> KILOPASCAL, "Degree Celsius" -> DEGREE_CELSIUS),
    skipping units QUDT marks deprecated; when several units match, only those QUDT gives the
    quantity kind named by the enum (Length_Unit_Enum -> quantitykind:Length) are kept, and if
    that does not leave exactly one, the value gets no link;
  - a value left with no unit, or with more than one, gets no link and is reported on stderr.

Output, sorted so that a re-run gives the same file:
  <ns>Enum.X s2x:qudtUnit unit:U .                       (the link)
  unit:U rdfs:label, qudt:conversionMultiplier, qudt:conversionOffset, qudt:unitForQuantityKind,
         qudt:hasDimensionVector                          (copied from QUDT as published)
  <ns>Enum.X s2x:conversion [ s2x:to <ns>Enum.Y ; s2x:factor f ; s2x:offset o ] .
                                                          (derived here; v in X is f * v + o in Y)

QUDT's convention: a value v in unit U is (v + o_U) * m_U in the SI unit of its quantity kind
(an offset QUDT does not state is 0): 32 degF is (32 + 459.67) * 5/9 = 273.15 K. So v in X is
v * m_X / m_Y + o_X * m_X / m_Y - o_Y in Y, computed
here in decimal arithmetic and rounded to 34 significant digits, QUDT's own precision.

Every value of a unit enum converts to itself (factor 1, offset 0), linked or not: a reading in
the declared unit needs no factor. Between two different values a conversion is written only if
both are linked to units that share a dimension vector and a quantity kind and have a non-zero
multiplier: the dimension vector alone
would let percent convert to decibels (both dimensionless), and QUDT gives logarithmic units such
as the decibel the multiplier 0.

Usage (inside the s2dm image, where graphql-core and rdflib are installed):
    python -I units.py <schema.graphql> <namespace> <VOCAB_QUDT-UNITS-ALL.ttl> <qudt-tag> <out.ttl>
"""

import re
import sys
from decimal import Decimal, getcontext

from graphql import parse
from graphql.language import (
    EnumTypeDefinitionNode,
    InterfaceTypeDefinitionNode,
    ObjectTypeDefinitionNode,
    StringValueNode,
)
from rdflib import Graph, Literal, Namespace, URIRef
from rdflib.namespace import RDFS

QUDT = Namespace("http://qudt.org/schema/qudt/")
UNIT = "http://qudt.org/vocab/unit/"
QK = "http://qudt.org/vocab/quantitykind/"

# Enum value -> QUDT unit local name, for values whose name is not one of the unit's labels.
OVERRIDES = {
    "POUNDS_PER_SQUARE_INCH": "PSI",    # QUDT labels it "Psi"
    "INCH": "IN",                        # "Inch" also labels unit:ZOLL, QUDT's exact match of IN
    "POUND": "LB",                       # QUDT: "Pound Mass"
    "RADIANS_PER_SECOND": "RAD-PER-SEC",  # QUDT: "Radian per Second"
    "METERS_PER_SECOND": "M-PER-SEC",    # QUDT: "Metre per Second"
    "RATIO": "FRACTION",                 # a dimensionless ratio, 0.5 for one half
    "NANO_METER_PER_KILOMETER": "PPT",   # 1 nm/km = 1e-12: QUDT's "Part per Trillion"
}


def normalise(label):
    return re.sub(r"[^A-Z0-9]+", "_", label.upper()).strip("_")


def conversion(m_from, o_from, m_to, o_to):
    """Factor f and offset o such that v in the first unit is v * f + o in the second, under
    QUDT's convention SI = (v + offset) * multiplier. The only place the formula is written."""
    return m_from / m_to, o_from * m_from / m_to - o_to


def check_convention(qudt):
    """Fail unless conversion() on QUDT's own degC, degF and K facts gives 0 degC = 32 degF,
    32 degF = 0 degC, 0 degC = 273.15 K and 373.15 K = 212 degF, so neither a change in QUDT's
    convention nor a slip in conversion() can pass silently. It checks conversion() only, not the
    code in main() that reads the facts and passes them to it."""
    def facts(unit):
        m = Decimal(str(next(qudt.objects(URIRef(UNIT + unit), QUDT.conversionMultiplier))))
        o = Decimal(str(next(qudt.objects(URIRef(UNIT + unit), QUDT.conversionOffset), Literal("0"))))
        return m, o
    for source, target, v, expected in (("DEG_C", "DEG_F", "0", "32"), ("DEG_F", "DEG_C", "32", "0"),
                                        ("DEG_C", "K", "0", "273.15"), ("K", "DEG_F", "373.15", "212")):
        f, o = conversion(*facts(source), *facts(target))
        got = Decimal(v) * f + o
        if abs(got - Decimal(expected)) > Decimal("1e-20"):
            sys.exit(f"conversion check failed: {v} {source} gave {got} {target}, not {expected}")


def decimal(d):
    text = format(d.normalize(), "f")
    return text if "." in text else text + ".0"


def type_name(node):
    while not hasattr(node, "name"):
        node = node.type
    return node.name.value


def reference(value):
    for directive in value.directives or ():
        if directive.name.value == "reference":
            for arg in directive.arguments:
                if arg.name.value == "uri" and isinstance(arg.value, StringValueNode):
                    return arg.value.value
    return None


def main(schema_path, ns, qudt_path, qudt_tag, out_path):
    with open(schema_path, encoding="utf-8") as f:
        document = parse(f.read())

    unit_enums = set()
    for definition in document.definitions:
        if isinstance(definition, (ObjectTypeDefinitionNode, InterfaceTypeDefinitionNode)):
            for field in definition.fields or ():
                for arg in field.arguments or ():
                    if arg.name.value == "unit":
                        unit_enums.add(type_name(arg.type))
    enums = {d.name.value: d for d in document.definitions
             if isinstance(d, EnumTypeDefinitionNode) and d.name.value in unit_enums}

    qudt = Graph()
    qudt.parse(qudt_path, format="turtle")
    deprecated = set(qudt.subjects(QUDT.deprecated, Literal(True)))
    by_label = {}
    for unit, label in qudt.subject_objects(RDFS.label):
        if str(unit).startswith(UNIT) and unit not in deprecated:
            by_label.setdefault(normalise(str(label)), set()).add(unit)

    links = {}
    unlinked = []
    for enum_name in sorted(enums):
        kind = URIRef(QK + enum_name.removesuffix("_Unit_Enum"))
        for value in enums[enum_name].values or ():
            v = value.name.value
            if reference(value):
                candidates = {URIRef(reference(value))}
            elif v in OVERRIDES:
                candidates = {URIRef(UNIT + OVERRIDES[v])}
            else:
                candidates = by_label.get(v, set())
                if len(candidates) > 1:
                    candidates = {u for u in candidates if (u, QUDT.unitForQuantityKind, kind) in qudt}
            if len(candidates) == 1:
                unit = next(iter(candidates))
                if (unit, None, None) not in qudt:
                    sys.exit(f"{enum_name}.{v}: {unit} is not in QUDT {qudt_tag}")
                links[f"{enum_name}.{v}"] = unit
            else:
                unlinked.append(f"{enum_name}.{v} ({len(candidates)} QUDT units match)")

    lines = [f"<{ns}{key}> s2x:qudtUnit <{unit}> ." for key, unit in links.items()]
    for unit in sorted(set(links.values())):
        for predicate in (RDFS.label, QUDT.conversionMultiplier, QUDT.conversionOffset,
                          QUDT.unitForQuantityKind, QUDT.hasDimensionVector):
            for obj in qudt.objects(unit, predicate):
                if predicate == RDFS.label and obj.language not in (None, "en"):
                    continue
                lines.append(f"<{unit}> <{predicate}> {obj.n3()} .")
    def facts(unit):
        m = next(qudt.objects(unit, QUDT.conversionMultiplier), None)
        o = next(qudt.objects(unit, QUDT.conversionOffset), Literal("0"))
        return (Decimal(str(m)) if m is not None else None, Decimal(str(o)),
                set(qudt.objects(unit, QUDT.hasDimensionVector)), set(qudt.objects(unit, QUDT.unitForQuantityKind)))

    getcontext().prec = 34
    check_convention(qudt)
    conversions = 0
    for enum_name in sorted(enums):
        for value in enums[enum_name].values or ():
            x = f"{enum_name}.{value.name.value}"
            lines.append(f"<{ns}{x}> s2x:conversion [ s2x:to <{ns}{x}> ; s2x:factor 1.0 ; s2x:offset 0.0 ] .")
            conversions += 1
    for x, ux in links.items():
        mx, ox, dx, kx = facts(ux)
        for y, uy in links.items():
            my, oy, dy, ky = facts(uy)
            if x == y or not mx or not my or not (dx & dy) or not (kx & ky):
                continue
            factor, offset = conversion(mx, ox, my, oy)
            lines.append(f"<{ns}{x}> s2x:conversion [ s2x:to <{ns}{y}> ; s2x:factor {decimal(factor)} ;"
                         f" s2x:offset {decimal(offset)} ] .")
            conversions += 1
    lines.sort()

    with open(out_path, "w", encoding="utf-8") as out:
        out.write("# Generated by CQELS4J scripts/s2dm/units.py (not by s2dm): the trailer schema's unit\n")
        out.write("# enum values linked to QUDT units, and the QUDT facts converting between them needs.\n")
        out.write(f"# QUDT {qudt_tag}, https://github.com/qudt/qudt-public-repo, (c) QUDT.org, CC BY 4.0\n")
        out.write("# (https://creativecommons.org/licenses/by/4.0/). Statements about QUDT units are copied\n")
        out.write("# as published; the s2x:qudtUnit links and the s2x:conversion factors derived from them\n")
        out.write("# are this repository's.\n")
        out.write("PREFIX s2x: <https://example.org/s2dm/supplement#>\n\n")
        out.write("\n".join(lines) + "\n")
    print(f"wrote {out_path}: {len(links)} enum values linked to {len(set(links.values()))} QUDT units,"
          f" {conversions} conversions", file=sys.stderr)
    for u in unlinked:
        print(f"  not linked: {u}", file=sys.stderr)


if __name__ == "__main__":
    if len(sys.argv) != 6:
        sys.exit(__doc__)
    main(*sys.argv[1:])
