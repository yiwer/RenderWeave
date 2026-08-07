# RenderWeave Schema Studio — Page Override

> Overrides `../MASTER.md` for `/prototype/schema-studio` and the future Schema editor. The generated master misclassified this desktop developer tool as a hero-centric mobile SaaS surface; this page override is authoritative.

## Product question

Which desktop information architecture best supports one shared `EditorSession` across structured form and one-level tree/map editing, while keeping validation, references and AI evidence visible but subordinate?

## Approved visual direction

- Dense warm editorial workbench, light-only.
- Canvas `#faf9f5`; raised surface `#fffdf9`; soft surface `#f3eee6`; hairline `#e2dbd1`.
- Ink `#1c1b19`; body `#45413c`; muted text `#6d675f`.
- Accessible primary `#a9583e` with white text. Coral `#cc785c` is accent/highlight only and must not carry normal-size white text.
- Contextual code/compiled-preview panels use `#1b1a18`, never a global dark theme.
- Serif is limited to product mark and major page title; controls, tables and field data use a system humanist sans stack; JSON uses a monospace stack.
- No Anthropic marks, copied layouts, licensed fonts, gradients, glassmorphism, hero sections, decorative AI imagery or marketing cards.

## Layout constraints

- Full acceptance widths: 1280×720 and 1440×900.
- 1024×768 must retain every operation by collapsing the inspector into a drawer.
- Below 1024px shows an unsupported-width explanation; no mobile navigation.
- Fixed 56px product bar, 232–264px resource rail where used, flexible work area, 320–380px inspector where used.
- Prefer hairlines and surface color changes over shadows.
- Default density: 8px base grid; controls 36–40px high; interactive target remains at least 44px through padding/hit area.

## Interaction and accessibility

- Form and map dispatch the same reducer actions; switching modes never serializes/reloads state.
- Validate stable local rules on blur; server-only graph rules on explicit save.
- Every field has visible label, error text association and visible focus ring.
- No drag-only behavior: reorder buttons and complete form-mode alternatives are mandatory.
- Canvas has a form fallback; all core journeys are keyboard-complete.
- Error/success meaning uses icon and text, never color alone.
- Motion is 120–180ms and functional; honor `prefers-reduced-motion`.

## Prototype variants

- **A — Column Workbench:** resource rail + form-first center + persistent inspector.
- **B — Map Studio:** map-first center + selected-node inspector + bottom diagnostics.
- **C — Schema Ledger:** dense field ledger + inline expansion + dark compiled preview rail.

The prototype is throwaway. Record the chosen structure and delete/absorb losing variants before production implementation.
