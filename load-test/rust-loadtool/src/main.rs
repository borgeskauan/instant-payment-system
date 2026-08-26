use std::path::PathBuf;
use std::process::ExitCode;
use std::{io, io::Write};

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
    ValidateProfile(ValidateProfileArgs),
    Run(RunArgs),
}

#[derive(Debug, Args)]
struct ValidateProfileArgs {
    #[arg(long, default_value = "uniform-smoke")]
    profile: String,
}

#[derive(Debug, Args)]
struct RunArgs {
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
        Command::ValidateProfile(args) => {
            let result =
                rust_loadtool::profile::compile(&PathBuf::from("../profiles"), &args.profile)
                    .and_then(|plan| plan.encode_pretty())
                    .and_then(|encoded| {
                        io::stdout()
                            .write_all(&encoded)
                            .map_err(anyhow::Error::from)
                    });
            match result {
                Ok(()) => ExitCode::SUCCESS,
                Err(error) => {
                    eprintln!("validate-profile failed: {error:#}");
                    ExitCode::FAILURE
                }
            }
        }
        Command::Run(args) => {
            let options = SimulationOptions {
                central_transfer_ca_cert: args.central_transfer_ca_cert,
                central_transfer_client_cert_root: args.central_transfer_client_cert_root,
                central_transfer_server_name: args.central_transfer_server_name,
                gateway_ca_cert: args.gateway_ca_cert,
                gateway_client_cert_root: args.gateway_client_cert_root,
                gateway_server_name: args.gateway_server_name,
            };
            let result = rust_loadtool::run(rust_loadtool::RunOptions {
                run_dir: args.run_dir,
                generator: options,
            })
            .await;
            match result {
                Ok(()) => ExitCode::SUCCESS,
                Err(error) => {
                    eprintln!("load-tool run failed: {error:#}");
                    ExitCode::FAILURE
                }
            }
        }
    }
}
