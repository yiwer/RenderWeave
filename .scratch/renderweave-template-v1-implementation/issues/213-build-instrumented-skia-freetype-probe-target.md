# T213 — Build instrumented Skia/FreeType probe target

Type: task
Status: done
Blocked by: T216 (done)

## What to build

Consume T212's verified offline closure to perform the first exact Linux x86-64-v2 Skia/FreeType build rehearsal. Apply the frozen patches and custom headers in their declared order, build an instrumented probe target, and use the T211 synthetic tricky font to prove the classifier, glyph-load flags, symbol boundary, ELF closure, and ISA contract.

Docker and WSL execution of this non-authenticated exact build rehearsal is authorized by the user on 2026-08-31. The result remains rehearsal evidence only and must not be represented as renderer certification or physical-machine conformance.

## Scope

- Hermetic configure/build entry point that consumes only the T212 offline bundle.
- Frozen Skia/FreeType patch and custom-header application with exact pre/post-image checks.
- An instrumented probe executable covering tricky-font classification and glyph loading.
- ELF dependency, exported-symbol, forbidden-symbol, and x86-64-v2 ISA audits.
- Reproducible command and evidence manifest for Docker/WSL rehearsal.

## Acceptance criteria

- Build succeeds with networking disabled and without host font, graphics, codec, or shaping libraries.
- Patch/header application order and resulting bytes match the frozen authority.
- The tricky font is classified as expected and every observed glyph load carries the required fail-closed flags.
- ELF closure and symbol audits show only the allowed runtime boundary; forbidden hinting/interpreter paths are absent or unreachable as required by the probe contract.
- A repeated clean rehearsal produces the same declared build identities, or any permitted nondeterminism is explicitly bounded and excluded from identity.

## Test plan

- Negative configure/build tests for missing offline inputs and forbidden host fallbacks.
- Probe tests for classifier result, load flags, custom CFF behavior, symbols, ELF dependencies, and ISA instructions.
- Two clean network-disabled Docker/WSL rehearsal runs from the verified T212 bundle.
- Run affected renderer and Template gates.

## Out of scope

- Renderer certification or Profile registration.
- Production Text node rendering.
- Physical x86-64-v2 host certification.
- Network access during configure, compile, link, or probe execution.

## Resolution

- The exact candidate v2 application order was attempted first and exposed two source-authority defects: its custom `ftoption.h` resolves the requested stock include back to itself, and its guarded `ftmodule.h` cannot satisfy FreeType's required second macro expansion. The frozen candidate therefore cannot meet this ticket's exact successful-build criterion.
- Added a reusable `prepare | build | probe | audit | rehearse` Linux harness plus `tools/run-renderer-exact-build-rehearsal.ps1`, which binds the exact OCI image, T212 bundle, empty named volume, disabled network, 4 CPUs, 8 GiB memory, and PID limit. It also provides actual missing-input and host-FreeType-fallback negative build modes.
- To isolate the defects, built `rw-renderer-t213-adapter-rehearsal-000001` with two explicit adapters while leaving every frozen candidate byte unchanged. The result is labelled `ADAPTER_REHEARSAL_PASSED_EXACT_CANDIDATE_BUILD_BLOCKED`; it is not candidate v2 build evidence, certification, physical replay, or READY evidence.
- Two clean adapter rehearsals from independent volumes produced byte-identical manifests, probe JSON, and binaries. Source tree is `sha256:125d2d782dd2c0b0898f89a4646fa09e0281faa63382a8475621e5ebf59143da`; probe JSON is `sha256:f2b633ef467e85fd9b85955826f1fc20453ab5fcc210ffe71b4921b2b4029210`; 2,729,472-byte binary is `sha256:0508c755f90daf3cbdaa686cec4e619c5be851bcf4921a4137dc5c9992556c17`; manifest is `sha256:5eff44eafb36e91aa3a73a853c3244db55efad13566f1dbe3736ec2dc5fbc4e0`.
- The positive control executed the TrueType interpreter three times. The required direct path and both actual Skia paths observed glyph loads with zero invalid loads and zero interpreter calls; the tricky path classified both opened faces as tricky and the CFF path classified none.
- The manifest closes all ten direct Skia/FreeType `FT_Load_Glyph` source call sites, exact dynamic exports, allowed OCI runtime dependencies, required/forbidden modules and symbols, and full x86-64-v2 disassembly. No forbidden symbol, runtime dispatch symbol, VEX/EVEX instruction, or v3/v4 instruction was observed.
- Focused gate: `powershell -ExecutionPolicy Bypass -File tools/run-renderer-exact-build-rehearsal-gate.ps1` passed 9 tests. Negative Docker modes rejected missing input and host fallback. T212 closure gate passed 23 inputs/2,248,288 checks; `run-gate.ps1 -Gate template` passed Template kernel/static replay and the 785-check tricky-font compatibility gate.
- T216 issued immutable candidate `rw-renderer-spike-linux-x86_64-v2-000003`, correcting only the two mechanical source defects. T213 then removed both adapters and deleted the obsolete repeat-include shim instead of carrying it into the exact target.
- Added compact successor lock `hermetic-build-lock-v2.json`: it binds the exact v1 lock, replaces only the renderer input and policy trees, and adds the previously ambient CFF fixture. The staged 24-input bundle independently verified with the source repository absent (`2,249,404` checks; inventory `sha256:001ad36fbc1d4051556207fc455d4e0fc7e04d7518b14be155542c6493cc3981`).
- Two clean, network-disabled candidate-v3 rehearsals in independent Docker volumes completed without adapters. Their 2,729,488-byte binaries (`sha256:4d1aba604c16cb1cd86030941b1d31740cc7ecc3e6bea285fe0858dacdb3a6ed`), probe JSON (`sha256:c5697daf54942c7c4f83cbe1846a2cc72e9c21909c7755266efc199b9514d197`), manifests (`sha256:a4219706b7911d478f194b2513d184d8d3f4a1ebd474c1e0e84150d6a8fc038d`), and source-tree identities were byte-identical across runs.
- The exact probe retained the positive bytecode-interpreter control, observed zero interpreter calls and zero invalid glyph loads on required direct, tricky Skia, and CFF Skia paths, and closed all ten static Skia/FreeType glyph-load call sites plus ELF dependency/export/symbol and full x86-64-v2 ISA audits.
- The missing-input Docker negative failed closed, and a forbidden system-FreeType configuration was rejected before build by the exact GN-args identity guard. Focused gates passed 11/11 rehearsal tests and 14/14 hermetic-stager tests. Compact exact evidence is `renderer/probes/t213/rehearsal-result-v2.json`; full manifests and binaries remain local build evidence.
- This remains a virtualized non-authenticated rehearsal: it does not certify a renderer, authorize READY or exact-output record issuance, complete a physical-machine replay, or close Ticket 19. T214 is now unblocked.
