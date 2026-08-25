import {
  createTemplate,
  listTemplates,
  type DesignDslKernel,
  type TemplateCatalogResponse,
  type TemplateCommitResponse,
} from '../../api/generated';

export interface CreateTemplateInput {
  schemaKey: string;
  versionTag: string;
  displayName: string;
  widthMm: number;
  heightMm: number;
}

export class TemplateProductRequestError extends Error {
  readonly causePayload: unknown;

  constructor(operation: string, causePayload: unknown) {
    super(templateProblemMessage(causePayload) ?? `${operation}失败。`);
    this.name = 'TemplateProductRequestError';
    this.causePayload = causePayload;
  }
}

export async function listTemplatesRequest(
  search = '',
  cursor?: string,
  limit = 20,
): Promise<TemplateCatalogResponse> {
  const normalizedSearch = search.trim();
  const result = await listTemplates({
    query: {
      ...(normalizedSearch ? { search: normalizedSearch } : {}),
      ...(cursor ? { cursor } : {}),
      limit,
    },
  });
  return unwrap(result.data, result.error, '读取 Template 目录');
}

export async function createTemplateRequest(
  input: CreateTemplateInput,
): Promise<TemplateCommitResponse> {
  const body: DesignDslKernel = {
    dslVersion: 'renderweave-design/1.0',
    expressionProfile: 'renderweave-expression/1.0',
    displayName: input.displayName,
    definitions: [],
    designRoot: {
      nodeId: globalThis.crypto.randomUUID(),
      kind: 'canvas',
      widthMm: input.widthMm,
      heightMm: input.heightMm,
      bindings: [],
      children: [],
    },
  };
  const result = await createTemplate({
    query: { schemaKey: input.schemaKey, versionTag: input.versionTag },
    body,
  });
  return unwrap(result.data, result.error, '创建 Template');
}

function unwrap<T>(data: T | undefined, error: unknown, operation: string): T {
  if (error !== undefined) throw new TemplateProductRequestError(operation, error);
  if (data === undefined) throw new Error(`${operation}时服务端未返回数据。`);
  return data;
}

function templateProblemMessage(value: unknown): string | undefined {
  if (typeof value !== 'object' || value === null) return undefined;
  const problem = value as Record<string, unknown>;
  if (typeof problem.detail === 'string' && problem.detail.trim()) return problem.detail;
  if (typeof problem.title === 'string' && problem.title.trim()) return problem.title;
  return undefined;
}
