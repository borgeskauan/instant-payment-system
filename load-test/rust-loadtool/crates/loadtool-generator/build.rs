fn main() -> Result<(), Box<dyn std::error::Error>> {
    let proto = "../../../../notification-gateway/src/main/proto/notification.proto";
    println!("cargo:rerun-if-changed={proto}");
    tonic_build::configure().compile_protos(
        &[proto],
        &["../../../../notification-gateway/src/main/proto"],
    )?;
    Ok(())
}
