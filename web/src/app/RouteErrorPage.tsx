import { AlertTriangle, ArrowLeft, RefreshCw } from 'lucide-react';
import { Link, useRouteError } from 'react-router-dom';

import { isChunkLoadError } from './lazy-route';

export function RouteErrorPage() {
  const error = useRouteError();
  const staleChunk = isChunkLoadError(error);
  return (
    <main className="route-error-shell">
      <section className="route-error-card" role="alert" aria-live="assertive">
        <span className="route-error-mark"><AlertTriangle aria-hidden="true" size={22} /></span>
        <div>
          <span className="route-error-kicker">RenderWeave 恢复中心</span>
          <h1>{staleChunk ? '页面资源暂时无法加载' : '这个页面暂时无法打开'}</h1>
          <p>{staleChunk
            ? '应用刚刚更新，当前浏览器仍在引用旧版本页面资源。自动恢复没有成功，请重新加载最新版本。'
            : '页面运行时遇到了未预期的问题。你的服务端数据没有因此被修改，可以重新加载或先返回数据结构设计。'}</p>
        </div>
        <div className="route-error-actions">
          <button type="button" className="button primary-button" onClick={() => window.location.reload()} autoFocus>
            <RefreshCw aria-hidden="true" size={15} />重新加载应用
          </button>
          <Link className="button ghost-button" to="/schemas"><ArrowLeft aria-hidden="true" size={15} />返回数据结构设计</Link>
        </div>
        <footer><code>{staleChunk ? 'ROUTE_CHUNK_UNAVAILABLE' : 'ROUTE_RENDER_FAILED'}</code><span>若重载后仍出现，请保留此代码用于诊断。</span></footer>
      </section>
    </main>
  );
}
