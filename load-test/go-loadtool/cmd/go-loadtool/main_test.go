package main

import (
	"fmt"
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
	if len(command.options.Scenarios) != 1 || command.options.Scenarios[0].Type != config.ScenarioHappyPath {
		t.Fatalf("report scenarios = %#v", command.options.Scenarios)
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
				_, err := parseReportConfig([]string{"--starts", "starts.csv", "--events", "events.csv"}, loader)
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
	if len(validation.Scenarios) != 1 {
		t.Fatalf("validation scenarios = %#v", validation.Scenarios)
	}
	scenario := validation.Scenarios[0]
	if scenario.Type != config.ScenarioHappyPath || scenario.Share != 1 {
		t.Fatalf("validation scenario = %#v", scenario)
	}
	if scenario.Participants.FirstPair != 101 || scenario.Participants.HotPairCount != 7 || scenario.Participants.ColdPairCount != 13 {
		t.Fatalf("validation participants = %#v", scenario.Participants)
	}
	if scenario.Funding.Balance != 123456 || scenario.Funding.ResetIfExists {
		t.Fatalf("validation funding = %#v", scenario.Funding)
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

func TestSimulateCommandLineOverridesTakePrecedenceOverProfile(t *testing.T) {
	cfg, err := parseSimulateConfig([]string{
		"--profile", "custom-smoke",
		"--out", "/override/output",
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
		Seed: 42,
		Scenarios: []config.Scenario{{
			Type:  config.ScenarioHappyPath,
			Share: 1,
			HappyPath: &config.HappyPathScenario{
				Participants: config.HotColdPairDistribution{
					FirstPair:       101,
					HotPairCount:    7,
					ColdPairCount:   13,
					HotTrafficShare: 0.75,
				},
				Funding: config.Funding{
					Balance:       123456,
					ResetIfExists: false,
				},
				Amount: config.SequentialRangeAmount{
					Minimum: 100,
					Maximum: 100098,
				},
				Expectations: config.HappyPathExpectations{
					HTTPStatus:        config.ExpectedHTTP2xx,
					PayerConfirmation: config.ConfirmationRequired,
				},
			},
		}},
		Reporting: config.Reporting{SLAThresholdMs: 987},
	}
}
