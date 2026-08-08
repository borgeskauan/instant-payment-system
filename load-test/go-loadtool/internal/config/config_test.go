package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const testProfile = `{
  "baseUrl": "https://127.0.0.1:8001",
  "centralTransferCaCert": "/tmp/central-ca.crt",
  "centralTransferClientCertRoot": "/tmp/central-certs",
  "centralTransferServerName": "kafka-producer",
  "gatewayAddress": "127.0.0.1:9090",
  "targetTxRate": 1234,
  "warmup": "10s",
  "duration": "45s",
  "drain": "12s",
  "hotPspCount": 7,
  "coldPspCount": 13,
  "hotTrafficShare": 0.75,
  "gatewayCaCert": "/tmp/ca.crt",
  "gatewayClientCertRoot": "/tmp/loadtool-certs",
  "gatewayServerName": "notification-gateway",
  "slaThresholdMs": 3200
}`

func TestLoadProfileReadsSimulatorAndReportSettings(t *testing.T) {
	dir := t.TempDir()
	writeProfile(t, dir, "explicit-profile", testProfile)

	cfg, err := loadProfileFromDir(dir, "explicit-profile")
	if err != nil {
		t.Fatalf("loadProfileFromDir returned error: %v", err)
	}

	if cfg.Sim.BaseURL != "https://127.0.0.1:8001" {
		t.Fatalf("BaseURL = %q", cfg.Sim.BaseURL)
	}
	if cfg.Sim.CentralTransferCACert != "/tmp/central-ca.crt" {
		t.Fatalf("CentralTransferCACert = %q", cfg.Sim.CentralTransferCACert)
	}
	if cfg.Sim.CentralTransferClientCertRoot != "/tmp/central-certs" {
		t.Fatalf("CentralTransferClientCertRoot = %q", cfg.Sim.CentralTransferClientCertRoot)
	}
	if cfg.Sim.CentralTransferServerName != "kafka-producer" {
		t.Fatalf("CentralTransferServerName = %q", cfg.Sim.CentralTransferServerName)
	}
	if cfg.Sim.GatewayAddress != "127.0.0.1:9090" {
		t.Fatalf("GatewayAddress = %q", cfg.Sim.GatewayAddress)
	}
	if cfg.Sim.TargetTxRate != 1234 {
		t.Fatalf("TargetTxRate = %d", cfg.Sim.TargetTxRate)
	}
	if cfg.Sim.Duration != 45*time.Second {
		t.Fatalf("Duration = %s", cfg.Sim.Duration)
	}
	if cfg.Sim.Warmup != 10*time.Second {
		t.Fatalf("Warmup = %s", cfg.Sim.Warmup)
	}
	if cfg.Sim.Drain != 12*time.Second {
		t.Fatalf("Drain = %s", cfg.Sim.Drain)
	}
	if cfg.Sim.HotPSPs != 7 {
		t.Fatalf("HotPSPs = %d", cfg.Sim.HotPSPs)
	}
	if cfg.Sim.ColdPSPs != 13 {
		t.Fatalf("ColdPSPs = %d", cfg.Sim.ColdPSPs)
	}
	if cfg.Sim.HotShare != 0.75 {
		t.Fatalf("HotShare = %f", cfg.Sim.HotShare)
	}
	if cfg.Sim.GatewayCACert != "/tmp/ca.crt" {
		t.Fatalf("GatewayCACert = %q", cfg.Sim.GatewayCACert)
	}
	if cfg.Sim.GatewayClientCertRoot != "/tmp/loadtool-certs" {
		t.Fatalf("GatewayClientCertRoot = %q", cfg.Sim.GatewayClientCertRoot)
	}
	if cfg.Sim.GatewayServerName != "notification-gateway" {
		t.Fatalf("GatewayServerName = %q", cfg.Sim.GatewayServerName)
	}
	if cfg.Sim.OutputDir != "results/go-loadtool/manual" {
		t.Fatalf("OutputDir = %q", cfg.Sim.OutputDir)
	}
	if cfg.SLAThresholdMs != 3200 {
		t.Fatalf("SLAThresholdMs = %d", cfg.SLAThresholdMs)
	}
}

func TestLoadProfileRejectsInvalidNames(t *testing.T) {
	for _, name := range []string{"", "Uppercase", "-leading", "under_score", "../escape", "nested/profile"} {
		t.Run(name, func(t *testing.T) {
			_, err := loadProfileFromDir(t.TempDir(), name)
			if err == nil || !strings.Contains(err.Error(), "invalid profile name") {
				t.Fatalf("error = %v, want clear invalid-name error", err)
			}
		})
	}
}

func TestLoadProfileNameCannotEscapeProfilesDirectory(t *testing.T) {
	root := t.TempDir()
	profilesDir := filepath.Join(root, "profiles")
	if err := os.Mkdir(profilesDir, 0o755); err != nil {
		t.Fatal(err)
	}
	writeProfile(t, root, "escaped", testProfile)

	_, err := loadProfileFromDir(profilesDir, "../escaped")
	if err == nil || !strings.Contains(err.Error(), "invalid profile name") {
		t.Fatalf("error = %v, want path escape to be rejected", err)
	}
}

func TestLoadProfileRejectsUnknownProfile(t *testing.T) {
	_, err := loadProfileFromDir(t.TempDir(), "missing-profile")
	if err == nil || !strings.Contains(err.Error(), `profile "missing-profile" not found`) {
		t.Fatalf("error = %v, want clear unknown-profile error", err)
	}
}

func TestLoadProfileRejectsMalformedProfile(t *testing.T) {
	dir := t.TempDir()
	writeProfile(t, dir, "broken-profile", `{not JSON}`)

	_, err := loadProfileFromDir(dir, "broken-profile")
	if err == nil || !strings.Contains(err.Error(), `profile "broken-profile" is malformed`) {
		t.Fatalf("error = %v, want clear malformed-profile error", err)
	}
}

func TestLoadProfileRejectsInvalidProfileValue(t *testing.T) {
	dir := t.TempDir()
	content := strings.Replace(testProfile, `"duration": "45s"`, `"duration": "soon"`, 1)
	writeProfile(t, dir, "invalid-duration", content)

	_, err := loadProfileFromDir(dir, "invalid-duration")
	if err == nil || !strings.Contains(err.Error(), "invalid duration") {
		t.Fatalf("error = %v, want clear invalid-duration error", err)
	}
}

func writeProfile(t *testing.T, dir string, name string, content string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(dir, name+".json"), []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}
