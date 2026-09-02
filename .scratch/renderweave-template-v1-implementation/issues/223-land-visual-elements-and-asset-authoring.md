# T223 — Author visual elements with real image and font Assets

Type: task
Status: done
Blocked by: T222 (done)

## What to build

Add exact default factories, canvas projections and task-grouped property controls for the admitted visual leaf kinds:
Text, Image, Rect, Ellipse, Line, Polygon, Polyline, Path, QRCode and Barcode. `Shape` remains an authoring preset that
lowers to existing geometric nodes, never a new persisted kind. Use formal Path `commands[]`, not prototype `pathData`.

Connect Image and Text authoring to the existing Asset catalog/current/preview APIs. The Text UI presents a simple
`文本值` and font picker while preserving the formal Text Run contract; documents outside the supported single-Run
authoring subset remain lossless and explicitly non-editable rather than being flattened.

## Acceptance criteria

- [x] Every formal visual leaf can be inserted by click or artboard drop with an admitted, author-meaningful default and
  can be edited, saved and reloaded without a prototype-only property shape.
- [x] Image and font selection use ACTIVE real Assets, expose stale/deleted dependency feedback, and never persist a
  browser URL or font-family name in place of an AssetRef.
- [x] Move/resize follows formal placement constraints; ellipse and other geometry fit the authored box at every size.
- [x] QR/Barcode browser projections are labelled/treated as local authoring previews and make no certified-output claim.

## Test plan

- Default-factory/property-codec tests for each leaf kind and AssetRef edge cases.
- Component and real-route E2E for Text/font, Image/asset and representative geometry save/reload.
- Run affected `asset`, `template` and `web` coverage through the available gates; do not run `render` unless sources
  under that boundary change.

## Out of scope

- Rich-text/multi-Run editing, arbitrary SVG/XML, font upload flow and authoritative browser rendering parity.

## Resolution

The production Template editor now authors every admitted visual leaf: Text, Image, Rect, Ellipse, Line, Polygon,
Polyline, formal `commands[]` Path, QRCode and Barcode. Shape is an editor preset lowered to Polygon rather than a new
wire kind. The grouped Inspector exposes only the supported single-Run Text subset as one text value and preserves
multi-Run documents losslessly as non-editable; click and artboard drop share the same default/command seam.

Image and font selection use the real ACTIVE Asset catalog, current and preview APIs. Only canonical AssetRefs enter
DesignDSL; browser object URLs and font-family names remain disposable projection state. Missing/deleted references stay
authored with explicit feedback. A persisted STALE Template dependency snapshot is shown separately from the current
Asset lifecycle and alongside the result of this editor session's authoritative readiness recheck.

Canvas projection now follows the frozen geometry rules needed by this slice: QR resize and semantic geometry remain
strictly square, an imported rectangular QR shows an invalid-layout placeholder instead of a stretched code, free
vectors map their geometry bounds while preserving authored physical-mm stroke and visible paint overflow, and
Rect/Ellipse preserve exact inward stroke width. Rect corner radii use one CSS common normalization factor. QR and
Barcode remain visibly labelled local, non-certified authoring previews.

The Template roundtrip journey now covers all visual leaves, real Image/FONT creation and selection, exact save/reload,
and zero browser errors through production routes. During candidate integration it passed 3/3 against fresh local
PostgreSQL and the real S3 Asset adapter backed by MinIO, without request interception; evidence is
`.sdlc/evidence/t223-visual-runner/template-roundtrip-journey`. Fixed-point review replaced the temporary local MinIO
bootstrap with the ADR-0043 path: a pinned owned MinIO container, bucket provisioning through `mc`, and cleanup limited
to that captured container. PowerShell parsing and forbidden local-executable/SigV4 scans passed. The local Docker daemon
did not answer a final runtime probe, so that revised bootstrap itself was not re-executed; the already-passed real-route
product journey and final component/gate coverage remain valid, with this environment-specific runner check recorded as
the residual risk.

Verification on the final code fixed point `14012353`:

- Standards and Spec reviews both passed with no hard blocker. Review regressions cover strict-square QR interaction and
  invalid legacy geometry, authored free-vector and inward primitive strokes, CSS radius normalization, and correctly
  attributed STALE/DELETED Asset feedback.
- Clean Node 24 `-Gate web` passed OpenAPI regeneration, typecheck, repository lint, 53 files / 513 tests and production
  build (2181 modules). Evidence:
  `.scratch/worktrees/t223-final-14012353-web/.sdlc/evidence/20260903-033103-web`.
- Clean `-Gate asset` passed 99/99 Maven tests, Asset kernel Java/Python 41/41 each and capacity Java/Python 12/12 each.
  Evidence: `.scratch/worktrees/t223-final-14012353-asset/.sdlc/evidence/20260903-033129-asset`.
- The same clean fixed point passed the Template portion of `-Gate template`: Schema 20/20, Template 194/194, kernel
  Java/Python 211/211 each, focused AssetRef 1/1 and independent AssetRef replay 3/3. The composite then stopped at the
  pre-existing Renderer tricky-font `INPUT_BINDING: renderer/process-manifest.json` failure (5 mutation tests, 1 pass / 4
  fail). The identical four failures reproduce directly at ticket base `e7bfd9bd`, and this ticket changes none of the
  renderer manifest/decision/verifier inputs. Evidence:
  `.scratch/worktrees/t223-final-14012353-asset/.sdlc/evidence/20260903-033410-template`.

T224 is now the frontier for exact StaticSchema projection, DesignDSL definitions and property bindings. Containers,
Repeat/Conditional/TemplateUse, RenderServer and arbitrary SVG/XML remain outside T223.
