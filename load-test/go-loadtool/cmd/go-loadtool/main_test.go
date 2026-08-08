package main

import (
	"fmt"
	"testing"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/config"
	"instant-payment-system/load-test/go-loadtool/internal/sim"
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
}

func TestCommandsDefaultToUniformSmokeProfile(t *testing.T) {
	for _, test := range []struct {
		name string
		run  func(profileLoader) error
	}{
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
		Sim: sim.Config{
			CentralTransferCACert:         "/profile/central-ca.crt",
			CentralTransferClientCertRoot: "/profile/central-clients",
			CentralTransferServerName:     "profile-central",
			GatewayCACert:                 "/profile/gateway-ca.crt",
			GatewayClientCertRoot:         "/profile/gateway-clients",
			GatewayServerName:             "profile-gateway",
			TargetTxRate:                  321,
			Warmup:                        12 * time.Second,
			Duration:                      34 * time.Second,
			OutputDir:                     "/profile/output",
		},
		SLAThresholdMs: 987,
	}
}
