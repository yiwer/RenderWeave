use std::env;
use std::fs::File;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

fn main() {
    println!("cargo:rerun-if-changed=native/native_text_skia.cpp");
    for name in [
        "RENDERWEAVE_SKIA_ROOT",
        "RENDERWEAVE_SKIA_OUT",
        "RENDERWEAVE_HARFBUZZ_ROOT",
        "RENDERWEAVE_HARFBUZZ_OUT",
        "RENDERWEAVE_CLANGXX",
        "RENDERWEAVE_LLVM_AR",
    ] {
        println!("cargo:rerun-if-env-changed={name}");
    }

    if env::var_os("CARGO_FEATURE_NATIVE_TEXT_SKIA").is_none() {
        return;
    }
    if env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("linux") {
        panic!("native-text-skia is only buildable for the exact Linux renderer target");
    }

    let skia_root = required_path("RENDERWEAVE_SKIA_ROOT");
    let skia_out = required_path("RENDERWEAVE_SKIA_OUT");
    let harfbuzz_root = required_path("RENDERWEAVE_HARFBUZZ_ROOT");
    let harfbuzz_out = required_path("RENDERWEAVE_HARFBUZZ_OUT");
    let clangxx = required_path("RENDERWEAVE_CLANGXX");
    let llvm_ar = required_path("RENDERWEAVE_LLVM_AR");
    let out_dir = PathBuf::from(env::var_os("OUT_DIR").expect("Cargo OUT_DIR is absent"));
    let object = out_dir.join("native_text_skia.o");
    let archive = out_dir.join("librenderweave_native_text.a");

    require_file(&skia_out.join("libskia.a"));
    require_file(&skia_out.join("libfreetype2.a"));
    require_file(&skia_out.join("libskcms.a"));
    require_file(&harfbuzz_out.join("libharfbuzz.a"));
    require_file(&clangxx);
    require_file(&llvm_ar);

    checked(
        Command::new(&clangxx)
            .arg("-std=c++20")
            .arg("-O2")
            .arg("-march=x86-64-v2")
            .arg("-mtune=generic")
            .arg("-mno-avx")
            .arg("-mno-avx2")
            .arg("-mno-fma")
            .arg("-fno-fast-math")
            .arg("-ffp-contract=off")
            .arg("-fPIC")
            .arg("-fno-exceptions")
            .arg("-fno-rtti")
            .arg("-ffile-prefix-map=/work=/renderweave/build")
            .arg(format!("-I{}", skia_root.display()))
            .arg(format!("-I{}/src", harfbuzz_root.display()))
            .arg("native/native_text_skia.cpp")
            .arg("-c")
            .arg("-o")
            .arg(&object),
        "compile native Text adapter",
    );

    let mut mri = File::create(out_dir.join("native-text.mri"))
        .expect("native Text archive script could not be created");
    writeln!(mri, "create {}", archive.display()).expect("write native Text archive script");
    writeln!(mri, "addmod {}", object.display()).expect("write native Text archive script");
    for library in ["libskia.a", "libfreetype2.a", "libskcms.a"] {
        writeln!(mri, "addlib {}", skia_out.join(library).display())
            .expect("write native Text archive script");
    }
    writeln!(
        mri,
        "addlib {}",
        harfbuzz_out.join("libharfbuzz.a").display()
    )
    .expect("write native Text archive script");
    writeln!(mri, "save\nend").expect("write native Text archive script");
    drop(mri);

    let script = File::open(out_dir.join("native-text.mri"))
        .expect("native Text archive script could not be reopened");
    checked(
        Command::new(&llvm_ar).arg("-M").stdin(Stdio::from(script)),
        "archive native Text adapter",
    );

    println!("cargo:rustc-link-search=native={}", out_dir.display());
    println!("cargo:rustc-link-lib=static=renderweave_native_text");
    println!("cargo:rustc-link-lib=dylib=stdc++");
    println!("cargo:rustc-link-lib=dylib=dl");
    println!("cargo:rustc-link-lib=dylib=pthread");
    println!("cargo:rustc-link-lib=dylib=m");
}

fn required_path(name: &str) -> PathBuf {
    PathBuf::from(env::var_os(name).unwrap_or_else(|| panic!("{name} is required")))
}

fn require_file(path: &Path) {
    assert!(
        path.is_file(),
        "required exact native input is absent: {}",
        path.display()
    );
}

fn checked(command: &mut Command, purpose: &str) {
    let status = command
        .status()
        .unwrap_or_else(|error| panic!("could not {purpose}: {error}"));
    assert!(status.success(), "failed to {purpose}: {status}");
}
