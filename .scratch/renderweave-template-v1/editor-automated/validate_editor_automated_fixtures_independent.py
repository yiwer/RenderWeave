import hashlib
import json
import platform
import sys
from pathlib import Path

SPEC = Path(__file__).resolve().parent.parent
ROOT = "editor-automated"
PROFILE = "renderweave-editor-automated-generator/1.0"
EXECUTION_CLASS = "EXEC::EDITOR_AUTOMATED::1.0"
BASELINE_ID = "baseline.editor-automated.minimal-v1"
ADAPTER_ID = "renderweave-editor-automated-observation-adapter/1.0"
SCENARIO_ID = "EDITOR-AUTOMATED-STRUCTURED-CLEAN-RECHECK-PENDING"
IMPLEMENTATION_REVISION = "editor-automated-fixture-generator/1.0"
FIXTURE_PATH = f"{ROOT}/fixtures/named-editor-structured-clean-recheck-pending.json"

checks = 0


def check(condition, message):
    global checks
    checks += 1
    if not condition:
        raise AssertionError(message)


def raw(path):
    return (SPEC / path).read_bytes()


def read_json(path):
    return json.loads(raw(path).decode("utf-8"))


def digest(content):
    return "sha256:" + hashlib.sha256(content).hexdigest()


def artifact(path):
    content = raw(path)
    return {"path": path, "sha256": digest(content), "byteLength": len(content)}


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2, separators=(",", ": ")) + "\n").encode("utf-8")


def exact_keys(value, expected, label):
    check(list(value.keys()) == expected, f"{label} keys")


def expected_adapter(profile):
    admitted = [probe for probe in profile["probes"] if EXECUTION_CLASS in probe["executionClasses"]]
    return {
        "artifactVersion": "renderweave-editor-automated-observation-adapter/1.0",
        "adapterId": ADAPTER_ID,
        "status": "FROZEN_CONTRACT_BROWSER_TARGET_PENDING",
        "executionClass": EXECUTION_CLASS,
        "probeProfile": profile["candidateProbeProfileId"],
        "closedObservationVersion": "renderweave-editor-automated-closed-observation/1.0",
        "observationBoundary": "one exact supported-browser and operating-system target after one named user-visible command or state transition settles",
        "genericJsonPathAllowed": False,
        "arbitraryScriptAllowed": False,
        "fallbackAllowed": False,
        "expectedValuesVisibleToTarget": False,
        "mappings": [{
            "probeId": probe["probeId"],
            "valueType": probe["valueType"],
            "source": f"closedObservation.{probe['probeId']}",
            "absentPolicy": "EXPLICIT_ABSENT" if "ABSENT" in probe["allowedOperators"] else "MUST_BE_PRESENT"
        } for probe in admitted],
        "mappingCount": len(admitted),
        "fixtureOnlyBoundary": {
            "closedObservationProduced": False,
            "browserTargetBound": False,
            "browserAutomationExecuted": False,
            "accessibilityAssertionsEvaluated": False,
            "j1Evaluated": False
        },
        "evidenceBoundary": "This adapter freezes exact probe extraction only. The named fixture does not produce an observation; one exact target and its required browser-automation runner must later populate and replay every mapped field."
    }


def main():
    paths = [
        f"{ROOT}/baseline-v1.json",
        f"{ROOT}/fixture-contract-v1.json",
        f"{ROOT}/observation-adapter-v1.json",
        FIXTURE_PATH,
        f"{ROOT}/capacity-scenarios-v1.json",
        f"{ROOT}/generator-goldens-v1.json",
        f"{ROOT}/generator-target-manifest-v1.json",
        f"{ROOT}/generator-implementation-manifest-v1.json"
    ]
    for path in paths:
        content = raw(path)
        check(not content.startswith(b"\xef\xbb\xbf"), f"{path} no BOM")
        check(b"\r" not in content, f"{path} LF only")
        check(content.endswith(b"\n"), f"{path} final LF")
        check(encoded(read_json(path)) == content, f"{path} canonical pretty JSON")

    baseline = read_json(f"{ROOT}/baseline-v1.json")
    exact_keys(baseline, ["fixtureVersion", "baselineId", "executionClass", "authorityBoundary", "informationArchitecture", "trustedCurrentContext", "editorSessionContext", "environmentContext", "faultSchedule", "browserAutomationInvoked", "webServerInvoked", "productCodeInvoked", "externalReadsAllowed", "networkReadsAllowed", "productMutationAllowedByFixtureGeneration"], "baseline")
    check(baseline["baselineId"] == BASELINE_ID, "baseline id")
    check(baseline["informationArchitecture"]["variant"] == "B_CANVAS_FOCUS", "Canvas Focus variant")
    session = baseline["editorSessionContext"]
    check(session["mode"] == "STRUCTURED", "Structured mode")
    check(session["dirty"] is False and session["canonicalSemanticDiffCount"] == 0, "clean semantic baseline")
    check(session["currentRecheckState"] == "PENDING", "current recheck pending")
    check(session["saveEnabled"] is False, "save disabled")
    check(session["authoritativePreview"]["enabled"] is False, "preview disabled")
    check(baseline["trustedCurrentContext"]["productContentHashConstructed"] is False, "no product content hash")
    check(baseline["environmentContext"]["exactBrowserAndOperatingSystemTargetBound"] is False, "no exact browser target")
    for key in ["browserAutomationInvoked", "webServerInvoked", "productCodeInvoked", "externalReadsAllowed", "networkReadsAllowed", "productMutationAllowedByFixtureGeneration"]:
        check(baseline[key] is False, f"baseline {key} false")

    contract = read_json(f"{ROOT}/fixture-contract-v1.json")
    check(contract["allowedModes"] == ["NAMED_SCENARIO"], "named only")
    check(contract["capacityBoundary"] == {"supported": False, "assignedAxisCount": 0, "reason": "Ticket19 assigns no capacity axis to EXEC::EDITOR_AUTOMATED::1.0"}, "zero capacity")
    check(contract["namedScenario"]["allowedScenarioIds"] == [SCENARIO_ID], "closed scenario")

    probe_profile = read_json("conformance-probe-profile-v1.json")
    adapter = read_json(f"{ROOT}/observation-adapter-v1.json")
    expected = expected_adapter(probe_profile)
    check(adapter == expected, "independently reconstructed adapter")
    admitted = [probe for probe in probe_profile["probes"] if EXECUTION_CLASS in probe["executionClasses"]]
    check(adapter["mappingCount"] == len(admitted), "mapping count")
    for mapping, probe in zip(adapter["mappings"], admitted):
        check(mapping["probeId"] == probe["probeId"], f"{probe['probeId']} order")
        check(mapping["valueType"] == probe["valueType"], f"{probe['probeId']} type")
        check(mapping["source"] == f"closedObservation.{probe['probeId']}", f"{probe['probeId']} source")

    fixture = read_json(FIXTURE_PATH)
    exact_keys(fixture, contract["fixtureTopLevelOrder"], "fixture")
    exact_keys(fixture["scenario"], contract["namedScenarioOrder"], "scenario")
    check(fixture["scenario"]["scenarioId"] == SCENARIO_ID, "scenario id")
    check(fixture["baseline"] == artifact(f"{ROOT}/baseline-v1.json"), "baseline binding")
    expected_adapter_ref = {"adapterId": ADAPTER_ID, **artifact(f"{ROOT}/observation-adapter-v1.json")}
    check(fixture["observationAdapter"] == expected_adapter_ref, "adapter binding")
    check(fixture["targetContract"]["fixtureOnlyContext"] is True, "fixture only")
    for key, value in fixture["targetContract"].items():
        if key not in ["fixtureOnlyContext", "exactSupportedBrowserAndOperatingSystemTargetRequired"]:
            check(value is False, f"target {key} false")
    fixture_text = json.dumps(fixture, ensure_ascii=False, separators=(",", ":"))
    for forbidden in contract["forbiddenFixtureMembers"]:
        check(f'"{forbidden}"' not in fixture_text, f"fixture forbids {forbidden}")

    scenarios = read_json(f"{ROOT}/capacity-scenarios-v1.json")
    check((scenarios["scenarioCount"], scenarios["capacityScenarioCount"], scenarios["namedScenarioCount"]) == (1, 0, 1), "scenario counts")
    check(scenarios["scenarios"][0]["parameters"]["fixtureArtifactSha256"] == artifact(FIXTURE_PATH)["sha256"], "scenario fixture digest")
    goldens = read_json(f"{ROOT}/generator-goldens-v1.json")
    check((goldens["goldenCount"], goldens["capacityGoldenCount"], goldens["namedGoldenCount"]) == (1, 0, 1), "golden counts")
    check(goldens["vectors"][0]["expectedFixtureArtifact"] == artifact(FIXTURE_PATH), "golden fixture")

    target = read_json(f"{ROOT}/generator-target-manifest-v1.json")
    check(target["expectedScenarioCount"] == 1 and target["capacityAxisCount"] == 0, "target counts")
    check(target["probeProfile"] == artifact("conformance-probe-profile-v1.json"), "probe profile binding")
    check(target["fixtureArtifacts"] == [artifact(FIXTURE_PATH)], "target fixture binding")
    for key in ["productTarget", "browserTarget", "productExecutionAllowed", "browserAutomationAllowed", "networkReadsAllowed", "environmentReadsAllowed", "currentTimeReadsAllowed"]:
        check(target[key] is False, f"target {key} false")

    implementation = read_json(f"{ROOT}/generator-implementation-manifest-v1.json")
    check(implementation["implementationRevision"] == IMPLEMENTATION_REVISION, "implementation revision")
    check(implementation["targetManifest"] == artifact(f"{ROOT}/generator-target-manifest-v1.json"), "implementation target binding")
    check(implementation["browserAutomationAllowed"] is False and implementation["productExecutionAllowed"] is False, "implementation execution disabled")

    result = {
        "resultVersion": "renderweave-editor-automated-fixture-generator-result/1.0",
        "executorId": "EDITOR_AUTOMATED_FIXTURE_GENERATOR::PYTHON::1.0",
        "role": "independent-editor-automated-fixture-generator-replayer",
        "status": "PASS",
        "checkCount": checks,
        "failureCount": 0,
        "runtime": f"CPython {platform.python_version()}",
        "generatorTargetSha256": artifact(f"{ROOT}/generator-target-manifest-v1.json")["sha256"],
        "fixtureCount": 1,
        "browserAutomationObserved": False,
        "productExecutionObserved": False,
        "j1Observed": False,
        "recordIssuanceAllowed": False,
        "implementationRevision": IMPLEMENTATION_REVISION,
        "targetManifest": artifact(f"{ROOT}/generator-target-manifest-v1.json"),
        "entrypoint": artifact(f"{ROOT}/validate_editor_automated_fixtures_independent.py")
    }
    (SPEC / ROOT / "independent-result-v1.json").write_bytes(encoded(result))
    print(json.dumps({"status": "PASS", "checkCount": checks, "fixtureCount": 1}, separators=(",", ":")))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(json.dumps({"status": "FAIL", "error": str(exc)}, separators=(",", ":")), file=sys.stderr)
        raise
