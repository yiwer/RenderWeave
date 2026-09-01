# Template v1 Candidate Preview validation (NOT_CERTIFIED)

Date: 2026-09-01

This validation answers one narrow question: can the current Template Editor save a visible authored change and
drive the real Evaluator plus exact native Renderer candidate to browser-verified PNG and JPEG output? It does not
certify a Renderer Profile or make Candidate Preview authoritative.

## Repeatable command

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run-template-candidate-preview-canary.ps1
```

The command builds the app and Web, starts fresh PostgreSQL/app/Web containers, mounts the exact T217 native
candidate, runs one real Playwright journey, captures payload-free evidence, and removes its containers and network.
The canonical command passed under `.sdlc/evidence/20260901-125059-template-candidate-preview`. After an independent
app/Web build, two additional repeatability runs used `-SkipBuild` and passed:

- `.sdlc/evidence/20260901-124732-template-candidate-preview`
- `.sdlc/evidence/20260901-124818-template-candidate-preview`

All three metadata records report `cleanupVerified: true`, `externalModelCallsAllowed: false`,
`formalCertificationIssued: false`, and assurance `NOT_CERTIFIED`.

## Demonstrated facts

- Real product URLs created a `system-empty@v1` Template, opened it, added a visible Rect, and explicitly saved
  revision 1. Playwright used no route interception or synthetic Template, save, Evaluation, or image response.
- The exact native executable
  `sha256:55cb098ff1022c6e3c94e940c4926be8fc6feddac29ca39376dd52e9f5bd392b` produced both formats through the real
  Evaluator, process Adapter, UDS protocol, and browser delivery path.
- Each fresh run produced the same 794 x 1123, 96-DPI results: PNG was 3,568,179 bytes with digest
  `sha-256=:eFPpG+WdlHYEsgxZoYCwaZA55VF2OPD/FeABNs2mKrU=:`; JPEG quality 90 was 28,584 bytes with digest
  `sha-256=:/0bZ2H2aSHnZmQMpVX8eFza8pK9ltDc1xRRjrMvU8iY=:`.
- The browser checked status, media type, byte length, digest, signatures, format, dimensions, DPI, quality, and
  Renderer/Layout/Output Profile metadata before displaying either image. Candidate UI and responses remained
  explicitly `NOT_CERTIFIED` and `no-store`.
- The normal Authoritative Preview remained closed in the same environment: HTTP 503,
  `RENDERER_UNAVAILABLE`, and no Candidate status header.
- The exercised desktop panel had zero serious/critical axe findings. Candidate pending feedback was observed and
  the formal failure summary received focus without relying on color.

## Rejected assumptions

- The previous 10/10/30/20 mm default Rect was not supported by the exact raster kernel at 96 DPI; it lowered to a
  non-pixel-aligned rectangle. The representative authored default is now a 25.4 mm square at 25.4 mm offsets, which
  lowers to exact 72 pt / 96 px geometry.
- Browser `load` was not a hermetic readiness signal because the page references external font stylesheets.
  The SPA canary now waits for `domcontentloaded` plus product landmarks instead.

## Deferred work

- Certified Profile registration, READY, physical-host certification, formal exact-output corpus issuance, public
  rollout, and production authorization remain explicitly unproven.
- This one Rect journey does not establish coverage for Text, assets, all node/layout variants, arbitrary geometry,
  mobile interaction, performance limits, or customer data.
- Removing the Web page's external font dependency is useful hermetic-build cleanup but did not block this local
  idea-validation loop.
