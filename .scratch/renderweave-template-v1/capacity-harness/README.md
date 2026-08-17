# PROTOTYPE — Template v1 capacity measurement harness

This throwaway, non-product harness answers one question: whether a candidate
single-request working set and coarse operation envelope are internally
plausible inside the pinned 4-vCPU/8-GiB virtualized Linux target.

It does **not** implement DesignDSL, Evaluation, layout, shaping, rasterization,
PNG/JPEG profiles, cancellation, fetch, or any product API. Its timing cannot
certify READY and its byte loops are not exact Renderer algorithms.

Compile and run only in the pinned image recorded in
[`../capacity-calibration.md`](../capacity-calibration.md), with networking
disabled and the declared cgroup limits. The command and raw JSON output must
be captured in the calibration record before using the result as A1 planning
evidence.

Arguments, in order:

1. surface pixels;
2. decoded-cache bytes;
3. raw-cache bytes;
4. encoded-buffer bytes;
5. paint passes;
6. synthetic layout operations;
7. synthetic glyph operations.

The candidate run intentionally commits every allocated page, walks every
decoded pixel, paints every surface pixel for each pass, copies the full
encoded buffer, and reports Linux `VmHWM`. These loops provide a conservative
planning signal, not a semantic or codec benchmark.
