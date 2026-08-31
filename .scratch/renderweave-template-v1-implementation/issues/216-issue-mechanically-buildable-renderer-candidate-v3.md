# T216 — Issue mechanically buildable Renderer candidate v3

Type: task
Status: done
Blocked by: none
Diagnostic input: T213 commit `d05ca309` (completed blocker discovery)

## What to build

Issue immutable candidate `rw-renderer-spike-linux-x86_64-v2-000003` as the append-only successor to
candidate `000002`. Correct only the two mechanical FreeType configuration defects found by T213:

- install the custom options header inside the exact FreeType source include tree and include the stock
  `ftoption.h` through a non-shadowing relative path;
- provide the custom module list as a deliberately repeatable `FT_USE_MODULE` expansion file with no include
  guard, matching FreeType's two-pass `ftinit.c` protocol.

## Scope and authority

- Preserve every v1/v2 candidate, decision, header and application-order byte exactly.
- Append a v3 semantic correction, candidate, source-target, prerequisites, application order, compatibility
  decision and supersession record.
- Reuse the exact Skia/FreeType revisions, downstream Skia patch, fixture bytes and runtime no-hinting policy.
- Verify through the public tricky-font compatibility verifier/gate, including mutations for options-header
  recursion, module-header guarding, wrong install paths and lifecycle overclaims.
- This ticket establishes corrected source/configuration authority only. T213 owns the successor offline closure,
  full exact build, instrumentation, ELF/ISA audit and reproducibility evidence.

## Acceptance criteria

- [x] Candidate `000001` and `000002` immutable artifacts retain their committed SHA-256 and byte lengths.
- [x] Candidate `000003` binds both corrected headers and exact application order with no T213 adapter seam.
- [x] The options header reaches the exact stock header without resolving to itself.
- [x] The modules header is guard-free and expands the exact six retained modules on every inclusion.
- [x] The public verifier and mutation suite reject recursion, an include guard, stale bindings and lifecycle
      overclaims; the compatibility and Template gates pass.
- [x] No build/certification/READY/Profile/Renderer Exact Output claim is made.

## Test plan

- Add the v3 public-verifier contract first and capture RED while the successor artifacts are absent.
- Add one focused mutation at a time for the two discovered mechanical failures and binding drift.
- Run the v1/v2/v3 verifier suites, focused compatibility gate and affected Template static gate.
- Commit locally, run fixed-point `code-review` against `d05ca309`, amend blocking findings, then mark done.

## Out of scope

- No network download, paid/live AI, real data, production operation, physical-host certification, Profile
  registration, push, tag or pull request.

## Resolution

Done. Issued immutable candidate `rw-renderer-spike-linux-x86_64-v2-000003` as
`sha256:189dd522a8ae62ddf8c29838f23c393a396dd6ae761298a0b84467b80d619f8d` (4,592 bytes), with
append-only v2 supersession registry and compatibility decision
`sha256:9156388f7a32d955445073b951434600b0d9e43c7b3e78b6a85de888c022c803`.

- The options header uses the non-shadowing quoted relative stock include; the module list is guard-free and
  contains exactly six repeatable `FT_USE_MODULE` entries.
- The v3 Skia patch applies to T212's exact preimage and records the real LF `BUILD.gn` postimage prefix
  `cf9576bf`; the application order contains no T213 adapter.
- Focused replay passed 26 golden/mutation tests and 1,656 compatibility checks, including synchronized semantic
  mutations and an intentionally stale SHA binding. The full Template gate passed 185 Java tests, 211/211
  independent kernel cases, Editor 38/21,867 checks, Registry 24,519/24,427 checks and `authorityDiff=0`.
- Fixed-point review findings were closed: the ticket dependency cycle and retired evidence grade were removed,
  binding-refresh test logic was deduplicated, and stale-binding coverage was added.
- Exact build observation, runtime-bytecode proof, physical replay, issuance, certification, READY and Ticket 19
  closure remain false. T213 owns the successor closure and exact build.
