package config

import (
	"encoding/json"
	"fmt"
	"os"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/sim"
)

const (
	DefaultPath      = "loadtool-config.json"
	defaultOutputDir = "results/go-loadtool/manual"
)

type Runtime struct {
	Sim            sim.Config
	SLAThresholdMs int64
}

type fileConfig struct {
	BaseURL               string  `json:"baseUrl"`
	GatewayAddress        string  `json:"gatewayAddress"`
	GatewayCACert         string  `json:"gatewayCaCert"`
	GatewayClientCertRoot string  `json:"gatewayClientCertRoot"`
	GatewayServerName     string  `json:"gatewayServerName"`
	TargetTxRate          int     `json:"targetTxRate"`
	Warmup                string  `json:"warmup"`
	Duration              string  `json:"duration"`
	Drain                 string  `json:"drain"`
	HotPSPCount           int     `json:"hotPspCount"`
	ColdPSPCount          int     `json:"coldPspCount"`
	HotTrafficShare       float64 `json:"hotTrafficShare"`
	SLAThresholdMs        int64   `json:"slaThresholdMs"`
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
	warmup := time.Duration(0)
	if file.Warmup != "" {
		warmup, err = time.ParseDuration(file.Warmup)
		if err != nil {
			return Runtime{}, fmt.Errorf("invalid warmup: %w", err)
		}
	}
	drain, err := time.ParseDuration(file.Drain)
	if err != nil {
		return Runtime{}, fmt.Errorf("invalid drain: %w", err)
	}

	return Runtime{
		Sim: sim.Config{
			BaseURL:               file.BaseURL,
			GatewayAddress:        file.GatewayAddress,
			GatewayCACert:         file.GatewayCACert,
			GatewayClientCertRoot: file.GatewayClientCertRoot,
			GatewayServerName:     gatewayServerName(file.GatewayServerName),
			TargetTxRate:          file.TargetTxRate,
			Warmup:                warmup,
			Duration:              duration,
			Drain:                 drain,
			HotPSPs:               file.HotPSPCount,
			ColdPSPs:              file.ColdPSPCount,
			HotShare:              file.HotTrafficShare,
			OutputDir:             defaultOutputDir,
		},
		SLAThresholdMs: file.SLAThresholdMs,
	}, nil
}

func gatewayServerName(value string) string {
	if value == "" {
		return "localhost"
	}
	return value
}
