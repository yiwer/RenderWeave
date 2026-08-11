# 定义 RenderEngine 与图片输出合同

Type: grilling
Status: open
Blocked by: 15

## Question

RenderEngine 如何消费 RenderDocument，并在内部完成 layout、资源获取/校验/解码、文本 shaping、LaidOutScene、绘制和编码；首版支持哪些画板与图片输出基数、格式、尺寸、像素密度、色彩、透明度、字体和错误语义；哪些 Engine Profile 参数属于版本化输入？

## Inherited constraints

- RenderDocument 已经只含 concrete static nodes/property values 与 exact ResolvedAsset entries；不得包含 ValueSource、Binding、BindingPolicy、Expression、Definition、ABSENT、loop frame、TemplateRef 或 capability call。
- RenderEngine 不读取 DesignDSL 或全局 BindingPolicyCatalog，也不重新验证表达式类型；它只按 RenderDSL/Engine Profile 对已经求值的 property 与资源完成 layout、shaping、绘制和编码。
- 任一资源 fetch/hash/media/length/decode 或 layout/encoding 失败必须零 RenderOutput；不能回退 DesignDSL static baseline 或产生部分成功图片。
- DesignRoot、Canonical DesignDSL 与 Design content hash 都停留在 Template/Evaluation 边界；RenderEngine 不把 Design content hash 当成 RenderDocument identity 或输出缓存键。
