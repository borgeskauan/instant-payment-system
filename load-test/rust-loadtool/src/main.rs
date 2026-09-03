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
    #[arg(long)]
    client_cert_root: Option<PathBuf>,
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
                    ExitCode::from(2)
                }
            }
        }
        Command::Run(args) => {
            let options = SimulationOptions {
                client_cert_root: args.client_cert_root,
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
                    ExitCode::from(2)
                }
            }
        }
    }
}
