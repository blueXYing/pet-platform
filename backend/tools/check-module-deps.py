#!/usr/bin/env python3
from pathlib import Path
import xml.etree.ElementTree as ET
import sys

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
violations = []

for pom in ROOT.glob("pet-*-biz/pom.xml"):
    tree = ET.parse(pom)
    artifact = pom.parent.name
    for dep in tree.findall(".//m:dependencies/m:dependency", NS):
        group = dep.findtext("m:groupId", default="", namespaces=NS)
        target = dep.findtext("m:artifactId", default="", namespaces=NS)
        if group == "com.petplatform" and target.endswith("-biz"):
            violations.append(f"{artifact} -> {target}")

if violations:
    print("Forbidden biz -> biz dependencies:")
    for v in violations:
        print("  " + v)
    sys.exit(1)

print("OK: no biz -> biz Maven dependencies")
