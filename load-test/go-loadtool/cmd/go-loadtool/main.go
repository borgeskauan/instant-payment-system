package main

import (
	"flag"
	"fmt"
	"os"

	"instant-payment-system/load-test/go-loadtool/internal/config"
	"instant-payment-system/load-test/go-loadtool/internal/report"
	"instant-payment-system/load-test/go-loadtool/internal/sim"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: go-loadtool <simulate|report>")
		os.Exit(2)
	}

	switch os.Args[1] {
	case "simulate":
		if err := runSimulate(os.Args[2:]); err != nil {
			fmt.Fprintf(os.Stderr, "simulate failed: %v\n", err)
			os.Exit(1)
		}
	case "report":
		if err := runReport(os.Args[2:]); err != nil {
			fmt.Fprintf(os.Stderr, "report failed: %v\n", err)
			os.Exit(1)
		}
	default:
		fmt.Fprintf(os.Stderr, "unknown command: %s\n", os.Args[1])
		os.Exit(2)
	}
}

func runSimulate(args []string) error {
	cfg, err := parseSimulateConfig(args, config.LoadProfile)
	if err != nil {
		return err
	}

	return sim.Run(cfg)
}

type profileLoader func(string) (config.Runtime, error)

type simulateOverrides struct {
	outputDir                     string
	centralTransferCACert         string
	centralTransferClientCertRoot string
	centralTransferServerName     string
	gatewayCACert                 string
	gatewayClientCertRoot         string
	gatewayServerName             string
}

func parseSimulateConfig(args []string, loadProfile profileLoader) (sim.Config, error) {
	profileName := config.DefaultProfile
	var overrides simulateOverrides
	flags := flag.NewFlagSet("simulate", flag.ContinueOnError)
	flags.StringVar(&profileName, "profile", profileName, "load-test profile name")
	flags.StringVar(&overrides.outputDir, "out", "", "output directory")
	flags.StringVar(&overrides.centralTransferCACert, "central-transfer-ca-cert", "", "kafka-producer CA certificate path")
	flags.StringVar(&overrides.centralTransferClientCertRoot, "central-transfer-client-cert-root", "", "root directory containing PSP client certificates for kafka-producer")
	flags.StringVar(&overrides.centralTransferServerName, "central-transfer-server-name", "", "TLS server name for kafka-producer")
	flags.StringVar(&overrides.gatewayCACert, "gateway-ca-cert", "", "notification gateway CA certificate path")
	flags.StringVar(&overrides.gatewayClientCertRoot, "gateway-client-cert-root", "", "root directory containing psp-<ISPB>/client certs")
	flags.StringVar(&overrides.gatewayServerName, "gateway-server-name", "", "TLS server name for notification gateway")
	if err := flags.Parse(args); err != nil {
		return sim.Config{}, err
	}

	runtimeCfg, err := loadProfile(profileName)
	if err != nil {
		return sim.Config{}, err
	}
	cfg := runtimeCfg.Sim
	flags.Visit(func(parsedFlag *flag.Flag) {
		switch parsedFlag.Name {
		case "out":
			cfg.OutputDir = overrides.outputDir
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

	return cfg, nil
}

func runReport(args []string) error {
	command, err := parseReportConfig(args, config.LoadProfile)
	if err != nil {
		return err
	}

	return report.Print(command.startsPath, command.eventsPath, command.options, os.Stdout)
}

type reportConfig struct {
	startsPath string
	eventsPath string
	options    report.Options
}

func parseReportConfig(args []string, loadProfile profileLoader) (reportConfig, error) {
	var startsPath string
	var eventsPath string
	profileName := config.DefaultProfile
	flags := flag.NewFlagSet("report", flag.ContinueOnError)
	flags.StringVar(&profileName, "profile", profileName, "load-test profile name")
	flags.StringVar(&startsPath, "starts", "", "starts.csv path")
	flags.StringVar(&eventsPath, "events", "", "events.csv path")
	if err := flags.Parse(args); err != nil {
		return reportConfig{}, err
	}

	runtimeCfg, err := loadProfile(profileName)
	if err != nil {
		return reportConfig{}, err
	}
	if startsPath == "" || eventsPath == "" {
		return reportConfig{}, fmt.Errorf("--starts and --events are required")
	}

	return reportConfig{
		startsPath: startsPath,
		eventsPath: eventsPath,
		options: report.Options{
			SLAThresholdMs: runtimeCfg.SLAThresholdMs,
			TargetTxRate:   runtimeCfg.Sim.TargetTxRate,
			Warmup:         runtimeCfg.Sim.Warmup,
			Duration:       runtimeCfg.Sim.Duration,
		},
	}, nil
}
