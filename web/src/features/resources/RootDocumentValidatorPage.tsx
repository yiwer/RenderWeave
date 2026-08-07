import { useMutation, useQuery } from '@tanstack/react-query';
import {
  AlertCircle,
  CheckCircle2,
  FileCheck2,
  Plus,
  Play,
  Trash2,
  XCircle,
} from 'lucide-react';
import { useState } from 'react';

import { StudioRequestError } from '../schema-studio/lossless-api';
import {
  listDraftsRequest,
  listStaticSchemasRequest,
  validateDocumentsRequest,
  type ValidationTargetInput,
} from './resource-api';
import { ResourceFrame } from './ResourceFrame';

interface SampleInput { id: number; json: string }

export function RootDocumentValidatorPage() {
  const [kind, setKind] = useState<'draft' | 'static'>('draft');
  const [schemaKey, setSchemaKey] = useState('');
  const [versionTag, setVersionTag] = useState('');
  const [samples, setSamples] = useState<SampleInput[]>([
    { id: 1, json: '{\n  "title": "示例数据",\n  "amount": 123.45\n}' },
  ]);
  const [nextId, setNextId] = useState(2);
  const drafts = useQuery({ queryKey: ['schema-drafts', 'validator'], queryFn: () => listDraftsRequest(1, 100) });
  const statics = useQuery({ queryKey: ['static-schemas', 'validator'], queryFn: () => listStaticSchemasRequest(1, 100) });
  const mutation = useMutation({
    mutationFn: () => {
      const target: ValidationTargetInput = kind === 'draft'
        ? { kind, schemaKey }
        : { kind, schemaKey, versionTag };
      return validateDocumentsRequest(target, samples.map((sample) => sample.json));
    },
  });
  const addSample = () => {
    setSamples((current) => [...current, { id: nextId, json: '{\n  \n}' }]);
    setNextId((value) => value + 1);
  };
  const canSubmit = schemaKey.trim() && (kind === 'draft' || versionTag.trim()) && samples.length > 0;
  return (
    <ResourceFrame
      eyebrow="ROOTDOCUMENT VALIDATION"
      title="用真实样本检查 Schema"
      description="一次冻结目标 Schema 图并批量验证一组 RootDocument；未知字段默认允许，诊断顺序稳定且最多返回 100 项。"
      actions={<button type="button" className="button primary-button" disabled={!canSubmit || mutation.isPending} onClick={() => mutation.mutate()}><Play aria-hidden="true" size={16} />{mutation.isPending ? '验证中…' : `验证 ${samples.length} 份样本`}</button>}
    >
      <section className="validator-target-card">
        <div className="section-heading"><div><span>FROZEN TARGET</span><h2>验证目标</h2></div><span>一次请求只解析一次依赖图</span></div>
        <div className="target-kind" role="group" aria-label="Schema 目标类型">
          <button type="button" className={kind === 'draft' ? 'active' : ''} aria-pressed={kind === 'draft'} onClick={() => { setKind('draft'); setVersionTag(''); mutation.reset(); }}>Draft · current</button>
          <button type="button" className={kind === 'static' ? 'active' : ''} aria-pressed={kind === 'static'} onClick={() => { setKind('static'); mutation.reset(); }}>StaticSchema · exact</button>
        </div>
        <div className="validator-target-fields">
          <label>schemaKey<input className="mono-input" list={kind === 'draft' ? 'draft-schema-options' : 'static-schema-options'} value={schemaKey} onChange={(event) => { setSchemaKey(event.target.value); mutation.reset(); }} /></label>
          {kind === 'static' && <label>versionTag<input className="mono-input" list="static-version-options" value={versionTag} onChange={(event) => { setVersionTag(event.target.value); mutation.reset(); }} /></label>}
        </div>
        <datalist id="draft-schema-options">{drafts.data?.items.map((item) => <option key={item.schemaKey} value={item.schemaKey}>{item.displayName}</option>)}</datalist>
        <datalist id="static-schema-options">{[...new Set(statics.data?.items.map((item) => item.schemaKey) ?? [])].map((key) => <option key={key} value={key} />)}</datalist>
        <datalist id="static-version-options">{statics.data?.items.filter((item) => !schemaKey || item.schemaKey === schemaKey).map((item) => <option key={`${item.schemaKey}@${item.versionTag}`} value={item.versionTag} />)}</datalist>
      </section>

      <div className="validator-layout">
        <section className="sample-editor-panel" aria-labelledby="sample-input-heading">
          <header><div><span>INPUT BATCH</span><h2 id="sample-input-heading">RootDocument 样本</h2></div><button type="button" className="button ghost-button" onClick={addSample}><Plus aria-hidden="true" size={15} />添加样本</button></header>
          <p>输入保留原始 JSON number token，不经 JavaScript 浮点解析；每份文档最大 2 MiB，整批最大 10 MiB。</p>
          <div className="sample-stack">
            {samples.map((sample, index) => (
              <article className="sample-input" key={sample.id}>
                <header><strong>Document {index + 1}</strong><span>{new TextEncoder().encode(sample.json).length} bytes</span><button type="button" disabled={samples.length === 1} aria-label={`删除 Document ${index + 1}`} onClick={() => { setSamples((current) => current.filter((item) => item.id !== sample.id)); mutation.reset(); }}><Trash2 aria-hidden="true" size={14} /></button></header>
                <textarea aria-label={`Document ${index + 1} JSON`} spellCheck={false} value={sample.json} onChange={(event) => { setSamples((current) => current.map((item) => item.id === sample.id ? { ...item, json: event.target.value } : item)); mutation.reset(); }} />
              </article>
            ))}
          </div>
        </section>

        <section className="validation-results" aria-labelledby="validation-result-heading">
          <header><span>RESULT</span><h2 id="validation-result-heading">验证结果</h2></header>
          {!mutation.data && !mutation.isError && <div className="validation-empty"><FileCheck2 aria-hidden="true" size={28} /><strong>等待显式验证</strong><span>编辑样本不会发送请求；点击“验证”后才读取并冻结目标。</span></div>}
          {mutation.isError && <ValidationRequestError error={mutation.error} />}
          {mutation.data && (
            <>
              <div className={`validation-summary-card ${mutation.data.summary.invalid === 0 ? 'is-valid' : 'is-invalid'}`}>
                {mutation.data.summary.invalid === 0 ? <CheckCircle2 aria-hidden="true" size={23} /> : <XCircle aria-hidden="true" size={23} />}
                <div><strong>{mutation.data.summary.invalid === 0 ? '全部样本有效' : `${mutation.data.summary.invalid} 份样本无效`}</strong><span>{mutation.data.summary.valid} valid · {mutation.data.summary.total} total</span></div>
              </div>
              <div className="resolved-target"><span>RESOLVED TARGET</span><code>{formatResolved(mutation.data.target)}</code><small>{mutation.data.resolvedSchemas.length} resolved schemas</small></div>
              <div className="document-results">
                {mutation.data.documents.map((document) => (
                  <article key={document.index} className={document.valid ? 'is-valid' : 'is-invalid'}>
                    <header>{document.valid ? <CheckCircle2 aria-hidden="true" size={16} /> : <AlertCircle aria-hidden="true" size={16} />}<strong>Document {document.index + 1}</strong><span>{document.valid ? 'VALID' : `${document.problems.length} PROBLEMS`}</span></header>
                    {document.problems.length > 0 && <ul>{document.problems.map((problem, problemIndex) => <li key={`${problem.code}-${problem.instancePath}-${problemIndex}`}><strong>{problem.code}</strong><code>{problem.instancePath || '/'}</code><span>schema: {problem.schemaPath}</span></li>)}</ul>}
                    {document.truncated && <p>该文档的问题已被 100 项全局上限截断。</p>}
                  </article>
                ))}
              </div>
            </>
          )}
        </section>
      </div>
    </ResourceFrame>
  );
}

function ValidationRequestError({ error }: { error: unknown }) {
  const problem = error instanceof StudioRequestError ? error.problem : undefined;
  return (
    <div className="validation-request-error" role="alert"><AlertCircle aria-hidden="true" size={20} /><div><strong>{problem?.title ?? '无法执行验证'}</strong><span>{problem?.detail ?? (error instanceof Error ? error.message : '请求失败')}</span>{problem?.violations?.map((violation) => <code key={`${violation.code}-${violation.pointer}`}>{violation.pointer} · {violation.message ?? violation.code}</code>)}</div></div>
  );
}

function formatResolved(target: { kind: string; schemaKey: string; revision?: number; versionTag?: string }): string {
  return target.kind === 'draft' ? `${target.schemaKey}@revision:${target.revision}` : `${target.schemaKey}@${target.versionTag}`;
}
