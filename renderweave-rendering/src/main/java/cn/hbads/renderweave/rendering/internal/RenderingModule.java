package cn.hbads.renderweave.rendering.internal;

/**
 * app 可 import 的唯一 Rendering {@code .internal} assembly seam（ADR-0041 窄例外，
 * 与 {@code TemplateModule}/{@code AssetModule} 同构）：final、static factory、无 public 构造器、
 * 无 Spring/反射旁路。factory 随各实现切片登记。
 */
public final class RenderingModule {

    private RenderingModule() {
    }
}
