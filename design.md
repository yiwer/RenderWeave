# Design — RenderWeave Web · Hum variant

A locked design system for the RenderWeave web workbench (`web/`), **Hum
variant**. This file forks the editorial system (`redesign/web-ui-ux`
worktree) and re-themes the app with Hallmark's catalog theme **Hum** at
explicit user request. It deliberately deviates from the approved direction
in `design-system/renderweave/pages/schema-editor.md` (serif display,
terracotta, ≤ 5% accent) — if the variant is rejected, that worktree's
`design.md` remains the compliant baseline.

## Genre

playful — warm, alive, smart-but-casual. Cream paper, multi-accent palette,
rounded sans, generous radii, spring motion. The room is warm and someone
smart is smiling — but this is still a dense pro tool: Hum is applied at the
"quiet" calibration (Brilliant cream + pear, not Duolingo saturation).

## Macrostructure family

- Marketing pages: none. This app has no marketing surface; do not invent one.
- App pages: **Workbench** — 56px product chrome + 232–264px resource rail +
  flexible workspace + optional 320–380px inspector (drawer below 1180px).
  Identical shape to the editorial variant; Hum changes the visual/interaction
  layer only.

## Theme

Multi-accent. No single accent dominates — each accent owns its own type of
surface. Never gradients between accents; never pure white paper; never pure
black ink.

- `--color-canvas`        oklch(96.9% 0.012 96)  <!-- #f7f5ec warm cream, rgb(247 245 236) -->
- `--color-surface`       oklch(98.8% 0.007 95) <!-- raised cream, never pure white -->
- `--color-surface-soft`  oklch(94.5% 0.014 95)  <!-- deeper band -->
- `--color-surface-strong` oklch(91.5% 0.018 95) <!-- hover -->
- `--color-ink`           oklch(20% 0.012 250)   <!-- near-black, cool tilt -->
- `--color-body`          oklch(37% 0.015 250)   <!-- 9.6:1 on cream -->
- `--color-muted`         oklch(50% 0.018 250)   <!-- #5c646d, ≥4.5 on all surfaces -->
- `--color-hairline`      oklch(88% 0.016 95)
- `--color-hairline-strong` oklch(82% 0.020 95)

The paper is deliberately warm-neutral: a green-tinted ground sat too close
to the spring accent and drained the page's sharpness. On the warm cream the
green reads crisp at every tint step.

Accent ownership (three-rule):

- **Spring green** `--color-accent` oklch(80% 0.16 150) — the brand anchor
  `rgb(102 218 133)`, primary ACTION face only, always with `--color-ink`
  text (10.3:1). Never green-accent text on cream.
  Deep supporting tokens: `--color-accent-deep` 64/0.15/150 (edges, borders),
  `--color-accent-ink` 42/0.09/150 (accent-coloured text on tints),
  `--color-accent-soft` 93.5/0.045/150 · `--color-accent-wash` 97.5/0.02/150.
- **Cyan** `--color-cyan` oklch(66% 0.18 235) — links / info family.
  Text links use `--color-cyan-ink` oklch(48% 0.13 235) (≥4.5 everywhere).
- **Coral-red** `--color-coral` oklch(68% 0.24 18) — the single pop moment:
  required-field dots, error family hue. Sparingly.
- Success family deliberately shifted to teal-green hue 168 (deep forest
  register) so status greens never read as the bright spring action green.
- `--color-lavender` oklch(74% 0.16 305) — reference-type identity dot.
- `--color-focus` oklch(52% 0.14 235) — focus ring, 4.7:1 on cream.

Status hues keep the icon + text discipline (never colour alone); each has
`-ink` (text), `-soft` (background), `-border` variants in `:root`. Error is
oklch(52% 0.20 18) — 4.9:1 on `--color-error-soft`, 5.6:1 on cream.

Dark panels survive only for code/compiled-preview surfaces, re-hued to the
ink family (`--color-dark` oklch(23% 0.014 250), `--color-dark-ink` oklch(78%
0.03 250) for secondary on-dark text). Never a global dark theme.

## Typography

Two webfonts (Google Fonts, variable 400..800) + system fallbacks. **No serif
anywhere** — if a serif sneaks in, the theme is misapplied.

- Display + body: **Plus Jakarta Sans** (rounded humanist, closed apertures),
  fallbacks `"Geist", ui-rounded, system-ui, sans-serif`. Display weight 600,
  tracking -0.02em; body 400 with 500/600 for emphasis.
- Mono: **JetBrains Mono**, fallbacks `ui-monospace, "SFMono-Regular",
  Consolas, monospace` — schemaKey, fieldKey, DSL/JSON, metrics, UPPERCASE
  micro-labels.
- CJK note: neither webfont carries CJK glyphs; Chinese text falls back to the
  system CJK sans. That is expected — the stacks govern Latin/numeric voice.
- Headings are always roman; emphasis via weight or accent colour.

## Spacing

8px base grid with 4px half-steps — identical tokens to the editorial
variant. Controls 36–40px high; pointer targets ≥ 44px through padding.

## Radii — big rounded everything

No square corners. `--radius-sm 10px · --radius-md 14px · --radius-lg 20px ·
--radius-input 12px · --radius-pill 999px`. Buttons and chips are pills;
cards/panels 20px; inputs 12px. This is the single most visible Hum signal.

## Motion

The loudest stack allowed in the app — but spatial motion stays
transform/opacity only.

- Durations: `--dur-press 70ms · --dur-fast 140ms · --dur-med 180ms ·
  --dur-slow 220ms`.
- Easings: `--ease-spring cubic-bezier(0.34,1.56,0.64,1)` (card lifts),
  `--ease-snap cubic-bezier(0.22,1,0.36,1)` (arrivals), plus the standard
  `--ease-out` / `--ease-in-out` fallbacks. Spring is reserved for cards and
  primary moments — never on every control.
- Entrance: first-paint stagger on `.draft-schema-card` / `.static-card` /
  `.field-row` — `rw-card-in` (12px rise + fade, 220ms snap, 45ms per-item
  delay capped at 315ms). Once per mount, never on scroll.
- Status pulse: the dirty chip carries a 3.2s pulsing dot — the one
  informational "character mark"; no mascot.
- `prefers-reduced-motion: reduce` collapses everything to ≤ 150ms opacity
  crossfade; the button press loses its travel, spinners freeze.

## Microinteractions stance

- **The press is the feedback** (Hum signature #1): `.primary-button` is a
  push button — pear face, ink text, `0 4px 0 0 var(--btn-edge)` hard edge +
  soft cast shadow. Hover lifts 2px (edge grows to 6px), `:active` presses
  DOWN 3px (edge shrinks to 1px, 70ms). Never `scale()`, never inset-shadow
  presses.
- Ghost buttons: soft variant — surface fill, hairline border, 2px lift on
  hover, 1px press.
- Cards: spring lift 4px + `--shadow-card` brighten + accent-wash tint deepen
  + accent-border on hover (220ms `--ease-spring`) — Hum's color-shift card
  move. Dense rows keep the quiet surface-shift hover (field rows also take
  the accent-wash tint).
- Links: breadcrumb/nav links underline-slide — a 1.5px `currentColor` band
  grows 0 → 100% on hover (180ms snap), never `text-decoration` pop-in.
- Rail links nudge 2px right on hover with the spring easing.
- Big honest counters (history summary, review progress) carry the Hum
  highlighter band — `background-image` at 88% baseline, 0.14em thick,
  accent at 45%. Numbers stay real; the band is the only flourish.
- Silent success — inline status chips/banners, never celebratory toasts.
- Focus-visible: 2px `--color-focus` ring, offset 2px, shown instantly.
- Native form controls keep one custom voice: selects carry the muted-ink
  chevron + accent-wash hover; checkboxes are 16px everywhere (no per-context
  resizing), hairline-strong border, accent fill + ink check when checked,
  `scale(0.88)` spring press on `:active`. Segmented controls stay slim
  (34px) with surface-fill active pills.
- Character moment (Hum signature #5) is the sanctioned omission — this is a
  product workbench, no mascot. Star-burst micro-celebration (#7) is not
  wired into app JS.

## CTA voice

- Primary: spring-green push button (above), pill radius, 38px height,
  weight 600, sentence-case Chinese verb + object (e.g. 创建 Draft).
- Secondary: ghost soft button (above).
- Danger: `--color-error` outline voice + confirm dialog (existing Radix
  pattern), never inline-nuke buttons alone.
- Icon buttons: 28–32px square, ghost until hover, `aria-label` mandatory.

## Per-page allowances

- All pages are app pages: **no enrichment** — no illustration, CSS art,
  video, or decorative imagery. Function carries the page.
- Dark surfaces only for code/compiled-preview panels (`--color-dark`).
  On dark panels the text hierarchy is `--color-surface-soft` (primary) →
  `--color-dark-ink` (secondary/muted); `--color-muted` and `--color-body`
  are paper-only and fail WCAG there.
- Prototype routes (`/prototype/*`) are throwaway and inherit the shared
  tokens. `/prototype/template-designer` is the one research exception: it
  may use a **professional Studio calibration** (48px compact chrome, 4–8px
  tool radii, square segmented controls, hairline panel boundaries and a
  neutral canvas field) to compare editor information architectures. It must
  keep the Hum font stacks and semantic colour ownership, reserve spring green
  for action/selection, and avoid card-in-card, universal pills, hover lifts
  and explanatory copy as permanent chrome. This allowance does not change
  product pages and expires with the throwaway prototype.
- Inference run pages are strictly split: the monitor page owns the execution
  log (timeline main column + telemetry rail at ≥1260px, stacked below it
  otherwise); the review page owns Candidate proofreading only, with core
  actions (cancel, atomic-create) lifted into the chrome actions at top right.
  The DraftSchema bundle switcher is a browser-tab strip merged into the
  workspace surface below (index · name · key · field count · status), with
  HTML5 drag-to-reorder plus Ctrl+←/→ keyboard parity, never a cramped side
  rail or arrow-button column. Review status is compressed into a single
  status line (progress + gate check chips + autosave + truncated run facts);
  the global diagnostics summary is a one-line chip row.

## What pages MUST share

- The RW weave-mark + Plus Jakarta Sans 700 "RenderWeave" product mark,
  linking to /schemas.
- The accent ownership three-rule (pear = action, cyan = link, coral = pop).
- The font stacks as scoped above; no serif anywhere.
- The push-button CTA voice (pear face, hard edge, press physics).
- The single dropdown treatment: every former `<select>` is the shared
  `SelectField` listbox (`web/src/components/SelectField.tsx`) — token-styled
  trigger + portalled panel, focus retained on the trigger with
  `aria-activedescendant`, reposition-on-scroll (never close-on-scroll).
- Chrome pattern: 56px product bar, breadcrumb or context text center-left,
  actions right; skip-link + `#main-content` landmark.
- Page heading rhythm: `resource-page-heading` — Jakarta 600 h1 + muted
  description line, `--space-xl` below-title gap before first section.

## What pages MAY differ on

- Rail content and inspector presence (studio keeps its own rail facts).
- Workspace internal layout (card grids vs three-column editor vs ledger).
- Status/fact tile composition per page's domain facts.

## Exports

The canonical token list lives in `web/src/styles.css` `:root` and is the
single source consumed by the app. `tokens.css` in `.hallmark/` mirrors it
for portability. Tailwind v4 `@theme` / DTCG / shadcn exports are not
applicable — the app authors plain CSS with Tailwind preflight only and uses
no utility-theme or ui-library token bridge.

## Evidence

- `npm run build` ✓ · `npx playwright test` 19 passed / 1 skipped ✓
  (includes axe-core WCAG 2 AA contrast gate on the studio + inference flows)
- Visual captures: `.scratch/shots/hum-*.png`
- Fonts verified loaded at runtime: Plus Jakarta Sans 400..800, JetBrains
  Mono 400..800 (`document.fonts`)
