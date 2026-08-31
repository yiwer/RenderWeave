#!/bin/sh
set -eu

readonly RW_BUNDLE="${RW_BUNDLE:-/bundle}"
readonly RW_WORK="${RW_WORK:-/work}"
readonly RW_HARNESS="$RW_BUNDLE/inputs/downstream-policy/repo-seam/tools/renderer-exact-build-rehearsal.sh"
readonly RW_SKIA_COMMIT="93458b5faf9cd8befbe6c93158d4d8ee7c8424ee"
readonly RW_FREETYPE_COMMIT="0a0221a1347e2f1e07c395263540026e9a0aa7c7"
readonly RW_OUT="$RW_WORK/src/skia/out/T213"
readonly RW_T213="$RW_WORK/t213"
readonly RW_EVIDENCE="$RW_WORK/evidence"
readonly RW_COMMANDS="$RW_EVIDENCE/commands"
readonly RW_BINARY="$RW_WORK/output/renderweave-t213-probe"
readonly RW_ARGS_SHA256="5cc1b1db22c8baa2efafc3eafa1cbcce50252ad198ffb0bcaba3cc6cbf4ef331"
readonly RW_BOOTSTRAP_PYTHON="/usr/local/bin/python3"

fail() {
    printf '%s\n' "T213_REHEARSAL_FAILED: $*" >&2
    exit 1
}

stream_xz() {
    archive="$1"
    [ -x "$RW_BOOTSTRAP_PYTHON" ] || fail "pinned OCI Python runtime is absent"
    "$RW_BOOTSTRAP_PYTHON" - "$archive" <<'PY'
import lzma
import shutil
import sys

with lzma.open(sys.argv[1], "rb") as source:
    shutil.copyfileobj(source, sys.stdout.buffer, length=1024 * 1024)
PY
}

extract_zip() {
    archive="$1"
    destination="$2"
    [ -x "$RW_BOOTSTRAP_PYTHON" ] || fail "pinned OCI Python runtime is absent"
    "$RW_BOOTSTRAP_PYTHON" - "$archive" "$destination" <<'PY'
import pathlib
import stat
import sys
import zipfile

destination = pathlib.Path(sys.argv[2]).resolve()
with zipfile.ZipFile(sys.argv[1]) as archive:
    for member in archive.infolist():
        path = pathlib.PurePosixPath(member.filename.replace("\\", "/"))
        if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
            raise ValueError(f"unsafe ZIP member: {member.filename}")
        if stat.S_ISLNK(member.external_attr >> 16):
            raise ValueError(f"ZIP symlink is forbidden: {member.filename}")
        target = destination.joinpath(*path.parts).resolve()
        if not target.is_relative_to(destination):
            raise ValueError(f"ZIP member escapes destination: {member.filename}")
    archive.extractall(destination)
PY
}

require_sha256() {
    expected="$1"
    path="$2"
    [ -f "$path" ] || fail "missing input: $path"
    actual="$(sha256sum "$path" | awk '{print $1}')"
    [ "$actual" = "$expected" ] || fail "sha256 mismatch: $path: $actual"
}

require_file() {
    [ -f "$1" ] || fail "missing input: $1"
}

require_empty_work() {
    [ -d "$RW_WORK" ] || fail "work root is absent: $RW_WORK"
    [ -z "$(find "$RW_WORK" -mindepth 1 -maxdepth 1 -print -quit)" ] ||
        fail "work root must be empty: $RW_WORK"
}

require_prepared() {
    [ -f "$RW_WORK/src/skia/src/ports/SkFontHost_FreeType.cpp" ] ||
        fail "exact source tree is not prepared"
    [ -x "$RW_WORK/toolchain/bin/clang++" ] || fail "exact toolchain is not prepared"
}

exact_env() {
    env -i \
        HOME="$RW_WORK/home" \
        LANG=C.UTF-8 \
        LC_ALL=C.UTF-8 \
        PATH="$RW_WORK/toolchain/bin:$RW_WORK/tools/gn:$RW_WORK/tools/ninja:/usr/local/bin:/usr/bin:/bin" \
        SOURCE_DATE_EPOCH=0 \
        TMPDIR="$RW_WORK/tmp" \
        TZ=UTC \
        "$@"
}

prepare() {
    require_empty_work

    skia_archive="$RW_BUNDLE/inputs/skia/skia-$RW_SKIA_COMMIT.tar"
    freetype_archive="$RW_BUNDLE/inputs/freetype/freetype-$RW_FREETYPE_COMMIT.tar"
    policy_archive="$RW_BUNDLE/inputs/downstream-policy/renderer-spike-policy-normalized.tar"
    renderer_archive="$RW_BUNDLE/inputs/downstream-policy/renderer-root-normalized.tar"
    cff_source="$RW_BUNDLE/inputs/downstream-policy/minimal-cff.otf"
    llvm_archive="$RW_BUNDLE/inputs/toolchain-sysroot/LLVM-22.1.8-Linux-X64.tar.xz"
    gn_archive="$RW_BUNDLE/inputs/build-tools/gn-linux-amd64-ajv8U9gl.zip"
    ninja_archive="$RW_BUNDLE/inputs/build-tools/ninja-linux-amd64-Px8cwPaa.zip"

    require_sha256 9dfc76b78fc6363e77f96b4faca566cfa0c28d06ad478f809f241e98966652af "$skia_archive"
    require_sha256 96b87b165f22e65edbba96409e3853fcbdccc290721a1ad54baa56ba710115cc "$freetype_archive"
    require_sha256 52178785eee689fcbbc42bf92db0d26665ea325b41003eaf73bd1930eee4a578 "$policy_archive"
    require_sha256 d1d577caff82a5df9a55e079098a84bf0decc6026112af50742f06c534694756 "$renderer_archive"
    require_sha256 eeef766ac75aecac694bbd82fbb3cd2b9a315075db14d91ff0cbe1bdec20f77f "$cff_source"
    require_sha256 df0e1ecf16caf3489a272a5eea4eec9b0d82878f6477fa309504f918a0006384 "$llvm_archive"
    require_sha256 6a3bfc53d825bccac5e5b4b7bdcc10ce9396a04a689ac684ec3308ca5c7f3d9c "$gn_archive"
    require_sha256 3f1f1cc0f69a1bcfdf67fb6c2bc7419b1dd812bb8d0e79afa9bfa8a3553b5082 "$ninja_archive"

    mkdir -p "$RW_WORK/src/skia" "$RW_WORK/policy" "$RW_WORK/renderer-input" "$RW_WORK/toolchain" \
        "$RW_WORK/tools/gn" "$RW_WORK/tools/ninja" "$RW_WORK/home" "$RW_WORK/tmp" \
        "$RW_T213/fixtures" "$RW_EVIDENCE" "$RW_COMMANDS" "$RW_WORK/output"
    chmod 0700 "$RW_WORK/home" "$RW_WORK/tmp"

    tar --no-same-owner -xf "$skia_archive" -C "$RW_WORK/src/skia" --strip-components=1
    freetype_root="$RW_WORK/src/skia/third_party/externals/freetype"
    mkdir -p "$freetype_root"
    [ -z "$(find "$freetype_root" -mindepth 1 -print -quit)" ] ||
        fail "Skia archive contains an ambient FreeType tree"
    tar --no-same-owner -xf "$freetype_archive" -C "$freetype_root" --strip-components=1
    tar --no-same-owner -xf "$policy_archive" -C "$RW_WORK/policy"
    tar --no-same-owner -xf "$renderer_archive" -C "$RW_WORK/renderer-input"
    stream_xz "$llvm_archive" |
        tar --no-same-owner -xf - -C "$RW_WORK/toolchain" --strip-components=1
    extract_zip "$gn_archive" "$RW_WORK/tools/gn"
    extract_zip "$ninja_archive" "$RW_WORK/tools/ninja"
    chmod 0555 "$RW_WORK/tools/gn/gn" "$RW_WORK/tools/ninja/ninja"

    patch_file="$RW_WORK/policy/renderer-spike/skia-m151-freetype-policy-v3.patch"
    ftoption="$RW_WORK/policy/renderer-spike/rw-freetype-ftoption-v3.h"
    ftmodule="$RW_WORK/policy/renderer-spike/rw-freetype-ftmodule-v3.h"
    fixture="$RW_WORK/policy/renderer-spike/tricky-font-fixture-v1/renderweave-cpop-fixture-v1.ttf"
    require_sha256 9fb58e637b9793149c108ac4cf97f04f71c29a22238a86ee6dd4b03cf0e7db52 "$patch_file"
    require_sha256 0dda07ee01c94f99545488dca2e3de25f4af248ae13e65d11827573277b54171 "$ftoption"
    require_sha256 ab9cde7d2723b3ace2173a27a6a162bec064ed32abc06f5bb0be0f16b0bfc4d8 "$ftmodule"
    require_sha256 315504d5386a2e53f0c96cd3efbf71b9ccc3b1fef237dbec9e7d25cdbcf7139f "$fixture"

    custom_config="$RW_WORK/src/skia/third_party/externals/freetype/include/renderweave"
    mkdir -p "$custom_config"
    cp "$ftoption" "$custom_config/ftoption.h"
    cp "$ftmodule" "$custom_config/ftmodule.h"

    renderer_root="$RW_WORK/renderer-input/renderer-root"
    patch_applier="$renderer_root/probes/t213/apply_exact_unified_diff.py"
    require_file "$patch_applier"
    require_sha256 be6c7ee076341077409f8074cc2ba9bafbb86fa9bb14ceae4ee906f663b7cca7 \
        "$patch_applier"
    "$RW_BOOTSTRAP_PYTHON" "$patch_applier" \
        --root "$RW_WORK/src/skia" \
        --patch "$patch_file" \
        --allow src/ports/SkFontHost_FreeType.cpp \
        --allow third_party/freetype2/BUILD.gn

    require_sha256 9f12d3b32a6a8d82d70bb1f262fee951e66a250d172bee999040871109df0ec2 \
        "$RW_WORK/src/skia/src/ports/SkFontHost_FreeType.cpp"
    require_sha256 b169be6f398413bbf244bab2bd23f107836cb684d10e4130d95834365829146b \
        "$RW_WORK/src/skia/third_party/freetype2/BUILD.gn"
    require_sha256 0dda07ee01c94f99545488dca2e3de25f4af248ae13e65d11827573277b54171 \
        "$custom_config/ftoption.h"
    require_sha256 ab9cde7d2723b3ace2173a27a6a162bec064ed32abc06f5bb0be0f16b0bfc4d8 \
        "$custom_config/ftmodule.h"

    args_source="$renderer_root/probes/t213/args.gn"
    probe_source="$renderer_root/probes/t213/instrumented_probe.cpp"
    audit_source="$renderer_root/probes/t213/audit_rehearsal.py"
    require_file "$args_source"
    require_file "$probe_source"
    require_file "$audit_source"
    require_sha256 "$RW_ARGS_SHA256" "$args_source"
    require_sha256 eeef766ac75aecac694bbd82fbb3cd2b9a315075db14d91ff0cbe1bdec20f77f "$cff_source"

    cp "$args_source" "$RW_T213/args.gn"
    cp "$probe_source" "$RW_T213/instrumented_probe.cpp"
    cp "$audit_source" "$RW_T213/audit_rehearsal.py"
    cp "$fixture" "$RW_T213/fixtures/renderweave-cpop-fixture-v1.ttf"
    cp "$cff_source" "$RW_T213/fixtures/minimal-otf.otf"

    exact_env "$RW_WORK/tools/gn/gn" --version
    exact_env "$RW_WORK/tools/ninja/ninja" --version
    exact_env "$RW_WORK/toolchain/bin/clang" --version | sed -n '1p'
    printf '%s\n' "T213_PREPARED_EXACT_SOURCE_TREE"
}

build() {
    require_prepared
    require_sha256 "$RW_ARGS_SHA256" "$RW_T213/args.gn"
    umask 0022
    mkdir -p "$RW_OUT" "$RW_COMMANDS" "$RW_WORK/output"
    cp "$RW_T213/args.gn" "$RW_OUT/args.gn"

    printf '%s\n' \
        "/work/tools/gn/gn gen /work/src/skia/out/T213 --root=/work/src/skia --fail-on-unused-args" \
        > "$RW_COMMANDS/gn-generate.txt"
    exact_env "$RW_WORK/tools/gn/gn" gen "$RW_OUT" \
        --root="$RW_WORK/src/skia" --fail-on-unused-args

    printf '%s\n' "/work/tools/ninja/ninja -C /work/src/skia/out/T213 skia" \
        > "$RW_COMMANDS/ninja-build.txt"
    exact_env "$RW_WORK/tools/ninja/ninja" -C "$RW_OUT" skia
    exact_env "$RW_WORK/tools/ninja/ninja" -C "$RW_OUT" -t commands skia \
        > "$RW_COMMANDS/ninja-commands.txt"
    exact_env "$RW_WORK/tools/ninja/ninja" -C "$RW_OUT" -t deps \
        > "$RW_COMMANDS/ninja-deps.txt"

    probe_response="$RW_COMMANDS/probe-link.txt"
    printf '%s\n' \
        -std=c++20 \
        -O2 \
        -march=x86-64-v2 \
        -mtune=generic \
        -mno-avx \
        -mno-avx2 \
        -mno-fma \
        -fno-fast-math \
        -ffp-contract=off \
        -ffunction-sections \
        -fdata-sections \
        -ffile-prefix-map=/work=/renderweave/build \
        -I/work/src/skia \
        -I/work/src/skia/third_party/externals/freetype/include \
        '-DFT_CONFIG_OPTIONS_H=<renderweave/ftoption.h>' \
        '-DFT_CONFIG_MODULES_H=<renderweave/ftmodule.h>' \
        -isystem \
        /work/src/skia/third_party/freetype2/include \
        /work/t213/instrumented_probe.cpp \
        -no-pie \
        -fuse-ld=lld \
        -Wl,--gc-sections \
        -Wl,--build-id=sha1 \
        -Wl,-z,relro \
        -Wl,-z,now \
        -Wl,-z,noexecstack \
        -Wl,--wrap=FT_New_Library \
        -Wl,--wrap=FT_Open_Face \
        -Wl,--wrap=FT_Load_Glyph \
        -Wl,--start-group \
        /work/src/skia/out/T213/libskia.a \
        /work/src/skia/out/T213/libfreetype2.a \
        /work/src/skia/out/T213/libskcms.a \
        -Wl,--end-group \
        -ldl \
        -lpthread \
        -lm \
        -o \
        /work/output/renderweave-t213-probe \
        > "$probe_response"
    exact_env "$RW_WORK/toolchain/bin/clang++" "@$probe_response"
    printf '%s\n' "T213_BUILT_INSTRUMENTED_PROBE"
}

probe() {
    [ -x "$RW_BINARY" ] || fail "instrumented probe is not built"
    exact_env "$RW_BINARY" \
        "$RW_T213/fixtures/renderweave-cpop-fixture-v1.ttf" \
        "$RW_T213/fixtures/minimal-otf.otf" \
        > "$RW_EVIDENCE/probe.json"
    printf '%s\n' "T213_PROBE_PASSED"
}

audit() {
    [ -f "$RW_EVIDENCE/probe.json" ] || fail "probe evidence is absent"
    exact_env python3 "$RW_T213/audit_rehearsal.py" \
        --binary "$RW_BINARY" \
        --probe-json "$RW_EVIDENCE/probe.json" \
        --source-root "$RW_WORK/src/skia" \
        --commands "$RW_COMMANDS" \
        --toolchain-bin "$RW_WORK/toolchain/bin" \
        --gn "$RW_WORK/tools/gn/gn" \
        --ninja "$RW_WORK/tools/ninja/ninja" \
        --tricky-font "$RW_T213/fixtures/renderweave-cpop-fixture-v1.ttf" \
        --cff-font "$RW_T213/fixtures/minimal-otf.otf" \
        --args-gn "$RW_T213/args.gn" \
        --patch "$RW_WORK/policy/renderer-spike/skia-m151-freetype-policy-v3.patch" \
        --ftoption "$RW_WORK/policy/renderer-spike/rw-freetype-ftoption-v3.h" \
        --ftmodule "$RW_WORK/policy/renderer-spike/rw-freetype-ftmodule-v3.h" \
        --bundle-inventory "$RW_BUNDLE/inventory.json" \
        --harness "$RW_HARNESS" \
        --output "$RW_EVIDENCE/manifest.json"
    printf '%s\n' "T213_AUDIT_PASSED"
}

rehearse() {
    prepare
    build
    probe
    audit
}

verify_missing_input_rejected() {
    log="/tmp/renderweave-t213-missing-input.log"
    set +e
    env RW_BUNDLE=/bundle-missing /bin/sh "$0" prepare > "$log" 2>&1
    result=$?
    set -e
    if [ "$result" -eq 0 ]; then
        fail "missing offline input unexpectedly passed prepare"
    fi
    expected="missing input: /bundle-missing/inputs/skia/skia-$RW_SKIA_COMMIT.tar"
    grep -F "$expected" "$log" > /dev/null || {
        sed -n '1,20p' "$log" >&2
        fail "missing offline input did not fail at the declared seam"
    }
    printf '%s\n' "T213_MISSING_INPUT_REJECTED"
}

verify_host_fallback_rejected() {
    prepare
    sed -i \
        's/skia_use_system_freetype2 = false/skia_use_system_freetype2 = true/' \
        "$RW_T213/args.gn"
    grep -F 'skia_use_system_freetype2 = true' "$RW_T213/args.gn" > /dev/null ||
        fail "host fallback mutation was not applied"
    log="/tmp/renderweave-t213-host-fallback.log"
    set +e
    /bin/sh "$0" build > "$log" 2>&1
    result=$?
    set -e
    if [ "$result" -eq 0 ]; then
        fail "system FreeType fallback unexpectedly reached a build"
    fi
    grep -F "sha256 mismatch: $RW_T213/args.gn" "$log" > /dev/null || {
        sed -n '1,20p' "$log" >&2
        fail "system FreeType fallback did not fail at the build seam"
    }
    printf '%s\n' "T213_HOST_FALLBACK_CONFIGURATION_REJECTED_BEFORE_BUILD"
}

case "${1:-}" in
    prepare)
        prepare
        ;;
    build)
        build
        ;;
    probe)
        probe
        ;;
    audit)
        audit
        ;;
    rehearse)
        rehearse
        ;;
    verify-missing-input-rejected)
        verify_missing_input_rejected
        ;;
    verify-host-fallback-rejected)
        verify_host_fallback_rejected
        ;;
    *)
        fail "usage: $0 prepare|build|probe|audit|rehearse|verify-missing-input-rejected|verify-host-fallback-rejected"
        ;;
esac
