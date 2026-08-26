// @vitest-environment happy-dom

import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';

import { ResourceRail } from './ResourceFrame';

afterEach(cleanup);

describe('final-product resource navigation', () => {
  it('keeps the Template entry active for every formal Template child route', () => {
    render(
      <MemoryRouter initialEntries={['/templates/new']}>
        <ResourceRail />
      </MemoryRouter>,
    );

    const templates = screen.getByRole('link', { name: '模板设计' });
    expect(templates.getAttribute('href')).toBe('/templates');
    expect(templates.getAttribute('aria-current')).toBe('page');
    expect(templates.className).toContain('active');
    expect(screen.getByText('0.17.0')).toBeTruthy();
    expect(screen.getByText(/Template 与权威渲染/)).toBeTruthy();
  });
});
