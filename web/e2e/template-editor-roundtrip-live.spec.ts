import { readFileSync } from 'node:fs';
import path from 'node:path';

import {
  expect,
  test,
  type APIRequestContext,
  type Page,
  type Response,
} from '@playwright/test';

const LIVE = process.env.RENDERWEAVE_TEMPLATE_ROUNDTRIP_LIVE === '1';
const DESIGN_MEDIA_TYPE = 'application/vnd.renderweave.design+json';
const COMPLETE_WIRE_FIXTURE = readFileSync(path.resolve(
  process.cwd(),
  '..',
  'renderweave-template',
  'src',
  'test',
  'resources',
  'cn',
  'hbads',
  'renderweave',
  'template',
  'complete-wire-v1',
  'all-kinds.json',
), 'utf8');
const COMPLETE_WIRE_EDITED_NAME = 'Complete wire edited in Structured mode';

interface TemplateCurrentBody {
  templateId: string;
  revision: number;
  contentHash: string;
  readiness: string;
  designDsl: Record<string, unknown>;
}

interface InvalidConfirmationBody {
  code: string;
  confirmationToken: string;
  truncated: boolean;
  problems: Array<{ code: string; canonicalPointer: string }>;
}

test.describe('complete DesignDSL real Template round trip', () => {
  test.skip(!LIVE, 'requires the explicit local Template roundtrip environment');

  test('opens all admitted shapes in Structured mode and preserves them through save/reload', async ({ page }) => {
    test.setTimeout(90_000);
    const browserErrors = captureBrowserErrors(page);
    await page.setViewportSize({ width: 1280, height: 720 });

    const setup = await createTemplateForCompleteWire(page.request);
    const fixtureCurrent = await saveAndConfirmInvalid(
      page.request,
      setup.templateId,
      setup.revision,
      COMPLETE_WIRE_FIXTURE,
    );
    expect(fixtureCurrent.revision).toBe(1);
    expect(fixtureCurrent.readiness).toBe('INVALID');
    expect(fixtureCurrent.designDsl).toEqual(JSON.parse(COMPLETE_WIRE_FIXTURE));
    expectCompleteWireCoverage(fixtureCurrent.designDsl);

    const initialOpen = page.waitForResponse(templateCurrentResponse(setup.templateId));
    await page.goto(`/templates/${setup.templateId}`, { waitUntil: 'domcontentloaded' });
    expect((await initialOpen).status()).toBe(200);
    await expect(page.getByRole('main', { name: 'Template 编辑工作区' })).toBeVisible();
    await expect(page.getByText('Structured Editor', { exact: true })).toBeVisible();
    await expect(page.getByText('revision 1', { exact: true })).toBeVisible();
    await expect(page.getByText('INVALID', { exact: true })).toBeVisible();
    await expect(page.getByText('18 个 authored 节点', { exact: true })).toBeVisible();

    await page.getByRole('textbox', { name: 'Template 名称' }).fill(COMPLETE_WIRE_EDITED_NAME);
    await page.getByRole('button', { name: '应用本地名称' }).click();
    await expect(page.getByText('Canonical 本地草稿', { exact: true })).toBeVisible();

    const offerResponse = page.waitForResponse(templateSaveResponse(setup.templateId, 422));
    await page.getByRole('button', { name: '保存 canonical 本地草稿' }).click();
    const offer = await offerResponse;
    const offerBody = await offer.json() as InvalidConfirmationBody;
    expect(offerBody.code).toBe('TEMPLATE_DEPENDENCY_CONFIRMATION_REQUIRED');
    expect(offerBody.truncated).toBe(false);
    expect(offerBody.confirmationToken).toMatch(/^[0-9a-f]{64}$/);
    expect(offerBody.problems.length).toBeGreaterThan(0);
    await expect(page.getByRole('heading', { name: '确认仍保存为 INVALID' })).toBeVisible();

    const confirmedResponse = page.waitForResponse(templateSaveResponse(setup.templateId, 200));
    await page.getByRole('button', { name: '仍保存为 INVALID' }).click();
    const confirmed = await confirmedResponse;
    expect(confirmed.request().headers()['x-confirmation-token']).toBe(offerBody.confirmationToken);
    const confirmedBody = await confirmed.json() as TemplateCurrentBody;
    expect(confirmedBody.revision).toBe(2);
    expect(confirmedBody.readiness).toBe('INVALID');
    await expect(page.getByText('revision 2', { exact: true })).toBeVisible();
    await expect(page.getByText('Canonical current', { exact: true })).toBeVisible();

    const reloadResponse = page.waitForResponse(templateCurrentResponse(setup.templateId));
    await page.reload({ waitUntil: 'domcontentloaded' });
    expect((await reloadResponse).status()).toBe(200);
    await expect(page.getByText('Structured Editor', { exact: true })).toBeVisible();
    await expect(page.getByRole('heading', {
      level: 1,
      name: COMPLETE_WIRE_EDITED_NAME,
    })).toBeVisible();
    await expect(page.getByText('revision 2', { exact: true })).toBeVisible();

    const reloadedResponse = await page.request.get(`/api/v1/templates/${setup.templateId}`);
    expect(reloadedResponse.status()).toBe(200);
    const reloaded = await reloadedResponse.json() as TemplateCurrentBody;
    expect(reloaded.revision).toBe(2);
    expect(reloaded.readiness).toBe('INVALID');
    expect(reloaded.contentHash).not.toBe(fixtureCurrent.contentHash);
    expect(reloaded.designDsl.displayName).toBe(COMPLETE_WIRE_EDITED_NAME);
    expect(withoutDisplayName(reloaded.designDsl)).toEqual(
      withoutDisplayName(fixtureCurrent.designDsl),
    );
    expectCompleteWireCoverage(reloaded.designDsl);
    expect(browserErrors.filter((message) => message.includes('status of 422'))).toHaveLength(1);
    expect(browserErrors.filter((message) => !message.includes('status of 422'))).toEqual([]);
  });
});

async function createTemplateForCompleteWire(
  request: APIRequestContext,
): Promise<TemplateCurrentBody> {
  const initialDesign = JSON.stringify({
    dslVersion: 'renderweave-design/1.0',
    expressionProfile: 'renderweave-expression/1.0',
    displayName: 'Complete wire setup',
    definitions: [],
    designRoot: {
      nodeId: '90000000-0000-4000-8000-000000000001',
      kind: 'canvas',
      widthMm: 210,
      heightMm: 297,
      bindings: [],
      children: [],
    },
  });
  const response = await request.post(
    '/api/v1/templates?schemaKey=system-basic-text&versionTag=v1',
    {
      headers: { 'Content-Type': DESIGN_MEDIA_TYPE },
      data: initialDesign,
    },
  );
  expect(response.status()).toBe(201);
  return response.json() as Promise<TemplateCurrentBody>;
}

async function saveAndConfirmInvalid(
  request: APIRequestContext,
  templateId: string,
  expectedRevision: number,
  canonicalDesign: string,
): Promise<TemplateCurrentBody> {
  const url = `/api/v1/templates/${templateId}?expectedRevision=${expectedRevision}`;
  const offerResponse = await request.put(url, {
    headers: { 'Content-Type': DESIGN_MEDIA_TYPE },
    data: canonicalDesign,
  });
  expect(offerResponse.status()).toBe(422);
  const offer = await offerResponse.json() as InvalidConfirmationBody;
  expect(offer.code).toBe('TEMPLATE_DEPENDENCY_CONFIRMATION_REQUIRED');
  expect(offer.truncated).toBe(false);
  expect(offer.confirmationToken).toMatch(/^[0-9a-f]{64}$/);
  expect(offer.problems.length).toBeGreaterThan(0);

  const committedResponse = await request.put(url, {
    headers: {
      'Content-Type': DESIGN_MEDIA_TYPE,
      'X-Confirmation-Token': offer.confirmationToken,
    },
    data: canonicalDesign,
  });
  expect(committedResponse.status()).toBe(200);
  return committedResponse.json() as Promise<TemplateCurrentBody>;
}

function templateCurrentResponse(templateId: string) {
  return (response: Response): boolean => {
    const url = new URL(response.url());
    return response.request().method() === 'GET'
      && url.pathname === `/api/v1/templates/${templateId}`;
  };
}

function templateSaveResponse(templateId: string, status: number) {
  return (response: Response): boolean => {
    const url = new URL(response.url());
    return response.request().method() === 'PUT'
      && url.pathname === `/api/v1/templates/${templateId}`
      && response.status() === status;
  };
}

function withoutDisplayName(designDsl: Record<string, unknown>): Record<string, unknown> {
  const copy = structuredClone(designDsl);
  delete copy.displayName;
  return copy;
}

function captureBrowserErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  page.on('pageerror', (error) => errors.push(error.message));
  return errors;
}

function expectCompleteWireCoverage(designDsl: Record<string, unknown>): void {
  const nodeKinds = new Set<string>();
  const placementTypes = new Set<string>();
  const sourceKinds = new Set<string>();
  const definitionKinds = new Set<string>();

  const visit = (value: unknown): void => {
    if (Array.isArray(value)) {
      value.forEach(visit);
      return;
    }
    if (value === null || typeof value !== 'object') return;
    const object = value as Record<string, unknown>;
    if (typeof object.kind === 'string') {
      if (ALL_NODE_KINDS.has(object.kind)) nodeKinds.add(object.kind);
      if (ALL_VALUE_SOURCE_KINDS.has(object.kind)) sourceKinds.add(object.kind);
      if (ALL_DEFINITION_KINDS.has(object.kind)) definitionKinds.add(object.kind);
    }
    if (object.placement !== null && typeof object.placement === 'object') {
      const placement = object.placement as Record<string, unknown>;
      if (typeof placement.type === 'string') placementTypes.add(placement.type);
    }
    Object.values(object).forEach(visit);
  };
  visit(designDsl);

  expect([...nodeKinds].sort()).toEqual([...ALL_NODE_KINDS].sort());
  expect([...placementTypes].sort()).toEqual(['ABSOLUTE', 'GRID', 'PACK', 'STACK']);
  expect([...definitionKinds].sort()).toEqual(['custom', 'expression', 'mapping']);
  expect([...sourceKinds].sort()).toEqual([
    'capability',
    'context',
    'definition',
    'literal',
    'loopIndex',
  ]);
}

const ALL_NODE_KINDS = new Set([
  'canvas',
  'group',
  'frame',
  'stack',
  'grid',
  'repeat',
  'text',
  'image',
  'rect',
  'ellipse',
  'line',
  'polygon',
  'polyline',
  'path',
  'qrCode',
  'barcode',
  'templateUse',
  'conditional',
]);
const ALL_VALUE_SOURCE_KINDS = new Set([
  'literal',
  'definition',
  'context',
  'loopIndex',
  'capability',
]);
const ALL_DEFINITION_KINDS = new Set(['custom', 'mapping', 'expression']);
