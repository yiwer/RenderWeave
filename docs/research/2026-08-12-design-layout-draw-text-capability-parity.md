# `design-layout-draw` Text 能力与 RenderWeave 合同对照

- 调研日期：2026-08-12
- 一手来源：`D:\Yiwer\code\design-layout-draw`
- 用途：RenderWeave Template v1 票据 09 的事实输入
- 性质：研究记录，不是 DesignDSL 决策或实现授权

## 结论

1. 旧系统的 `WpfText` 是**单一 `text` 加整框样式**，不是富文本 `runs[]`。因此 RenderWeave 的纯文本无需第二套 wire：一个完整 `TextRun` 就是纯文本；不要再加会形成双重权威的顶层 `text` 快捷字段。
2. 旧系统看似有“默认字体”，实际存在互相冲突的三套来源：Skia 字段元数据默认 `"L"`、构造器/字体列表默认“黑体”、渲染环境再按配置或 Source Han fallback。它不是稳定的作者事实。RenderWeave 不应复制隐式全局默认；编辑器可以有“新建 Text 默认 FONT Asset”，但必须把选定的 `fontRef` 显式写入每个 Run。
3. 当前拟定合同能表达单样式纯文本，也新增了旧系统没有的多 Run 富文本；但它**不能完全还原旧系统的文字表现**。真实高频缺口是竖排、横纵 `justify/evenly`、Text 内边距和文字描边；另有自动尺寸、缩小适配、装饰及旧 overflow/fallback 差异。

## 1. 旧系统真实作者模型

`WpfText` 的 native 作者对象继承 Leafer `Text` 并混入布局能力；构造器直接接收一个 `text` 和整框字体/布局属性：`svg-edit-web/src/views/Editor/core/shapes/wpfText.native.ts:17-28,43-74,76-120`。Skia 版本把实际参与重绘的作者字段集中列为 `text/fontFamily/fontSize/fontWeight/italic/fill/stroke/.../padding/overScale`：`svg-edit-web/src/views/Editor/core/shapes/wpfText.skia.ts:66-107`，对应 class 声明见同文件 `194-264`。

组件库新建 Text 时也是单值：

```json
{
  "text": "输入文本",
  "fontSize": 50,
  "fontWeight": "normal",
  "fill": [{"type": "solid", "color": "rgba(0,0,0,1)"}]
}
```

来源：`svg-edit-web/src/views/Editor/layouts/panel/leftPanel/busWrap/ComponentLibrary.vue:242-256`。右侧面板直接编辑 `text`，Binding 也指向单一 `text`：`svg-edit-web/src/views/Editor/layouts/panel/rightPanel/attrs/textAttr.vue:297-312`。

### 1.1 真实 fixture 统计

沿已识别 authored root 的 `children[]` 递归统计 `canvaskit/public/template/test` 基线语料，得到 1,681 个 `WpfText`。全部 1,681 个都有 `text/fontFamily/fontSize`，没有一个 `runs`。代表性节点见 `canvaskit/public/template/test/common.json:165-209`。

| 作者字段/值 | 数量 |
| --- | ---: |
| `textDirection=vertical` | 1,088 |
| `textDirection=horizontal` | 329 |
| `textAlign=center / evenly / right / left / justify` | 1,204 / 178 / 91 / 45 / 31 |
| `verticalAlign=justify / middle / evenly / bottom / top` | 1,048 / 479 / 25 / 16 / 3 |
| `padding` 显式存在 | 1,265 |
| `stroke` 显式存在 | 100 |
| `autoSizeText=true` | 13 |
| `overScale=true` | 1 |

未显式写入方向或对齐的节点会依赖运行时默认，所以各枚举计数不必合计为 1,681。fixture 确实覆盖竖排与各种分布对齐，例如 `common.json:2407-2611,3176-3321,3411-3884`；唯一 `overScale=true` 位于 `kaili-1.json:33948`，代表性 `autoSizeText=true` 位于 `shenzhen-1.json:778`。

## 2. “默认字体”不是单一权威

旧系统至少有三层互相覆盖的默认/兜底：

1. **字段元数据默认**：Skia `RENDER_FIELDS` 把 `fontFamily` 默认写成 `"L"`：`svg-edit-web/src/views/Editor/core/shapes/wpfText.skia.ts:77-97`。
2. **构造器和编辑器默认**：Skia 与 native 构造器在输入未带 `fontFamily` 时都写“黑体”：`wpfText.skia.ts:380-390`、`wpfText.native.ts:106-110`；字体 Store 又把 `SimHei/黑体` 作为唯一内置列表项：`svg-edit-web/src/store/modules/font/font.ts:6-24,57-76`。
3. **实际渲染 fallback**：没有有效 URL 时，Skia Renderer 先读环境变量 `VITE_SKIA_FALLBACK_FONT_URL`，再调用 V4 fallback：`wpfText.skiaRenderer.ts:47-70`；字体加载/缺字又会按字重选择 Source Han Sans：`canvaskit/src/drawCore/V4/text/font/checker.ts:13-24,260-320`。V4 `drawText` 在字体资源加载失败时也直接进入 fallback：`canvaskit/src/drawCore/V4/text/index.ts:56-71`。

这三层使“同一缺省作者 JSON”可能随模式、资源库和环境得到不同字形。它不是应继承到 RenderWeave 的能力，而是需要消除的多权威。

建议的 RenderWeave 规则：

- DesignDSL 不设隐式全局字体，也不允许 Renderer 按机器字体兜底。
- 在线编辑器可以配置一个“新建 Text 默认 FONT Asset”，但创建节点时立即快照为显式 `fontRef`。
- 导入缺少字体的旧 DSL 时，迁移器必须显式选择 FONT Asset 并写回，或报告无效；不能把选择延迟到 Render。
- 若未来需要模板级字体 token，应作为显式、版本化且可解析为 AssetRef 的作者定义，不能让 Run 缺失 `fontRef` 后再查环境默认。

## 3. 纯文本在 `runs[]` 合同中的形式

单样式纯文本就是一个 Run：

```json
{
  "nodeId": "...",
  "kind": "text",
  "runs": [
    {
      "text": "纯文本内容",
      "fontRef": {"assetId": "..."},
      "fontSizeMm": 4.233333,
      "color": "#000000FF",
      "letterSpacingMm": 0
    }
  ],
  "bindings": []
}
```

空文本仍用一个完整样式的 `text: ""` Run。多个 Run 只在同一文本框确实需要混合样式或分段 Binding 时出现。顶层再增加 `text` 或 `plainText` alias 会产生“alias 与 `runs` 谁是事实源”的问题，不建议加入。

## 4. 属性面板、作者数据与实际绘制的边界

### 4.1 UI 暴露且 CanvasKit 实际实现

- 字体、字重、字号、行高、字距、换行与横/竖方向：面板见 `textAttr.vue:317-439`；CanvasKit 的字段合同见 `canvaskit/src/editor.ts:31-57,61-93`。
- 斜体和下划线/删除线：面板见 `textAttr.vue:442-487`；CanvasKit 映射 italic 与 decoration 见 `drawCore/V4/text/style.ts:7-62`，实际进入 ParagraphStyle 见 `horizontal.ts:424-447`。
- 横向 `left/center/right/justify/evenly` 与纵向 `top/middle/bottom/justify/evenly`：面板见 `textAttr.vue:490-701`。横排 `evenly/justify` 分别有独立计算/绘制分支：`drawCore/V4/text/index.ts:167-231`；竖排方向及分布分支见同文件 `232-265` 和 `vertical.ts:523-568`。
- 四边 Text padding：面板见 `textAttr.vue:754-783`；V4 先从 Text 框扣除 padding 得到内容框：`drawCore/V4/text/index.ts:89-102`。
- 文字描边：通用 Fill/Stroke 面板对 `WpfText` 可见：`rightPanel/setting.vue:188-211`；横排实际先描边再填充：`drawCore/V4/text/horizontal.ts:651-715,722-800`，竖排同样实际绘制：`vertical.ts:733-760`。
- `autoSizeText` 与 `overScale`：面板开关见 `textAttr.vue:740-751`；横排分别按 intrinsic width 调整宽度、二分缩小字号：`horizontal.ts:504-610`；竖排 auto-size 调整高度并尊重 `maxHeight`：`vertical.ts:452-485`。
- overflow：面板提供 show/hide/自定义字符串：`textAttr.vue:178-204,705-735`；V4 只明确识别 `show`、`hide`、`string(...)` 和 `...`：`drawCore/V4/text/style.ts:92-114`。

### 4.2 有作者字段但不能算稳定绘制能力

- `textCase`、`paraIndent`、`paraSpacing` 被 Skia class 注册并透传：`wpfText.skia.ts:94-100,210-226`；但对 V4 目录的一手检索只发现字段清单/缓存签名，没有在 `drawText`、horizontal 或 vertical 中改变内容/布局。因此不能仅凭字段存在宣称旧系统已实现这些能力。
- 面板把任意自定义 overflow 字符串直接写入 `textOverflow`，而 V4 只识别特定编码形式；“UI 可输入”不等于所有输入都实际生效。
- 编辑器入口可以在 native Leafer 与 Skia 两种 `WpfText` 实现间切换，默认 native：`core/shapes/wpfText.ts:1-19`。只有 Skia 编辑器入口明确复用 V4 `drawText`，承诺与出图侧同管线：`canvaskit/src/editor.ts:1-15`。因此 native 预览表现本身不能作为 Renderer 语义权威。

## 5. 当前 RenderWeave Text 合同的逐项 parity

当前候选：`runs[]`；每 Run 为 `text/fontRef/fontSizeMm/color/letterSpacingMm`；Text 为 `lineBreak/overflow/horizontalAlign/verticalAlign/lineHeight/maxLines`；明确不含 writing mode、decoration、shrink-to-fit、Text stroke。

| 旧能力 | 当前合同 | 是否可完整还原 | 说明 |
| --- | --- | --- | --- |
| 单一纯文本 | 一个完整 Run | 是 | 不需顶层 `text` alias。 |
| 多样式富文本 | 多 Run | 新增能力 | 旧 `WpfText` 本身没有 runs。 |
| 字体与字号 | `fontRef/fontSizeMm` | 有条件 | 若每个旧 family/weight 都映射到确切 FONT Asset 可还原；不应依赖名字/fallback。 |
| `fontWeight` | 由 FONT Asset 表达 | 有条件 | 字体文件有对应 face 时可还原；旧系统也允许 weight 选择/合成，缺少对应 Asset 时不等价。 |
| synthetic italic | 无 | 否 | 旧 V4 会 `setSkewX(-0.25)`，见 `horizontal.ts:149-165` 与 `vertical.ts:501-510`；单靠 italic FONT Asset 不保证像素一致。 |
| 固定字距 | `letterSpacingMm` | 是 | 静态 px/percent 可在迁移时换算为 mm。 |
| 百分比字距 | 只有固定 mm | 否（动态语义） | 若字号被 Binding 改变，旧 percent 字距随字号变化；建议增加 `FACTOR | FIXED` 联合。旧换算见 `style.ts:64-69`。 |
| 行高 | `FACTOR | FIXED` | 基本是 | 对应旧 percent/px，换算后可表达；旧 `textWrap=none` 会强制 multiplier=1，见 `style.ts:71-83`。 |
| 普通换行/不换行 | `WORD/NONE` | 基本是 | `CHAR` 是新能力；需冻结显式换行符和 Unicode 分词规则才能跨 Renderer 确定。 |
| 横向 left/center/right | `START/CENTER/END`（若最终包含） | 基本是 | 需冻结 start/end 与书写方向关系。 |
| 横向 justify/evenly | 无 | 否 | fixture 分别 31/178 个，是已使用能力。 |
| 纵向 top/middle/bottom | 现有 verticalAlign | 是（若枚举齐全） | 这是文字在 Text 内容框内部的对齐，不是节点在父容器中的 placement。 |
| 纵向 justify/evenly | 无 | 否 | fixture 分别 1,048/25 个，是高频核心能力。 |
| 竖排 | 无 writing mode | 否 | fixture 1,088 个，属于最大缺口。 |
| Text 内边距 | 无 | 否 | fixture 1,265 个；外包 Frame 可能近似，但不能保留相同 Text 属性/Binding 和内容框语义。 |
| 下划线/删除线 | 无 decoration | 否 | UI 与 CanvasKit 均实际支持；基线 fixture 仅显式保存 `none`，但产品能力存在。 |
| 文字描边 | 无 | 否 | fixture 100 个，且横排、竖排都有实际绘制分支。 |
| show/hide/custom overflow | `CLIP/ELLIPSIS/FAIL` | 否（语义不同） | 缺少旧 `SHOW` 与自定义 marker；旧 `hide` 也不是一般化 maxLines。 |
| auto width/height | 无明确 Text sizing mode | 否/待布局票据 | 需由 placement sizing 的 `HUG_CONTENT` 或 Text 自身策略冻结；`maxLines` 不能替代。 |
| `overScale` | 明确不做 shrink | 否 | fixture 仅 1 个，但实际 Renderer 已实现。 |
| 字体缺字 fallback | 明确失败 | 否（有意收紧） | 这牺牲旧容错，但换来可复现和资源有效性；不建议为了“兼容”恢复环境 fallback。 |
| `textCase/paraIndent/paraSpacing` | 无 | 不构成已证实缺口 | 旧代码只有字段通道，未找到实际绘制语义。 |

## 6. 对后续 grilling 的事实建议

若目标是“能迁移绝大多数真实旧模板并保持文字布局”，至少需要重新决定：

1. `writingMode: HORIZONTAL | VERTICAL`；
2. `horizontalAlign` 增加 `JUSTIFY | EVENLY`，`verticalAlign` 增加 `JUSTIFY | EVENLY`；
3. Text 自有四边 `paddingMm`；
4. Run 的字距支持 `FACTOR | FIXED`；
5. Text/Run 的 decoration 与文字 stroke 是否进入 v1；
6. auto-size 是否由布局票据的 `HUG_CONTENT` 表达，shrink-to-fit 是否接受有意不兼容；
7. overflow 是追求旧系统逐项兼容，还是采用更小、确定的新语义并由导入器报告不可还原项。

无论上述选择如何，纯文本仍应坚持“一个 Run”，字体仍应坚持显式 AssetRef。这样既保留 `runs[]` 的单一事实源，也避免把旧系统的三套默认字体权威带入新 DSL。
