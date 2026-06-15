package config

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestLoadReadsSimulatorAndSLAConfig(t *testing.T) {
	path := filepath.Join(t.TempDir(), "loadtool-config.json")
	content := `{
  "baseUrl": "http://127.0.0.1:8001",
  "gatewayAddress": "127.0.0.1:9090",
  "targetTxRate": 1234,
  "duration": "45s",
  "drain": "12s",
  "hotPspCount": 7,
  "coldPspCount": 13,
  "hotTrafficShare": 0.75,
  "outputDir": "summary/custom",
  "slaThresholdMs": 3200
}`
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}

	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load returned error: %v", err)
	}

	if cfg.Sim.BaseURL != "http://127.0.0.1:8001" {
		t.Fatalf("BaseURL = %q", cfg.Sim.BaseURL)
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
	if cfg.Sim.OutputDir != "summary/custom" {
		t.Fatalf("OutputDir = %q", cfg.Sim.OutputDir)
	}
	if cfg.SLAThresholdMs != 3200 {
		t.Fatalf("SLAThresholdMs = %d", cfg.SLAThresholdMs)
	}
}
