"""Browser-level audit for the disposable Schema Studio prototype."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from playwright.sync_api import Page, sync_playwright


def assert_no_horizontal_overflow(page: Page) -> None:
    dimensions = page.evaluate(
        """() => ({
          clientWidth: document.documentElement.clientWidth,
          scrollWidth: document.documentElement.scrollWidth,
        })"""
    )
    assert dimensions["scrollWidth"] <= dimensions["clientWidth"], dimensions


def audit_variant(page: Page, base_url: str, variant: str, output_dir: Path) -> dict[str, object]:
    console_errors: list[str] = []
    page_errors: list[str] = []
    page.on("console", lambda message: console_errors.append(message.text) if message.type == "error" else None)
    page.on("pageerror", lambda error: page_errors.append(str(error)))

    page.goto(f"{base_url}/prototype/schema-studio?variant={variant}")
    page.wait_for_load_state("networkidle")

    root = page.locator('[data-prototype="schema-studio"]')
    assert root.is_visible()
    assert page.get_by_role("heading", name="商品推广卡").is_visible()
    assert page.locator(".prototype-switcher").is_visible()
    assert_no_horizontal_overflow(page)

    if variant == "A":
        assert page.get_by_text("6 fields · additionalProperties=true", exact=True).is_visible()
        page.get_by_role("button", name="添加字段").click()
        assert page.get_by_text("7 fields · additionalProperties=true", exact=True).is_visible()
        page.get_by_role("button", name="保存（模拟）").click()
        assert page.locator(".schema-meta strong").text_content() == "8"
    elif variant == "B":
        assert page.locator(".react-flow__node").count() == 7
        assert page.get_by_role("region", name="诊断摘要").is_visible()
        page.get_by_role("button", name="字段", exact=True).click()
        assert page.locator(".react-flow__node").count() == 8
    else:
        assert page.get_by_role("table", name="Schema 字段账本").is_visible()
        assert page.get_by_role("complementary", name="编译后 JSON Schema 预览").is_visible()
        page.get_by_role("button", name="截止时间设为必填").click()
        assert page.get_by_role("button", name="截止时间设为可选").get_attribute("aria-pressed") == "true"

    page.keyboard.press("Home")
    page.keyboard.press("Tab")
    assert page.locator(":focus").count() == 1

    screenshot = output_dir / f"schema-studio-{variant.lower()}-1440x900.png"
    page.screenshot(path=str(screenshot), full_page=True)
    assert not console_errors, console_errors
    assert not page_errors, page_errors
    return {
        "variant": variant,
        "screenshot": str(screenshot),
        "consoleErrors": console_errors,
        "pageErrors": page_errors,
    }


def audit_minimum_width(page: Page, base_url: str) -> None:
    page.set_viewport_size({"width": 1024, "height": 768})
    page.goto(f"{base_url}/prototype/schema-studio?variant=A")
    page.wait_for_load_state("networkidle")
    assert page.locator(".variant-shell").is_visible()
    assert_no_horizontal_overflow(page)

    page.set_viewport_size({"width": 1000, "height": 768})
    page.reload()
    page.wait_for_load_state("networkidle")
    assert page.get_by_text("RenderWeave v1 需要至少 1024px 宽度").is_visible()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:4173")
    parser.add_argument("--output", default=".sdlc/evidence/prototype-audit")
    args = parser.parse_args()

    output_dir = Path(args.output).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context(
            viewport={"width": 1440, "height": 900},
            reduced_motion="reduce",
            locale="zh-CN",
        )
        context.set_default_timeout(10_000)
        results = [audit_variant(context.new_page(), args.base_url, variant, output_dir) for variant in "ABC"]
        audit_minimum_width(context.new_page(), args.base_url)
        browser.close()

    print(json.dumps({"status": "passed", "variants": results, "minimumWidth": 1024}, ensure_ascii=False))


if __name__ == "__main__":
    main()
