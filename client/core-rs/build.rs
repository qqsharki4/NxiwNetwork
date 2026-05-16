use std::env;
use std::path::PathBuf;
use std::process::Command;

fn main() {
    println!("cargo:rerun-if-changed=native/backend.c");
    println!("cargo:rerun-if-changed=native/backend.h");

    let out_dir = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR is required"));
    let object_file = out_dir.join("backend.o");
    let library_file = out_dir.join("libnxiw_rs_c.a");

    let cc = env::var("CC").unwrap_or_else(|_| "cc".to_string());
    let status = Command::new(&cc)
        .args([
            "-O3",
            "-std=c11",
            "-Wall",
            "-Wextra",
            "-c",
            "native/backend.c",
            "-o",
        ])
        .arg(&object_file)
        .status()
        .expect("failed to run C compiler");
    assert!(status.success(), "C compiler failed");

    let ar = env::var("AR").unwrap_or_else(|_| "ar".to_string());
    let status = Command::new(&ar)
        .arg("crs")
        .arg(&library_file)
        .arg(&object_file)
        .status()
        .expect("failed to run ar");
    assert!(status.success(), "ar failed");

    println!("cargo:rustc-link-search=native={}", out_dir.display());
    println!("cargo:rustc-link-lib=static=nxiw_rs_c");
}
