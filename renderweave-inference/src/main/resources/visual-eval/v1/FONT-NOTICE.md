# Visual evaluation font notice

`RenderWeaveVisualEval.ttf` is a character subset made solely for the versioned synthetic visual
evaluation corpus. Its source is Google Fonts `NotoSansSC[wght].ttf` at:

`https://github.com/google/fonts/blob/main/ofl/notosanssc/NotoSansSC%5Bwght%5D.ttf`

- Source SHA-256: `a3041811a78c361b1de50f953c805e0244951c21c5bd412f7232ef0d899af0da`
- Subset SHA-256: `13640fa00ef05d468983c9d680fbd291c1fcce6093f99d4bb0072ecd01251514`
- Source license: SIL Open Font License 1.1; the exact upstream notice is preserved in `OFL.txt`.
- Subset input characters: the complete UTF-8 text of `scenes.json`.
- Tool: fonttools `pyftsubset` 4.63.0 with all layout features, names, legacy/symbol cmaps,
  recommended/notdef glyphs and no hinting.

The renamed subset is an evaluation asset, not a product font or branding claim. Rebuilding it is an
explicit corpus/toolchain change and therefore changes the repository evaluation identity.
