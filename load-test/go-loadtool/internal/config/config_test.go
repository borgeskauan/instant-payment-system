package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const testProfile = `{
  "schemaVersion": 1,
  "connections": {
    "centralTransfer": {
      "baseUrl": "https://127.0.0.1:8001",
      "caCert": "/tmp/central-ca.crt",
      "clientCertRoot": "/tmp/central-certs",
      "serverName": "kafka-producer"
    },
    "notificationGateway": {
      "address": "127.0.0.1:9090",
      "caCert": "/tmp/gateway-ca.crt",
      "clientCertRoot": "/tmp/gateway-certs",
      "serverName": "notification-gateway"
    }
  },
  "load": {
    "targetTxRate": 1234,
    "warmup": "10s",
    "duration": "45s",
    "drain": "12s"
  },
  "seed": 42,
  "scenarios": [
    {
      "type": "happy-path",
      "share": 1.0,
      "participants": {
        "firstPair": 101,
        "hotPairCount": 7,
        "coldPairCount": 13,
        "hotTrafficShare": 0.75
      },
      "funding": {
        "balance": 1000000000,
        "resetIfExists": true
      },
      "amount": {
        "minimum": 100,
        "maximum": 100098
      },
      "expectations": {
        "httpStatus": "2xx",
        "payerConfirmation": "required"
      }
    }
  ],
  "reporting": {
    "slaThresholdMs": 3200
  }
}`

func TestLoadProfileReadsVersionedRuntimeSettings(t *testing.T) {
	dir := t.TempDir()
	writeProfile(t, dir, "explicit-profile", testProfile)

	cfg, err := loadProfileFromDir(dir, "explicit-profile")
	if err != nil {
		t.Fatalf("loadProfileFromDir returned error: %v", err)
	}

	if cfg.SchemaVersion != 1 {
		t.Fatalf("SchemaVersion = %d", cfg.SchemaVersion)
	}
	if cfg.Connections.CentralTransfer.BaseURL != "https://127.0.0.1:8001" {
		t.Fatalf("central transfer BaseURL = %q", cfg.Connections.CentralTransfer.BaseURL)
	}
	if cfg.Connections.CentralTransfer.CACert != "/tmp/central-ca.crt" {
		t.Fatalf("central transfer CACert = %q", cfg.Connections.CentralTransfer.CACert)
	}
	if cfg.Connections.NotificationGateway.Address != "127.0.0.1:9090" {
		t.Fatalf("gateway Address = %q", cfg.Connections.NotificationGateway.Address)
	}
	if cfg.Load.TargetTxRate != 1234 || cfg.Load.Warmup != 10*time.Second || cfg.Load.Duration != 45*time.Second || cfg.Load.Drain != 12*time.Second {
		t.Fatalf("Load = %#v", cfg.Load)
	}
	if cfg.Seed != 42 || len(cfg.Scenarios) != 1 {
		t.Fatalf("Seed/Scenarios = %d/%#v", cfg.Seed, cfg.Scenarios)
	}
	scenario := cfg.Scenarios[0]
	if scenario.Type != ScenarioHappyPath || scenario.Share != 1 || scenario.HappyPath == nil {
		t.Fatalf("Scenario = %#v", scenario)
	}
	distribution := scenario.HappyPath.Participants
	if distribution.FirstPair != 101 || distribution.HotPairCount != 7 || distribution.ColdPairCount != 13 || distribution.HotTrafficShare != 0.75 {
		t.Fatalf("Participants = %#v", distribution)
	}
	if scenario.HappyPath.Amount.Minimum != 100 || scenario.HappyPath.Amount.Maximum != 100098 {
		t.Fatalf("Amount = %#v", scenario.HappyPath.Amount)
	}
	if scenario.HappyPath.Expectations.HTTPStatus != ExpectedHTTP2xx || scenario.HappyPath.Expectations.PayerConfirmation != ConfirmationRequired {
		t.Fatalf("Expectations = %#v", scenario.HappyPath.Expectations)
	}
	if scenario.HappyPath.Funding.Balance != 1_000_000_000 || !scenario.HappyPath.Funding.ResetIfExists {
		t.Fatalf("Funding = %#v", scenario.HappyPath.Funding)
	}
	if cfg.Reporting.SLAThresholdMs != 3200 {
		t.Fatalf("Reporting = %#v", cfg.Reporting)
	}
}

func TestUniformSmokePreservesCompatibilityWorkload(t *testing.T) {
	cfg, err := loadProfileFromDir(filepath.Join("..", "..", "profiles"), DefaultProfile)
	if err != nil {
		t.Fatal(err)
	}

	if cfg.Load.TargetTxRate != 2000 || cfg.Load.Warmup != time.Minute || cfg.Load.Duration != time.Minute || cfg.Load.Drain != 30*time.Second {
		t.Fatalf("uniform-smoke Load = %#v", cfg.Load)
	}
	scenario := cfg.Scenarios[0]
	distribution := scenario.HappyPath.Participants
	if distribution.FirstPair != 1 || distribution.HotPairCount != 10 || distribution.ColdPairCount != 40 || distribution.HotTrafficShare != 0.8 {
		t.Fatalf("uniform-smoke Distribution = %#v", distribution)
	}
	if scenario.Type != ScenarioHappyPath || scenario.Share != 1 || scenario.HappyPath.Amount.Minimum != 100 || scenario.HappyPath.Amount.Maximum != 100098 {
		t.Fatalf("uniform-smoke Scenario = %#v", scenario)
	}
	if scenario.HappyPath.Funding.Balance != 1_000_000_000 || !scenario.HappyPath.Funding.ResetIfExists || cfg.Reporting.SLAThresholdMs != 1000 {
		t.Fatalf("uniform-smoke Funding/Reporting = %#v/%#v", scenario.HappyPath.Funding, cfg.Reporting)
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

func TestLoadProfileRejectsMalformedJSON(t *testing.T) {
	dir := t.TempDir()
	writeProfile(t, dir, "broken-profile", `{not JSON}`)

	_, err := loadProfileFromDir(dir, "broken-profile")
	if err == nil || !strings.Contains(err.Error(), `profile "broken-profile" is malformed`) {
		t.Fatalf("error = %v, want clear malformed-profile error", err)
	}
}

func TestLoadProfileRejectsFlatLegacyContract(t *testing.T) {
	dir := t.TempDir()
	writeProfile(t, dir, "legacy", `{"schemaVersion":1,"targetTxRate":2000}`)

	_, err := loadProfileFromDir(dir, "legacy")
	if err == nil || !strings.Contains(err.Error(), "unknown field") {
		t.Fatalf("error = %v, want flat contract rejection", err)
	}
}

func TestLoadProfileRejectsPreviousNestedContract(t *testing.T) {
	dir := t.TempDir()
	content := strings.Replace(testProfile, `  "seed": 42,`, `  "participants": {},
  "traffic": {},
  "funding": {},
  "seed": 42,`, 1)
	writeProfile(t, dir, "previous-contract", content)

	_, err := loadProfileFromDir(dir, "previous-contract")
	if err == nil || !strings.Contains(err.Error(), `unknown field "participants"`) {
		t.Fatalf("error = %v, want previous root layout rejection", err)
	}
}

func TestLoadProfileRejectsRemovedSingletonTypeFields(t *testing.T) {
	tests := []struct {
		name string
		old  string
		new  string
	}{
		{
			name: "participants",
			old:  `"participants": {`,
			new:  `"participants": {"type": "hot-cold-pairs",`,
		},
		{
			name: "funding",
			old:  `"funding": {`,
			new:  `"funding": {"type": "uniform",`,
		},
		{
			name: "amount",
			old:  `"amount": {`,
			new:  `"amount": {"type": "sequential-range",`,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			dir := t.TempDir()
			content := strings.Replace(testProfile, test.old, test.new, 1)
			writeProfile(t, dir, "removed-type", content)

			_, err := loadProfileFromDir(dir, "removed-type")
			if err == nil || !strings.Contains(err.Error(), `unknown field "type"`) {
				t.Fatalf("error = %v, want removed type field rejection", err)
			}
		})
	}
}

func TestLoadProfileRejectsUnknownFields(t *testing.T) {
	dir := t.TempDir()
	content := strings.Replace(testProfile, `"schemaVersion": 1,`, `"schemaVersion": 1, "unexpected": true,`, 1)
	writeProfile(t, dir, "unknown-field", content)

	_, err := loadProfileFromDir(dir, "unknown-field")
	if err == nil || !strings.Contains(err.Error(), `unknown field "unexpected"`) {
		t.Fatalf("error = %v, want unknown-field error", err)
	}
}

func TestLoadProfileRejectsInvalidSemanticValues(t *testing.T) {
	tests := []struct {
		name        string
		old         string
		new         string
		wantMessage string
	}{
		{name: "schema version", old: `"schemaVersion": 1`, new: `"schemaVersion": 2`, wantMessage: "schemaVersion"},
		{name: "duration", old: `"duration": "45s"`, new: `"duration": "soon"`, wantMessage: "load.duration"},
		{name: "whole seconds", old: `"drain": "12s"`, new: `"drain": "1500ms"`, wantMessage: "whole number of seconds"},
		{name: "seed", old: `"seed": 42`, new: `"seed": -1`, wantMessage: "invalid seed"},
		{name: "scenario", old: `"type": "happy-path",`, new: `"type": "insufficient-funds",`, wantMessage: `unsupported scenario type "insufficient-funds"`},
		{name: "share", old: `"share": 1.0`, new: `"share": 0.5`, wantMessage: "must be 1.0"},
		{name: "first pair", old: `"firstPair": 101`, new: `"firstPair": 0`, wantMessage: "participants.firstPair"},
		{name: "pair range overflow", old: `"firstPair": 101`, new: `"firstPair": 999990`, wantMessage: "maximum is 999999"},
		{name: "amount range", old: `"maximum": 100098`, new: `"maximum": 99`, wantMessage: "amount.maximum"},
		{name: "HTTP expectation", old: `"httpStatus": "2xx"`, new: `"httpStatus": "4xx"`, wantMessage: "expectations.httpStatus"},
		{name: "confirmation expectation", old: `"payerConfirmation": "required"`, new: `"payerConfirmation": "forbidden"`, wantMessage: "expectations.payerConfirmation"},
		{name: "funding balance", old: `"balance": 1000000000`, new: `"balance": 0`, wantMessage: "funding.balance"},
		{name: "SLA", old: `"slaThresholdMs": 3200`, new: `"slaThresholdMs": 0`, wantMessage: "reporting.slaThresholdMs"},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			dir := t.TempDir()
			content := strings.Replace(testProfile, test.old, test.new, 1)
			if content == testProfile {
				t.Fatalf("test replacement %q was not applied", test.old)
			}
			writeProfile(t, dir, "invalid-profile", content)

			_, err := loadProfileFromDir(dir, "invalid-profile")
			if err == nil || !strings.Contains(err.Error(), test.wantMessage) {
				t.Fatalf("error = %v, want message containing %q", err, test.wantMessage)
			}
		})
	}
}

func TestLoadProfileRequiresFundingResetBehavior(t *testing.T) {
	dir := t.TempDir()
	content := strings.Replace(testProfile, `,
        "resetIfExists": true`, "", 1)
	writeProfile(t, dir, "missing-reset", content)

	_, err := loadProfileFromDir(dir, "missing-reset")
	if err == nil || !strings.Contains(err.Error(), "scenarios[0].funding.resetIfExists") {
		t.Fatalf("error = %v, want required reset behavior", err)
	}
}

func TestLoadProfileRejectsMultipleScenarios(t *testing.T) {
	dir := t.TempDir()
	content := strings.Replace(testProfile, `  ],
  "reporting"`, `    ,{"type":"happy-path"}
  ],
  "reporting"`, 1)
	writeProfile(t, dir, "multiple-scenarios", content)

	_, err := loadProfileFromDir(dir, "multiple-scenarios")
	if err == nil || !strings.Contains(err.Error(), "must contain exactly one happy-path scenario") {
		t.Fatalf("error = %v, want multiple-scenario rejection", err)
	}
}

func TestLoadProfileRequiresSeed(t *testing.T) {
	dir := t.TempDir()
	content := strings.Replace(testProfile, `  "seed": 42,
`, "", 1)
	writeProfile(t, dir, "missing-seed", content)

	_, err := loadProfileFromDir(dir, "missing-seed")
	if err == nil || !strings.Contains(err.Error(), "invalid seed") {
		t.Fatalf("error = %v, want required seed", err)
	}
}

func writeProfile(t *testing.T, dir string, name string, content string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(dir, name+".json"), []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}
