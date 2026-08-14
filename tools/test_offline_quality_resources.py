#!/usr/bin/env python3
"""Contract tests for repository-owned offline protocol and challenger catalog."""

from __future__ import annotations

import copy
import pathlib
import unittest

import offline_quality_resources as resources


REPOSITORY = pathlib.Path(__file__).resolve().parent.parent


class OfflineQualityResourcesTest(unittest.TestCase):
    def test_protocol_is_closed_semantic_and_bound_to_corpus(self) -> None:
        verified = resources.load_protocol(REPOSITORY)
        self.assertEqual(resources.PROTOCOL_VERSION, verified.document["protocolVersion"])
        self.assertEqual(4, len(verified.document["r3ProbeCaseIds"]))
        self.assertEqual(4, len(verified.document["r5ProbeCaseIds"]))

        for mutation in ("extra", "threshold-type", "overlap"):
            changed = copy.deepcopy(verified.document)
            if mutation == "extra":
                changed["unexpectedEvidence"] = 0
            elif mutation == "threshold-type":
                changed["thresholds"]["minimumStructuralImprovementBps"] = 500.0
            else:
                changed["r5ProbeCaseIds"] = list(changed["r3ProbeCaseIds"])
            with self.subTest(mutation=mutation), self.assertRaises(
                resources.ResourceContractError
            ):
                resources.validate_protocol_document(changed, REPOSITORY)

    def test_catalog_is_closed_and_fail_closed(self) -> None:
        verified = resources.load_challenger_catalog(REPOSITORY)
        self.assertEqual(resources.CATALOG_VERSION, verified.document["catalogVersion"])
        self.assertEqual(
            ["pp-structurev3", "tesseract-tsv-hocr"],
            [item["challengerId"] for item in verified.document["challengers"]],
        )
        self.assertTrue(all(
            item["admissionDisposition"] == "NOT_ADMITTED" and item["executable"] is False
            for item in verified.document["challengers"]
        ))

        for mutation in ("extra", "priority-type", "runtime", "license"):
            changed = copy.deepcopy(verified.document)
            challenger = changed["challengers"][0]
            if mutation == "extra":
                challenger["unexpectedEvidence"] = 0
            elif mutation == "priority-type":
                challenger["priority"] = True
            elif mutation == "runtime":
                challenger["runtimeDownloadAllowed"] = True
            else:
                challenger["weightLicense"]["evidenceReference"] = \
                    challenger["codeLicense"]["evidenceReference"]
            with self.subTest(mutation=mutation), self.assertRaises(
                resources.ResourceContractError
            ):
                resources.validate_catalog_document(changed)


if __name__ == "__main__":
    unittest.main()
