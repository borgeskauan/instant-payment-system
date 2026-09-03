//! Application boundary for the Rust load tool.

use std::future::Future;
use std::path::{Path, PathBuf};

use anyhow::Result;
use loadtool_contract::bundle::Bundle;
use loadtool_contract::generation_window::GenerationWindow;
use loadtool_generator::simulator::{self, SimulationOptions};

pub mod profile;

#[derive(Clone, Debug)]
pub struct RunOptions {
    pub run_dir: PathBuf,
    pub generator: SimulationOptions,
}

pub async fn run(options: RunOptions) -> Result<()> {
    run_with(
        &options.run_dir,
        options.generator,
        simulator::run,
        loadtool_report::write,
    )
    .await
}

#[doc(hidden)]
pub async fn run_with<G, F, R>(
    run_dir: &Path,
    options: SimulationOptions,
    generator: G,
    reporter: R,
) -> Result<()>
where
    G: FnOnce(Bundle, SimulationOptions) -> F,
    F: Future<Output = Result<GenerationWindow>>,
    R: FnOnce(&Bundle, GenerationWindow) -> Result<()>,
{
    let bundle = Bundle::resolve(run_dir)?;
    bundle.load_prepared()?;
    let window = generator(bundle.clone(), options).await?;
    reporter(&bundle, window)?;
    Ok(())
}
