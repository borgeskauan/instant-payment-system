package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"

	"instant-payment-system/load-test/go-loadtool/internal/config"
	"instant-payment-system/load-test/go-loadtool/internal/report"
	"instant-payment-system/load-test/go-loadtool/internal/sim"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: go-loadtool <validate-profile|simulate|report>")
		os.Exit(2)
	}

	switch os.Args[1] {
	case "validate-profile":
		if err := runValidateProfile(os.Args[2:]); err != nil {
			fmt.Fprintf(os.Stderr, "validate-profile failed: %v\n", err)
			os.Exit(1)
		}
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
	cfg := simulatorConfig(runtimeCfg)
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

func simulatorConfig(runtimeCfg config.Runtime) sim.Config {
	return sim.Config{
		BaseURL:                       runtimeCfg.Connections.CentralTransfer.BaseURL,
		CentralTransferCACert:         runtimeCfg.Connections.CentralTransfer.CACert,
		CentralTransferClientCertRoot: runtimeCfg.Connections.CentralTransfer.ClientCertRoot,
		CentralTransferServerName:     runtimeCfg.Connections.CentralTransfer.ServerName,
		GatewayAddress:                runtimeCfg.Connections.NotificationGateway.Address,
		GatewayCACert:                 runtimeCfg.Connections.NotificationGateway.CACert,
		GatewayClientCertRoot:         runtimeCfg.Connections.NotificationGateway.ClientCertRoot,
		GatewayServerName:             runtimeCfg.Connections.NotificationGateway.ServerName,
		TargetTxRate:                  runtimeCfg.Load.TargetTxRate,
		Warmup:                        runtimeCfg.Load.Warmup,
		Duration:                      runtimeCfg.Load.Duration,
		Drain:                         runtimeCfg.Load.Drain,
		Scenarios:                     runtimeCfg.Scenarios,
		OutputDir:                     config.DefaultOutputDir,
	}
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
			SLAThresholdMs: runtimeCfg.Reporting.SLAThresholdMs,
			TargetTxRate:   runtimeCfg.Load.TargetTxRate,
			Warmup:         runtimeCfg.Load.Warmup,
			Duration:       runtimeCfg.Load.Duration,
			Scenarios:      runtimeCfg.Scenarios,
		},
	}, nil
}

type profileValidation struct {
	Profile       string                      `json:"profile"`
	SchemaVersion int                         `json:"schemaVersion"`
	WarmupSeconds int64                       `json:"warmupSeconds"`
	ActiveSeconds int64                       `json:"activeSeconds"`
	DrainSeconds  int64                       `json:"drainSeconds"`
	Scenarios     []profileValidationScenario `json:"scenarios"`
}

type profileValidationScenario struct {
	Name         string                        `json:"name"`
	Share        float64                       `json:"share"`
	Participants profileValidationParticipants `json:"participants"`
	Amount       profileValidationAmount       `json:"amount"`
	Funding      profileValidationFunding      `json:"funding"`
	Provisioning profileValidationProvisioning `json:"provisioning"`
	Expectations profileValidationExpectations `json:"expectations"`
}

type profileValidationParticipants struct {
	PairNumberStart int     `json:"pairNumberStart"`
	HotPairCount    int     `json:"hotPairCount"`
	ColdPairCount   int     `json:"coldPairCount"`
	HotTrafficShare float64 `json:"hotTrafficShare"`
}

type profileValidationAmount struct {
	Minimum int64 `json:"minimum"`
	Maximum int64 `json:"maximum"`
}

type profileValidationFunding struct {
	Payer         profileValidationFundingAccount `json:"payer"`
	Receiver      profileValidationFundingAccount `json:"receiver"`
	ResetIfExists bool                            `json:"resetIfExists"`
}

type profileValidationFundingAccount struct {
	Mode    string `json:"mode"`
	Balance string `json:"balance,omitempty"`
}

type profileValidationProvisioning struct {
	PayerBalance    string `json:"payerBalance"`
	ReceiverBalance string `json:"receiverBalance"`
	ResetIfExists   bool   `json:"resetIfExists"`
}

type profileValidationExpectations struct {
	HTTPStatus        string                             `json:"httpStatus"`
	PayerNotification profileValidationPayerNotification `json:"payerNotification"`
}

type profileValidationPayerNotification struct {
	Count int `json:"count"`
}

func runValidateProfile(args []string) error {
	validation, err := parseValidateProfile(args, config.LoadProfile)
	if err != nil {
		return err
	}
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetIndent("", "  ")
	return encoder.Encode(validation)
}

func parseValidateProfile(args []string, loadProfile profileLoader) (profileValidation, error) {
	profileName := config.DefaultProfile
	flags := flag.NewFlagSet("validate-profile", flag.ContinueOnError)
	flags.StringVar(&profileName, "profile", profileName, "load-test profile name")
	if err := flags.Parse(args); err != nil {
		return profileValidation{}, err
	}
	if flags.NArg() != 0 {
		return profileValidation{}, fmt.Errorf("validate-profile accepts no positional arguments")
	}

	runtimeCfg, err := loadProfile(profileName)
	if err != nil {
		return profileValidation{}, err
	}
	provisioning, err := sim.DeriveProvisioning(simulatorConfig(runtimeCfg))
	if err != nil {
		return profileValidation{}, fmt.Errorf("derive provisioning for profile %q: %w", profileName, err)
	}
	validation := profileValidation{
		Profile:       profileName,
		SchemaVersion: runtimeCfg.SchemaVersion,
		WarmupSeconds: int64(runtimeCfg.Load.Warmup.Seconds()),
		ActiveSeconds: int64(runtimeCfg.Load.Duration.Seconds()),
		DrainSeconds:  int64(runtimeCfg.Load.Drain.Seconds()),
		Scenarios:     make([]profileValidationScenario, len(runtimeCfg.Scenarios)),
	}
	for index, scenario := range runtimeCfg.Scenarios {
		validation.Scenarios[index] = profileValidationScenario{
			Name:  scenario.Name,
			Share: scenario.Share,
			Participants: profileValidationParticipants{
				PairNumberStart: scenario.Participants.PairNumberStart,
				HotPairCount:    scenario.Participants.HotPairCount,
				ColdPairCount:   scenario.Participants.ColdPairCount,
				HotTrafficShare: scenario.Participants.HotTrafficShare,
			},
			Amount: profileValidationAmount{
				Minimum: scenario.Amount.Minimum,
				Maximum: scenario.Amount.Maximum,
			},
			Funding: profileValidationFunding{
				Payer: profileValidationFundingAccount{
					Mode:    scenario.Funding.Payer.Mode,
					Balance: scenario.Funding.Payer.Balance,
				},
				Receiver: profileValidationFundingAccount{
					Mode:    scenario.Funding.Receiver.Mode,
					Balance: scenario.Funding.Receiver.Balance,
				},
				ResetIfExists: scenario.Funding.ResetIfExists,
			},
			Provisioning: profileValidationProvisioning{
				PayerBalance:    provisioning[index].PayerBalance,
				ReceiverBalance: provisioning[index].ReceiverBalance,
				ResetIfExists:   provisioning[index].ResetIfExists,
			},
			Expectations: profileValidationExpectations{
				HTTPStatus: scenario.Expectations.HTTPStatus,
				PayerNotification: profileValidationPayerNotification{
					Count: scenario.Expectations.PayerNotification.Count,
				},
			},
		}
	}
	return validation, nil
}
