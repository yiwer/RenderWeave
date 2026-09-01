use std::env;
use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

fn main() {
    println!("cargo:rerun-if-changed=native/jpeg_encoder.c");
    for name in [
        "RENDERWEAVE_JPEG_TURBO_ROOT",
        "RENDERWEAVE_JPEG_TURBO_OUT",
        "RENDERWEAVE_CANONICAL_SRGB_ICC",
        "RENDERWEAVE_CLANG",
        "RENDERWEAVE_LLVM_AR",
    ] {
        println!("cargo:rerun-if-env-changed={name}");
    }

    if env::var_os("CARGO_FEATURE_NATIVE_JPEG_TURBO").is_none() {
        return;
    }
    if env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("linux") {
        panic!("native-jpeg-turbo is only buildable for the exact Linux renderer target");
    }

    let source_root = required_path("RENDERWEAVE_JPEG_TURBO_ROOT");
    let build_root = required_path("RENDERWEAVE_JPEG_TURBO_OUT");
    let icc = required_path("RENDERWEAVE_CANONICAL_SRGB_ICC");
    let clang = required_path("RENDERWEAVE_CLANG");
    let llvm_ar = required_path("RENDERWEAVE_LLVM_AR");
    let out_dir = PathBuf::from(env::var_os("OUT_DIR").expect("Cargo OUT_DIR is absent"));
    let object = out_dir.join("jpeg_encoder.o");
    let archive = out_dir.join("librenderweave_native_jpeg.a");
    let libjpeg = build_root.join("libjpeg.a");

    for path in [
        source_root.join("src/jpeglib.h"),
        build_root.join("jconfig.h"),
        libjpeg.clone(),
        icc.clone(),
        clang.clone(),
        llvm_ar.clone(),
    ] {
        require_file(&path);
    }
    let icc_bytes = fs::read(&icc).expect("canonical sRGB ICC could not be read");
    assert_eq!(3_144, icc_bytes.len(), "canonical sRGB ICC length drifted");
    fs::write(out_dir.join("canonical-srgb.icc"), icc_bytes)
        .expect("canonical sRGB ICC could not be staged for compilation");

    checked(
        Command::new(&clang)
            .arg("-std=c17")
            .arg("-O2")
            .arg("-march=x86-64-v2")
            .arg("-mtune=generic")
            .arg("-mno-avx")
            .arg("-mno-avx2")
            .arg("-mno-fma")
            .arg("-fno-fast-math")
            .arg("-ffp-contract=off")
            .arg("-fPIC")
            .arg("-ffile-prefix-map=/work=/renderweave/build")
            .arg(format!("-I{}", source_root.join("src").display()))
            .arg(format!("-I{}", build_root.display()))
            .arg("native/jpeg_encoder.c")
            .arg("-c")
            .arg("-o")
            .arg(&object),
        "compile native JPEG adapter",
    );

    let mut mri = File::create(out_dir.join("native-jpeg.mri"))
        .expect("native JPEG archive script could not be created");
    writeln!(mri, "create {}", archive.display()).expect("write native JPEG archive script");
    writeln!(mri, "addmod {}", object.display()).expect("write native JPEG archive script");
    writeln!(mri, "addlib {}", libjpeg.display()).expect("write native JPEG archive script");
    writeln!(mri, "save\nend").expect("write native JPEG archive script");
    drop(mri);

    let script = File::open(out_dir.join("native-jpeg.mri"))
        .expect("native JPEG archive script could not be reopened");
    checked(
        Command::new(&llvm_ar).arg("-M").stdin(Stdio::from(script)),
        "archive native JPEG adapter",
    );

    println!("cargo:rustc-link-search=native={}", out_dir.display());
    println!("cargo:rustc-link-lib=static=renderweave_native_jpeg");
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
