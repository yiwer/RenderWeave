"""Browser-level automated observation for the throwaway T09 Editor-state-model prototype.

Drives the guided walkthrough scenarios plus a free-play smoke pass and records
assertion results, screenshots and console/page errors. The prototype and its
assertions are the deterministic fixture model from
web/src/prototype/editor-state-model/model.ts (NOT product code).
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from playwright.sync_api import Page, sync_playwright


def collect_errors(page: Page, console_errors: list[str], page_errors: list[str]) -> None:
    page.on("console", lambda message: console_errors.append(message.text) if message.type == "error" else None)
    page.on("pageerror", lambda error: page_errors.append(str(error)))


def run_scenario(page: Page, index: int, scenario_name: str, output_dir: Path) -> dict[str, object]:
    page.locator(".esm-scenario-list button").nth(index).click()
    page.wait_for_load_state("networkidle")

    step_rows = page.locator(".esm-steps li")
    expected_steps = step_rows.count()
    results: list[dict[str, object]] = []
    # Each step's 执行 button appears only on the current step row; running a step
    # advances to the next row and renders the assertion result in the row just run.
    for _ in range(expected_steps):
        run_button = page.locator(".esm-steps li").filter(
            has=page.get_by_role("button", name="执行", exact=True)
        ).get_by_role("button", name="执行", exact=True)
        if run_button.count() == 0:
            break
        run_button.click()
        # Read the newest assertion result across all step rows.
        pass_spans = page.locator(".esm-steps li .esm-pass")
        fail_spans = page.locator(".esm-steps li .esm-fail")
        if pass_spans.count() > 0 and fail_spans.count() > 0:
            latest = page.locator(
                ".esm-steps li .esm-pass, .esm-steps li .esm-fail"
            ).last
            is_pass = "esm-pass" in (latest.get_attribute("class") or "")
            message = (latest.text_content() or "").strip()
            results.append({"pass": is_pass, "message": message})
        elif pass_spans.count() > 0:
            results.append({"pass": True, "message": pass_spans.last.text_content() or ""})
        elif fail_spans.count() > 0:
            results.append({"pass": False, "message": fail_spans.last.text_content() or ""})

    screenshot = output_dir / f"scenario-{index + 1:02d}-{scenario_name}.png"
    page.screenshot(path=str(screenshot), full_page=True)
    return {
        "scenario": scenario_name,
        "expectedSteps": expected_steps,
        "asserted": len(results),
        "passed": sum(1 for r in results if r["pass"]),
        "failed": [r for r in results if not r["pass"]],
        "screenshot": screenshot.name,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    console_errors: list[str] = []
    page_errors: list[str] = []
    output_dir = args.output
    output_dir.mkdir(parents=True, exist_ok=True)

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        page = browser.new_page(viewport={"width": 1440, "height": 900})
        collect_errors(page, console_errors, page_errors)

        page.goto(f"{args.base_url}/prototype/editor-state-model")
        page.wait_for_load_state("networkidle")
        page.get_by_role("button", name="引导走查（10 场景）", exact=True).click()
        page.wait_for_load_state("networkidle")

        scenario_names = [
            "open-baseline",
            "edit-save",
            "conflict-overwrite",
            "unknown-reconcile",
            "preview-guard",
            "save-and-preview",
            "recovery-lifecycle",
            "dirty-guard",
            "modes",
            "failure-a11y",
        ]
        scenario_results = [
            run_scenario(page, index, name, output_dir)
            for index, name in enumerate(scenario_names)
        ]

        # Free-play smoke pass: a couple of actions + keyboard focus check.
        page.get_by_role("button", name="自由操作", exact=True).click()
        page.wait_for_load_state("networkidle")
        page.get_by_role("button", name="打开 Template", exact=True).click()
        page.get_by_role("button", name="编辑（改 displayName）", exact=True).click()
        page.get_by_role("button", name="保存", exact=True).click()
        state_text = page.locator(".esm-state").inner_text()
        assert "baseline" in state_text and "dirty（canonical 判定）" in state_text
        page.keyboard.press("Home")
        page.keyboard.press("Tab")
        assert page.locator(":focus").count() == 1
        page.screenshot(path=str(output_dir / "freeplay-smoke.png"), full_page=True)

        page.get_by_role("button", name="结论与切片", exact=True).click()
        page.wait_for_load_state("networkidle")
        verdict = page.locator(".esm-panel").inner_text()
        assert "必须丢弃" in verdict and "E9" in verdict
        page.screenshot(path=str(output_dir / "verdict.png"), full_page=True)

        browser.close()

    summary = {
        "prototype": "editor-state-model (T09 throwaway)",
        "scenarios": scenario_results,
        "allAssertionsPassed": all(
            s["failed"] == [] and s["asserted"] == s["expectedSteps"] for s in scenario_results
        ),
        "consoleErrors": console_errors,
        "pageErrors": page_errors,
    }
    summary_path = output_dir / "editor-state-model-observation.json"
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if not summary["allAssertionsPassed"] or console_errors or page_errors:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
