/**
 * PROTOTYPE — throwaway. node kind → lucide 图标映射(独立文件以满足 fast-refresh
 * 只导出组件的约定)。
 */
import {
  Barcode,
  Circle,
  Frame,
  Group,
  Hexagon,
  Image,
  Layers,
  LayoutGrid,
  Minus,
  PenTool,
  Puzzle,
  QrCode,
  Repeat,
  Split,
  Square,
  Type,
} from 'lucide-react';
import type { ReactNode } from 'react';

import type { NodeKind } from './model';

export const kindIcons: Record<NodeKind, ReactNode> = {
  canvas: <Square aria-hidden="true" size={15} />,
  group: <Group aria-hidden="true" size={15} />,
  frame: <Frame aria-hidden="true" size={15} />,
  stack: <Layers aria-hidden="true" size={15} />,
  grid: <LayoutGrid aria-hidden="true" size={15} />,
  text: <Type aria-hidden="true" size={15} />,
  image: <Image aria-hidden="true" size={15} />,
  rect: <Square aria-hidden="true" size={15} />,
  ellipse: <Circle aria-hidden="true" size={15} />,
  line: <Minus aria-hidden="true" size={15} />,
  polygon: <Hexagon aria-hidden="true" size={15} />,
  polyline: <PenTool aria-hidden="true" size={15} />,
  path: <PenTool aria-hidden="true" size={15} />,
  qrCode: <QrCode aria-hidden="true" size={15} />,
  barcode: <Barcode aria-hidden="true" size={15} />,
  repeat: <Repeat aria-hidden="true" size={15} />,
  conditional: <Split aria-hidden="true" size={15} />,
  templateUse: <Puzzle aria-hidden="true" size={15} />,
};
