package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/sim"
)

const (
	DefaultProfile   = "uniform-smoke"
	profilesDir      = "profiles"
	defaultOutputDir = "results/go-loadtool/manual"
)

var profileNamePattern = regexp.MustCompile(`^[a-z0-9][a-z0-9-]*$`)

type Runtime struct {
	Sim            sim.Config
	SLAThresholdMs int64
}

type fileConfig struct {
	BaseURL                       string  `json:"baseUrl"`
	CentralTransferCACert         string  `json:"centralTransferCaCert"`
	CentralTransferClientCertRoot string  `json:"centralTransferClientCertRoot"`
	CentralTransferServerName     string  `json:"centralTransferServerName"`
	GatewayAddress                string  `json:"gatewayAddress"`
	GatewayCACert                 string  `json:"gatewayCaCert"`
	GatewayClientCertRoot         string  `json:"gatewayClientCertRoot"`
	GatewayServerName             string  `json:"gatewayServerName"`
	TargetTxRate                  int     `json:"targetTxRate"`
	Warmup                        string  `json:"warmup"`
	Duration                      string  `json:"duration"`
	Drain                         string  `json:"drain"`
	HotPSPCount                   int     `json:"hotPspCount"`
	ColdPSPCount                  int     `json:"coldPspCount"`
	HotTrafficShare               float64 `json:"hotTrafficShare"`
	SLAThresholdMs                int64   `json:"slaThresholdMs"`
}

func LoadDefault() (Runtime, error) {
	return LoadProfile(DefaultProfile)
}

func LoadProfile(name string) (Runtime, error) {
	return loadProfileFromDir(profilesDir, name)
}

func loadProfileFromDir(dir string, name string) (Runtime, error) {
	if !profileNamePattern.MatchString(name) {
		return Runtime{}, fmt.Errorf("invalid profile name %q: use only lowercase letters, digits, and hyphens, beginning with a letter or digit", name)
	}

	path := filepath.Join(dir, name+".json")
	data, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return Runtime{}, fmt.Errorf("profile %q not found", name)
		}
		return Runtime{}, fmt.Errorf("read profile %q: %w", name, err)
	}

	var file fileConfig
	if err := json.Unmarshal(data, &file); err != nil {
		return Runtime{}, fmt.Errorf("profile %q is malformed: invalid JSON: %w", name, err)
	}
	for _, required := range []struct {
		name  string
		value string
	}{
		{name: "baseUrl", value: file.BaseURL},
		{name: "centralTransferCaCert", value: file.CentralTransferCACert},
		{name: "centralTransferClientCertRoot", value: file.CentralTransferClientCertRoot},
		{name: "gatewayAddress", value: file.GatewayAddress},
		{name: "gatewayCaCert", value: file.GatewayCACert},
		{name: "gatewayClientCertRoot", value: file.GatewayClientCertRoot},
	} {
		if required.value == "" {
			return Runtime{}, malformedProfile(name, required.name, errors.New("must be a non-empty string"))
		}
	}

	duration, err := time.ParseDuration(file.Duration)
	if err != nil {
		return Runtime{}, malformedProfile(name, "duration", err)
	}
	if duration <= 0 {
		return Runtime{}, malformedProfile(name, "duration", errors.New("must be positive"))
	}
	warmup := time.Duration(0)
	if file.Warmup != "" {
		warmup, err = time.ParseDuration(file.Warmup)
		if err != nil {
			return Runtime{}, malformedProfile(name, "warmup", err)
		}
		if warmup < 0 {
			return Runtime{}, malformedProfile(name, "warmup", errors.New("must not be negative"))
		}
	}
	drain, err := time.ParseDuration(file.Drain)
	if err != nil {
		return Runtime{}, malformedProfile(name, "drain", err)
	}
	if drain < 0 {
		return Runtime{}, malformedProfile(name, "drain", errors.New("must not be negative"))
	}
	if file.TargetTxRate <= 0 {
		return Runtime{}, malformedProfile(name, "targetTxRate", errors.New("must be positive"))
	}
	if file.HotPSPCount <= 0 {
		return Runtime{}, malformedProfile(name, "hotPspCount", errors.New("must be positive"))
	}
	if file.ColdPSPCount <= 0 {
		return Runtime{}, malformedProfile(name, "coldPspCount", errors.New("must be positive"))
	}
	if file.HotTrafficShare <= 0 || file.HotTrafficShare >= 1 {
		return Runtime{}, malformedProfile(name, "hotTrafficShare", errors.New("must be greater than 0 and less than 1"))
	}
	if file.SLAThresholdMs <= 0 {
		return Runtime{}, malformedProfile(name, "slaThresholdMs", errors.New("must be positive"))
	}

	return Runtime{
		Sim: sim.Config{
			BaseURL:                       file.BaseURL,
			CentralTransferCACert:         file.CentralTransferCACert,
			CentralTransferClientCertRoot: file.CentralTransferClientCertRoot,
			CentralTransferServerName:     serverName(file.CentralTransferServerName),
			GatewayAddress:                file.GatewayAddress,
			GatewayCACert:                 file.GatewayCACert,
			GatewayClientCertRoot:         file.GatewayClientCertRoot,
			GatewayServerName:             serverName(file.GatewayServerName),
			TargetTxRate:                  file.TargetTxRate,
			Warmup:                        warmup,
			Duration:                      duration,
			Drain:                         drain,
			HotPSPs:                       file.HotPSPCount,
			ColdPSPs:                      file.ColdPSPCount,
			HotShare:                      file.HotTrafficShare,
			OutputDir:                     defaultOutputDir,
		},
		SLAThresholdMs: file.SLAThresholdMs,
	}, nil
}

func malformedProfile(name string, field string, err error) error {
	return fmt.Errorf("profile %q is malformed: invalid %s: %w", name, field, err)
}

func serverName(value string) string {
	if value == "" {
		return "localhost"
	}
	return value
}
