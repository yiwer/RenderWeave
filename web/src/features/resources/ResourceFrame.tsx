import {
  Braces,
  BrainCircuit,
  Database,
  FileCheck2,
  Layers3,
  Plus,
} from 'lucide-react';
import { Link, NavLink } from 'react-router-dom';
import type { ReactNode } from 'react';

export function ResourceFrame({
  eyebrow,
  title,
  description,
  actions,
  children,
}: {
  eyebrow: string;
  title: string;
  description: string;
  actions?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="resource-shell">
      <a className="skip-link" href="#main-content">跳到主要内容</a>
      <header className="resource-chrome">
        <Link className="product-mark" to="/schemas" aria-label="RenderWeave Draft 列表">
          <span className="weave-mark" aria-hidden="true">RW</span><span>RenderWeave</span>
        </Link>
        <span className="resource-chrome-context">Schema foundation + replay review · v1</span>
        <div className="chrome-actions">{actions}</div>
      </header>
      <div className="resource-body">
        <ResourceRail />
        <main className="resource-main" id="main-content" tabIndex={-1}>
          <header className="resource-page-heading">
            <span className="eyebrow">{eyebrow}</span>
            <h1>{title}</h1>
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
  return (
    <nav className="resource-rail app-resource-rail" aria-label="RenderWeave 资源导航">
      <div className="rail-section-label">SCHEMA FOUNDATION</div>
      <NavLink className={({ isActive }) => `rail-link ${isActive ? 'active' : ''}`} to="/schemas" end>
        <Braces aria-hidden="true" size={17} />Draft 列表
      </NavLink>
      <Link className="rail-create" to="/schemas/new"><Plus aria-hidden="true" size={15} />新建 Draft</Link>
      <NavLink className={({ isActive }) => `rail-link ${isActive ? 'active' : ''}`} to="/static-schemas">
        <Layers3 aria-hidden="true" size={17} />StaticSchema
      </NavLink>
      <NavLink className={({ isActive }) => `rail-link ${isActive ? 'active' : ''}`} to="/validator">
        <FileCheck2 aria-hidden="true" size={17} />样本验证器
      </NavLink>
      <div className="rail-divider" />
      <div className="rail-section-label">AI ASSIST</div>
      <NavLink className={({ isActive }) => `rail-link ${isActive ? 'active' : ''}`} to="/inference">
        <BrainCircuit aria-hidden="true" size={17} />Replay 推断
      </NavLink>
      <div className="rail-divider" />
      <div className="rail-context-card system-contract-card">
        <span>API CONTRACT</span>
        <strong>0.8.0</strong>
        <small><Database aria-hidden="true" size={12} /> PostgreSQL · strict DSL</small>
      </div>
      <div className="rail-note">
        <strong>当前 v1 范围</strong>
        <span>Draft、StaticSchema、RootDocument 验证与零网络 Candidate 审核；不展示 Template/Render。</span>
      </div>
    </nav>
  );
}
