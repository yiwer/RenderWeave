# T223 — Author visual elements with real image and font Assets

Type: task
Status: blocked
Blocked by: T222

## What to build

Add exact default factories, canvas projections and task-grouped property controls for the admitted visual leaf kinds:
Text, Image, Rect, Ellipse, Line, Polygon, Polyline, Path, QRCode and Barcode. `Shape` remains an authoring preset that
lowers to existing geometric nodes, never a new persisted kind. Use formal Path `commands[]`, not prototype `pathData`.

Connect Image and Text authoring to the existing Asset catalog/current/preview APIs. The Text UI presents a simple
`文本值` and font picker while preserving the formal Text Run contract; documents outside the supported single-Run
authoring subset remain lossless and explicitly non-editable rather than being flattened.

## Acceptance criteria

- [ ] Every formal visual leaf can be inserted by click or artboard drop with an admitted, author-meaningful default and
  can be edited, saved and reloaded without a prototype-only property shape.
- [ ] Image and font selection use ACTIVE real Assets, expose stale/deleted dependency feedback, and never persist a
  browser URL or font-family name in place of an AssetRef.
- [ ] Move/resize follows formal placement constraints; ellipse and other geometry fit the authored box at every size.
- [ ] QR/Barcode browser projections are labelled/treated as local authoring previews and make no certified-output claim.

## Test plan

- Default-factory/property-codec tests for each leaf kind and AssetRef edge cases.
- Component and real-route E2E for Text/font, Image/asset and representative geometry save/reload.
- Run affected `asset`, `template` and `web` coverage through the available gates; do not run `render` unless sources
  under that boundary change.

## Out of scope

- Rich-text/multi-Run editing, arbitrary SVG/XML, font upload flow and authoritative browser rendering parity.

## Resolution
