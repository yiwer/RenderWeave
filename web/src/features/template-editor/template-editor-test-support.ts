import type { CanonicalTemplateBaseline } from './template-editor-model';

export const TEMPLATE_ID = '9034a1da-5a76-469c-8de0-516eebf2c742';
export const CONTENT_HASH = 'sha256:6f7cb26778e2b94e6528ea997a3249b87f37fb882c009601fc093c3dcba0aef2';
export const CANONICAL_DESIGN = '{"definitions":[],"designRoot":{"bindings":[],"children":[],"heightMm":297,"kind":"canvas","nodeId":"123e4567-e89b-42d3-a456-426614174000","widthMm":210},"displayName":"API template","dslVersion":"renderweave-design/1.0","expressionProfile":"renderweave-expression/1.0"}';
export const STRUCTURED_CANONICAL_DESIGN = '{"definitions":[],"designRoot":{"bindings":[],"children":[{"bindings":[],"children":[{"bindings":[],"displayName":"底色","kind":"rect","nodeId":"rect-id"}],"displayName":"内容区","kind":"frame","nodeId":"frame-id"}],"displayName":"画布","heightMm":297,"kind":"canvas","nodeId":"canvas-id","widthMm":210},"displayName":"门店价签","dslVersion":"renderweave-design/1.0","expressionProfile":"renderweave-expression/1.0"}';

export function currentResponse(revision: string, templateId = TEMPLATE_ID): string {
  return `{"templateId":"${templateId}","disclosure":"READABLE","revision":${revision},"staticSchema":{"schemaKey":"system-empty","versionTag":"v1"},"contentHash":"${CONTENT_HASH}","readiness":"STALE","designDsl":${CANONICAL_DESIGN}}`;
}

export function recheckResponse(
  revision: string,
  readiness: string,
  templateId = TEMPLATE_ID,
): string {
  return `{"templateId":"${templateId}","revision":${revision},"contentHash":"${CONTENT_HASH}","readiness":"${readiness}"}`;
}

export function structuredBaseline(): CanonicalTemplateBaseline {
  return {
    templateId: TEMPLATE_ID,
    revision: '7',
    staticSchema: { schemaKey: 'system-empty', versionTag: 'v1' },
    contentHash: 'sha256:' + 'a'.repeat(64),
    persistedReadiness: 'STALE',
    canonicalDesignDsl: STRUCTURED_CANONICAL_DESIGN,
    designDsl: {
      dslVersion: 'renderweave-design/1.0',
      expressionProfile: 'renderweave-expression/1.0',
      displayName: '门店价签',
      definitions: [],
      designRoot: {
        nodeId: 'canvas-id', kind: 'canvas', displayName: '画布', widthMm: 210, heightMm: 297,
        bindings: [], children: [{
          nodeId: 'frame-id', kind: 'frame', displayName: '内容区', bindings: [], children: [{
            nodeId: 'rect-id', kind: 'rect', displayName: '底色', bindings: [],
          }],
        }],
      },
    },
  };
}
