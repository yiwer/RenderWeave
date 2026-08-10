// @vitest-environment happy-dom

import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import type { PersistedDefinition } from './editor-types';
import { ReadonlyDefinitionForm, ReadonlyDefinitionTree } from './ReadonlyDefinitionViews';

const definition: PersistedDefinition = {
  dslVersion: 'renderweave-schema/1.0',
  displayName: '站牌',
  description: '公交站牌数据定义',
  fields: [
    {
      fieldKey: 'stationName',
      displayName: '站点名称',
      required: true,
      value: { type: 'text' },
    },
    {
      fieldKey: 'routes',
      displayName: '线路',
      required: true,
      value: {
        type: 'array',
        items: { type: 'reference', ref: { schemaKey: 'route', versionTag: 'v1' } },
      },
    },
    {
      fieldKey: 'notice',
      displayName: '温馨提示',
      required: false,
      value: { type: 'reference', ref: { schemaKey: 'notice' } },
    },
  ],
};

afterEach(cleanup);

describe('immutable definition readers', () => {
  it('shows root, field order and nested array/reference detail in the tree', () => {
    render(<ReadonlyDefinitionTree schemaKey="bus-stop" definition={definition} />);

    expect(screen.getByRole('region', { name: '站牌 字段树' })).toBeTruthy();
    expect(screen.getByText('bus-stop')).toBeTruthy();
    expect(screen.getByText('数组元素')).toBeTruthy();
    expect(screen.getByText('route@v1')).toBeTruthy();
    expect(screen.getByText('SchemaRef')).toBeTruthy();
  });

  it('keeps the same immutable fields readable in form mode', () => {
    render(<ReadonlyDefinitionForm definition={definition} />);

    expect(screen.getByRole('region', { name: '站牌 字段表单' })).toBeTruthy();
    expect(screen.getByText('站点名称')).toBeTruthy();
    expect(screen.getByText('Array[引用]')).toBeTruthy();
    expect(screen.getAllByText('必填')).toHaveLength(2);
  });
});
