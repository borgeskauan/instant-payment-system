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
	runtimeCfg, err := config.LoadDefault()
	if err != nil {
		return err
	}
	cfg := runtimeCfg.Sim
	flags := flag.NewFlagSet("simulate", flag.ContinueOnError)
	flags.StringVar(&cfg.OutputDir, "out", cfg.OutputDir, "output directory")
	flags.StringVar(&cfg.GatewayCACert, "gateway-ca-cert", cfg.GatewayCACert, "notification gateway CA certificate path")
	flags.StringVar(&cfg.GatewayClientCertRoot, "gateway-client-cert-root", cfg.GatewayClientCertRoot, "root directory containing psp-<ISPB>/client certs")
	flags.StringVar(&cfg.GatewayServerName, "gateway-server-name", cfg.GatewayServerName, "TLS server name for notification gateway")
	if err := flags.Parse(args); err != nil {
		return err
	}

	return sim.Run(cfg)
}

func runReport(args []string) error {
	var startsPath string
	var eventsPath string
	flags := flag.NewFlagSet("report", flag.ContinueOnError)
	flags.StringVar(&startsPath, "starts", "", "starts.csv path")
	flags.StringVar(&eventsPath, "events", "", "events.csv path")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if startsPath == "" || eventsPath == "" {
		return fmt.Errorf("--starts and --events are required")
	}

	runtimeCfg, err := config.LoadDefault()
	if err != nil {
		return err
	}

	return report.Print(startsPath, eventsPath, report.Options{
		SLAThresholdMs: runtimeCfg.SLAThresholdMs,
		TargetTxRate:   runtimeCfg.Sim.TargetTxRate,
		Warmup:         runtimeCfg.Sim.Warmup,
		Duration:       runtimeCfg.Sim.Duration,
	}, os.Stdout)
}
