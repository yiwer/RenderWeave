"""Browser-level audit for the production Schema Draft create/save/reload journey."""

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


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--schema-key", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    output_dir = Path(args.output).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    console_errors: list[str] = []
    page_errors: list[str] = []

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context(
            viewport={"width": 1440, "height": 900},
            reduced_motion="reduce",
            locale="zh-CN",
        )
        context.set_default_timeout(12_000)
        page = context.new_page()
        page.on(
            "console",
            lambda message: console_errors.append(message.text)
            if message.type == "error"
            else None,
        )
        page.on("pageerror", lambda error: page_errors.append(str(error)))

        page.goto(f"{args.base_url}/schemas/new")
        page.wait_for_load_state("networkidle")
        assert page.locator('[data-product="schema-studio"]').is_visible()
        assert page.get_by_role("heading", name="未命名 DraftSchema").is_visible()
        assert_no_horizontal_overflow(page)

        # Invalid local state must never be sent to the server.
        page.get_by_role("button", name="创建 Draft", exact=True).click()
        assert page.get_by_text("项需要处理", exact=False).is_visible()
        assert page.url.endswith("/schemas/new")

        page.locator("#schema-key").fill(args.schema_key)
        page.locator("#schema-display-name").fill("商品展示卡")
        page.locator("#schema-description").fill("P1 浏览器闭环生成的 Schema Draft。")
        page.get_by_label("fieldKey", exact=True).fill("title")
        page.get_by_label("显示名称（可选）", exact=True).fill("商品标题")
        min_length = page.locator('[data-pointer="/definition/fields/0/value/constraints/minLength"]')
        min_length.locator("xpath=..").get_by_role("checkbox").check()
        min_length.fill("1")
        max_length = page.locator('[data-pointer="/definition/fields/0/value/constraints/maxLength"]')
        max_length.locator("xpath=..").get_by_role("checkbox").check()
        max_length.fill("80")
        page.locator(".required-segmented-control").get_by_role(
            "button", name="必填", exact=True
        ).click()

        # Exercise the lossless decimal boundary through the real browser/API path.
        huge_decimal = "12345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678"
        page.get_by_role("button", name="添加字段", exact=True).click()
        page.get_by_label("fieldKey", exact=True).fill("amount")
        page.get_by_label("显示名称（可选）", exact=True).fill("精确金额")
        page.get_by_label("字段类型").select_option("decimal")
        decimal_const = page.locator('[data-pointer="/definition/fields/1/value/constraints/const"]')
        decimal_const.locator("xpath=..").get_by_role("checkbox").check()
        decimal_const.fill(huge_decimal)

        page.get_by_role("button", name="创建 Draft", exact=True).click()
        page.wait_for_url(f"**/schemas/{args.schema_key}")
        page.wait_for_load_state("networkidle")
        page.locator(".rail-context-card small").filter(has_text="revision 0").wait_for()
        assert page.locator("#schema-key").is_editable() is False

        page.locator("#schema-display-name").fill("商品展示卡 · 已复核")
        page.get_by_role("button", name="保存 revision", exact=True).click()
        page.locator(".rail-context-card small").filter(has_text="revision 1").wait_for()
        assert page.locator(".studio-feedback").get_by_text("revision 1 已保存", exact=True).is_visible()

        page.get_by_role("button", name="树状图", exact=True).click()
        page.locator(".react-flow__node").first.wait_for()
        assert page.locator(".react-flow__node").count() == 3
        assert "title" in (page.locator(".react-flow__node").nth(1).text_content() or "")
        page.screenshot(path=str(output_dir / "schema-studio-map-1440x900.png"), full_page=True)

        response = page.request.get(f"{args.base_url}/api/v1/schema-drafts/{args.schema_key}")
        assert response.ok, response.text()
        stored = response.json()
        assert stored["revision"] == 1, stored
        assert stored["definition"]["displayName"] == "商品展示卡 · 已复核", stored
        assert stored["definition"]["fields"] == [
            {
                "fieldKey": "title",
                "displayName": "商品标题",
                "required": True,
                "value": {
                    "type": "text",
                    "constraints": {"minLength": 1, "maxLength": 80},
                },
            },
            {
                "fieldKey": "amount",
                "displayName": "精确金额",
                "required": False,
                "value": {
                    "type": "decimal",
                    "constraints": {"const": int(huge_decimal)},
                },
            },
        ], stored

        page.reload()
        page.wait_for_load_state("networkidle")
        assert page.locator("#schema-display-name").input_value() == "商品展示卡 · 已复核"
        assert page.get_by_label("fieldKey", exact=True).input_value() == "title"
        assert "revision 1" in (page.locator(".rail-context-card small").text_content() or "")
        assert_no_horizontal_overflow(page)
        page.screenshot(path=str(output_dir / "schema-studio-draft-1440x900.png"), full_page=True)

        # Exercise the production resource lifecycle against PostgreSQL, not route mocks.
        page.goto(f"{args.base_url}/schemas")
        page.wait_for_load_state("networkidle")
        assert page.get_by_role("heading", name="数据结构设计").is_visible()
        assert page.get_by_text(args.schema_key, exact=True).is_visible()
        page.screenshot(path=str(output_dir / "schema-draft-list-1440x900.png"), full_page=True)

        page.goto(f"{args.base_url}/schemas/{args.schema_key}")
        page.wait_for_load_state("networkidle")
        page.get_by_role("button", name="历史", exact=True).click()
        page.get_by_role("heading", name="不可变 revision 历史").wait_for()
        page.get_by_role("button", name="revision 0").click()
        page.locator(".history-preview .readonly-schema-tree").wait_for()
        assert "商品展示卡" in (page.locator(".history-preview").text_content() or "")
        page.get_by_role("button", name="恢复为新 revision").click()
        page.locator(".rail-context-card small").filter(has_text="revision 2").wait_for()
        assert page.locator("#schema-display-name").input_value() == "商品展示卡"

        page.get_by_role("button", name="删除", exact=True).click()
        page.get_by_role("heading", name=f"软删除 {args.schema_key}").wait_for()
        page.get_by_role("button", name="确认软删除").click()
        page.wait_for_url(f"{args.base_url}/schemas")
        page.get_by_text("商品展示卡 已软删除", exact=True).wait_for()
        assert page.get_by_text(args.schema_key, exact=True).count() == 1  # restore banner only
        page.get_by_role("button", name="撤销删除").click()
        page.wait_for_url(f"**/schemas/{args.schema_key}")
        page.locator(".rail-context-card small").filter(has_text="revision 3").wait_for()

        version_tag = "browser-v1"
        page.get_by_role("button", name="保存并发布", exact=True).click()
        page.get_by_label("versionTag").fill(version_tag)
        page.get_by_label("发布说明（可选）").fill("真实 PostgreSQL 浏览器闭环")
        page.get_by_role("button", name="原子发布").click()
        page.wait_for_url(f"**/static-schemas/{args.schema_key}/{version_tag}")
        page.get_by_text("不可变边界已建立", exact=True).wait_for()
        page.get_by_role("tab", name="Definition DSL", exact=True).click()
        page.locator(".artifact-panel pre").wait_for()
        assert huge_decimal in (page.locator(".artifact-panel pre").text_content() or "")
        page.screenshot(path=str(output_dir / "static-schema-detail-1440x900.png"), full_page=True)

        copy_key = f"{args.schema_key}-copy"
        page.get_by_role("button", name="复制为 Draft", exact=True).click()
        dialog = page.get_by_role("dialog")
        dialog.get_by_label("新 schemaKey").fill(copy_key)
        dialog.get_by_label("显示名称", exact=True).fill("商品展示卡 · Static 副本")
        dialog.get_by_role("button", name="创建 Draft").click()
        page.wait_for_url(f"**/schemas/{copy_key}")
        page.locator(".rail-context-card small").filter(has_text="revision 0").wait_for()
        assert page.locator("#schema-display-name").input_value() == "商品展示卡 · Static 副本"

        page.goto(f"{args.base_url}/static-schemas")
        page.wait_for_load_state("networkidle")
        static_card = page.locator(".static-card").filter(has_text=args.schema_key)
        assert static_card.is_visible()
        assert static_card.get_by_label(f"版本 {version_tag}").is_visible()

        page.goto(f"{args.base_url}/validator")
        page.wait_for_load_state("networkidle")
        page.get_by_role("button", name="StaticSchema · exact", exact=True).click()
        page.get_by_label("schemaKey").fill(args.schema_key)
        page.get_by_label("versionTag").fill(version_tag)
        page.get_by_label("Document 1 JSON").fill(
            '{\n  "title": "可验证商品",\n  "amount": '
            + huge_decimal
            + ',\n  "unknown": {"accepted": true}\n}'
        )
        page.get_by_role("button", name="验证 1 份样本").click()
        page.get_by_text("全部样本有效", exact=True).wait_for()
        assert f"{args.schema_key}@{version_tag}" in (
            page.locator(".resolved-target").text_content() or ""
        )
        assert_no_horizontal_overflow(page)
        page.screenshot(path=str(output_dir / "root-document-validator-1440x900.png"), full_page=True)

        page.goto(f"{args.base_url}/schemas/{args.schema_key}")
        page.wait_for_load_state("networkidle")
        assert "revision 3" in (page.locator(".rail-context-card small").text_content() or "")

        page.set_viewport_size({"width": 1024, "height": 768})
        page.reload()
        page.wait_for_load_state("networkidle")
        assert page.locator(".studio-body").is_visible()
        assert page.locator(".unsupported-width").is_hidden()
        assert_no_horizontal_overflow(page)
        page.screenshot(path=str(output_dir / "schema-studio-draft-1024x768.png"), full_page=True)

        page.set_viewport_size({"width": 1000, "height": 768})
        page.reload()
        page.wait_for_load_state("networkidle")
        assert page.get_by_text("RenderWeave v1 需要至少 1024px 宽度").is_visible()
        assert page.locator(".studio-body").is_hidden()
        assert_no_horizontal_overflow(page)

        browser.close()

    assert not console_errors, console_errors
    assert not page_errors, page_errors
    print(
        json.dumps(
            {
                "status": "passed",
                "schemaKey": args.schema_key,
                "revision": 3,
                "modes": ["form", "map"],
                "minimumWidth": 1024,
                "screenshots": [
                    str(output_dir / "schema-studio-draft-1440x900.png"),
                    str(output_dir / "schema-studio-map-1440x900.png"),
                    str(output_dir / "schema-draft-list-1440x900.png"),
                    str(output_dir / "static-schema-detail-1440x900.png"),
                    str(output_dir / "root-document-validator-1440x900.png"),
                    str(output_dir / "schema-studio-draft-1024x768.png"),
                ],
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
