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
readonly RW_HARFBUZZ_ARCHIVE="$RW_BUNDLE/inputs/shaping-unicode/harfbuzz-9cb1fee51069b206effb4736e443b038d230789d.tar"
readonly RW_RUST_ARCHIVE="$RW_BUNDLE/inputs/toolchain-sysroot/rust-1.89.0-x86_64-unknown-linux-gnu.tar.xz"
readonly RW_RENDERER_ROOT_ARCHIVE="$RW_BUNDLE/inputs/downstream-policy/renderer-root-normalized.tar"
readonly RW_RENDERER_CRATES_ARCHIVE="$RW_BUNDLE/inputs/downstream-policy/renderer-crates-normalized.tar"
readonly RW_RENDERER_VENDOR_ARCHIVE="$RW_BUNDLE/inputs/image-codecs/renderer-vendor-normalized.tar"
readonly RW_FONT_FIXTURE="$RW_BUNDLE/inputs/downstream-policy/minimal-ttf.ttf"
readonly RW_ASSET_VECTORS="$RW_BUNDLE/inputs/downstream-policy/asset-acceptance-kernel-v1-vectors.json"
readonly RW_RENDER_NODE_CONTRACT="$RW_BUNDLE/inputs/downstream-policy/render-node-contract-v1.json"
readonly RW_CANONICAL_SRGB_ICC="$RW_BUNDLE/inputs/canonical-icc/sRGB-IEC61966-2.1.icc"

fail() {
    printf '%s\n' "T214_REHEARSAL_FAILED: $*" >&2
    exit 1
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

    require_sha256 04dc5cb06d3f0aca600afbec8ebb51b556018c549fe24c427e9d1de9eb89cd26 \
        "$RW_RENDERER_ROOT_ARCHIVE"
    require_sha256 ec0baae67820ea8ddbd0d8352032d1088b3a117e4ba4e717ba9581b9fd55222b \
        "$RW_RENDERER_CRATES_ARCHIVE"
    require_sha256 2271053938840ce6fa5738a471d963cfd99e9e1b95db888ac4f74fddd3adeff0 \
        "$RW_RENDERER_VENDOR_ARCHIVE"
    require_sha256 563f77993d3eaf2f74734c13f768afd178383cbf180d33e69b7c35df35779ceb \
        "$RW_FONT_FIXTURE"
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
    cp "$RW_ASSET_VECTORS" \
        "$RW_REPO/renderweave-asset/src/test/resources/cn/hbads/renderweave/asset/acceptance-kernel-v1/vectors.json"
    cp "$RW_RENDER_NODE_CONTRACT" \
        "$RW_REPO/renderweave-rendering/src/main/resources/cn/hbads/renderweave/rendering/render-node-contract-v1.json"
    cp "$RW_CANONICAL_SRGB_ICC" \
        "$RW_REPO/renderweave-asset/src/main/resources/cn/hbads/renderweave/asset/acceptance/sRGB-IEC61966-2.1.icc"
    require_sha256 1257ba1f94eb6aefda075f74a6e9bf41efbe43373a5a5aae3fdb2b8b536a64ed \
        "$RW_REPO/renderer/production-text-command-v1.json"
    require_sha256 4d25500fb52cf97899d0bcc8fac75fb9a7e9ec9528595f2aff3e5dae88111d3a \
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
    tar --no-same-owner -xf "$RW_RUST_ARCHIVE" -C "$RW_RUST_DIST"
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

exact_cargo() {
    require_native_inputs
    prepare_source
    prepare_harfbuzz
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
        RUSTFLAGS='-C target-cpu=x86-64-v2 -C linker=/work/toolchain/bin/clang++ -C link-arg=-fuse-ld=lld -C link-arg=-no-pie -C link-arg=-Wl,--build-id=sha1 -C link-arg=-Wl,-z,relro -C link-arg=-Wl,-z,now -C link-arg=-Wl,-z,noexecstack -C link-arg=-Wl,--gc-sections -C debuginfo=0 -C strip=symbols --remap-path-prefix=/repo=/renderweave/src --remap-path-prefix=/work=/renderweave/build' \
        RENDERWEAVE_SKIA_ROOT="$RW_SKIA_ROOT" \
        RENDERWEAVE_SKIA_OUT="$RW_SKIA_OUT" \
        RENDERWEAVE_HARFBUZZ_ROOT="$RW_HARFBUZZ_ROOT" \
        RENDERWEAVE_HARFBUZZ_OUT="$RW_HARFBUZZ_OUT" \
        RENDERWEAVE_CLANGXX="$RW_WORK/toolchain/bin/clang++" \
        RENDERWEAVE_LLVM_AR="$RW_WORK/toolchain/bin/llvm-ar" \
        "$RW_RUST/bin/cargo" "$@"
}

test_engine() {
    exact_cargo test \
        --manifest-path "$RW_REPO/renderer/Cargo.toml" \
        -p renderweave-renderer-engine \
        --features native-text-skia \
        --test native_text_vectors \
        --release \
        --locked \
        --offline \
        -- --nocapture
    printf '%s\n' "T214_NATIVE_TEXT_ENGINE_TEST_PASSED"
}

test_daemon() {
    exact_cargo test \
        --manifest-path "$RW_REPO/renderer/Cargo.toml" \
        -p renderweave-renderer-daemon \
        --features native-text-skia \
        --lib production_ \
        --release \
        --locked \
        --offline \
        -- --nocapture
    printf '%s\n' "T214_NATIVE_TEXT_DAEMON_TEST_PASSED"
}

clippy_candidate() {
    exact_cargo clippy \
        --manifest-path "$RW_REPO/renderer/Cargo.toml" \
        -p renderweave-renderer-engine \
        -p renderweave-renderer-daemon \
        --features native-text-skia \
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
        --features native-text-skia \
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
        ;;
    *)
        fail "usage: $0 install-rust|test-engine|test-daemon|clippy|build-daemon|rehearse"
        ;;
esac
