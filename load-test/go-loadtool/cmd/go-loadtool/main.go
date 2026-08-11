package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"os"

	"instant-payment-system/load-test/go-loadtool/internal/config"
	"instant-payment-system/load-test/go-loadtool/internal/report"
	"instant-payment-system/load-test/go-loadtool/internal/runwindow"
	"instant-payment-system/load-test/go-loadtool/internal/sim"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: go-loadtool <validate-profile|simulate|report|run>")
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
	case "run":
		if err := runRun(os.Args[2:]); err != nil {
			fmt.Fprintf(os.Stderr, "run failed: %v\n", err)
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
	runWindowPath                 string
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
	flags.StringVar(&overrides.runWindowPath, "run-window", "", "authoritative run-window.json output path")
	registerMTLSOverrides(flags, &overrides)
	if err := flags.Parse(args); err != nil {
		return sim.Config{}, err
	}

	runtimeCfg, err := loadProfile(profileName)
	if err != nil {
		return sim.Config{}, err
	}
	cfg := simulatorConfig(runtimeCfg)
	cfg.ProfileName = runtimeCfg.Name
	flags.Visit(func(parsedFlag *flag.Flag) {
		switch parsedFlag.Name {
		case "out":
			cfg.OutputDir = overrides.outputDir
		case "run-window":
			cfg.RunWindowPath = overrides.runWindowPath
		}
	})
	applyMTLSOverrides(flags, &cfg, overrides)

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
		Replay:                        runtimeCfg.Replay,
		Scenarios:                     runtimeCfg.Scenarios,
		OutputDir:                     config.DefaultOutputDir,
	}
}

func runReport(args []string) error {
	command, err := parseReportConfig(args, config.LoadProfile)
	if err != nil {
		return err
	}
	return renderReport(command, os.Stdout)
}

func renderReport(command reportConfig, output io.Writer) error {
	document, err := runwindow.Read(command.runWindowPath)
	if err != nil {
		return err
	}
	window, err := runwindow.Resolve(document, command.profileName, command.options.Warmup, command.options.Duration, command.options.Drain, command.options.Replay)
	if err != nil {
		return err
	}
	if err := validateReportArtifacts(command, document.SchemaVersion); err != nil {
		return err
	}
	command.options.Window = window
	return report.PrintWithArtifacts(command.startsPath, command.eventsPath, command.statusStartsPath, command.replaysPath, command.options, output)
}

type reportConfig struct {
	startsPath       string
	eventsPath       string
	replaysPath      string
	statusStartsPath string
	runWindowPath    string
	profileName      string
	options          report.Options
}

func parseReportConfig(args []string, loadProfile profileLoader) (reportConfig, error) {
	var startsPath string
	var eventsPath string
	var replaysPath string
	var statusStartsPath string
	var runWindowPath string
	profileName := config.DefaultProfile
	flags := flag.NewFlagSet("report", flag.ContinueOnError)
	flags.StringVar(&profileName, "profile", profileName, "load-test profile name")
	flags.StringVar(&startsPath, "starts", "", "starts.csv path")
	flags.StringVar(&eventsPath, "events", "", "events.csv path")
	flags.StringVar(&replaysPath, "replays", "", "replays.csv path")
	flags.StringVar(&statusStartsPath, "status-starts", "", "status-starts.csv path")
	flags.StringVar(&runWindowPath, "run-window", "", "authoritative run-window.json path")
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
	if runWindowPath == "" {
		return reportConfig{}, fmt.Errorf("--run-window is required")
	}
	if (runtimeCfg.Replay.Pacs008 != nil || runtimeCfg.Replay.Pacs002 != nil) && replaysPath == "" {
		return reportConfig{}, fmt.Errorf("--replays is required for profile %q", runtimeCfg.Name)
	}

	return reportConfig{
		startsPath:       startsPath,
		eventsPath:       eventsPath,
		replaysPath:      replaysPath,
		statusStartsPath: statusStartsPath,
		runWindowPath:    runWindowPath,
		profileName:      runtimeCfg.Name,
		options:          reportOptions(runtimeCfg),
	}, nil
}

func validateReportArtifacts(command reportConfig, windowSchemaVersion int) error {
	if windowSchemaVersion == runwindow.SchemaVersion && command.options.Replay.Pacs002 != nil && command.statusStartsPath == "" {
		return fmt.Errorf("--status-starts is required for profile %q", command.profileName)
	}
	return nil
}

type profileValidation struct {
	Profile       string                      `json:"profile"`
	SchemaVersion int                         `json:"schemaVersion"`
	WarmupSeconds int64                       `json:"warmupSeconds"`
	ActiveSeconds int64                       `json:"activeSeconds"`
	DrainSeconds  int64                       `json:"drainSeconds"`
	Replay        profileValidationReplay     `json:"replay"`
	Scenarios     []profileValidationScenario `json:"scenarios"`
}

type profileValidationReplay struct {
	Pacs008 *profileValidationPacs008Replay `json:"pacs008,omitempty"`
	Pacs002 *profileValidationPacs002Replay `json:"pacs002,omitempty"`
}

type profileValidationPacs008Replay struct {
	Share        float64 `json:"share"`
	DelaySeconds int64   `json:"delaySeconds"`
}

type profileValidationPacs002Replay struct {
	Share        float64 `json:"share"`
	DelaySeconds int64   `json:"delaySeconds"`
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
	DeliverySemantics string   `json:"deliverySemantics"`
	Status            string   `json:"status"`
	ReasonCodes       []string `json:"reasonCodes"`
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
		return profileValidation{}, fmt.Errorf("derive provisioning for profile %q: %w", runtimeCfg.Name, err)
	}
	validation := profileValidation{
		Profile:       runtimeCfg.Name,
		SchemaVersion: runtimeCfg.SchemaVersion,
		WarmupSeconds: int64(runtimeCfg.Load.Warmup.Seconds()),
		ActiveSeconds: int64(runtimeCfg.Load.Duration.Seconds()),
		DrainSeconds:  int64(runtimeCfg.Load.Drain.Seconds()),
		Scenarios:     make([]profileValidationScenario, len(runtimeCfg.Scenarios)),
	}
	if runtimeCfg.Replay.Pacs008 != nil {
		validation.Replay.Pacs008 = &profileValidationPacs008Replay{
			Share:        runtimeCfg.Replay.Pacs008.Share,
			DelaySeconds: int64(runtimeCfg.Replay.Pacs008.Delay.Seconds()),
		}
	}
	if runtimeCfg.Replay.Pacs002 != nil {
		validation.Replay.Pacs002 = &profileValidationPacs002Replay{
			Share:        runtimeCfg.Replay.Pacs002.Share,
			DelaySeconds: int64(runtimeCfg.Replay.Pacs002.Delay.Seconds()),
		}
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
					DeliverySemantics: scenario.Expectations.PayerNotification.DeliverySemantics,
					Status:            scenario.Expectations.PayerNotification.Status,
					ReasonCodes:       scenario.Expectations.PayerNotification.ReasonCodes,
				},
			},
		}
	}
	return validation, nil
}
