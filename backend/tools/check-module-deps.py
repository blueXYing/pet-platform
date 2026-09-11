#!/usr/bin/env python3
"""ARCH-001: inspect every declared production module, including profile dependencies."""
from pathlib import Path
import argparse
import xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def check(root):
    model = ET.parse(root / "pom.xml")
    modules = [n.text.strip() for n in model.findall("./m:modules/m:module", NS)]
    if not modules or len(modules) != len(set(modules)):
        raise ValueError("ARCH-COVERAGE: missing or duplicate reactor modules")
    discovered = {p.parent.name for p in root.glob("pet-*/pom.xml")}
    if set(modules) != discovered:
        raise ValueError(f"ARCH-COVERAGE: reactor/disk mismatch: {set(modules) ^ discovered}")
    violations = []
    biz_count = 0
    for name in modules:
        pom = ET.parse(root / name / "pom.xml")
        if pom.findtext("m:artifactId", namespaces=NS) != name:
            raise ValueError(f"ARCH-COVERAGE: artifact/directory mismatch: {name}")
        if not name.endswith("-biz"):
            continue
        biz_count += 1
        for dep in pom.findall(".//m:dependencies/m:dependency", NS):
            target = dep.findtext("m:artifactId", default="", namespaces=NS)
            # Aliased groups, test scope and inactive profiles cannot hide a biz edge.
            if target.endswith("-biz") or "${" in target:
                violations.append(f"ARCH-001: {name} -> {target}")
    if not biz_count:
        raise ValueError("ARCH-COVERAGE: no biz modules scanned")
    return modules, biz_count, violations


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    try:
        modules, count, violations = check(args.root)
        print(f"ARCH-001 scanned {len(modules)} reactor modules, {count} biz POMs")
        print("\n".join(violations) if violations else "PASS: no biz -> biz dependencies")
        raise SystemExit(bool(violations))
    except (ValueError, OSError, ET.ParseError) as error:
        print(error)
        raise SystemExit(1)
