from pathlib import Path
from playwright.sync_api import sync_playwright


BASE = "http://127.0.0.1:5173/prototype/template-designer"
SHOTS = Path(".scratch/shots")
SHOTS.mkdir(parents=True, exist_ok=True)


def expect_text(page, text: str) -> None:
    page.get_by_text(text, exact=False).first.wait_for(state="visible")


with sync_playwright() as playwright:
    browser = playwright.chromium.launch(headless=True)
    page = browser.new_page(viewport={"width": 1600, "height": 1000}, device_scale_factor=1)
    browser_errors: list[str] = []
    page.on("pageerror", lambda error: browser_errors.append(f"pageerror:{error}"))
    page.on(
        "console",
        lambda message: browser_errors.append(f"console:{message.type}:{message.text}")
        if message.type == "error"
        else None,
    )

    page.goto(f"{BASE}?variant=A")
    page.wait_for_load_state("networkidle")
    page.locator('[data-prototype="template-designer"]').wait_for()
    expect_text(page, "A · 三栏工作台")

    page.locator(".td-tree-row", has_text="tagLoop 标签循环").click()
    expect_text(page, "itemLayout")
    expect_text(page, "instanceLayout")
    expect_text(page, "system-basic-text@v1")

    page.locator(".td-tree-row", has_text="titleText 标题").click()
    page.locator(".td-binding.is-interactive").first.click()
    expect_text(page, "node-local targetPropertyRef")
    expect_text(page, '"rootPropertyId": "runs"')
    expect_text(page, "property[index].member")
    page.get_by_role("button", name="取消", exact=True).click()

    page.locator(".td-rail-button", has_text="资产").click()
    page.get_by_role("button", name="多文件上传", exact=True).click()
    expect_text(page, "REJECTED · ASSET_MEDIA_TYPE_UNSUPPORTED")
    page.get_by_role("button", name="删除影响检查", exact=True).click()
    expect_text(page, "全量影响 5 occurrences")
    expect_text(page, "redactedCount 1")

    page.get_by_role("button", name="导入", exact=True).click()
    expect_text(page, "Import / Export 是 authoring 边界")
    page.get_by_role("button", name="打开不受支持版本", exact=False).click()
    expect_text(page, "禁止 partial-model save")

    page.locator(".td-a-bottom-tabs").get_by_role("tab", name="权威预览").click()
    preview = page.locator(".td-preview")
    preview.get_by_role("button", name="JPEG", exact=True).click()
    preview.get_by_label("有界 LayoutTrace").check()
    preview.get_by_role("button", name="运行", exact=True).click()
    expect_text(page, "完整 length + digest 已核验")
    expect_text(page, "JPEG · 340×204px · 96 DPI")
    expect_text(page, "成功结果 sidecar")
    page.screenshot(path=str(SHOTS / "ticket17-variant-a.png"), full_page=False)

    page.goto(f"{BASE}?variant=B")
    page.wait_for_load_state("networkidle")
    expect_text(page, "Immersive Canvas")
    page.locator(".td-b-tabs").get_by_role("tab", name="交换", exact=True).click()
    expect_text(page, "导入 bare DesignDSL")
    page.screenshot(path=str(SHOTS / "ticket17-variant-b.png"), full_page=False)

    page.get_by_role("button", name="下一个原型方案").click()
    page.wait_for_url("**variant=C")
    expect_text(page, "C · 绑定工作台")
    page.locator(".td-binding.is-interactive").first.click()
    expect_text(page, "BindingPolicyCatalog 命中")
    page.get_by_role("button", name="取消", exact=True).click()
    page.screenshot(path=str(SHOTS / "ticket17-variant-c.png"), full_page=False)

    unnamed_buttons = page.locator("button").evaluate_all(
        """buttons => buttons
          .filter(button => !((button.getAttribute('aria-label') || button.innerText || '').trim()))
          .map(button => button.outerHTML.slice(0, 180))"""
    )
    viewport_overflow = page.evaluate(
        "document.documentElement.scrollWidth > window.innerWidth"
    )
    print({
        "browser_errors": browser_errors,
        "unnamed_buttons": unnamed_buttons,
        "viewport_overflow": viewport_overflow,
        "final_url": page.url,
    })
    assert not browser_errors, browser_errors
    assert not unnamed_buttons, unnamed_buttons
    assert not viewport_overflow
    browser.close()
