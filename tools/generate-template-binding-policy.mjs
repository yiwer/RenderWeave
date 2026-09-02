import { readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = join(scriptDirectory, '..');
const openApiPath = join(repositoryRoot, 'openapi', 'renderweave-v1.yaml');
const outputPath = join(
  repositoryRoot,
  'web',
  'src',
  'features',
  'template-editor',
  'template-editor-binding-policy.generated.ts',
);

const source = await readFile(openApiPath, 'utf8');
const policy = parseBindingPolicy(source);
const output = renderBindingPolicy(policy);
let previous = null;
try {
  previous = await readFile(outputPath, 'utf8');
} catch (error) {
  if (error?.code !== 'ENOENT') throw error;
}
if (previous !== output) {
  await writeFile(outputPath, output, 'utf8');
}

function parseBindingPolicy(document) {
  const lines = document.replaceAll('\r\n', '\n').split('\n');
  const markers = lines
    .map((line, index) => ({ line, index }))
    .filter(({ line }) => line.trim() === 'x-renderweave-binding-policy:');
  invariant(markers.length === 1,
    `expected one x-renderweave-binding-policy block, found ${markers.length}`);

  const marker = markers[0];
  const markerIndent = indentation(marker.line);
  let end = marker.index + 1;
  while (end < lines.length) {
    const line = lines[end];
    if (line.trim().length > 0 && indentation(line) <= markerIndent) break;
    end += 1;
  }

  const sectionIndent = markerIndent + 2;
  const entryIndent = markerIndent + 4;
  const commonNonCanvas = [];
  const commonNonGroup = [];
  const byKind = new Map();
  const valueTypes = new Map();
  const sections = new Set();
  let section = null;

  for (let index = marker.index + 1; index < end; index += 1) {
    const line = lines[index];
    if (line.trim().length === 0) continue;
    const indent = indentation(line);
    const location = `${openApiPath}:${index + 1}`;
    if (indent === sectionIndent) {
      const match = /^\s*([A-Za-z][A-Za-z0-9]*):\s*$/.exec(line);
      invariant(match, `${location}: expected a binding-policy section`);
      section = match[1];
      invariant(
        ['commonNonCanvas', 'commonNonGroup', 'byKind', 'valueTypes'].includes(section),
        `${location}: unknown binding-policy section ${section}`,
      );
      invariant(!sections.has(section), `${location}: duplicate section ${section}`);
      sections.add(section);
      continue;
    }
    invariant(indent === entryIndent && section !== null,
      `${location}: unexpected binding-policy indentation`);

    if (section === 'commonNonCanvas' || section === 'commonNonGroup') {
      const match = /^\s*-\s+(.+?)\s*$/.exec(line);
      invariant(match, `${location}: expected a block-list item`);
      const target = section === 'commonNonCanvas' ? commonNonCanvas : commonNonGroup;
      target.push(parseScalar(match[1], location));
      continue;
    }

    const match = /^\s*(.+?):\s*(.*?)\s*$/.exec(line);
    invariant(match, `${location}: expected a mapping entry`);
    const key = parseScalar(match[1], `${location} key`);
    if (section === 'byKind') {
      invariant(!byKind.has(key), `${location}: duplicate node kind ${key}`);
      byKind.set(key, parseInlineList(match[2], location));
    } else {
      invariant(!valueTypes.has(key), `${location}: duplicate value type path ${key}`);
      valueTypes.set(key, parseScalar(match[2], location));
    }
  }

  const requiredSections = ['commonNonCanvas', 'commonNonGroup', 'byKind', 'valueTypes'];
  for (const required of requiredSections) {
    invariant(sections.has(required), `missing binding-policy section ${required}`);
  }
  assertUnique(commonNonCanvas, 'commonNonCanvas');
  assertUnique(commonNonGroup, 'commonNonGroup');
  for (const [kind, paths] of byKind) assertUnique(paths, `byKind.${kind}`);

  const policyPaths = new Set([
    ...commonNonCanvas,
    ...commonNonGroup,
    ...[...byKind.values()].flat(),
  ]);
  const allowedTypes = new Set([
    'text', 'decimal', 'boolean', 'date', 'time', 'color', 'imageRef', 'fontRef',
  ]);
  for (const [path, valueType] of valueTypes) {
    invariant(policyPaths.has(path), `valueTypes invents non-policy path ${path}`);
    invariant(allowedTypes.has(valueType), `unsupported value type ${valueType} for ${path}`);
  }
  for (const path of policyPaths) {
    invariant(valueTypes.has(path), `valueTypes omits policy path ${path}`);
  }

  return { commonNonCanvas, commonNonGroup, byKind, valueTypes };
}

function parseInlineList(token, location) {
  invariant(token.startsWith('[') && token.endsWith(']'),
    `${location}: expected an inline YAML list`);
  const body = token.slice(1, -1).trim();
  if (body.length === 0) return [];
  return splitInlineItems(body, location).map((item) => parseScalar(item, location));
}

function splitInlineItems(body, location) {
  const items = [];
  let start = 0;
  let quote = null;
  for (let index = 0; index < body.length; index += 1) {
    const character = body[index];
    if (quote === "'" && character === "'" && body[index + 1] === "'") {
      index += 1;
      continue;
    }
    if (quote === '"' && character === '\\') {
      index += 1;
      continue;
    }
    if (character === "'" || character === '"') {
      quote = quote === null ? character : (quote === character ? null : quote);
    } else if (character === ',' && quote === null) {
      items.push(body.slice(start, index).trim());
      start = index + 1;
    }
  }
  invariant(quote === null, `${location}: unterminated quoted scalar`);
  items.push(body.slice(start).trim());
  invariant(items.every((item) => item.length > 0), `${location}: empty inline-list item`);
  return items;
}

function parseScalar(token, location) {
  const value = token.trim();
  invariant(value.length > 0, `${location}: empty scalar`);
  if (value.startsWith("'")) {
    invariant(value.endsWith("'"), `${location}: unterminated single-quoted scalar`);
    return value.slice(1, -1).replaceAll("''", "'");
  }
  if (value.startsWith('"')) {
    try {
      const parsed = JSON.parse(value);
      invariant(typeof parsed === 'string', `${location}: scalar must be a string`);
      return parsed;
    } catch (error) {
      throw new Error(`${location}: invalid double-quoted scalar`, { cause: error });
    }
  }
  invariant(!/\s|[\[\]{},]/u.test(value), `${location}: unsupported plain scalar ${value}`);
  return value;
}

function renderBindingPolicy(policy) {
  const lines = [
    '// Generated by tools/generate-template-binding-policy.mjs from',
    '// openapi/renderweave-v1.yaml DesignNode.x-renderweave-binding-policy.',
    '// Do not edit by hand.',
    'export const TEMPLATE_BINDING_POLICY = Object.freeze({',
  ];
  renderArrayProperty(lines, 'commonNonCanvas', policy.commonNonCanvas, 2);
  renderArrayProperty(lines, 'commonNonGroup', policy.commonNonGroup, 2);
  lines.push('  byKind: Object.freeze({');
  for (const [kind, paths] of policy.byKind) {
    if (paths.length === 0) {
      lines.push(`    ${propertyKey(kind)}: Object.freeze([]),`);
    } else {
      lines.push(`    ${propertyKey(kind)}: Object.freeze([`);
      for (const path of paths) lines.push(`      ${jsString(path)},`);
      lines.push('    ]),');
    }
  }
  lines.push('  }),', '  valueTypes: Object.freeze({');
  for (const [path, valueType] of policy.valueTypes) {
    lines.push(`    ${propertyKey(path)}: ${jsString(valueType)},`);
  }
  lines.push('  }),', '});', '');
  return lines.join('\n');
}

function renderArrayProperty(lines, name, values, indent) {
  const prefix = ' '.repeat(indent);
  lines.push(`${prefix}${name}: Object.freeze([`);
  for (const value of values) lines.push(`${prefix}  ${jsString(value)},`);
  lines.push(`${prefix}]),`);
}

function propertyKey(value) {
  return /^[A-Za-z_$][A-Za-z0-9_$]*$/u.test(value) ? value : jsString(value);
}

function jsString(value) {
  return `'${value.replaceAll('\\', '\\\\').replaceAll("'", "\\'")}'`;
}

function assertUnique(values, location) {
  const seen = new Set();
  for (const value of values) {
    invariant(!seen.has(value), `${location} contains duplicate ${value}`);
    seen.add(value);
  }
}

function indentation(line) {
  return /^ */u.exec(line)[0].length;
}

function invariant(condition, message) {
  if (!condition) throw new Error(message);
}
