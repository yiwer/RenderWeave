#!/usr/bin/env python3
"""Shared fail-closed primitives for independent offline JSON verifiers."""

from __future__ import annotations

import json
from typing import Any


FORBIDDEN_PAYLOAD_TOKENS = (
    "base64",
    "data:image",
    "ocrtext",
    "ocr_text",
    "imagebytes",
    "prompttext",
    "providerrequest",
    "providerresponse",
    "modeloutput",
    "candidatejson",
    "rootdocument",
    "boundingbox",
    '"bbox"',
    "inspectionrequest",
    "ignore prior instructions",
    "bearer ",
)
JAVA_INT_MAX = 2_147_483_647
JAVA_LONG_MAX = 9_223_372_036_854_775_807


def same_json_value(actual: Any, expected: Any) -> bool:
    """Compare JSON values without Python's bool/int or int/float coercion."""
    if type(actual) is not type(expected):
        return False
    if type(actual) is dict:
        return set(actual) == set(expected) and all(
            same_json_value(actual[key], expected[key]) for key in actual
        )
    if type(actual) is list:
        return len(actual) == len(expected) and all(
            same_json_value(left, right) for left, right in zip(actual, expected)
        )
    return actual == expected


def exact_object(value: Any, fields: set[str] | frozenset[str]) -> bool:
    return type(value) is dict and set(value) == set(fields)


def strict_nonnegative_int(value: Any, maximum: int = JAVA_LONG_MAX) -> bool:
    return type(value) is int and 0 <= value <= maximum


def strict_positive_int(value: Any, maximum: int = JAVA_LONG_MAX) -> bool:
    return type(value) is int and 0 < value <= maximum


def payload_safe(value: Any) -> bool:
    """Scan decoded JSON so Unicode escapes cannot hide forbidden material."""
    serialized = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).casefold()
    return all(token not in serialized for token in FORBIDDEN_PAYLOAD_TOKENS)


def raw_payload_safe(raw: bytes) -> bool:
    lowered = raw.lower()
    return all(token.encode("utf-8") not in lowered for token in FORBIDDEN_PAYLOAD_TOKENS)
