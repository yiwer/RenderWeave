import { describe, expect, it } from 'vitest';

import { serializeDefinition } from './editor-types';
import { sessionFromDraft } from './editor-session';
import { parseDraftSnapshot } from './lossless-api';

describe('lossless Draft HTTP boundary', () => {
  it('preserves decimal tokens from response text through the next request body', () => {
    const huge = '12345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678';
    const snapshot = parseDraftSnapshot(`{
      "schemaKey":"money","revision":7,
      "definition":{"dslVersion":"renderweave-schema/1.0","displayName":"Money","fields":[
        {"fieldKey":"amount","required":true,"value":{"type":"decimal","constraints":{"const":${huge}}}}
      ]},
      "creationSource":"USER","createdAt":"2026-08-08T00:00:00Z",
      "updatedAt":"2026-08-08T00:00:00Z","savedAt":"2026-08-08T00:00:00Z",
      "resolvedRevisions":{"money":7}
    }`);
    const session = sessionFromDraft(snapshot);
    const serialized = serializeDefinition(session.displayName, session.description, session.fields);

    expect(snapshot.definition.fields[0]?.value).toEqual({
      type: 'decimal', constraints: { const: huge },
    });
    expect(serialized).toContain(`"const":${huge}`);
  });
});
