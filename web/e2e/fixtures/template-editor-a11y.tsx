import { createRoot } from 'react-dom/client';

import '../../src/styles.css';
import { createSessionFromBaseline } from '../../src/features/template-editor/template-editor-model';
import { applyTemplateDisplayName } from '../../src/features/template-editor/template-editor-session';
import { structuredBaseline } from '../../src/features/template-editor/template-editor-test-support';
import { TemplateEditorShell } from '../../src/features/template-editor/TemplateEditorShell';
import type { TemplateSaveTransport } from '../../src/features/template-editor/template-save';

const initial = createSessionFromBaseline(
  structuredBaseline(),
  { state: 'checked', value: 'READY' },
);
if (initial.mode !== 'structured') throw new Error('fixture requires Structured Editor');
const edited = applyTemplateDisplayName(initial, '浏览器可访问性草稿');
if (edited.state !== 'applied') throw new Error('fixture requires a dirty working copy');

const saveTransport: TemplateSaveTransport = {
  getCurrent: async () => {
    throw new Error('fixture does not reconcile');
  },
  putCurrent: async (...args) => ({
    status: 422,
    body: await invalidProblemResponse(args[2]),
  }),
};

createRoot(requiredElement('root')).render(
  <TemplateEditorShell session={edited.session} saveTransport={saveTransport} />,
);

function requiredElement(id: string): HTMLElement {
  const element = document.getElementById(id);
  if (!element) throw new Error(`missing fixture element ${id}`);
  return element;
}

async function invalidProblemResponse(canonicalDesignDsl: string): Promise<string> {
  const bytes = new TextEncoder().encode(
    `renderweave-design-content/1\0${canonicalDesignDsl}`,
  );
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  const proposedContentHash = `sha256:${Array.from(
    digest,
    (byte) => byte.toString(16).padStart(2, '0'),
  ).join('')}`;
  return JSON.stringify({
    code: 'TEMPLATE_DEPENDENCY_CONFIRMATION_REQUIRED',
    proposedContentHash,
    confirmationToken: 'a'.repeat(64),
    expiresAt: '2099-01-01T00:00:00Z',
    problems: [{
      code: 'TEMPLATE_USE_FILL_TYPE_MISMATCH',
      category: 'DEPENDENCY',
      severity: 'ERROR',
      canonicalPointer: '/designRoot/children/0/fills/0/source',
      messageArgs: [],
    }],
    truncated: false,
  });
}
