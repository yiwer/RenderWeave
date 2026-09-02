// @vitest-environment happy-dom

import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import {
  designPathCommandsToSvgPath,
  projectDesignPathCommandsToAuthoredBox,
  templatePointsToCanvasPixels,
} from './template-editor-visual-projection';
import {
  TemplateEditorVisualNode,
} from './TemplateEditorVisualNode';

afterEach(cleanup);

describe('Template Editor visual-node draft projector', () => {
  it('fits a non-square ellipse to the current authored box instead of forcing a circle', () => {
    const view = render(<TemplateEditorVisualNode
      node={visualNode('ellipse', {
        fill: { color: '#5DAF83FF' },
        stroke: stroke('#173F35FF', 1),
      })}
      widthMm={40}
      heightMm={20}
    />);

    const surface = view.container.querySelector('svg');
    const fillEllipse = view.container.querySelector<SVGEllipseElement>(
      '[data-template-vector-layer="fill"]',
    );
    const strokeEllipse = view.container.querySelector<SVGEllipseElement>(
      '[data-template-vector-layer="inward-stroke"]',
    );
    expect(surface?.getAttribute('viewBox')).toBe('0 0 40 20');
    expect(surface?.getAttribute('preserveAspectRatio')).toBe('none');
    expect(fillEllipse?.getAttribute('cx')).toBe('20');
    expect(fillEllipse?.getAttribute('cy')).toBe('10');
    expect(fillEllipse?.getAttribute('rx')).toBe('20');
    expect(fillEllipse?.getAttribute('ry')).toBe('10');
    expect(strokeEllipse?.getAttribute('rx')).toBe('19.5');
    expect(strokeEllipse?.getAttribute('ry')).toBe('9.5');
    expect(strokeEllipse?.getAttribute('stroke-width')).toBe('1');
  });

  it('projects line, polygon, polyline, and commands[] path geometry into the live box', () => {
    const rect = render(<TemplateEditorVisualNode
      node={visualNode('rect', {
        fill: { color: '#EAF7F0FF' },
        stroke: stroke('#0E6151FF', 0.5),
        cornerRadii: {
          topLeftMm: 1, topRightMm: 2, bottomRightMm: 3, bottomLeftMm: 4,
        },
      })}
      widthMm={30}
      heightMm={10}
    />);
    const rectFill = rect.container.querySelector<SVGPathElement>(
      '[data-template-vector-layer="fill"]',
    );
    const rectStroke = rect.container.querySelector<SVGPathElement>(
      '[data-template-vector-layer="inward-stroke"]',
    );
    expect(rectFill?.getAttribute('fill')).toBe('#EAF7F0FF');
    expect(rectFill?.getAttribute('d')).toContain('Q 30 0 30 2');
    expect(rectStroke?.getAttribute('d')).toContain('Q 29.75 0.25 29.75 2');
    expect(rectStroke?.getAttribute('stroke-width')).toBe('0.5');
    rect.unmount();

    const line = render(<TemplateEditorVisualNode
      node={visualNode('line', {
        start: { xMm: 1, yMm: 2 },
        end: { xMm: 29, yMm: 8 },
        stroke: stroke('#0E6151FF', 0.5),
      })}
      widthMm={30}
      heightMm={10}
    />);
    const lineSurface = line.container.querySelector('svg');
    expect(lineSurface?.style.overflow).toBe('visible');
    expect(line.container.querySelector('line')?.getAttribute('stroke-width')).toBe('0.5');
    expect(line.container.querySelector('line')?.getAttribute('x1')).toBe('0');
    expect(line.container.querySelector('line')?.getAttribute('y1')).toBe('0');
    expect(line.container.querySelector('line')?.getAttribute('x2')).toBe('30');
    expect(line.container.querySelector('line')?.getAttribute('y2')).toBe('10');
    line.unmount();

    const polygon = render(<TemplateEditorVisualNode
      node={visualNode('polygon', {
        points: [{ xMm: 0, yMm: 10 }, { xMm: 10, yMm: 0 }, { xMm: 20, yMm: 10 }],
        fill: { color: '#E9C46AFF' },
      })}
      widthMm={20}
      heightMm={10}
    />);
    expect(polygon.container.querySelector('polygon')?.getAttribute('points'))
      .toBe('0,10 10,0 20,10');
    polygon.unmount();

    const polyline = render(<TemplateEditorVisualNode
      node={visualNode('polyline', {
        points: [{ xMm: 0, yMm: 9 }, { xMm: 10, yMm: 1 }, { xMm: 20, yMm: 9 }],
        stroke: stroke('#1A535CFF', 0.4),
      })}
      widthMm={20}
      heightMm={10}
    />);
    const polylineElement = polyline.container.querySelector('polyline');
    expect(polyline.container.querySelector('svg')?.style.overflow).toBe('visible');
    expect(polylineElement?.getAttribute('points')).toBe('0,10 10,0 20,10');
    expect(polylineElement?.getAttribute('stroke-width')).toBe('0.4');
    expect(polylineElement?.getAttribute('fill')).toBe('none');
    polyline.unmount();

    const commands = [
      { type: 'MOVE_TO', xMm: 1, yMm: 8 },
      { type: 'LINE_TO', xMm: 5, yMm: 1 },
      { type: 'QUAD_TO', cxMm: 8, cyMm: 0, xMm: 10, yMm: 4 },
      {
        type: 'CUBIC_TO', c1xMm: 12, c1yMm: 7, c2xMm: 16, c2yMm: 7, xMm: 19, yMm: 2,
      },
      { type: 'CLOSE' },
    ];
    const path = render(<TemplateEditorVisualNode
      node={visualNode('path', {
        commands,
        pathData: 'M 999 999',
        fill: { color: '#D8F3DCFF' },
        stroke: stroke('#081C15FF', 0.25),
        fillRule: 'EVEN_ODD',
      })}
      widthMm={20}
      heightMm={10}
    />);
    const pathElement = path.container.querySelector('path');
    expect(pathElement?.getAttribute('data-template-path-source')).toBe('commands');
    expect(designPathCommandsToSvgPath(commands)).toBe(
      'M 1 8 L 5 1 Q 8 0 10 4 C 12 7 16 7 19 2 Z',
    );
    expect(pathElement?.getAttribute('d')).not.toContain('999');
    expect(pathElement?.getAttribute('d')).not.toBe(designPathCommandsToSvgPath(commands));
    expect(pathElement?.getAttribute('d')?.endsWith(' Z')).toBe(true);
    expect(pathElement?.getAttribute('fill-rule')).toBe('evenodd');
    expect(pathElement?.getAttribute('stroke-width')).toBe('0.25');
    expect(path.container.querySelector('svg')?.style.overflow).toBe('visible');
    expect(designPathCommandsToSvgPath([{ type: 'ARC_TO', xMm: 1, yMm: 2 }])).toBe('');
  });

  it('renormalizes raw local-mm geometry across enlarged and shrunken authored boxes', () => {
    const lineNode = visualNode('line', {
      start: { xMm: 10, yMm: 5 },
      end: { xMm: 20, yMm: 15 },
      stroke: stroke('#0E6151FF', 2),
    });
    const line = render(<TemplateEditorVisualNode
      node={lineNode}
      widthMm={60}
      heightMm={30}
    />);
    const lineElement = () => line.container.querySelector('line');
    expect([
      lineElement()?.getAttribute('x1'), lineElement()?.getAttribute('y1'),
      lineElement()?.getAttribute('x2'), lineElement()?.getAttribute('y2'),
    ]).toEqual(['0', '0', '60', '30']);

    line.rerender(<TemplateEditorVisualNode node={lineNode} widthMm={12} heightMm={8} />);
    expect([
      lineElement()?.getAttribute('x1'), lineElement()?.getAttribute('y1'),
      lineElement()?.getAttribute('x2'), lineElement()?.getAttribute('y2'),
    ]).toEqual(['0', '0', '12', '8']);
    line.unmount();

    const polygon = render(<TemplateEditorVisualNode
      node={visualNode('polygon', {
        points: [
          { xMm: 100, yMm: 100 },
          { xMm: 150, yMm: 50 },
          { xMm: 200, yMm: 100 },
        ],
        fill: { color: '#E9C46AFF' },
        stroke: stroke('#173F35FF', 2),
      })}
      widthMm={30}
      heightMm={10}
    />);
    expect(polygon.container.querySelector('polygon')?.getAttribute('points'))
      .toBe('0,10 15,0 30,10');
    polygon.unmount();

    const polyline = render(<TemplateEditorVisualNode
      node={visualNode('polyline', {
        points: [
          { xMm: -5, yMm: 20 },
          { xMm: 0, yMm: 10 },
          { xMm: 5, yMm: 20 },
        ],
        stroke: stroke('#173F35FF', 2),
      })}
      widthMm={40}
      heightMm={20}
    />);
    expect(polyline.container.querySelector('polyline')?.getAttribute('points'))
      .toBe('0,20 20,0 40,20');
    polyline.unmount();

    const path = render(<TemplateEditorVisualNode
      node={visualNode('path', {
        commands: [
          { type: 'MOVE_TO', xMm: 10, yMm: 5 },
          { type: 'LINE_TO', xMm: 20, yMm: 15 },
        ],
        stroke: stroke('#173F35FF', 2),
      })}
      widthMm={30}
      heightMm={12}
    />);
    expect(path.container.querySelector('path')?.getAttribute('d')).toBe('M 0 0 L 30 12');
    path.unmount();

    const vertical = render(<TemplateEditorVisualNode
      node={visualNode('line', {
        start: { xMm: 10, yMm: 5 },
        end: { xMm: 10, yMm: 15 },
        stroke: stroke('#173F35FF', 2),
      })}
      widthMm={30}
      heightMm={20}
    />);
    const verticalLine = vertical.container.querySelector('line');
    expect([
      verticalLine?.getAttribute('x1'), verticalLine?.getAttribute('y1'),
      verticalLine?.getAttribute('x2'), verticalLine?.getAttribute('y2'),
    ]).toEqual(['15', '0', '15', '20']);
  });

  it('uses curve extrema rather than control-point bounds when normalizing a Path', () => {
    expect(projectDesignPathCommandsToAuthoredBox([
      { type: 'MOVE_TO', xMm: 0, yMm: 0 },
      { type: 'QUAD_TO', cxMm: 5, cyMm: 10, xMm: 10, yMm: 0 },
    ], { widthMm: 20, heightMm: 10 })).toBe('M 0 0 Q 10 20 20 0');
  });

  it('ignores non-drawing MOVE_TO commands when fitting painted Path geometry', () => {
    expect(projectDesignPathCommandsToAuthoredBox([
      { type: 'MOVE_TO', xMm: 100, yMm: 100 },
      { type: 'MOVE_TO', xMm: 0, yMm: 0 },
      { type: 'LINE_TO', xMm: 10, yMm: 0 },
      { type: 'MOVE_TO', xMm: -100, yMm: -100 },
      { type: 'CLOSE' },
    ], { widthMm: 20, heightMm: 10 })).toBe(
      'M 200 5 M 0 5 L 20 5 M -200 5 Z',
    );
  });

  it('preserves physical stroke width and visible overflow for free vectors', () => {
    const line = render(<TemplateEditorVisualNode
      node={visualNode('line', {
        start: { xMm: 0, yMm: 0 },
        end: { xMm: 10, yMm: 10 },
        stroke: { color: '#173F35FF', widthMm: 10, cap: 'SQUARE', join: 'ROUND' },
      })}
      widthMm={20}
      heightMm={8}
    />);
    const lineSurface = line.container.querySelector('svg');
    const lineElement = line.container.querySelector('line');
    expect(lineSurface?.dataset.templateStrokeProjection).toBe('authored');
    expect(lineSurface?.style.overflow).toBe('visible');
    expect(lineElement?.getAttribute('stroke-width')).toBe('10');
    expect([
      lineElement?.getAttribute('x1'), lineElement?.getAttribute('y1'),
      lineElement?.getAttribute('x2'), lineElement?.getAttribute('y2'),
    ]).toEqual(['0', '0', '20', '8']);
    line.unmount();

    const polygon = render(<TemplateEditorVisualNode
      node={visualNode('polygon', {
        points: [{ xMm: 0, yMm: 10 }, { xMm: 10, yMm: 0 }, { xMm: 20, yMm: 10 }],
        fill: { color: '#E9C46AFF' },
        stroke: { color: '#173F35FF', widthMm: 10, cap: 'BUTT', join: 'MITER' },
      })}
      widthMm={20}
      heightMm={8}
    />);
    const polygonSurface = polygon.container.querySelector('svg');
    const polygonElement = polygon.container.querySelector('polygon');
    expect(polygonSurface?.dataset.templateStrokeProjection).toBe('authored');
    expect(polygonSurface?.style.overflow).toBe('visible');
    expect(polygonElement?.getAttribute('stroke-width')).toBe('10');
    expect(polygonElement?.getAttribute('stroke-miterlimit')).toBe('4');
    expect(polygonElement?.getAttribute('points')).toBe('0,8 10,0 20,8');
  });

  it('keeps exact over-thick Rect and Ellipse strokes inward without semantic downgrade', () => {
    const rect = render(<TemplateEditorVisualNode
      node={visualNode('rect', {
        fill: { color: '#E9C46AFF' },
        stroke: { color: '#173F35FF', widthMm: 10, cap: 'BUTT', join: 'MITER' },
        cornerRadii: {
          topLeftMm: 0, topRightMm: 0, bottomRightMm: 0, bottomLeftMm: 0,
        },
      })}
      widthMm={20}
      heightMm={8}
    />);
    const rectSurface = rect.container.querySelector('svg');
    const rectFill = rect.container.querySelector<SVGPathElement>(
      '[data-template-vector-layer="fill"]',
    );
    const rectStroke = rect.container.querySelector<SVGPathElement>(
      '[data-template-vector-layer="inward-stroke"]',
    );
    expect(rectSurface?.dataset.templateStrokeProjection).toBe('authored-inward');
    expect(rectSurface?.style.overflow).toBe('hidden');
    expect(rectFill?.getAttribute('d')).toContain('M 0 0 H 20');
    expect(rectStroke?.getAttribute('stroke-width')).toBe('10');
    expect(rectStroke?.getAttribute('fill')).toBe('none');
    expect(rectStroke?.getAttribute('clip-path')).toMatch(/^url\(#.+\)$/);
    rect.unmount();

    const ellipse = render(<TemplateEditorVisualNode
      node={visualNode('ellipse', {
        fill: { color: '#E9C46AFF' },
        stroke: { color: '#173F35FF', widthMm: 10, cap: 'ROUND', join: 'ROUND' },
      })}
      widthMm={20}
      heightMm={8}
    />);
    const ellipseSurface = ellipse.container.querySelector('svg');
    const ellipseFill = ellipse.container.querySelector<SVGEllipseElement>(
      '[data-template-vector-layer="fill"]',
    );
    const ellipseStroke = ellipse.container.querySelector<SVGEllipseElement>(
      '[data-template-vector-layer="inward-stroke"]',
    );
    expect(ellipseSurface?.dataset.templateStrokeProjection).toBe('authored-inward');
    expect(ellipseSurface?.style.overflow).toBe('hidden');
    expect(ellipseFill?.getAttribute('rx')).toBe('10');
    expect(ellipseFill?.getAttribute('ry')).toBe('4');
    expect(ellipseStroke?.getAttribute('stroke-width')).toBe('10');
    expect(ellipseStroke?.getAttribute('rx')).toBe('5');
    expect(Number(ellipseStroke?.getAttribute('ry'))).toBeGreaterThan(0);
    expect(Number(ellipseStroke?.getAttribute('ry'))).toBeLessThan(0.001);
    expect(ellipseStroke?.getAttribute('clip-path')).toMatch(/^url\(#.+\)$/);
  });

  it('normalizes asymmetric Rect corner radii with one CSS scale factor', () => {
    const view = render(<TemplateEditorVisualNode
      node={visualNode('rect', {
        fill: { color: '#E9C46AFF' },
        stroke: stroke('#173F35FF', 2),
        cornerRadii: {
          topLeftMm: 20,
          topRightMm: 10,
          bottomRightMm: 8,
          bottomLeftMm: 4,
        },
      })}
      widthMm={30}
      heightMm={10}
    />);
    const fillPath = view.container.querySelector<SVGPathElement>(
      '[data-template-vector-layer="fill"]',
    );
    const strokePath = view.container.querySelector<SVGPathElement>(
      '[data-template-vector-layer="inward-stroke"]',
    );
    expect(fillPath?.getAttribute('d')).toBe([
      'M 8.333333 0',
      'H 25.833333',
      'Q 30 0 30 4.166667',
      'V 6.666667',
      'Q 30 10 26.666667 10',
      'H 1.666667',
      'Q 0 10 0 8.333333',
      'V 8.333333',
      'Q 0 0 8.333333 0',
      'Z',
    ].join(' '));
    expect(strokePath?.getAttribute('d'))
      .toContain('M 8.333333 1 H 25.833333 Q 29 1 29 4.166667');
    expect(strokePath?.getAttribute('stroke-width')).toBe('2');
  });

  it('converts formal point sizes to canvas pixels so the parent world transform scales text', () => {
    const fontFamilies: Record<string, string> = {
      '11111111-1111-4111-8111-111111111111': 'rw-preview-font-1111',
    };
    const view = render(<TemplateEditorVisualNode
      node={visualNode('text', {
        runs: [{
          text: '价格 18.00',
          fontRef: { assetId: '11111111-1111-4111-8111-111111111111' },
          fontSizePt: 12,
          color: '#183A37FF',
          decoration: 'NONE',
        }],
        padding: { topMm: 1, rightMm: 2, bottomMm: 1, leftMm: 2 },
        lineHeight: { type: 'FACTOR', factor: 1.4 },
        stroke: { color: '#FFFFFFFF', widthPt: 0.5, cap: 'ROUND', join: 'ROUND' },
      })}
      widthMm={40}
      heightMm={12}
      pixelsPerMm={4}
      resources={{ resolveFontFamily: (assetId) => fontFamilies[assetId] }}
    />);

    const root = view.container.querySelector<HTMLElement>('[data-template-visual-kind="text"]');
    const run = view.container.querySelector<HTMLElement>('[data-template-text-run]');
    expect(root?.dataset.templateTextSizeSpace).toBe('canvas-px');
    expect(root?.style.padding).toBe('4px 8px');
    expect(Number.parseFloat(run?.style.fontSize ?? '0'))
      .toBeCloseTo(templatePointsToCanvasPixels(12, 4), 6);
    expect(run?.style.fontFamily).toBe('rw-preview-font-1111');
    expect(run?.style.lineHeight).toBe('1.4');
    expect(run?.textContent).toBe('价格 18.00');
  });

  it('preserves explicit LF text and applies vertical alignment and visible overflow', () => {
    const view = render(<TemplateEditorVisualNode
      node={visualNode('text', {
        runs: [{
          text: '第一行\n  第二行',
          fontRef: { assetId: '11111111-1111-4111-8111-111111111111' },
          fontSizePt: 12,
          color: '#183A37FF',
          decoration: 'NONE',
          letterSpacingPt: 0,
        }],
        lineBreak: 'WORD',
        overflow: 'VISIBLE',
        verticalAlign: 'CENTER',
      })}
      widthMm={40}
      heightMm={20}
    />);

    const root = view.container.querySelector<HTMLElement>('[data-template-visual-kind="text"]');
    const flow = root?.firstElementChild as HTMLElement | null;
    expect(root?.dataset.templateTextOverflow).toBe('visible');
    expect(root?.style.flexDirection).toBe('column');
    expect(root?.style.justifyContent).toBe('center');
    expect(root?.style.overflow).toBe('visible');
    expect(flow?.style.whiteSpace).toBe('pre-wrap');
    expect(flow?.textContent).toBe('第一行\n  第二行');
  });

  it('uses only an injected ephemeral image URL and preserves fit and sampling feedback', () => {
    const node = visualNode('image', {
      displayName: '产品照片',
      imageRef: { assetId: '22222222-2222-4222-8222-222222222222' },
      fit: 'COVER',
      sampling: 'NEAREST',
    });
    render(<TemplateEditorVisualNode
      node={node}
      widthMm={45}
      heightMm={30}
      resources={{ imagePreviewUrl: 'blob:renderweave-ephemeral', imageAlt: '产品照片预览' }}
    />);

    const image = screen.getByAltText('产品照片预览') as HTMLImageElement;
    expect(image.getAttribute('src')).toBe('blob:renderweave-ephemeral');
    expect(image.style.objectFit).toBe('cover');
    expect(image.style.imageRendering).toBe('pixelated');
    expect(node.imageRef).toEqual({ assetId: '22222222-2222-4222-8222-222222222222' });
    expect(JSON.stringify(node)).not.toContain('blob:renderweave-ephemeral');
  });

  it('labels QR and barcode visuals as local, non-certified draft projections', () => {
    const qr = render(<TemplateEditorVisualNode
      node={visualNode('qrCode', { content: 'https://example.invalid/draft' })}
      widthMm={24}
      heightMm={24}
    />);
    expect(screen.getByRole('img', { name: '二维码本地草稿示意，非认证输出' })
      .dataset.templatePreviewAuthority).toBe('non-certified-local-draft');
    expect(qr.container.querySelector('svg')?.getAttribute('preserveAspectRatio'))
      .toBe('xMidYMid meet');
    expect(screen.getByText('本地草稿 · 非认证')).toBeTruthy();
    qr.unmount();

    render(<TemplateEditorVisualNode
      node={visualNode('barcode', { format: 'CODE_128', value: 'RW-DRAFT-001' })}
      widthMm={48}
      heightMm={18}
    />);
    expect(screen.getByRole('img', { name: '条形码本地草稿示意，非认证输出' })
      .dataset.templatePreviewAuthority).toBe('non-certified-local-draft');
    expect(screen.getByText('本地草稿 · 非认证')).toBeTruthy();
  });

  it('shows explicit invalid feedback instead of QR cells for a rectangular final box', () => {
    const view = render(<TemplateEditorVisualNode
      node={visualNode('qrCode', { content: 'RW-NOT-SQUARE' })}
      widthMm={30}
      heightMm={20}
    />);

    const invalid = screen.getByRole('img', {
      name: '二维码本地草稿无效：最终尺寸必须为正方形',
    });
    expect(invalid.dataset.templatePreviewAuthority).toBe('non-certified-local-draft');
    expect(invalid.dataset.templatePreviewValidity).toBe('invalid-layout');
    expect(screen.getByText('最终尺寸必须为严格正方形')).toBeTruthy();
    expect(view.container.querySelector('svg')).toBeNull();
    expect(view.container.querySelector('[data-template-qr-cell]')).toBeNull();
  });

  it('shows a closed image placeholder when no temporary preview resource is available', () => {
    render(<TemplateEditorVisualNode
      node={visualNode('image', {
        imageRef: { assetId: '33333333-3333-4333-8333-333333333333' },
      })}
      widthMm={30}
      heightMm={20}
    />);
    expect(screen.getByText('图片资产预览不可用').dataset.templateVisualResource)
      .toBe('unavailable');
  });
});

function visualNode(
  kind: string,
  properties: Readonly<Record<string, unknown>>,
): Readonly<Record<string, unknown>> {
  return {
    nodeId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    kind,
    displayName: kind,
    bindings: [],
    placement: {
      type: 'ABSOLUTE',
      xMm: 0,
      yMm: 0,
      widthMode: 'FIXED',
      widthMm: 20,
      heightMode: 'FIXED',
      heightMm: 10,
    },
    ...properties,
  };
}

function stroke(color: string, widthMm: number) {
  return { color, widthMm, cap: 'ROUND', join: 'ROUND' };
}
