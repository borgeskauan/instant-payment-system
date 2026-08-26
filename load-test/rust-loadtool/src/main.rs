use std::path::PathBuf;
use std::process::ExitCode;

use clap::{Args, Parser, Subcommand};
use loadtool_generator::simulator::SimulationOptions;

#[derive(Debug, Parser)]
#[command(name = "rust-loadtool")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Debug, Subcommand)]
enum Command {
    Simulate(SimulateArgs),
}

#[derive(Debug, Args)]
struct SimulateArgs {
    #[arg(long)]
    run_dir: PathBuf,
    #[arg(long, hide = true)]
    central_transfer_ca_cert: Option<PathBuf>,
    #[arg(long, hide = true)]
    central_transfer_client_cert_root: Option<PathBuf>,
    #[arg(long, hide = true)]
    central_transfer_server_name: Option<String>,
    #[arg(long, hide = true)]
    gateway_ca_cert: Option<PathBuf>,
    #[arg(long, hide = true)]
    gateway_client_cert_root: Option<PathBuf>,
    #[arg(long, hide = true)]
    gateway_server_name: Option<String>,
}

#[tokio::main]
async fn main() -> ExitCode {
    match Cli::parse().command {
        Command::Simulate(args) => {
            let options = SimulationOptions {
                central_transfer_ca_cert: args.central_transfer_ca_cert,
                central_transfer_client_cert_root: args.central_transfer_client_cert_root,
                central_transfer_server_name: args.central_transfer_server_name,
                gateway_ca_cert: args.gateway_ca_cert,
                gateway_client_cert_root: args.gateway_client_cert_root,
                gateway_server_name: args.gateway_server_name,
            };
            let result = rust_loadtool::simulate(&args.run_dir, options).await;
            match result {
                Ok(()) => ExitCode::SUCCESS,
                Err(error) => {
                    eprintln!("Rust simulation failed: {error:#}");
                    ExitCode::FAILURE
                }
            }
        }
    }
}
