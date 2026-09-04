// @vitest-environment happy-dom

import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { App } from './App';

vi.mock('../features/templates/TemplateProductPages', () => ({
  TemplateListPage: () => <main aria-label="Template catalog route">catalog</main>,
  TemplateCreatePage: () => <main aria-label="Template create route">create</main>,
  TemplateEditorPage: () => <main aria-label="Template editor route">editor</main>,
}));

vi.mock('../features/resources/DraftListPage', () => ({
  DraftListPage: () => <main aria-label="Schema catalog route">schemas</main>,
}));

vi.mock('../prototype/schema-studio/SchemaStudioPrototype', () => ({
  SchemaStudioPrototype: () => <main aria-label="Schema Studio prototype route">prototype</main>,
}));

afterEach(cleanup);

describe('formal Template product routes', () => {
  it.each([
    ['/templates', 'Template catalog route'],
    ['/templates/new', 'Template create route'],
    ['/templates/opaque-template-id', 'Template editor route'],
  ])('mounts %s through the production App', async (path, accessibleName) => {
    render(
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>,
    );

    expect(await screen.findByRole('main', { name: accessibleName })).toBeTruthy();
  });

  it('keeps the Schema Studio prototype available', async () => {
    render(
      <MemoryRouter initialEntries={['/prototype/schema-studio?variant=A']}>
        <App />
      </MemoryRouter>,
    );

    expect(await screen.findByRole('main', { name: 'Schema Studio prototype route' })).toBeTruthy();
  });

  it.each([
    '/prototype/template-designer?variant=A',
    '/prototype/editor-state-model',
  ])('retires obsolete prototype route %s from normal navigation', async (path) => {
    render(
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>,
    );

    expect(await screen.findByRole('main', { name: 'Schema catalog route' })).toBeTruthy();
  });
});
