package main

import (
	"bytes"
	"flag"
	"fmt"
	"io"
	"os"

	"instant-payment-system/load-test/go-loadtool/internal/config"
	"instant-payment-system/load-test/go-loadtool/internal/report"
	"instant-payment-system/load-test/go-loadtool/internal/runbundle"
	"instant-payment-system/load-test/go-loadtool/internal/runwindow"
	"instant-payment-system/load-test/go-loadtool/internal/sim"
)

type runProfileLoader func(string) (config.Runtime, error)

type runConfig struct {
	layout    runbundle.Layout
	runtime   config.Runtime
	simulator sim.Config
}

type runDependencies struct {
	loadProfile  runProfileLoader
	simulate     func(sim.Config) error
	renderReport func(runbundle.Layout, config.Runtime, io.Writer) error
	stdout       io.Writer
}

func runRun(args []string) error {
	return executeRun(args, runDependencies{
		loadProfile:  config.LoadRunProfile,
		simulate:     sim.Run,
		renderReport: renderRunReport,
		stdout:       os.Stdout,
	})
}

func executeRun(args []string, dependencies runDependencies) error {
	command, err := parseRunConfig(args, dependencies.loadProfile)
	if err != nil {
		return err
	}
	if err := command.layout.PrepareOutputs(); err != nil {
		return err
	}
	if err := dependencies.simulate(command.simulator); err != nil {
		return fmt.Errorf("simulate run: %w", err)
	}

	var output bytes.Buffer
	if err := dependencies.renderReport(command.layout, command.runtime, &output); err != nil {
		return fmt.Errorf("render run report: %w", err)
	}
	if err := command.layout.WriteReportAtomically(output.Bytes()); err != nil {
		return err
	}
	if _, err := fmt.Fprintf(dependencies.stdout, "report written to %s\n", command.layout.Report); err != nil {
		return fmt.Errorf("write run result: %w", err)
	}
	return nil
}

func parseRunConfig(args []string, loadProfile runProfileLoader) (runConfig, error) {
	var runDir string
	var overrides mTLSOverrides
	flags := flag.NewFlagSet("run", flag.ContinueOnError)
	flags.StringVar(&runDir, "run-dir", "", "prepared run directory")
	registerMTLSOverrides(flags, &overrides)
	if err := flags.Parse(args); err != nil {
		return runConfig{}, err
	}
	if flags.NArg() != 0 {
		return runConfig{}, fmt.Errorf("run accepts no positional arguments")
	}
	if runDir == "" {
		return runConfig{}, fmt.Errorf("--run-dir is required")
	}

	layout, err := runbundle.Resolve(runDir)
	if err != nil {
		return runConfig{}, err
	}
	if err := layout.ValidatePrepared(); err != nil {
		return runConfig{}, err
	}
	runtimeCfg, err := loadProfile(layout.Profile)
	if err != nil {
		return runConfig{}, err
	}

	simulator := simulatorConfig(runtimeCfg)
	simulator.ProfileName = runtimeCfg.Name
	simulator.OutputDir = layout.EventsDir
	simulator.RunWindowPath = layout.RunWindow
	applyMTLSOverrides(flags, &simulator, overrides)

	return runConfig{
		layout:    layout,
		runtime:   runtimeCfg,
		simulator: simulator,
	}, nil
}

func renderRunReport(layout runbundle.Layout, runtimeCfg config.Runtime, output io.Writer) error {
	document, err := runwindow.Read(layout.RunWindow)
	if err != nil {
		return err
	}
	options := reportOptions(runtimeCfg)
	window, err := runwindow.Resolve(document, runtimeCfg.Name, options.Warmup, options.Duration, options.Drain, options.Replay)
	if err != nil {
		return err
	}
	options.Window = window
	return report.PrintWithArtifacts(layout.Pacs008Starts, layout.Notifications, layout.Pacs002Starts, layout.Replays, options, output)
}

func registerMTLSOverrides(flags *flag.FlagSet, overrides *mTLSOverrides) {
	flags.StringVar(&overrides.centralTransferCACert, "central-transfer-ca-cert", "", "kafka-producer CA certificate path")
	flags.StringVar(&overrides.centralTransferClientCertRoot, "central-transfer-client-cert-root", "", "root directory containing PSP client certificates for kafka-producer")
	flags.StringVar(&overrides.centralTransferServerName, "central-transfer-server-name", "", "TLS server name for kafka-producer")
	flags.StringVar(&overrides.gatewayCACert, "gateway-ca-cert", "", "notification gateway CA certificate path")
	flags.StringVar(&overrides.gatewayClientCertRoot, "gateway-client-cert-root", "", "root directory containing psp-<ISPB>/client certs")
	flags.StringVar(&overrides.gatewayServerName, "gateway-server-name", "", "TLS server name for notification gateway")
}

func applyMTLSOverrides(flags *flag.FlagSet, cfg *sim.Config, overrides mTLSOverrides) {
	flags.Visit(func(parsedFlag *flag.Flag) {
		switch parsedFlag.Name {
		case "central-transfer-ca-cert":
			cfg.CentralTransferCACert = overrides.centralTransferCACert
		case "central-transfer-client-cert-root":
			cfg.CentralTransferClientCertRoot = overrides.centralTransferClientCertRoot
		case "central-transfer-server-name":
			cfg.CentralTransferServerName = overrides.centralTransferServerName
		case "gateway-ca-cert":
			cfg.GatewayCACert = overrides.gatewayCACert
		case "gateway-client-cert-root":
			cfg.GatewayClientCertRoot = overrides.gatewayClientCertRoot
		case "gateway-server-name":
			cfg.GatewayServerName = overrides.gatewayServerName
		}
	})
}

func reportOptions(runtimeCfg config.Runtime) report.Options {
	return report.Options{
		SLAThresholdMs: runtimeCfg.Reporting.SLAThresholdMs,
		TargetTxRate:   runtimeCfg.Load.TargetTxRate,
		Warmup:         runtimeCfg.Load.Warmup,
		Duration:       runtimeCfg.Load.Duration,
		Drain:          runtimeCfg.Load.Drain,
		Replay:         runtimeCfg.Replay,
		Scenarios:      runtimeCfg.Scenarios,
	}
}
