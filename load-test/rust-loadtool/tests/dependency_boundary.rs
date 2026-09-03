#[test]
fn generator_manifest_has_no_report_dependency() {
    let manifest = std::fs::read_to_string(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/crates/loadtool-generator/Cargo.toml"
    ))
    .expect("read generator manifest");

    assert!(!manifest.contains("loadtool-report"));
}
