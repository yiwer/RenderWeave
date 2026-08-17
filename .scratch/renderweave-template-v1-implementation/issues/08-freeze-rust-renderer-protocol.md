# 冻结 Rust Renderer process protocol 与认证计划

Type: grilling
Status: open
Blocked by: 02, 07

## Question

独立 Rust Renderer executable 与 Java process adapter 之间应冻结怎样的 framed protocol、Command/RenderDocument identity、resource fetch、deadline/cancel、stdout/stderr、exit、crash、length/digest、trace 和零 partial output 合同；同时怎样把 hermetic build、ELF closure、portable tricky-font、byte/pixel replay 与两种物理 Linux CPU-family 外部认证拆成诚实门控，使 Windows/WSL 或 scripted adapter 结果永远不能升级为 Renderer READY？
