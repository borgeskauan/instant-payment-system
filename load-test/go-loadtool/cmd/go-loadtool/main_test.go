package main

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/config"
)

func TestSimulateUsesExplicitProfile(t *testing.T) {
	var loadedProfile string
	loader := func(name string) (config.Runtime, error) {
		loadedProfile = name
		return commandTestRuntime(), nil
	}

	cfg, err := parseSimulateConfig([]string{"--profile", "custom-smoke"}, loader)
	if err != nil {
		t.Fatal(err)
	}

	if loadedProfile != "custom-smoke" {
		t.Fatalf("loaded profile = %q, want custom-smoke", loadedProfile)
	}
	if cfg.TargetTxRate != 321 || cfg.Warmup != 12*time.Second || cfg.Duration != 34*time.Second {
		t.Fatalf("simulate settings were not loaded from selected profile: %#v", cfg)
	}
	if cfg.Replay.Pacs008 == nil || cfg.Replay.Pacs008.Share != 0.25 || cfg.Replay.Pacs008.Delay != 7*time.Second {
		t.Fatalf("simulate replay settings were not loaded from selected profile: %#v", cfg.Replay)
	}
	if cfg.ProfileName != "custom-smoke" || cfg.Replay.Pacs002 == nil || cfg.Replay.Pacs002.Share != 0.20 || cfg.Replay.Pacs002.Delay != 11*time.Second {
		t.Fatalf("simulate PACS.002 settings = %#v", cfg)
	}
}

func TestReportUsesExplicitProfile(t *testing.T) {
	var loadedProfile string
	loader := func(name string) (config.Runtime, error) {
		loadedProfile = name
		return commandTestRuntime(), nil
	}

	command, err := parseReportConfig([]string{
		"--profile", "custom-report",
		"--starts", "starts.csv",
		"--events", "events.csv",
		"--status-starts", "status-starts.csv",
		"--replays", "replays.csv",
		"--run-window", "run-window.json",
	}, loader)
	if err != nil {
		t.Fatal(err)
	}

	if loadedProfile != "custom-report" {
		t.Fatalf("loaded profile = %q, want custom-report", loadedProfile)
	}
	if command.options.TargetTxRate != 321 || command.options.Warmup != 12*time.Second || command.options.Duration != 34*time.Second || command.options.SLAThresholdMs != 987 {
		t.Fatalf("report settings were not loaded from selected profile: %#v", command.options)
	}
	if len(command.options.Scenarios) != 1 || command.options.Scenarios[0].Name != "happy-path" {
		t.Fatalf("report scenarios = %#v", command.options.Scenarios)
	}
	if command.replaysPath != "replays.csv" || command.statusStartsPath != "status-starts.csv" || command.runWindowPath != "run-window.json" || command.options.Replay.Pacs008 == nil || command.options.Replay.Pacs008.Delay != 7*time.Second || command.options.Replay.Pacs002 == nil || command.options.Replay.Pacs002.Delay != 11*time.Second {
		t.Fatalf("report replay settings = %#v / %q", command.options.Replay, command.replaysPath)
	}
}

func TestReportRequiresReplayArtifactOnlyForReplayProfiles(t *testing.T) {
	loader := func(string) (config.Runtime, error) { return commandTestRuntime(), nil }
	if _, err := parseReportConfig([]string{"--starts", "starts.csv", "--events", "events.csv", "--status-starts", "status-starts.csv", "--run-window", "run-window.json"}, loader); err == nil || !strings.Contains(err.Error(), "--replays is required") {
		t.Fatalf("replay-enabled report error = %v", err)
	}

	withoutReplay := commandTestRuntime()
	withoutReplay.Replay = config.Replay{}
	command, err := parseReportConfig([]string{"--starts", "starts.csv", "--events", "events.csv", "--run-window", "run-window.json"}, func(string) (config.Runtime, error) {
		return withoutReplay, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if command.replaysPath != "" {
		t.Fatalf("replaysPath = %q, want empty", command.replaysPath)
	}
}

func TestReportRequiresStatusStartsForPacs002Replay(t *testing.T) {
	command, err := parseReportConfig([]string{
		"--starts", "starts.csv",
		"--events", "events.csv",
		"--replays", "replays.csv",
		"--run-window", "run-window.json",
	}, func(string) (config.Runtime, error) { return commandTestRuntime(), nil })
	if err != nil {
		t.Fatal(err)
	}
	err = validateReportArtifacts(command, 2)
	if err == nil || !strings.Contains(err.Error(), "--status-starts is required") {
		t.Fatalf("error = %v", err)
	}
}

func TestReportRequiresAuthoritativeRunWindow(t *testing.T) {
	_, err := parseReportConfig([]string{
		"--starts", "starts.csv",
		"--events", "events.csv",
		"--status-starts", "status-starts.csv",
		"--replays", "replays.csv",
	}, func(string) (config.Runtime, error) { return commandTestRuntime(), nil })
	if err == nil || !strings.Contains(err.Error(), "--run-window is required") {
		t.Fatalf("error = %v", err)
	}
}

func TestCommandsDefaultToUniformSmokeProfile(t *testing.T) {
	for _, test := range []struct {
		name string
		run  func(profileLoader) error
	}{
		{
			name: "validate-profile",
			run: func(loader profileLoader) error {
				_, err := parseValidateProfile(nil, loader)
				return err
			},
		},
		{
			name: "simulate",
			run: func(loader profileLoader) error {
				_, err := parseSimulateConfig(nil, loader)
				return err
			},
		},
		{
			name: "report",
			run: func(loader profileLoader) error {
				_, err := parseReportConfig([]string{"--starts", "starts.csv", "--events", "events.csv", "--status-starts", "status-starts.csv", "--replays", "replays.csv", "--run-window", "run-window.json"}, loader)
				return err
			},
		},
	} {
		t.Run(test.name, func(t *testing.T) {
			var loadedProfile string
			loader := func(name string) (config.Runtime, error) {
				loadedProfile = name
				return commandTestRuntime(), nil
			}
			if err := test.run(loader); err != nil {
				t.Fatal(err)
			}
			if loadedProfile != config.DefaultProfile {
				t.Fatalf("loaded profile = %q, want %q", loadedProfile, config.DefaultProfile)
			}
		})
	}
}

func TestValidateProfileReturnsNormalizedRunnerMetadata(t *testing.T) {
	var loadedProfile string
	validation, err := parseValidateProfile([]string{"--profile", "custom-validation"}, func(name string) (config.Runtime, error) {
		loadedProfile = name
		return commandTestRuntime(), nil
	})
	if err != nil {
		t.Fatal(err)
	}

	if loadedProfile != "custom-validation" || validation.Profile != "custom-validation" {
		t.Fatalf("loaded/output profile = %q/%q", loadedProfile, validation.Profile)
	}
	if validation.SchemaVersion != 1 || validation.WarmupSeconds != 12 || validation.ActiveSeconds != 34 || validation.DrainSeconds != 9 {
		t.Fatalf("validation window = %#v", validation)
	}
	if validation.Replay.Pacs008 == nil || validation.Replay.Pacs008.Share != 0.25 || validation.Replay.Pacs008.DelaySeconds != 7 {
		t.Fatalf("validation replay = %#v", validation.Replay)
	}
	if validation.Replay.Pacs002 == nil || validation.Replay.Pacs002.Share != 0.20 || validation.Replay.Pacs002.DelaySeconds != 11 {
		t.Fatalf("validation PACS.002 replay = %#v", validation.Replay)
	}
	if len(validation.Scenarios) != 1 {
		t.Fatalf("validation scenarios = %#v", validation.Scenarios)
	}
	scenario := validation.Scenarios[0]
	if scenario.Name != "happy-path" || scenario.Share != 1 {
		t.Fatalf("validation scenario = %#v", scenario)
	}
	if scenario.Participants.PairNumberStart != 101 || scenario.Participants.HotPairCount != 7 || scenario.Participants.ColdPairCount != 13 {
		t.Fatalf("validation participants = %#v", scenario.Participants)
	}
	if scenario.Amount.Minimum != 100 || scenario.Amount.Maximum != 100098 {
		t.Fatalf("validation amount = %#v", scenario.Amount)
	}
	if scenario.Funding.Payer.Mode != config.FundingCoverGeneratedDebits || scenario.Funding.Payer.Balance != "" || scenario.Funding.Receiver.Balance != "0.00" || !scenario.Funding.ResetIfExists {
		t.Fatalf("validation funding = %#v", scenario.Funding)
	}
	if scenario.Provisioning.PayerBalance == "0.00" || scenario.Provisioning.ReceiverBalance != "0.00" || !scenario.Provisioning.ResetIfExists {
		t.Fatalf("validation provisioning = %#v", scenario.Provisioning)
	}
	if scenario.Expectations.HTTPStatus != config.ExpectedHTTP2xx || scenario.Expectations.PayerNotification.DeliverySemantics != config.DeliveryAtLeastOnce || scenario.Expectations.PayerNotification.Status != "ACSC" || len(scenario.Expectations.PayerNotification.ReasonCodes) != 0 {
		t.Fatalf("validation expectations = %#v", scenario.Expectations)
	}
	encoded, err := json.Marshal(validation)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(encoded), `"seed"`) || strings.Contains(string(encoded), `"firstPair"`) || strings.Contains(string(encoded), `"type"`) || strings.Contains(string(encoded), `"payerConfirmation"`) || !strings.Contains(string(encoded), `"pairNumberStart"`) {
		t.Fatalf("normalized execution plan exposes removed fields: %s", encoded)
	}
}

func TestValidateProfileReturnsMixedScenarioProvisioning(t *testing.T) {
	runtimeCfg := commandTestRuntime()
	runtimeCfg.Load = config.Load{TargetTxRate: 100, Warmup: 5 * time.Second, Duration: 10 * time.Second, Drain: 10 * time.Second}
	runtimeCfg.Scenarios[0].Share = 0.8
	runtimeCfg.Scenarios = append(runtimeCfg.Scenarios, config.Scenario{
		Name:         "insufficient-funds",
		Share:        0.2,
		Participants: config.HotColdPairDistribution{PairNumberStart: 121, HotPairCount: 2, ColdPairCount: 8, HotTrafficShare: 0.8},
		Amount:       config.SequentialRangeAmount{Minimum: 100, Maximum: 100098},
		Funding: config.ScenarioFunding{
			Payer:         config.FundingAccount{Mode: config.FundingFixed, Balance: "0.00"},
			Receiver:      config.FundingAccount{Mode: config.FundingFixed, Balance: "0.00"},
			ResetIfExists: true,
		},
		Expectations: config.ScenarioExpectations{HTTPStatus: config.ExpectedHTTP2xx, PayerNotification: config.PayerNotificationExpectation{DeliverySemantics: config.DeliveryAtLeastOnce, Status: "RJCT", ReasonCodes: []string{"AM04"}}},
	})
	validation, err := parseValidateProfile([]string{"--profile", "mixed"}, func(string) (config.Runtime, error) {
		return runtimeCfg, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(validation.Scenarios) != 2 {
		t.Fatalf("scenarios = %#v", validation.Scenarios)
	}
	if validation.Scenarios[0].Provisioning.PayerBalance == "0.00" || validation.Scenarios[0].Provisioning.ReceiverBalance != "0.00" {
		t.Fatalf("happy provisioning = %#v", validation.Scenarios[0].Provisioning)
	}
	if validation.Scenarios[1].Name != "insufficient-funds" || validation.Scenarios[1].Provisioning.PayerBalance != "0.00" || validation.Scenarios[1].Provisioning.ReceiverBalance != "0.00" {
		t.Fatalf("insufficient provisioning = %#v", validation.Scenarios[1])
	}
}

func TestValidateProfileRejectsPositionalArguments(t *testing.T) {
	_, err := parseValidateProfile([]string{"unexpected"}, func(string) (config.Runtime, error) {
		return commandTestRuntime(), nil
	})
	if err == nil {
		t.Fatal("validate-profile accepted positional argument")
	}
}

func TestCommandsExposeNoSeedOption(t *testing.T) {
	loader := func(string) (config.Runtime, error) { return commandTestRuntime(), nil }
	if _, err := parseValidateProfile([]string{"--seed", "1"}, loader); err == nil {
		t.Fatal("validate-profile accepted --seed")
	}
	if _, err := parseSimulateConfig([]string{"--seed", "1"}, loader); err == nil {
		t.Fatal("simulate accepted --seed")
	}
	if _, err := parseReportConfig([]string{"--seed", "1", "--starts", "starts.csv", "--events", "events.csv"}, loader); err == nil {
		t.Fatal("report accepted --seed")
	}
}

func TestSimulateCommandLineOverridesTakePrecedenceOverProfile(t *testing.T) {
	cfg, err := parseSimulateConfig([]string{
		"--profile", "custom-smoke",
		"--out", "/override/output",
		"--run-window", "/override/run-window.json",
		"--central-transfer-ca-cert", "/override/central-ca.crt",
		"--central-transfer-client-cert-root", "/override/central-clients",
		"--central-transfer-server-name", "override-central",
		"--gateway-ca-cert", "/override/gateway-ca.crt",
		"--gateway-client-cert-root", "/override/gateway-clients",
		"--gateway-server-name", "override-gateway",
	}, func(string) (config.Runtime, error) {
		return commandTestRuntime(), nil
	})
	if err != nil {
		t.Fatal(err)
	}

	if cfg.OutputDir != "/override/output" {
		t.Fatalf("OutputDir = %q", cfg.OutputDir)
	}
	if cfg.RunWindowPath != "/override/run-window.json" {
		t.Fatalf("RunWindowPath = %q", cfg.RunWindowPath)
	}
	if cfg.CentralTransferCACert != "/override/central-ca.crt" {
		t.Fatalf("CentralTransferCACert = %q", cfg.CentralTransferCACert)
	}
	if cfg.CentralTransferClientCertRoot != "/override/central-clients" {
		t.Fatalf("CentralTransferClientCertRoot = %q", cfg.CentralTransferClientCertRoot)
	}
	if cfg.CentralTransferServerName != "override-central" {
		t.Fatalf("CentralTransferServerName = %q", cfg.CentralTransferServerName)
	}
	if cfg.GatewayCACert != "/override/gateway-ca.crt" {
		t.Fatalf("GatewayCACert = %q", cfg.GatewayCACert)
	}
	if cfg.GatewayClientCertRoot != "/override/gateway-clients" {
		t.Fatalf("GatewayClientCertRoot = %q", cfg.GatewayClientCertRoot)
	}
	if cfg.GatewayServerName != "override-gateway" {
		t.Fatalf("GatewayServerName = %q", cfg.GatewayServerName)
	}
	if cfg.TargetTxRate != 321 {
		t.Fatalf("non-overridden TargetTxRate = %d, want profile value", cfg.TargetTxRate)
	}
}

func TestCommandsReturnSelectedProfileLoadError(t *testing.T) {
	loader := func(name string) (config.Runtime, error) {
		return config.Runtime{}, fmt.Errorf("profile %q not found", name)
	}

	if _, err := parseSimulateConfig([]string{"--profile", "missing"}, loader); err == nil {
		t.Fatal("simulate accepted missing profile")
	}
	if _, err := parseReportConfig([]string{"--profile", "missing", "--starts", "starts.csv", "--events", "events.csv"}, loader); err == nil {
		t.Fatal("report accepted missing profile")
	}
}

func commandTestRuntime() config.Runtime {
	return config.Runtime{
		SchemaVersion: 1,
		Connections: config.Connections{
			CentralTransfer: config.CentralTransferConnection{
				BaseURL:        "https://profile-central:8001",
				CACert:         "/profile/central-ca.crt",
				ClientCertRoot: "/profile/central-clients",
				ServerName:     "profile-central",
			},
			NotificationGateway: config.NotificationGatewayConnection{
				Address:        "profile-gateway:9090",
				CACert:         "/profile/gateway-ca.crt",
				ClientCertRoot: "/profile/gateway-clients",
				ServerName:     "profile-gateway",
			},
		},
		Load: config.Load{
			TargetTxRate: 321,
			Warmup:       12 * time.Second,
			Duration:     34 * time.Second,
			Drain:        9 * time.Second,
		},
		Replay: config.Replay{
			Pacs008: &config.Pacs008Replay{Share: 0.25, Delay: 7 * time.Second},
			Pacs002: &config.Pacs002Replay{Share: 0.20, Delay: 11 * time.Second},
		},
		Scenarios: []config.Scenario{{
			Name:  "happy-path",
			Share: 1,
			Participants: config.HotColdPairDistribution{
				PairNumberStart: 101,
				HotPairCount:    7,
				ColdPairCount:   13,
				HotTrafficShare: 0.75,
			},
			Amount: config.SequentialRangeAmount{
				Minimum: 100,
				Maximum: 100098,
			},
			Funding: config.ScenarioFunding{
				Payer:         config.FundingAccount{Mode: config.FundingCoverGeneratedDebits},
				Receiver:      config.FundingAccount{Mode: config.FundingFixed, Balance: "0.00"},
				ResetIfExists: true,
			},
			Expectations: config.ScenarioExpectations{
				HTTPStatus:        config.ExpectedHTTP2xx,
				PayerNotification: config.PayerNotificationExpectation{DeliverySemantics: config.DeliveryAtLeastOnce, Status: "ACSC", ReasonCodes: []string{}},
			},
		}},
		Reporting: config.Reporting{SLAThresholdMs: 987},
	}
}
