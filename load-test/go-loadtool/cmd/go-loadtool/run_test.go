package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/config"
	"instant-payment-system/load-test/go-loadtool/internal/events"
	"instant-payment-system/load-test/go-loadtool/internal/runwindow"
	"instant-payment-system/load-test/go-loadtool/internal/sim"
)

func TestParseRunConfigUsesRunProfileAndFixedBundlePaths(t *testing.T) {
	runDir := preparedRunDirectory(t)
	var loadedPath string
	loader := func(path string) (config.Runtime, error) {
		loadedPath = path
		runtimeCfg := commandTestRuntime()
		runtimeCfg.Name = "run-profile"
		return runtimeCfg, nil
	}

	command, err := parseRunConfig([]string{"--run-dir", runDir}, loader)
	if err != nil {
		t.Fatalf("parseRunConfig() error = %v", err)
	}

	profilePath := filepath.Join(runDir, "profile.json")
	if loadedPath != profilePath {
		t.Fatalf("loaded profile path = %q, want %q", loadedPath, profilePath)
	}
	if command.simulator.ProfileName != "run-profile" {
		t.Fatalf("simulator ProfileName = %q", command.simulator.ProfileName)
	}
	if command.simulator.OutputDir != filepath.Join(runDir, "go-loadtool") || command.simulator.RunWindowPath != filepath.Join(runDir, "run-window.json") {
		t.Fatalf("simulator paths = %q / %q", command.simulator.OutputDir, command.simulator.RunWindowPath)
	}
	if command.report.startsPath != filepath.Join(runDir, "go-loadtool", "starts.csv") ||
		command.report.eventsPath != filepath.Join(runDir, "go-loadtool", "events.csv") ||
		command.report.statusStartsPath != filepath.Join(runDir, "go-loadtool", "status-starts.csv") ||
		command.report.replaysPath != filepath.Join(runDir, "go-loadtool", "replays.csv") ||
		command.report.runWindowPath != filepath.Join(runDir, "run-window.json") {
		t.Fatalf("report paths = %#v", command.report)
	}
	if command.report.profileName != "run-profile" || command.report.options.TargetTxRate != 321 {
		t.Fatalf("report profile/options = %q / %#v", command.report.profileName, command.report.options)
	}
}

func TestParseRunConfigAppliesExplicitMTLSOverrides(t *testing.T) {
	runDir := preparedRunDirectory(t)
	runtimeCfg := commandTestRuntime()
	runtimeCfg.Name = "run-profile"

	command, err := parseRunConfig([]string{
		"--run-dir", runDir,
		"--central-transfer-ca-cert", "/override/central-ca.crt",
		"--central-transfer-client-cert-root", "/override/central-clients",
		"--central-transfer-server-name", "override-central",
		"--gateway-ca-cert", "/override/gateway-ca.crt",
		"--gateway-client-cert-root", "/override/gateway-clients",
		"--gateway-server-name", "override-gateway",
	}, func(string) (config.Runtime, error) { return runtimeCfg, nil })
	if err != nil {
		t.Fatalf("parseRunConfig() error = %v", err)
	}

	cfg := command.simulator
	if cfg.CentralTransferCACert != "/override/central-ca.crt" ||
		cfg.CentralTransferClientCertRoot != "/override/central-clients" ||
		cfg.CentralTransferServerName != "override-central" ||
		cfg.GatewayCACert != "/override/gateway-ca.crt" ||
		cfg.GatewayClientCertRoot != "/override/gateway-clients" ||
		cfg.GatewayServerName != "override-gateway" {
		t.Fatalf("mTLS overrides = %#v", cfg)
	}
	if cfg.BaseURL != runtimeCfg.Connections.CentralTransfer.BaseURL || cfg.GatewayAddress != runtimeCfg.Connections.NotificationGateway.Address {
		t.Fatalf("non-overridable connections changed: %#v", cfg)
	}
}

func TestParseRunConfigRequiresRunDirAndRejectsPositionalArguments(t *testing.T) {
	loader := func(string) (config.Runtime, error) { return config.Runtime{}, nil }
	if _, err := parseRunConfig(nil, loader); err == nil || !strings.Contains(err.Error(), "--run-dir is required") {
		t.Fatalf("missing run-dir error = %v", err)
	}
	if _, err := parseRunConfig([]string{"--run-dir", preparedRunDirectory(t), "unexpected"}, loader); err == nil || !strings.Contains(err.Error(), "accepts no positional arguments") {
		t.Fatalf("positional argument error = %v", err)
	}
}

func TestParseRunConfigRejectsLegacyPathAndProfileFlags(t *testing.T) {
	runDir := preparedRunDirectory(t)
	for _, flagName := range []string{"--profile", "--config", "--out", "--run-window", "--starts", "--events", "--status-starts", "--replays"} {
		t.Run(flagName, func(t *testing.T) {
			_, err := parseRunConfig([]string{"--run-dir", runDir, flagName, "value"}, func(string) (config.Runtime, error) {
				return commandTestRuntime(), nil
			})
			if err == nil {
				t.Fatalf("parseRunConfig() accepted %s", flagName)
			}
		})
	}
}

func TestExecuteRunPreparesSimulatesReportsAndPublishesInOrder(t *testing.T) {
	runDir := preparedRunDirectory(t)
	runtimeCfg := commandTestRuntime()
	runtimeCfg.Name = "run-profile"
	var stdout bytes.Buffer
	var order []string

	err := executeRun([]string{"--run-dir", runDir}, runDependencies{
		loadProfile: func(string) (config.Runtime, error) { return runtimeCfg, nil },
		simulate: func(cfg sim.Config) error {
			if info, err := os.Stat(cfg.OutputDir); err != nil || !info.IsDir() {
				t.Fatalf("simulation output directory was not prepared: %v", err)
			}
			order = append(order, "simulate")
			return nil
		},
		renderReport: func(command reportConfig, output io.Writer) error {
			order = append(order, "report")
			_, err := output.Write([]byte("{\n  \"result\": \"ok\"\n}\n"))
			return err
		},
		stdout: &stdout,
	})
	if err != nil {
		t.Fatalf("executeRun() error = %v", err)
	}
	if strings.Join(order, ",") != "simulate,report" {
		t.Fatalf("execution order = %v", order)
	}
	wantReport := "{\n  \"result\": \"ok\"\n}\n"
	gotReport, err := os.ReadFile(filepath.Join(runDir, "sla-report.json"))
	if err != nil {
		t.Fatalf("ReadFile(sla-report.json) error = %v", err)
	}
	if string(gotReport) != wantReport {
		t.Fatalf("report = %q, want %q", gotReport, wantReport)
	}
	wantOutput := "report written to " + filepath.Join(runDir, "sla-report.json") + "\n"
	if stdout.String() != wantOutput {
		t.Fatalf("stdout = %q, want %q", stdout.String(), wantOutput)
	}
}

func TestExecuteRunDoesNotReportAfterSimulationFailure(t *testing.T) {
	runDir := preparedRunDirectory(t)
	runtimeCfg := commandTestRuntime()
	runtimeCfg.Name = "run-profile"
	wantErr := errors.New("simulation failed")
	renderCalled := false

	err := executeRun([]string{"--run-dir", runDir}, runDependencies{
		loadProfile: func(string) (config.Runtime, error) { return runtimeCfg, nil },
		simulate:    func(sim.Config) error { return wantErr },
		renderReport: func(reportConfig, io.Writer) error {
			renderCalled = true
			return nil
		},
		stdout: io.Discard,
	})
	if !errors.Is(err, wantErr) {
		t.Fatalf("executeRun() error = %v, want simulation error", err)
	}
	if renderCalled {
		t.Fatal("report renderer was called after simulation failure")
	}
	if _, err := os.Stat(filepath.Join(runDir, "go-loadtool")); err != nil {
		t.Fatalf("partial output directory was not retained: %v", err)
	}
	if _, err := os.Stat(filepath.Join(runDir, "sla-report.json")); !os.IsNotExist(err) {
		t.Fatalf("report exists or cannot be inspected after simulation failure: %v", err)
	}
}

func TestExecuteRunDoesNotPublishAfterReportFailure(t *testing.T) {
	runDir := preparedRunDirectory(t)
	runtimeCfg := commandTestRuntime()
	runtimeCfg.Name = "run-profile"
	wantErr := errors.New("report failed")
	var stdout bytes.Buffer

	err := executeRun([]string{"--run-dir", runDir}, runDependencies{
		loadProfile: func(string) (config.Runtime, error) { return runtimeCfg, nil },
		simulate:    func(sim.Config) error { return nil },
		renderReport: func(reportConfig, io.Writer) error {
			return wantErr
		},
		stdout: &stdout,
	})
	if !errors.Is(err, wantErr) {
		t.Fatalf("executeRun() error = %v, want report error", err)
	}
	if _, err := os.Stat(filepath.Join(runDir, "sla-report.json")); !os.IsNotExist(err) {
		t.Fatalf("report exists or cannot be inspected after report failure: %v", err)
	}
	if stdout.Len() != 0 {
		t.Fatalf("stdout = %q after report failure", stdout.String())
	}
}

func TestExecuteRunUsesRealReportRendererWithCompletedArtifacts(t *testing.T) {
	runDir := preparedRunDirectory(t)
	runtimeCfg := commandTestRuntime()
	runtimeCfg.Name = "run-profile"

	err := executeRun([]string{"--run-dir", runDir}, runDependencies{
		loadProfile:  func(string) (config.Runtime, error) { return runtimeCfg, nil },
		simulate:     writeMinimalRunArtifacts,
		renderReport: renderReport,
		stdout:       io.Discard,
	})
	if err != nil {
		t.Fatalf("executeRun() error = %v", err)
	}

	data, err := os.ReadFile(filepath.Join(runDir, "sla-report.json"))
	if err != nil {
		t.Fatalf("ReadFile(sla-report.json) error = %v", err)
	}
	var document struct {
		Run struct {
			TargetTPS int `json:"target_tps"`
		} `json:"run"`
		LoadGeneration struct {
			Violations int `json:"violations"`
		} `json:"load_generation"`
	}
	if err := json.Unmarshal(data, &document); err != nil {
		t.Fatalf("report is not valid JSON: %v", err)
	}
	if document.Run.TargetTPS != 321 || document.LoadGeneration.Violations == 0 {
		t.Fatalf("report document = %#v", document)
	}
}

func TestRunCommandLoadsRunProfileBeforeCreatingOutputs(t *testing.T) {
	runDir := preparedRunDirectory(t)

	err := runRun([]string{"--run-dir", runDir})
	if err == nil || !strings.Contains(err.Error(), "invalid name") {
		t.Fatalf("runRun() error = %v, want invalid run profile error", err)
	}
	if _, err := os.Stat(filepath.Join(runDir, "go-loadtool")); !os.IsNotExist(err) {
		t.Fatalf("output directory exists or cannot be inspected after profile failure: %v", err)
	}
}

func writeMinimalRunArtifacts(cfg sim.Config) error {
	document := runwindow.New(cfg.ProfileName, time.Now(), cfg.Warmup, cfg.Duration, cfg.Drain, cfg.Replay)
	if err := runwindow.Write(cfg.RunWindowPath, document); err != nil {
		return err
	}
	startWriter, err := events.NewStartWriter(filepath.Join(cfg.OutputDir, "starts.csv"))
	if err != nil {
		return err
	}
	if err := startWriter.Close(); err != nil {
		return err
	}
	eventWriter, err := events.NewNotificationWriter(filepath.Join(cfg.OutputDir, "events.csv"))
	if err != nil {
		return err
	}
	if err := eventWriter.Close(); err != nil {
		return err
	}
	statusWriter, err := events.NewStatusStartWriter(filepath.Join(cfg.OutputDir, "status-starts.csv"))
	if err != nil {
		return err
	}
	if err := statusWriter.Close(); err != nil {
		return err
	}
	replayWriter, err := events.NewReplayWriter(filepath.Join(cfg.OutputDir, "replays.csv"))
	if err != nil {
		return err
	}
	return replayWriter.Close()
}

func preparedRunDirectory(t *testing.T) string {
	t.Helper()
	runDir := t.TempDir()
	if err := os.WriteFile(filepath.Join(runDir, "profile.json"), []byte("{}\n"), 0o644); err != nil {
		t.Fatalf("write profile.json: %v", err)
	}
	return runDir
}
