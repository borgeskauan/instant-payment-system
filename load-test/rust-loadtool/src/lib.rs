//! Application boundary for the Rust load tool.

use std::path::Path;

use anyhow::Result;
use loadtool_contract::bundle::Bundle;
use loadtool_generator::simulator::{self, SimulationOptions};

pub async fn simulate(run_dir: &Path, options: SimulationOptions) -> Result<()> {
    let bundle = Bundle::resolve(run_dir)?;
    simulator::run(bundle, options).await
}
