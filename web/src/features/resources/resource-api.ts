import {
  listDraftRevisions,
  listDrafts,
  listStaticSchemas,
  type DraftHistoryResponse,
  type DraftListResponse,
  type ListDraftsData,
  type ListStaticSchemasData,
  type Problem,
  type StaticSchemaListResponse,
  type ValidationBatchResponse,
} from '../../api/generated';
import { StudioRequestError, studioRequestText } from '../schema-studio/lossless-api';

export type ValidationTargetInput =
  | { kind: 'draft'; schemaKey: string }
  | { kind: 'static'; schemaKey: string; versionTag: string };

type DraftListQuery = NonNullable<ListDraftsData['query']>;
type StaticSchemaListQuery = NonNullable<ListStaticSchemasData['query']>;

export type DraftListSort = NonNullable<DraftListQuery['sort']>;
export type StaticSchemaListSort = NonNullable<StaticSchemaListQuery['sort']>;
export type StaticSchemaOriginFilter = NonNullable<StaticSchemaListQuery['origin']>;

export async function listDraftsRequest(
  page = 1,
  size = 50,
  search = '',
  sort: DraftListSort = 'UPDATED_DESC',
): Promise<DraftListResponse> {
  const result = await listDrafts({ query: { page, size, search, sort } });
  return unwrap(result.data, result.error, '读取 Draft 列表');
}

export async function listDraftHistoryRequest(
  schemaKey: string,
  page = 1,
  size = 50,
): Promise<DraftHistoryResponse> {
  const result = await listDraftRevisions({ path: { schemaKey }, query: { page, size } });
  return unwrap(result.data, result.error, '读取 revision 历史');
}

export async function listStaticSchemasRequest(
  page = 1,
  size = 50,
  search = '',
  sort: StaticSchemaListSort = 'PUBLISHED_DESC',
  origin: StaticSchemaOriginFilter = 'ALL',
): Promise<StaticSchemaListResponse> {
  const result = await listStaticSchemas({ query: { page, size, search, sort, origin } });
  return unwrap(result.data, result.error, '读取 StaticSchema 列表');
}

export async function validateDocumentsRequest(
  target: ValidationTargetInput,
  documents: string[],
): Promise<ValidationBatchResponse> {
  const targetJson = target.kind === 'draft'
    ? `{"kind":"draft","schemaKey":${JSON.stringify(target.schemaKey)}}`
    : `{"kind":"static","schemaKey":${JSON.stringify(target.schemaKey)},"versionTag":${JSON.stringify(target.versionTag)}}`;
  const documentJson = documents.map((document) => `{"document":${document}}`).join(',');
  const response = await studioRequestText('/api/v1/root-document-validations', {
    method: 'POST',
    body: `{"target":${targetJson},"documents":[${documentJson}]}`,
  });
  return JSON.parse(response) as ValidationBatchResponse;
}

function unwrap<T>(data: T | undefined, error: Problem | undefined, operation: string): T {
  if (error) throw new StudioRequestError(error);
  if (data === undefined) throw new Error(`${operation}时服务端未返回数据。`);
  return data;
}
