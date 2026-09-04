# 在线编辑模板：数据源领域与实现语义

> 研究对象：`D:\Yiwer\code\design-layout-draw` 当前工作树中的 `svg-edit-web`、`canvaskit` 与 `ts-layout-wpf`。
> 结论范围：仓库不含 `/api/template/dyn-source/**` 的服务端实现，因此本文能确认的是前端/渲染器可见的领域合同、HTTP 形状和消费边界；数据库表、服务端求值顺序等不可从该仓库直接证明。

## 结论

旧系统的 `sysSource`、`definedSource`、`mappingSource`、`joinSource` 是**模板内动态数据源目录的四个分类标签**，不是四个运行时作用域，也不是 RenderWeave `ValueSource` 的四种等价 kind。它们的作者侧引用身份是 `{dynSourceType, dynSourceName}`；`dynSourceId` 是自定义、映射、拼接记录的 CRUD 身份。所有列表都按 `templateId` 查询，因而最强可证明的命名边界只是“模板内目录”。类型集合为 `text | text_arr | number | color | boolean | image`。见：

- `D:/Yiwer/code/design-layout-draw/svg-edit-web/src/types/index.ts:1-19`、`:22-49`
- `D:/Yiwer/code/design-layout-draw/svg-edit-web/src/api/rid/template/mapping.types.ts:10-24`、`:41-56`
- `D:/Yiwer/code/design-layout-draw/svg-edit-web/src/api/rid/template/index.ts:93-99`

正常元素绑定由独立 bind API 持有，按 `(templateId, elementId, applyProperty)` 关联到一个按名称和分类选择的数据源；列表属性再多一层 `inner[index]`。循环单值模板的 `loopArraySource` 则不同：它直接写在 `WpfBox` JSON 上，选择一个 `text_arr` 源来决定循环集合。

旧系统**不存在 RenderWeave 式 invocation frame / loop lexical frame**。它有模板嵌套、暴露源赋值和循环模板展开，但未建模请求级不可变 invocation、typed context、loop item/index、ancestor-only lexical domain 或 child invocation 隔离。CanvasKit 接收后端已经计算好的模板树，只做布局和绘制；四类数据源及 `loopArraySource` 不进入渲染器求值。

## 1. 三套并存、不可混同的绑定形态

领域文档把 `dataSource` 定义为“元素的数据绑定字段名”，把 `DynamicAssignment` 定义为把公交数据填入 JSON 模板树，并把 `loop` 描述为按数据项复制子元素（`svg-edit-web/docs/domain.md:38-45`）。实际代码保留了三个年代/用途不同的形态：

1. **旧式 `dataSource: string`**：预制元素直接保存如 `stopsName`、`routeName` 的字符串。手写 `DynamicAssignment` 以硬编码字段判断修改 `frame.text`，例如 `data.name -> dataSource === "stopsName"`（`svg-edit-web/src/views/Editor/bus/core/index.ts:209-227`）。该函数不识别四类 dynamic source；在当前编辑器中只看到 import，footer 中的调用也已注释，不能把它当成当前四类数据源的统一求值器。
2. **节点内 `useDynSource[]`**：预制 `stationName` 仍有 `{type:"sysSource", source:"stopName", applyProperty:"text"}` 示例（`svg-edit-web/src/views/Editor/template/dataSource/index.ts:1-40`）。但当前右侧绑定面板中直接读写 active object 的代码已注释（`rightPanel/bind/index.vue:137-140`、`:197-203`），实际走独立 bind API。
3. **当前 bind 记录**：查询返回 `simpleUseDynSources[]` 与 `collectionUseDynSources[]`，前端按目标属性回显（`rightPanel/bind/index.vue:215-220`、`:233-305`）；保存时使用 `dynSourceName` / `dynSourceType`，而不是旧 `source` / `type` 字段（`:326-412`）。

因此，“元素绑定存储形态”不能概括为单一 `dataSource` 字段。当前作者流程的权威 HTTP 形态是独立绑定记录；`dataSource` 与 `useDynSource` 是仍可出现在模板 JSON/旧素材中的兼容形态。

## 2. 四类动态数据源

### 2.1 `sysSource`：只读系统目录项

- 前端只有 `GET /api/template/dyn-source/system/query/{templateId}`，没有系统源的 add/edit/delete（`svg-edit-web/src/api/rid/template/index.ts:93-99`）。所以其生命周期由服务端/平台管理，模板编辑器只能发现和引用。
- 返回项至少有 `dynSourceName`、`propertyType`。左侧“数据源”卡片把系统源实例化为文本、图片或二维码元素，然后发送 `{templateId, elementId, applyProperty, dynSourceName, dynSourceType:"sysSource"}` 触发绑定（`leftPanel/busWrap/dataSource.vue:48-109`）。列表也按当前 `templateId` 重新查询（`:126-158`）。
- “system”表示系统提供的业务数据字段目录，不表示 RenderWeave StaticSchema field path，也不表示全局词法根。查询仍带 `templateId`，引用仍只是名称加分类。

### 2.2 `definedSource`：模板内用户自定义、有默认值的 typed slot

- 完整 CRUD 为 query/add/edit/delete，另有排序和按 `dynSourceId` 懒加载 remark（`svg-edit-web/src/api/rid/template/diy.ts:10-64`）。新增携带 `templateId`；编辑与 remark 读取以 `dynSourceId` 定位；删除同时带 `templateId` 和 `dynSourceId`。
- 记录包含名称、`propertyType`、`defaultValue`、是否暴露，以及若干公交渲染配置。提交形状见 `leftPanel/busWrap/diy.vue:613-689`；`text_arr` 默认值以数组提交（`:684-688`），`color` 则包装为 solid fill（`:678-682`）。
- 列表项用 `dynSourceId` 编辑、删除和排序（`:433-445`、`:484-546`），但消费者引用不携带该 ID，而携带 `{dynSourceType:"definedSource", dynSourceName}`。这说明 record identity 与 authoring reference identity 是两层不同身份。
- 它是模板配置中的命名输入/default slot；仓库没有证据表明它是独立数据集、连接器、RootDocument 或版本化发布物。没有 revision/publish/immutable 生命周期。

### 2.3 `mappingSource`：有类型的规则派生源

映射源是一个模板内命名计算定义，而不是元素绑定本身。其 DTO 为：

```text
{ templateId, dynSourceName, propertyType, multiSource,
  source?: DynSourceRef,
  mappings: [{ source?, matchType, guardValue, result | resultRef, seq }],
  defaultValue? }
```

合同见 `svg-edit-web/src/api/rid/template/mapping.types.ts:41-92`。语义要点：

- 单输入模式的 `source`，以及多输入模式每条 rule 的 `source`，在 UI 中只从 `sysSource` / `definedSource` 选取（`leftPanel/busWrap/mapping/index.vue:1249-1266`、`:1269-1336`）。
- rule 按 `seq` 排列，以 `matchType` + `guardValue` 匹配；输出可为 literal `result`，也可为 `resultRef`。提交前强制二者恰一非空（`api/rid/template/mapping.types.ts:51-79`、`:125-153`）。
- `resultRef` 可引用同模板的 system/custom/mapping 源，但排除 join、排除 `text_arr`、要求输出类型严格相同，并排除自身；后端还有不存在、类型不符、非法类型和引用环错误（`mapping.types.ts:54-56`、`:100-115`；`mapping/index.vue:1009-1044`、`:1094-1110`）。所以 mapping 可以形成受环检测的派生 DAG，但这仍是模板级名字图，不是词法环境。
- mapping 也有 query/add/edit/delete，记录以 `dynSourceId` 修改/删除（`api/rid/template/mapping.ts:10-42`；`mapping/index.vue:879-903`、`:1381-1459`）。删除被元素使用时可确认重试；被其他动态源引用则有独立的拒绝码。没有版本/发布状态。

### 2.4 `joinSource`：有序文本拼接派生源

- 拼接源有名字和有序 `joinSources[]`。每段要么是 literal `staticSource`，要么是 `dynSource:{dynSourceName,dynSourceType,propertyType}`（`leftPanel/busWrap/join.vue:85-177`、`:532-545`）。
- 动态段可来自 sys/custom/mapping，但不能来自另一个 join；UI 只允许其输入类型为 `text` 或 `number`，最多 100 段（`:549-560`、`:563-595`、`:620-661`）。右侧绑定面板也只有目标支持 `text` 时才展示 join 分类（`rightPanel/bind/index.vue:174-185`）。因此 join 的有效输出用途是文本拼接。
- 保存时每段二选一保留 literal 或 dynamic ref，并按顺序提交（`join.vue:686-706`）。当前实现还把段级字体覆盖编码为隐藏的 `{{WPF_STYLE:...}}` literal marker；这是一项 transport 兼容技巧，不是新数据源种类（`leftPanel/busWrap/joinFontStyle.ts:27-31`、`:141-184`）。
- join 同样有 query/add/edit/delete，以 `dynSourceId` 变更记录（`api/rid/template/join.ts:10-42`），没有版本化生命周期。

## 3. 当前元素绑定的精确 HTTP 形状

绑定 API 与模板 JSON API 分离：模板设计由 `/api/template/design/query|save/{templateId}` 读取/保存，而绑定由 `/api/template/dyn-source/bind/**` 单独读写（`svg-edit-web/src/api/rid/template/index.ts:15-31`、`:55-80`）。编辑器保存模板时只发送 `editor.contentFrame.toJSON()`（`layouts/header/right/saveOper.vue:302-343`）。由此可确认：正常右侧面板的 bind 操作不是通过修改节点 JSON 完成；服务端最终如何落表不在本仓库中。

单值绑定请求：

```json
{
  "templateId": 123,
  "elementId": "node-id",
  "applyProperty": "text",
  "dynSourceName": "线路名",
  "dynSourceType": "sysSource"
}
```

构造与 POST 见 `rightPanel/bind/index.vue:392-412` 和 `api/rid/template/index.ts:66-69`。解绑只给 `{templateId, elementId, applyProperty}`（`bind/index.vue:470-478`）。

集合属性绑定以目标 `applyProperty` 聚合，`inner[]` 的每项再以数组 index 定位：

```text
{
  templateId, elementId, applyProperty,
  inner: [{ index, applyProperty: null, dynSourceName, dynSourceType }, ...]
}
```

构造、去空和保存见 `rightPanel/bind/index.vue:331-390`、`:437-467`。回显时先按外层 `applyProperty` 找集合，再按 `inner.index` 找具体绑定（`:246-274`）。这些引用没有 `dynSourceId`、schema path、revision 或 lexical domain；类型主要依赖打开 picker 时按目标 `limitType` 过滤候选（`:527-607`）。

## 4. `loopArraySource` 的使用流程

`WpfBox` 同时保存：

- `loopContainer`：是否为循环容器；
- `loopTemplateId`：要展开的循环子模板；
- `templateType`：公交业务循环类型；
- `loopArraySource?`：当子模板为 `SINGLE_VALUE`（code 7）时选择哪个文本列表源。

字段定义与 JSON 初始化见 `svg-edit-web/src/views/Editor/core/shapes/wpfBox.ts:6-21`、`:72-89`、`:169-178`、`:243-250`。

作者流程如下：

1. 选中启用 `loopContainer` 的 `WpfBox`，从 `/api/template/loop-template/query` 选择 `loopTemplateId`。候选包括容器原业务类型以及 `SINGLE_VALUE` code 7（`loopAttr.vue:177-241`、`:472-505`；API 在 `api/rid/template/index.ts:101-105`）。
2. 仅当选中的子模板类型是 7 时显示数组源 picker；换成其他类型会立即清除已有 `loopArraySource`（`loopAttr.vue:120-140`、`:567-626`）。
3. picker 并发查询**父/当前模板**的 system 与 custom 源，只保留 `propertyType === "text_arr"`；虽然 TypeScript union 还写着 mapping/join，当前 UI 实际只提供 sys/defined（`loopAttr.vue:345-386`；`wpfBox.ts:6-16`）。
4. 下拉用 `dynSourceType::dynSourceName` 避免跨分类重名，最终在节点上保存 `{dynSourceName,dynSourceType,propertyType:"text_arr"}`（`loopAttr.vue:388-417`）。随后普通模板保存会把它作为 `WpfBox` JSON 属性送出。
5. 预览先把当前 JSON POST 到 `/api/render/template-preview/{templateId}` 换取 `previewId`，再由 CanvasKit GET `/api/render/template-preview/query/{previewId}` 取得渲染数据（`saveOper.vue:683-701`；`canvaskit/src/api/draw/index.ts:73-84`）。CanvasKit 将返回数据直接送入 layout，再把 layout tree 送入 render engine（`canvaskit/src/preRender/V2/services/template-data-service.ts:271-289`；`canvaskit/src/preRender/V2/index.ts:496-542`）。

对 `canvaskit/src` 和 `ts-layout-wpf/src` 搜索 `useDynSource|loopArraySource|dynSourceType` 无消费结果；可见的 `loopContainer` 逻辑只处理已经展开的子节点/溢出布局。故最可靠的边界结论是：`loopArraySource` 的查值与按项实例化发生在后端 render/template-preview 物化阶段，CanvasKit 不建立循环数据环境。

## 5. 与 RenderWeave lexical scope 的比较

RenderWeave 的基准语义是：

- `RenderInput` 只有一个 `rootDocument` 和根级 custom overrides，不叫 DataSource（`RenderWeave/CONTEXT.md:101`）。
- `ValueSource` 是 literal、显式 context path、loop index、命名 definition 或获准 capability 的封闭描述（`RenderWeave/CONTEXT.md:112`）。
- 每个具体模板调用创建请求级不可变 invocation frame；每个 Repeat item 创建带 typed item、原 index、稳定 `loopId` 且只链接同模板词法祖先的 loop frame（`RenderWeave/CONTEXT.md:107-108`）。
- `TemplateUse` 通过显式 `ContextSelector` 与 PUBLIC custom fills 建立隔离 child invocation，不做同名自动继承（`RenderWeave/CONTEXT.md:105-107`、`:129-130`）。

旧系统没有这些构件：

- 数据源只有模板级 `(type,name)`，没有显式 invocation/loop domain；
- `loopArraySource` 只选择集合，没有 loop item/index 可引用形态、稳定 loop identity 或祖先可见性规则；
- mapping/join 的引用图按同模板名称解析，与树中元素出现位置无关；
- 嵌套模板虽有 `embeddedTemplate` / `exposedDynSources`，且 exposed-source API 以父模板、子模板和元素 ID 读写显式赋值（`wpfBox.ts:72-89`；`svg-edit-web/src/api/rid/template/expose.ts:21-41`），但未形成 typed、request-local、immutable、隔离的 child frame；
- 渲染器看不到未求值 source，只消费物化后的 JSON 树。

所以不能把旧系统的“模板作用域目录 + 服务端预展开”翻译为 RenderWeave invocation/loop lexical scope。可借鉴的是 UI 信息架构（系统/自定义/派生来源、目标属性绑定、单值模板选择 list 源），不可继承的是它的身份、生命周期、类型证明或作用域语义。

## 6. 迁移/产品建模提示

- 不要在 RenderWeave 新建一个持久化 `DataSource` 聚合去一比一复刻四个 tab。旧四类更接近 UI projection：`sysSource` 可映射到 StaticSchema context path，`definedSource` 接近 CustomDefinition，`mappingSource` 接近 MappingDefinition，`joinSource` 若产品需要则应落入已批准的封闭 Expression 能力；每个选择最终仍应生成现有 `ValueSource`。
- UI 可以保留“分类 + 名称”用于展示，但持久 DesignDSL 必须保存 RenderWeave 的精确 definition ID / typed context path / lexical domain，不能保存旧 `{type,name}` 弱引用。
- Repeat picker 应从当前允许的 lexical domain 选择 list-typed `ValueSource`；选择后产生 Repeat `items` source，而不是引入 `loopArraySource` 旁路。
- 旧系统的系统源只读、用户源 CRUD、派生源引用保护等交互可以作为体验参考；其无 revision 的可变记录和服务端隐式求值不能成为 RenderWeave 领域权威。
