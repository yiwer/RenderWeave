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
});
