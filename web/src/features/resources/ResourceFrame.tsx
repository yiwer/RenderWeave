import {
  Braces,
  BrainCircuit,
  ChevronRight,
  Database,
  FileCheck2,
  Layers3,
} from 'lucide-react';
import { Link, NavLink, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';

import { Sparkle, Squiggle } from '../../components/doodles';

export function ResourceFrame({
  title,
  description,
  actions,
  breadcrumbs,
  detail = false,
  children,
}: {
  title: string;
  description: string;
  actions?: ReactNode;
  breadcrumbs?: Array<{ label: string; to?: string }>;
  detail?: boolean;
  children: ReactNode;
}) {
  return (
    <div className="resource-shell">
      <a className="skip-link" href="#main-content">跳到主要内容</a>
      <header className="resource-chrome">
        <Link className="product-mark" to="/schemas" aria-label="RenderWeave 数据结构设计">
          <span className="weave-mark" aria-hidden="true">RW</span><span>RenderWeave</span>
        </Link>
        {breadcrumbs ? (
          <nav className="resource-breadcrumb" aria-label="面包屑">
            {breadcrumbs.map((item, index) => (
              <span key={`${item.label}-${index}`}>
                {index > 0 && <ChevronRight aria-hidden="true" size={14} />}
                {item.to ? <Link to={item.to}>{item.label}</Link> : <strong aria-current="page">{item.label}</strong>}
              </span>
            ))}
          </nav>
        ) : <span className="resource-chrome-context">结构定义与推断审核 · v1</span>}
        <div className="chrome-actions">{actions}</div>
      </header>
      <div className="resource-body">
        <ResourceRail />
        <main className={`resource-main ${detail ? 'resource-main-detail' : ''}`} id="main-content" tabIndex={-1}>
          <header className={`resource-page-heading ${detail ? 'is-detail' : ''}`}>
            <div className="page-heading-title">
              <h1>{title}</h1>
              <Squiggle />
            </div>
            <Sparkle className="is-heading-a" />
            <Sparkle className="is-heading-b" delay={1300} />
            <p>{description}</p>
          </header>
          {children}
        </main>
      </div>
      <div className="unsupported-width" role="status">
        <strong>RenderWeave v1 需要至少 1024px 宽度</strong>
        <span>请在桌面端扩大窗口后继续。</span>
      </div>
    </div>
  );
}

export function ResourceRail() {
  const { pathname } = useLocation();
  const inferenceActive = pathname.startsWith('/inference') || pathname.startsWith('/inference-runs/');
  return (
    <nav className="resource-rail app-resource-rail" aria-label="RenderWeave 资源导航">
      <div className="rail-section-label">结构定义</div>
      <NavLink className={({ isActive }) => `rail-link ${isActive ? 'active' : ''}`} to="/schemas" end>
        <Braces aria-hidden="true" size={17} />数据结构设计
      </NavLink>
      <NavLink className={({ isActive }) => `rail-link ${isActive ? 'active' : ''}`} to="/static-schemas">
        <Layers3 aria-hidden="true" size={17} />数据结构资产
      </NavLink>
      <NavLink className={({ isActive }) => `rail-link ${isActive ? 'active' : ''}`} to="/validator">
        <FileCheck2 aria-hidden="true" size={17} />样本验证器
      </NavLink>
      <div className="rail-divider" />
      <div className="rail-section-label">智能辅助</div>
      <NavLink
        className={({ isActive }) => `rail-link ${isActive || inferenceActive ? 'active' : ''}`}
        aria-current={inferenceActive ? 'page' : undefined}
        to="/inference"
      >
        <BrainCircuit aria-hidden="true" size={17} />智能识别
      </NavLink>
      <div className="rail-divider" />
      <div className="rail-context-card system-contract-card">
        <span>接口版本</span>
        <strong>0.10.0</strong>
        <small><Database aria-hidden="true" size={12} /> PostgreSQL · strict DSL</small>
      </div>
      <div className="rail-note">
        <strong>当前 v1 范围</strong>
        <span>Draft、StaticSchema、RootDocument 验证与可审核的 Candidate；不展示 Template/Render。</span>
      </div>
    </nav>
  );
}
