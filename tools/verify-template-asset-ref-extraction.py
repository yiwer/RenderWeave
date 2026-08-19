#!/usr/bin/env python3
"""Independent replay of the Template AssetRef/TemplateUse dependency-projection extraction.

A2: re-implements the extraction rules of the Java AssetRefAtomExtractor from the frozen
DesignDSL contract (ticket 09 §213, ticket 12 §118) and compares against the Java primary
report over the shared fixture corpus. An AssetRef atom is any object whose member set is
exactly {assetId} with a canonical UUID v4; the kind comes from the hosting imageRef/fontRef
member or the typing valueType (literal sources, mapping operands, custom defaults,
asset-ref list items). TemplateUse occurrences come from kind=templateUse + templateRef.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any

UUID_V4 = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
ASSET_REF_MEMBERS = {"assetId"}
ASSET_KIND_MEMBERS = {"imageRef", "fontRef"}


def escape_pointer(token: str) -> str:
    return token.replace("~", "~0").replace("/", "~1")


def typed_asset_kind(object_value: dict[str, Any]) -> str | None:
    value_type = object_value.get("valueType")
    if isinstance(value_type, str) and value_type in ASSET_KIND_MEMBERS:
        return value_type
    if (
        isinstance(value_type, dict)
        and value_type.get("type") == "list"
        and isinstance(value_type.get("items"), str)
        and value_type["items"] in ASSET_KIND_MEMBERS
    ):
        return value_type["items"]
    return None


def is_asset_ref(object_value: dict[str, Any]) -> bool:
    if set(object_value.keys()) != ASSET_REF_MEMBERS:
        return False
    asset_id = object_value.get("assetId")
    return isinstance(asset_id, str) and UUID_V4.fullmatch(asset_id) is not None


class Extractor:
    def __init__(self) -> None:
        self.atoms: list[tuple[str, str, str]] = []
        self.uses: list[tuple[str, str]] = []

    def walk(self, value: Any, pointer: str) -> None:
        if isinstance(value, dict):
            for member_name in ASSET_KIND_MEMBERS:
                ref = value.get(member_name)
                if isinstance(ref, dict) and is_asset_ref(ref):
                    self.atoms.append((ref["assetId"], member_name, pointer + "/" + member_name))
            kind = typed_asset_kind(value)
            if kind is not None:
                for member_name in ("value", "defaultValue"):
                    member_value = value.get(member_name)
                    if isinstance(member_value, dict) and is_asset_ref(member_value):
                        self.atoms.append(
                            (member_value["assetId"], kind, pointer + "/" + member_name)
                        )
                    elif isinstance(member_value, list):
                        for index, item in enumerate(member_value):
                            if isinstance(item, dict) and is_asset_ref(item):
                                self.atoms.append(
                                    (item["assetId"], kind,
                                     pointer + "/" + member_name + "/" + str(index))
                                )
            if (
                value.get("kind") == "templateUse"
                and isinstance(value.get("templateRef"), dict)
                and isinstance(value["templateRef"].get("templateId"), str)
            ):
                self.uses.append(
                    (value["templateRef"]["templateId"], pointer + "/templateRef/templateId")
                )
            for member_name, member_value in value.items():
                self.walk(member_value, pointer + "/" + escape_pointer(member_name))
        elif isinstance(value, list):
            for index, item in enumerate(value):
                self.walk(item, pointer + "/" + str(index))

    def extract(self, design_dsl: str) -> tuple[list[dict[str, str]], list[dict[str, str]]]:
        parsed = json.loads(design_dsl)
        self.walk(parsed, "")
        atoms = sorted(
            (
                {"assetId": asset_id, "kind": kind, "canonicalPointer": pointer}
                for asset_id, kind, pointer in self.atoms
            ),
            key=lambda atom: atom["canonicalPointer"],
        )
        uses = sorted(
            (
                {"targetTemplateId": target, "canonicalPointer": pointer}
                for target, pointer in self.uses
            ),
            key=lambda use: use["canonicalPointer"],
        )
        return atoms, uses


def load_json(path: Path) -> tuple[bytes, Any]:
    raw = path.read_bytes()
    return raw, json.loads(raw.decode("utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixtures", required=True, type=Path)
    parser.add_argument("--primary-report", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()

    fixture_bytes, fixtures_doc = load_json(args.fixtures)
    _, primary = load_json(args.primary_report)
    if primary["reportVersion"] != "renderweave-template-asset-ref-primary/1":
        raise AssertionError("Unexpected Java primary report version")
    if primary["engine"] != "java-primary":
        raise AssertionError("Unexpected primary engine")
    if primary["fixturesSha256"] != hashlib.sha256(fixture_bytes).hexdigest():
        raise AssertionError("Primary fixture bytes drift")

    results = []
    for fixture in fixtures_doc["fixtures"]:
        extractor = Extractor()
        atoms, uses = extractor.extract(fixture["designDsl"])
        result = {"id": fixture["id"], "assetAtoms": atoms, "templateUses": uses}
        results.append(result)

    if primary["fixtures"] != results:
        raise AssertionError("Java primary and independent Python extractions differ")

    report = {
        "reportVersion": "renderweave-template-asset-ref-independent/1",
        "engine": "python-independent",
        "assurance": "A2",
        "fixturesSha256": hashlib.sha256(fixture_bytes).hexdigest(),
        "primaryReportSha256": hashlib.sha256(args.primary_report.read_bytes()).hexdigest(),
        "fixtures": results,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(
        f"Template asset-ref extraction independent replay: {len(results)} fixtures, 0 failures"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
