import {
  getTemplateCurrent,
  listTemplates,
  type TemplateCatalogEntry,
  type TemplateCatalogResponse,
  type TemplateReadableResponse,
} from '../../api/generated';

const CATALOG_LIMIT = 50;

export interface TemplateEditorCompositionTransport {
  listCatalog(signal?: AbortSignal): Promise<TemplateCatalogResponse>;
  getCurrent(templateId: string, signal?: AbortSignal): Promise<TemplateReadableResponse>;
}

export class TemplateCompositionRequestError extends Error {
  constructor(readonly operation: string, readonly problem: unknown) {
    super(problemMessage(problem) ?? `${operation}失败。`);
    this.name = 'TemplateCompositionRequestError';
  }
}

export class TemplateCompositionIntegrityError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'TemplateCompositionIntegrityError';
  }
}

export const defaultTemplateEditorCompositionTransport: TemplateEditorCompositionTransport = {
  async listCatalog(signal) {
    const result = await listTemplates({
      query: { limit: CATALOG_LIMIT },
      ...(signal ? { signal } : {}),
    });
    return unwrap(result.data, result.error, '读取 Template 目录');
  },
  async getCurrent(templateId, signal) {
    const result = await getTemplateCurrent({
      path: { templateId },
      ...(signal ? { signal } : {}),
    });
    return unwrap(result.data, result.error, '读取被调用 Template current');
  },
};

export async function loadTemplateCompositionCatalog(
  transport: TemplateEditorCompositionTransport = defaultTemplateEditorCompositionTransport,
  signal?: AbortSignal,
): Promise<readonly TemplateCatalogEntry[]> {
  signal?.throwIfAborted();
  const response = await transport.listCatalog(signal);
  signal?.throwIfAborted();
  if (!Array.isArray(response.items)) {
    throw new TemplateCompositionIntegrityError('Template catalog response has no items');
  }
  return Object.freeze([...response.items]);
}

export async function loadTemplateCompositionCurrent(
  expected: TemplateCatalogEntry,
  transport: TemplateEditorCompositionTransport = defaultTemplateEditorCompositionTransport,
  signal?: AbortSignal,
): Promise<TemplateReadableResponse> {
  signal?.throwIfAborted();
  const current = await transport.getCurrent(expected.templateId, signal);
  signal?.throwIfAborted();
  if (current.disclosure !== 'READABLE'
    || current.templateId !== expected.templateId
    || current.revision !== expected.revision
    || current.readiness !== expected.readiness
    || current.staticSchema.schemaKey !== expected.staticSchema.schemaKey
    || current.staticSchema.versionTag !== expected.staticSchema.versionTag) {
    throw new TemplateCompositionIntegrityError(
      'Selected Template current does not match its catalog identity',
    );
  }
  return current;
}

function unwrap<T>(data: T | undefined, error: unknown, operation: string): T {
  if (error !== undefined) throw new TemplateCompositionRequestError(operation, error);
  if (data === undefined) throw new TemplateCompositionIntegrityError(`${operation}时服务端未返回数据。`);
  return data;
}

function problemMessage(value: unknown): string | undefined {
  if (typeof value !== 'object' || value === null) return undefined;
  const problem = value as Record<string, unknown>;
  if (typeof problem.detail === 'string' && problem.detail.trim()) return problem.detail;
  if (typeof problem.title === 'string' && problem.title.trim()) return problem.title;
  return undefined;
}
