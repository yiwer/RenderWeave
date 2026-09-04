import {
  createTemplate,
  listTemplates,
  type DesignDslKernel,
  type TemplateCatalogResponse,
  type TemplateOpaqueCommitResponse,
  type TemplateReadableResponse,
} from '../../api/generated';

export interface CreateTemplateInput {
  schemaKey: string;
  versionTag: string;
  displayName: string;
  widthMm: number;
  heightMm: number;
}

export type CreateTemplateOutcome =
  | { readonly kind: 'READABLE'; readonly template: TemplateReadableResponse }
  | { readonly kind: 'OPAQUE'; readonly receipt: TemplateOpaqueCommitResponse }
  | { readonly kind: 'TRANSPORT_UNKNOWN' };

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
): Promise<CreateTemplateOutcome> {
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
  let result: Awaited<ReturnType<typeof createTemplate>>;
  try {
    result = await createTemplate({
      query: { schemaKey: input.schemaKey, versionTag: input.versionTag },
      body,
    });
  } catch {
    return { kind: 'TRANSPORT_UNKNOWN' };
  }
  if (result.error !== undefined) {
    if (isTemplateProblem(result.error)) {
      throw new TemplateProductRequestError('创建 Template', result.error);
    }
    return { kind: 'TRANSPORT_UNKNOWN' };
  }
  if (result.data === undefined) return { kind: 'TRANSPORT_UNKNOWN' };
  return result.data.disclosure === 'READABLE'
    ? { kind: 'READABLE', template: result.data }
    : { kind: 'OPAQUE', receipt: result.data };
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

function isTemplateProblem(value: unknown): value is Record<string, unknown> {
  if (typeof value !== 'object' || value === null || value instanceof Error) return false;
  const problem = value as Record<string, unknown>;
  return typeof problem.type === 'string'
    && typeof problem.title === 'string'
    && typeof problem.status === 'number'
    && typeof problem.code === 'string'
    && typeof problem.traceId === 'string';
}
