#!/bin/sh
set -eu

readonly RW_BUNDLE="${RW_BUNDLE:-/bundle}"
readonly RW_WORK="${RW_WORK:-/work}"
readonly RW_T214="$RW_WORK/t214"
readonly RW_REPO="${RW_REPO:-$RW_T214/source}"
readonly RW_RUST="$RW_T214/rust"
readonly RW_RUST_DIST="$RW_T214/rust-dist"
readonly RW_CARGO_HOME="$RW_T214/cargo-home"
readonly RW_TARGET="$RW_T214/target"
readonly RW_SKIA_ROOT="$RW_WORK/src/skia"
readonly RW_SKIA_OUT="$RW_SKIA_ROOT/out/T213"
readonly RW_HARFBUZZ_ROOT="$RW_T214/harfbuzz"
readonly RW_HARFBUZZ_OUT="$RW_HARFBUZZ_ROOT/out/T214"
readonly RW_JPEG_ROOT="$RW_T214/libjpeg-turbo"
readonly RW_JPEG_OUT="$RW_JPEG_ROOT/out/T217"
readonly RW_HARFBUZZ_ARCHIVE="$RW_BUNDLE/inputs/shaping-unicode/harfbuzz-9cb1fee51069b206effb4736e443b038d230789d.tar"
readonly RW_JPEG_ARCHIVE="$RW_BUNDLE/inputs/jpeg-output/libjpeg-turbo-3.2.0.tar.gz"
readonly RW_CMAKE_ARCHIVE="$RW_BUNDLE/inputs/build-tools/cmake-4.4.2-linux-x86_64.tar.gz"
readonly RW_RUST_ARCHIVE="$RW_BUNDLE/inputs/toolchain-sysroot/rust-1.89.0-x86_64-unknown-linux-gnu.tar.xz"
readonly RW_RENDERER_ROOT_ARCHIVE="$RW_BUNDLE/inputs/downstream-policy/renderer-root-normalized.tar"
readonly RW_RENDERER_CRATES_ARCHIVE="$RW_BUNDLE/inputs/downstream-policy/renderer-crates-normalized.tar"
readonly RW_RENDERER_VENDOR_ARCHIVE="$RW_BUNDLE/inputs/image-codecs/renderer-vendor-normalized.tar"
readonly RW_FONT_FIXTURE="$RW_BUNDLE/inputs/downstream-policy/minimal-ttf.ttf"
readonly RW_CFF_FONT_FIXTURE="$RW_BUNDLE/inputs/downstream-policy/minimal-cff.otf"
readonly RW_ASSET_VECTORS="$RW_BUNDLE/inputs/downstream-policy/asset-acceptance-kernel-v1-vectors.json"
readonly RW_RENDER_NODE_CONTRACT="$RW_BUNDLE/inputs/downstream-policy/render-node-contract-v1.json"
readonly RW_CANONICAL_SRGB_ICC="$RW_BUNDLE/inputs/canonical-icc/sRGB-IEC61966-2.1.icc"
readonly RW_BOOTSTRAP_PYTHON="/usr/local/bin/python3"

fail() {
    printf '%s\n' "T214_REHEARSAL_FAILED: $*" >&2
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

require_sha256() {
    expected="$1"
    path="$2"
    [ -f "$path" ] || fail "missing input: $path"
    actual="$(sha256sum "$path" | awk '{print $1}')"
    [ "$actual" = "$expected" ] || fail "sha256 mismatch: $path: $actual"
}

require_native_inputs() {
    [ -f "$RW_WORK/evidence/manifest.json" ] || fail "T213 exact manifest is absent"
    [ -f "$RW_WORK/evidence/commands/ninja-deps.txt" ] ||
        fail "T213 exact dependency closure is absent"
    [ -f "$RW_SKIA_OUT/libskia.a" ] || fail "exact Skia archive is absent"
    [ -f "$RW_SKIA_OUT/libfreetype2.a" ] || fail "exact FreeType archive is absent"
    [ -f "$RW_SKIA_OUT/libskcms.a" ] || fail "exact skcms archive is absent"
    [ -x "$RW_WORK/toolchain/bin/clang++" ] || fail "exact clang++ is absent"
    [ -x "$RW_WORK/toolchain/bin/llvm-ar" ] || fail "exact llvm-ar is absent"
}

prepare_source() {
    source_marker="$RW_REPO/.renderweave-t214-source"
    if [ -f "$source_marker" ]; then
        [ -f "$RW_REPO/renderer/Cargo.toml" ] || fail "prepared Renderer source is incomplete"
        [ -f "$RW_REPO/renderer/production-text-command-v1.json" ] ||
            fail "prepared Java Command fixture is absent"
        [ -f "$RW_REPO/renderweave-asset/src/test/resources/asset-fixtures/minimal-ttf.ttf" ] ||
            fail "prepared exact FONT fixture is absent"
        if [ ! -f "$RW_REPO/renderweave-asset/src/test/resources/asset-fixtures/minimal-otf.otf" ]; then
            require_sha256 eeef766ac75aecac694bbd82fbb3cd2b9a315075db14d91ff0cbe1bdec20f77f \
                "$RW_CFF_FONT_FIXTURE"
            cp "$RW_CFF_FONT_FIXTURE" \
                "$RW_REPO/renderweave-asset/src/test/resources/asset-fixtures/minimal-otf.otf"
        fi
        require_sha256 eeef766ac75aecac694bbd82fbb3cd2b9a315075db14d91ff0cbe1bdec20f77f \
            "$RW_REPO/renderweave-asset/src/test/resources/asset-fixtures/minimal-otf.otf"
        if [ ! -f "$RW_REPO/renderweave-asset/src/test/resources/cn/hbads/renderweave/asset/acceptance-kernel-v1/vectors.json" ]; then
            require_sha256 0f44fdef29d989049e77bcf3659fca4b7958b7009053c82d74c46f9f1984e4ca \
                "$RW_ASSET_VECTORS"
            mkdir -p "$RW_REPO/renderweave-asset/src/test/resources/cn/hbads/renderweave/asset/acceptance-kernel-v1"
            cp "$RW_ASSET_VECTORS" \
                "$RW_REPO/renderweave-asset/src/test/resources/cn/hbads/renderweave/asset/acceptance-kernel-v1/vectors.json"
        fi
        require_sha256 0f44fdef29d989049e77bcf3659fca4b7958b7009053c82d74c46f9f1984e4ca \
            "$RW_REPO/renderweave-asset/src/test/resources/cn/hbads/renderweave/asset/acceptance-kernel-v1/vectors.json"
        if [ ! -f "$RW_REPO/renderweave-rendering/src/main/resources/cn/hbads/renderweave/rendering/render-node-contract-v1.json" ]; then
            require_sha256 55e9062e2b988c1bc878d216c850e3ada7e6584b19ae2aca7ce2765d2a3b9752 \
                "$RW_RENDER_NODE_CONTRACT"
            mkdir -p "$RW_REPO/renderweave-rendering/src/main/resources/cn/hbads/renderweave/rendering"
            cp "$RW_RENDER_NODE_CONTRACT" \
                "$RW_REPO/renderweave-rendering/src/main/resources/cn/hbads/renderweave/rendering/render-node-contract-v1.json"
        fi
        require_sha256 55e9062e2b988c1bc878d216c850e3ada7e6584b19ae2aca7ce2765d2a3b9752 \
            "$RW_REPO/renderweave-rendering/src/main/resources/cn/hbads/renderweave/rendering/render-node-contract-v1.json"
        if [ ! -f "$RW_REPO/renderweave-asset/src/main/resources/cn/hbads/renderweave/asset/acceptance/sRGB-IEC61966-2.1.icc" ]; then
            require_sha256 2b3aa1645779a9e634744faf9b01e9102b0c9b88fd6deced7934df86b949af7e \
                "$RW_CANONICAL_SRGB_ICC"
            mkdir -p "$RW_REPO/renderweave-asset/src/main/resources/cn/hbads/renderweave/asset/acceptance"
            cp "$RW_CANONICAL_SRGB_ICC" \
                "$RW_REPO/renderweave-asset/src/main/resources/cn/hbads/renderweave/asset/acceptance/sRGB-IEC61966-2.1.icc"
        fi
        require_sha256 2b3aa1645779a9e634744faf9b01e9102b0c9b88fd6deced7934df86b949af7e \
            "$RW_REPO/renderweave-asset/src/main/resources/cn/hbads/renderweave/asset/acceptance/sRGB-IEC61966-2.1.icc"
        return
    fi
    [ ! -e "$RW_REPO" ] || fail "partial T214 source tree is present"
    source_stage="$RW_T214/source-stage"
    [ ! -e "$source_stage" ] || fail "partial T214 source staging tree is present"

    require_sha256 474b0038f4db3c9c98f1d4d49f6753fc23ca5ffcd90ba9da720bf9e48e69af78 \
        "$RW_RENDERER_ROOT_ARCHIVE"
    require_sha256 335a4f17427cd20908b03407ea4c6d2f21da7893735e771beff67ba9ead661b1 \
        "$RW_RENDERER_CRATES_ARCHIVE"
    require_sha256 2271053938840ce6fa5738a471d963cfd99e9e1b95db888ac4f74fddd3adeff0 \
        "$RW_RENDERER_VENDOR_ARCHIVE"
    require_sha256 563f77993d3eaf2f74734c13f768afd178383cbf180d33e69b7c35df35779ceb \
        "$RW_FONT_FIXTURE"
    require_sha256 eeef766ac75aecac694bbd82fbb3cd2b9a315075db14d91ff0cbe1bdec20f77f \
        "$RW_CFF_FONT_FIXTURE"
    require_sha256 0f44fdef29d989049e77bcf3659fca4b7958b7009053c82d74c46f9f1984e4ca \
        "$RW_ASSET_VECTORS"
    require_sha256 55e9062e2b988c1bc878d216c850e3ada7e6584b19ae2aca7ce2765d2a3b9752 \
        "$RW_RENDER_NODE_CONTRACT"
    require_sha256 2b3aa1645779a9e634744faf9b01e9102b0c9b88fd6deced7934df86b949af7e \
        "$RW_CANONICAL_SRGB_ICC"

    mkdir -p "$source_stage" "$RW_REPO/renderer" \
        "$RW_REPO/renderweave-asset/src/test/resources/asset-fixtures" \
        "$RW_REPO/renderweave-asset/src/test/resources/cn/hbads/renderweave/asset/acceptance-kernel-v1" \
        "$RW_REPO/renderweave-asset/src/main/resources/cn/hbads/renderweave/asset/acceptance" \
        "$RW_REPO/renderweave-rendering/src/main/resources/cn/hbads/renderweave/rendering"
    tar --no-same-owner -xf "$RW_RENDERER_ROOT_ARCHIVE" -C "$source_stage"
    cp -a "$source_stage/renderer-root/." "$RW_REPO/renderer/"
    tar --no-same-owner -xf "$RW_RENDERER_CRATES_ARCHIVE" -C "$RW_REPO/renderer"
    tar --no-same-owner -xf "$RW_RENDERER_VENDOR_ARCHIVE" -C "$RW_REPO/renderer"
    cp "$RW_FONT_FIXTURE" \
        "$RW_REPO/renderweave-asset/src/test/resources/asset-fixtures/minimal-ttf.ttf"
    cp "$RW_CFF_FONT_FIXTURE" \
        "$RW_REPO/renderweave-asset/src/test/resources/asset-fixtures/minimal-otf.otf"
    cp "$RW_ASSET_VECTORS" \
        "$RW_REPO/renderweave-asset/src/test/resources/cn/hbads/renderweave/asset/acceptance-kernel-v1/vectors.json"
    cp "$RW_RENDER_NODE_CONTRACT" \
        "$RW_REPO/renderweave-rendering/src/main/resources/cn/hbads/renderweave/rendering/render-node-contract-v1.json"
    cp "$RW_CANONICAL_SRGB_ICC" \
        "$RW_REPO/renderweave-asset/src/main/resources/cn/hbads/renderweave/asset/acceptance/sRGB-IEC61966-2.1.icc"
    require_sha256 1257ba1f94eb6aefda075f74a6e9bf41efbe43373a5a5aae3fdb2b8b536a64ed \
        "$RW_REPO/renderer/production-text-command-v1.json"
    require_sha256 c4f7135f4627feff332982603dbbee1e7a8a73a32c9ef926892ea00d7327ec47 \
        "$RW_REPO/renderer/Cargo.lock"
    printf '%s\n' "renderweave-t214-source/1" > "$source_marker"
    printf '%s\n' "T214_EXACT_SOURCE_PREPARED"
}

install_rust() {
    require_native_inputs
    require_sha256 c4f2796b10ee886001f0799bc40caea38746403a33c379d77878c4f4683f9b51 \
        "$RW_RUST_ARCHIVE"
    if [ -x "$RW_RUST/bin/cargo" ] && [ -x "$RW_RUST/bin/rustc" ]; then
        "$RW_RUST/bin/cargo" --version | grep -F 'cargo 1.89.0 ' >/dev/null ||
            fail "installed Cargo identity drifted"
        "$RW_RUST/bin/rustc" --version | grep -F 'rustc 1.89.0 ' >/dev/null ||
            fail "installed rustc identity drifted"
        printf '%s\n' "T214_EXACT_RUST_ALREADY_INSTALLED"
        return
    fi
    [ ! -e "$RW_RUST" ] || fail "partial exact Rust installation is present"
    [ ! -e "$RW_RUST_DIST" ] || fail "partial exact Rust distribution is present"
    mkdir -p "$RW_RUST_DIST" "$RW_CARGO_HOME" "$RW_TARGET" "$RW_T214/home" "$RW_T214/tmp"
    stream_xz "$RW_RUST_ARCHIVE" |
        tar --no-same-owner -xf - -C "$RW_RUST_DIST"
    "$RW_RUST_DIST/rust-1.89.0-x86_64-unknown-linux-gnu/install.sh" \
        --prefix="$RW_RUST" \
        --disable-ldconfig
    "$RW_RUST/bin/cargo" --version | grep -F 'cargo 1.89.0 ' >/dev/null ||
        fail "installed Cargo identity drifted"
    "$RW_RUST/bin/rustc" --version | grep -F 'rustc 1.89.0 ' >/dev/null ||
        fail "installed rustc identity drifted"
    printf '%s\n' "T214_EXACT_RUST_INSTALLED"
}

prepare_harfbuzz() {
    require_native_inputs
    require_sha256 553a73bae6658c6a9e10fd91546d2f08febf3a0502a299478c956e4d882a3185 \
        "$RW_HARFBUZZ_ARCHIVE"
    source_marker="$RW_HARFBUZZ_ROOT/.renderweave-harfbuzz-9cb1fee"
    if [ ! -f "$source_marker" ]; then
        [ ! -e "$RW_HARFBUZZ_ROOT" ] || fail "partial exact HarfBuzz source is present"
        source_stage="$RW_T214/harfbuzz-source-stage"
        [ ! -e "$source_stage" ] || fail "partial exact HarfBuzz staging tree is present"
        mkdir -p "$source_stage"
        tar --no-same-owner --strip-components=1 -xf "$RW_HARFBUZZ_ARCHIVE" -C "$source_stage"
        [ -f "$source_stage/src/harfbuzz.cc" ] || fail "exact HarfBuzz unity source is absent"
        [ -f "$source_stage/src/hb.h" ] || fail "exact HarfBuzz public header is absent"
        [ -f "$source_stage/src/hb-ot.h" ] || fail "exact HarfBuzz OpenType header is absent"
        mv "$source_stage" "$RW_HARFBUZZ_ROOT"
        printf '%s\n' "harfbuzz-9cb1fee51069b206effb4736e443b038d230789d" > "$source_marker"
    fi
    grep -Fx 'harfbuzz-9cb1fee51069b206effb4736e443b038d230789d' "$source_marker" >/dev/null ||
        fail "exact HarfBuzz source marker drifted"
    mkdir -p "$RW_HARFBUZZ_OUT"
    "$RW_WORK/toolchain/bin/clang++" \
        -std=c++20 -O2 -march=x86-64-v2 -mtune=generic \
        -mno-avx -mno-avx2 -mno-fma -fno-fast-math -ffp-contract=off \
        -fPIC -fno-exceptions -fno-rtti -DHB_NO_MT \
        -ffile-prefix-map=/work=/renderweave/build \
        -I"$RW_HARFBUZZ_ROOT/src" \
        -c "$RW_HARFBUZZ_ROOT/src/harfbuzz.cc" \
        -o "$RW_HARFBUZZ_OUT/harfbuzz.o"
    "$RW_WORK/toolchain/bin/llvm-ar" rcsD \
        "$RW_HARFBUZZ_OUT/libharfbuzz.a" "$RW_HARFBUZZ_OUT/harfbuzz.o"
    [ -f "$RW_HARFBUZZ_OUT/libharfbuzz.a" ] || fail "exact HarfBuzz archive is absent"
    printf '%s\n' "T214_EXACT_HARFBUZZ_BUILT"
}

prepare_jpeg() {
    require_native_inputs
    require_sha256 6f30092cef9fb839779646608f4ee14ae3cbac989c47fa05e841b0841f09878e \
        "$RW_JPEG_ARCHIVE"
    require_sha256 3ada9a3f5d8a85413579bdd0ea6aa8e8da86efdd6d15c91a1afa517f2021956c \
        "$RW_CMAKE_ARCHIVE"
    cmake_root="$RW_T214/cmake"
    if [ ! -x "$cmake_root/bin/cmake" ]; then
        [ ! -e "$cmake_root" ] || fail "partial exact CMake installation is present"
        mkdir -p "$cmake_root"
        tar --no-same-owner --strip-components=1 -xf "$RW_CMAKE_ARCHIVE" -C "$cmake_root"
    fi
    "$cmake_root/bin/cmake" --version | grep -F 'cmake version 4.4.2' >/dev/null ||
        fail "exact CMake identity drifted"
    source_marker="$RW_JPEG_ROOT/.renderweave-libjpeg-turbo-3.2.0"
    if [ ! -f "$source_marker" ]; then
        [ ! -e "$RW_JPEG_ROOT" ] || fail "partial exact libjpeg-turbo source is present"
        mkdir -p "$RW_JPEG_ROOT"
        tar --no-same-owner --strip-components=1 -xf "$RW_JPEG_ARCHIVE" -C "$RW_JPEG_ROOT"
        printf '%s\n' "libjpeg-turbo-3.2.0/sha256:6f30092cef9fb839779646608f4ee14ae3cbac989c47fa05e841b0841f09878e" \
            > "$source_marker"
    fi
    grep -Fx 'libjpeg-turbo-3.2.0/sha256:6f30092cef9fb839779646608f4ee14ae3cbac989c47fa05e841b0841f09878e' \
        "$source_marker" >/dev/null || fail "exact libjpeg-turbo source marker drifted"
    if [ ! -f "$RW_JPEG_OUT/libjpeg.a" ]; then
        mkdir -p "$RW_JPEG_OUT" "$RW_T214/home" "$RW_T214/tmp"
        env -i \
            HOME="$RW_T214/home" \
            LANG=C.UTF-8 \
            LC_ALL=C.UTF-8 \
            PATH="$cmake_root/bin:$RW_WORK/toolchain/bin:$RW_WORK/tools/ninja:/usr/bin:/bin" \
            SOURCE_DATE_EPOCH=0 \
            TMPDIR="$RW_T214/tmp" \
            TZ=UTC \
            "$cmake_root/bin/cmake" \
                -S "$RW_JPEG_ROOT" \
                -B "$RW_JPEG_OUT" \
                -G Ninja \
                -DCMAKE_MAKE_PROGRAM="$RW_WORK/tools/ninja/ninja" \
                -DCMAKE_C_COMPILER="$RW_WORK/toolchain/bin/clang" \
                -DCMAKE_AR="$RW_WORK/toolchain/bin/llvm-ar" \
                -DCMAKE_RANLIB="$RW_WORK/toolchain/bin/llvm-ranlib" \
                -DCMAKE_BUILD_TYPE=Release \
                -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
                '-DCMAKE_C_FLAGS_RELEASE=-O2 -DNDEBUG -march=x86-64-v2 -mtune=generic -mno-avx -mno-avx2 -mno-fma -fno-fast-math -ffp-contract=off -ffile-prefix-map=/work=/renderweave/build' \
                -DENABLE_SHARED=OFF \
                -DENABLE_STATIC=ON \
                -DREQUIRE_SIMD=OFF \
                -DWITH_SIMD=OFF \
                -DWITH_TURBOJPEG=OFF \
                -DWITH_TOOLS=OFF \
                -DWITH_TESTS=OFF \
                -DWITH_ARITH_ENC=OFF \
                -DWITH_ARITH_DEC=OFF \
                -DWITH_JPEG7=OFF \
                -DWITH_JPEG8=OFF
        env -i \
            HOME="$RW_T214/home" \
            LANG=C.UTF-8 \
            LC_ALL=C.UTF-8 \
            PATH="$cmake_root/bin:$RW_WORK/toolchain/bin:$RW_WORK/tools/ninja:/usr/bin:/bin" \
            SOURCE_DATE_EPOCH=0 \
            TMPDIR="$RW_T214/tmp" \
            TZ=UTC \
            "$cmake_root/bin/cmake" --build "$RW_JPEG_OUT" --target jpeg-static --parallel 4
    fi
    [ -f "$RW_JPEG_OUT/libjpeg.a" ] || fail "exact static libjpeg archive is absent"
    if find "$RW_JPEG_OUT" -maxdepth 2 -name 'libjpeg.so*' -print -quit | grep -q .
    then
        fail "shared libjpeg output is forbidden"
    fi
    grep -F 'WITH_SIMD:BOOL=OFF' "$RW_JPEG_OUT/CMakeCache.txt" >/dev/null ||
        fail "libjpeg-turbo SIMD disablement drifted"
    grep -F 'ENABLE_SHARED:BOOL=OFF' "$RW_JPEG_OUT/CMakeCache.txt" >/dev/null ||
        fail "libjpeg-turbo shared build disablement drifted"
    grep -F 'ENABLE_STATIC:BOOL=ON' "$RW_JPEG_OUT/CMakeCache.txt" >/dev/null ||
        fail "libjpeg-turbo static build enablement drifted"
    printf '%s\n' "T217_EXACT_LIBJPEG_TURBO_BUILT"
}

exact_cargo() {
    require_native_inputs
    prepare_source
    prepare_harfbuzz
    prepare_jpeg
    [ -x "$RW_RUST/bin/cargo" ] || fail "exact Cargo is not installed"
    mkdir -p "$RW_CARGO_HOME" "$RW_TARGET" "$RW_T214/home" "$RW_T214/tmp"
    cd "$RW_REPO/renderer"
    env -i \
        HOME="$RW_T214/home" \
        LANG=C.UTF-8 \
        LC_ALL=C.UTF-8 \
        PATH="$RW_RUST/bin:$RW_WORK/toolchain/bin:/usr/bin:/bin" \
        SOURCE_DATE_EPOCH=0 \
        TMPDIR="$RW_T214/tmp" \
        TZ=UTC \
        CARGO_HOME="$RW_CARGO_HOME" \
        CARGO_TARGET_DIR="$RW_TARGET" \
        CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER="$RW_WORK/toolchain/bin/clang++" \
        RUSTFLAGS='-C target-cpu=x86-64-v2 -C linker=/work/toolchain/bin/clang++ -C link-arg=-fuse-ld=lld -C link-arg=-no-pie -C link-arg=-Wl,--build-id=sha1 -C link-arg=-Wl,-z,relro -C link-arg=-Wl,-z,now -C link-arg=-Wl,-z,noexecstack -C link-arg=-Wl,--gc-sections -C link-arg=-Wl,--wrap=FT_Load_Glyph -C link-arg=-Wl,--wrap=FT_New_Library -C debuginfo=0 -C strip=symbols --remap-path-prefix=/repo=/renderweave/src --remap-path-prefix=/work=/renderweave/build' \
        RENDERWEAVE_SKIA_ROOT="$RW_SKIA_ROOT" \
        RENDERWEAVE_SKIA_OUT="$RW_SKIA_OUT" \
        RENDERWEAVE_HARFBUZZ_ROOT="$RW_HARFBUZZ_ROOT" \
        RENDERWEAVE_HARFBUZZ_OUT="$RW_HARFBUZZ_OUT" \
        RENDERWEAVE_JPEG_TURBO_ROOT="$RW_JPEG_ROOT" \
        RENDERWEAVE_JPEG_TURBO_OUT="$RW_JPEG_OUT" \
        RENDERWEAVE_CANONICAL_SRGB_ICC="$RW_CANONICAL_SRGB_ICC" \
        RENDERWEAVE_CLANG="$RW_WORK/toolchain/bin/clang" \
        RENDERWEAVE_CLANGXX="$RW_WORK/toolchain/bin/clang++" \
        RENDERWEAVE_LLVM_AR="$RW_WORK/toolchain/bin/llvm-ar" \
        "$RW_RUST/bin/cargo" "$@"
}

test_engine() {
    exact_cargo test \
        --manifest-path "$RW_REPO/renderer/Cargo.toml" \
        -p renderweave-renderer-output-jpeg \
        --features native-jpeg-turbo \
        --release \
        --locked \
        --offline \
        -- --nocapture
    exact_cargo test \
        --manifest-path "$RW_REPO/renderer/Cargo.toml" \
        -p renderweave-renderer-engine \
        --features native-text-skia,native-jpeg-turbo \
        --lib \
        --release \
        --locked \
        --offline \
        -- --nocapture
    exact_cargo test \
        --manifest-path "$RW_REPO/renderer/Cargo.toml" \
        -p renderweave-renderer-engine \
        --features native-text-skia,native-jpeg-turbo \
        --test native_jpeg_vectors \
        --release \
        --locked \
        --offline \
        -- --nocapture
    exact_cargo test \
        --manifest-path "$RW_REPO/renderer/Cargo.toml" \
        -p renderweave-renderer-engine \
        --features native-text-skia,native-jpeg-turbo \
        --test native_text_vectors \
        --release \
        --locked \
        --offline \
        -- --nocapture
    printf '%s\n' "T214_NATIVE_TEXT_ENGINE_TEST_PASSED"
}

audit_glyph_policy() {
    PYTHONPATH="$RW_REPO/renderer/probes/t213" \
        "$RW_BOOTSTRAP_PYTHON" - \
        "$RW_SKIA_ROOT" \
        "$RW_WORK/evidence/commands/ninja-deps.txt" <<'PY'
import pathlib
import sys

from audit_rehearsal import glyph_load_inventory

inventory = glyph_load_inventory(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]))
if inventory["sourceCallSiteCount"] != 13 or inventory["sourceDefinitionCount"] != 1:
    raise SystemExit("exact glyph-load inventory cardinality differs")
print("T215_EXACT_BUILD_GLYPH_SOURCE_INVENTORY_PASSED")
PY
    for source in \
        "$RW_REPO/renderer/crates/engine/native/native_text_skia.cpp" \
        "$RW_HARFBUZZ_ROOT/src/harfbuzz.cc"
    do
        [ -f "$source" ] || fail "production C++ source is absent: $source"
        if grep -En '(^|[^[:alnum:]_])FT_Load_Glyph[[:space:]]*\(' "$source" >/dev/null
        then
            fail "unregistered production FT_Load_Glyph call: $source"
        fi
    done
    archives="$RW_T214/glyph-policy-archives.txt"
    find "$RW_TARGET/release/build" \
        -path '*/out/librenderweave_native_text.a' \
        -type f -print | sort > "$archives"
    [ -s "$archives" ] || fail "production native archive is absent"
    archive_count=0
    while IFS= read -r native_archive
    do
        [ -f "$native_archive" ] || fail "production native archive disappeared"
        archive_count="$((archive_count + 1))"
        symbols="$RW_T214/glyph-policy-symbols-$archive_count.txt"
        "$RW_WORK/toolchain/bin/llvm-nm" -C --defined-only "$native_archive" > "$symbols"
        for symbol in \
            __wrap_FT_Load_Glyph \
            __wrap_FT_New_Library \
            renderweave_glyph_policy_accepts_flags \
            renderweave_skia_raster_text
        do
            grep -F "$symbol" "$symbols" >/dev/null ||
                fail "production glyph policy symbol is absent: $symbol"
        done
    done < "$archives"
    printf '%s\n' "T215_PRODUCTION_GLYPH_POLICY_AUDIT_PASSED"
}

test_daemon() {
    exact_cargo test \
        --manifest-path "$RW_REPO/renderer/Cargo.toml" \
        -p renderweave-renderer-daemon \
        --features native-text-skia,native-jpeg-turbo \
        --lib production_ \
        --release \
        --locked \
        --offline \
        -- --nocapture
    exact_cargo test \
        --manifest-path "$RW_REPO/renderer/Cargo.toml" \
        -p renderweave-renderer-daemon \
        --features native-text-skia,native-jpeg-turbo \
        --test prepared_png_result \
        --release \
        --locked \
        --offline \
        -- --nocapture
    printf '%s\n' "T214_NATIVE_TEXT_DAEMON_TEST_PASSED"
}

audit_jpeg_policy() {
    [ -x "$RW_WORK/toolchain/bin/llvm-nm" ] || fail "exact llvm-nm is absent"
    [ -x "$RW_WORK/toolchain/bin/llvm-readelf" ] || fail "exact llvm-readelf is absent"
    archives="$RW_T214/jpeg-policy-archives.txt"
    find "$RW_TARGET/release/build" \
        -path '*/out/librenderweave_native_jpeg.a' \
        -type f -print | sort > "$archives"
    [ -s "$archives" ] || fail "production native JPEG archive is absent"
    archive_count=0
    while IFS= read -r native_archive
    do
        [ -f "$native_archive" ] || fail "production native JPEG archive disappeared"
        archive_count="$((archive_count + 1))"
        symbols="$RW_T214/jpeg-policy-symbols-$archive_count.txt"
        "$RW_WORK/toolchain/bin/llvm-nm" --defined-only "$native_archive" > "$symbols"
        for symbol in \
            renderweave_encode_jpeg \
            renderweave_free_jpeg \
            jpeg_CreateCompress \
            jpeg_write_scanlines
        do
            grep -F "$symbol" "$symbols" >/dev/null ||
                fail "production native JPEG symbol is absent: $symbol"
        done
    done < "$archives"
    [ -x "$RW_TARGET/release/renderweave-renderer-daemon" ] ||
        fail "production Renderer daemon binary is absent"
    if "$RW_WORK/toolchain/bin/llvm-readelf" -d \
        "$RW_TARGET/release/renderweave-renderer-daemon" | grep -Eiq 'NEEDED.*(jpeg|turbo)'
    then
        fail "production Renderer dynamically depends on an ambient JPEG codec"
    fi
    if grep -F 'jpeg_set_quality' \
        "$RW_REPO/renderer/crates/output-jpeg/native/jpeg_encoder.c" >/dev/null
    then
        fail "JPEG quality helper is forbidden as output authority"
    fi
    printf '%s\n' "T217_PRODUCTION_JPEG_POLICY_AUDIT_PASSED"
}

clippy_candidate() {
    exact_cargo clippy \
        --manifest-path "$RW_REPO/renderer/Cargo.toml" \
        -p renderweave-renderer-engine \
        -p renderweave-renderer-daemon \
        -p renderweave-renderer-output-jpeg \
        --features native-text-skia,native-jpeg-turbo \
        --release \
        --locked \
        --offline \
        -- -D warnings
    printf '%s\n' "T214_NATIVE_TEXT_CLIPPY_PASSED"
}

build_daemon() {
    exact_cargo build \
        --manifest-path "$RW_REPO/renderer/Cargo.toml" \
        -p renderweave-renderer-daemon \
        --features native-text-skia,native-jpeg-turbo \
        --release \
        --locked \
        --offline
    [ -x "$RW_TARGET/release/renderweave-renderer-daemon" ] ||
        fail "production Renderer daemon binary is absent"
    printf '%s\n' "T214_NATIVE_TEXT_DAEMON_BUILD_PASSED"
}

case "${1:-}" in
    install-rust)
        install_rust
        ;;
    test-engine)
        test_engine
        ;;
    test-daemon)
        test_daemon
        ;;
    build-daemon)
        build_daemon
        ;;
    clippy)
        clippy_candidate
        ;;
    rehearse)
        install_rust
        test_engine
        test_daemon
        clippy_candidate
        build_daemon
        audit_glyph_policy
        audit_jpeg_policy
        ;;
    audit-glyph-policy)
        audit_glyph_policy
        ;;
    audit-jpeg-policy)
        audit_jpeg_policy
        ;;
    *)
        fail "usage: $0 install-rust|test-engine|test-daemon|clippy|build-daemon|audit-glyph-policy|audit-jpeg-policy|rehearse"
        ;;
esac
