use std::path::PathBuf;
use std::process::ExitCode;

use clap::{Args, Parser, Subcommand};

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

fn main() -> ExitCode {
    match Cli::parse().command {
        Command::Simulate(args) => {
            let _ = (
                args.run_dir,
                args.central_transfer_ca_cert,
                args.central_transfer_client_cert_root,
                args.central_transfer_server_name,
                args.gateway_ca_cert,
                args.gateway_client_cert_root,
                args.gateway_server_name,
            );
            eprintln!("simulation is not implemented");
            ExitCode::FAILURE
        }
    }
}
