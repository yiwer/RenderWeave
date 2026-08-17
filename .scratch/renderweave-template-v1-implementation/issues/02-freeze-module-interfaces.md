# 冻结 Template 实施模块 interface 与依赖方向

Type: grilling
Status: open
Blocked by: 01

## Question

在已冻结 `renderweave-template`、`renderweave-asset`、`renderweave-rendering` 三个 deep Maven module、`renderweave-app` adapter 边界与独立 Rust process seam 的前提下，精确的编译依赖图、package ownership、跨上下文 closed interface、Host capability、错误类型和测试 seam 应如何划分，才能避免循环依赖、泛化 `common`、领域对象泄漏和未来 JNI/FFI 耦合，并给后续实现提供一份可执行 ADR 与 architecture test？
