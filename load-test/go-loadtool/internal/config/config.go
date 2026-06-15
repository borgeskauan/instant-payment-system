package config

import (
	"encoding/json"
	"fmt"
	"os"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/sim"
)

const DefaultPath = "loadtool-config.json"

type Runtime struct {
	Sim            sim.Config
	SLAThresholdMs int64
}

type fileConfig struct {
	BaseURL         string  `json:"baseUrl"`
	GatewayAddress  string  `json:"gatewayAddress"`
	TargetTxRate    int     `json:"targetTxRate"`
	Duration        string  `json:"duration"`
	Drain           string  `json:"drain"`
	HotPSPCount     int     `json:"hotPspCount"`
	ColdPSPCount    int     `json:"coldPspCount"`
	HotTrafficShare float64 `json:"hotTrafficShare"`
	OutputDir       string  `json:"outputDir"`
	SLAThresholdMs  int64   `json:"slaThresholdMs"`
}

func LoadDefault() (Runtime, error) {
	return Load(DefaultPath)
}

func Load(path string) (Runtime, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return Runtime{}, err
	}

	var file fileConfig
	if err := json.Unmarshal(data, &file); err != nil {
		return Runtime{}, err
	}

	duration, err := time.ParseDuration(file.Duration)
	if err != nil {
		return Runtime{}, fmt.Errorf("invalid duration: %w", err)
	}
	drain, err := time.ParseDuration(file.Drain)
	if err != nil {
		return Runtime{}, fmt.Errorf("invalid drain: %w", err)
	}

	return Runtime{
		Sim: sim.Config{
			BaseURL:        file.BaseURL,
			GatewayAddress: file.GatewayAddress,
			TargetTxRate:   file.TargetTxRate,
			Duration:       duration,
			Drain:          drain,
			HotPSPs:        file.HotPSPCount,
			ColdPSPs:       file.ColdPSPCount,
			HotShare:       file.HotTrafficShare,
			OutputDir:      file.OutputDir,
		},
		SLAThresholdMs: file.SLAThresholdMs,
	}, nil
}
