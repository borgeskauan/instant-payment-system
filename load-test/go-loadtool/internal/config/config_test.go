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
  "scenarios": [
    {
      "type": "happy-path",
      "share": 1.0,
      "participants": {
        "hotPairCount": 7,
        "coldPairCount": 13,
        "hotTrafficShare": 0.75
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
	if len(cfg.Scenarios) != 1 {
		t.Fatalf("Scenarios = %#v", cfg.Scenarios)
	}
	scenario := cfg.Scenarios[0]
	if scenario.Type != ScenarioHappyPath || scenario.Share != 1 || scenario.HappyPath == nil {
		t.Fatalf("Scenario = %#v", scenario)
	}
	distribution := scenario.HappyPath.Participants
	if distribution.PairNumberStart != 1 || distribution.HotPairCount != 7 || distribution.ColdPairCount != 13 || distribution.HotTrafficShare != 0.75 {
		t.Fatalf("Participants = %#v", distribution)
	}
	if scenario.HappyPath.Amount.Minimum != 100 || scenario.HappyPath.Amount.Maximum != 100098 {
		t.Fatalf("Amount = %#v", scenario.HappyPath.Amount)
	}
	if scenario.HappyPath.Expectations.HTTPStatus != ExpectedHTTP2xx || scenario.HappyPath.Expectations.PayerConfirmation != ConfirmationRequired {
		t.Fatalf("Expectations = %#v", scenario.HappyPath.Expectations)
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
	if distribution.PairNumberStart != 1 || distribution.HotPairCount != 10 || distribution.ColdPairCount != 40 || distribution.HotTrafficShare != 0.8 {
		t.Fatalf("uniform-smoke Distribution = %#v", distribution)
	}
	if scenario.Type != ScenarioHappyPath || scenario.Share != 1 || scenario.HappyPath.Amount.Minimum != 100 || scenario.HappyPath.Amount.Maximum != 100098 {
		t.Fatalf("uniform-smoke Scenario = %#v", scenario)
	}
	if cfg.Reporting.SLAThresholdMs != 1000 {
		t.Fatalf("uniform-smoke Reporting = %#v", cfg.Reporting)
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
	content := strings.Replace(testProfile, `  "scenarios": [`, `  "participants": {},
  "traffic": {},
  "funding": {},
  "scenarios": [`, 1)
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
		{name: "scenario", old: `"type": "happy-path",`, new: `"type": "not-supported",`, wantMessage: `unsupported scenario type "not-supported"`},
		{name: "share", old: `"share": 1.0`, new: `"share": 0.5`, wantMessage: "shares must sum"},
		{name: "pair range overflow", old: `"hotPairCount": 7`, new: `"hotPairCount": 1000000`, wantMessage: "maximum pair number 999999"},
		{name: "amount range", old: `"maximum": 100098`, new: `"maximum": 99`, wantMessage: "amount.maximum"},
		{name: "HTTP expectation", old: `"httpStatus": "2xx"`, new: `"httpStatus": "4xx"`, wantMessage: "expectations.httpStatus"},
		{name: "confirmation expectation", old: `"payerConfirmation": "required"`, new: `"payerConfirmation": "forbidden"`, wantMessage: "expectations.payerConfirmation"},
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

func TestLoadProfileRejectsDuplicateScenarioTypes(t *testing.T) {
	data, err := os.ReadFile(filepath.Join("..", "..", "profiles", "mixed-outcomes-smoke.json"))
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	content := strings.Replace(string(data), `"type": "insufficient-funds"`, `"type": "happy-path"`, 1)
	content = strings.Replace(content, `"payerConfirmation": "forbidden"`, `"payerConfirmation": "required"`, 1)
	writeProfile(t, dir, "duplicate-scenario", content)

	_, err = loadProfileFromDir(dir, "duplicate-scenario")
	if err == nil || !strings.Contains(err.Error(), "duplicate scenario type") {
		t.Fatalf("error = %v, want duplicate scenario rejection", err)
	}
}

func TestMixedOutcomesSmokeLoadsBothTypedScenarios(t *testing.T) {
	cfg, err := loadProfileFromDir(filepath.Join("..", "..", "profiles"), "mixed-outcomes-smoke")
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Load.TargetTxRate != 100 || cfg.Load.Warmup != 5*time.Second || cfg.Load.Duration != 10*time.Second || cfg.Load.Drain != 10*time.Second {
		t.Fatalf("mixed load = %#v", cfg.Load)
	}
	if len(cfg.Scenarios) != 2 || cfg.Scenarios[0].HappyPath == nil || cfg.Scenarios[1].InsufficientFunds == nil {
		t.Fatalf("mixed scenarios = %#v", cfg.Scenarios)
	}
	if cfg.Scenarios[0].Share != 0.8 || cfg.Scenarios[1].Share != 0.2 {
		t.Fatalf("mixed shares = %#v", cfg.Scenarios)
	}
	if cfg.Scenarios[1].InsufficientFunds.Expectations.PayerConfirmation != ConfirmationForbidden {
		t.Fatalf("insufficient expectations = %#v", cfg.Scenarios[1].InsufficientFunds.Expectations)
	}
	if cfg.Scenarios[0].HappyPath.Participants.PairNumberStart != 1 || cfg.Scenarios[1].InsufficientFunds.Participants.PairNumberStart != 41 {
		t.Fatalf("allocated ranges = %#v", cfg.Scenarios)
	}
}

func TestLoadProfileRejectsRemovedFundingField(t *testing.T) {
	dir := t.TempDir()
	content := strings.Replace(testProfile, `      "amount": {`, `      "funding": {"balance": 1, "resetIfExists": true},
      "amount": {`, 1)
	writeProfile(t, dir, "removed-funding", content)
	_, err := loadProfileFromDir(dir, "removed-funding")
	if err == nil || !strings.Contains(err.Error(), `unknown field "funding"`) {
		t.Fatalf("error = %v, want removed funding rejection", err)
	}
}

func TestLoadProfileAllocatesConsecutiveScenarioRanges(t *testing.T) {
	data, err := os.ReadFile(filepath.Join("..", "..", "profiles", "mixed-outcomes-smoke.json"))
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	writeProfile(t, dir, "allocated", string(data))
	cfg, err := loadProfileFromDir(dir, "allocated")
	if err != nil {
		t.Fatal(err)
	}
	first, _ := cfg.Scenarios[0].Participants()
	second, _ := cfg.Scenarios[1].Participants()
	if first.PairNumberStart != 1 || second.PairNumberStart != 41 {
		t.Fatalf("allocated starts = %d/%d, want 1/41", first.PairNumberStart, second.PairNumberStart)
	}
}

func TestLoadProfileRejectsFractionalBlockQuota(t *testing.T) {
	dir := t.TempDir()
	content := strings.Replace(testProfile, `"share": 1.0`, `"share": 0.999`, 1)
	writeProfile(t, dir, "fractional-quota", content)
	_, err := loadProfileFromDir(dir, "fractional-quota")
	if err == nil || !strings.Contains(err.Error(), "whole number of entries") {
		t.Fatalf("error = %v, want exact block quota rejection", err)
	}
}

func TestLoadProfileRejectsRemovedSeedAndPairStartFields(t *testing.T) {
	tests := []struct {
		name    string
		content string
		field   string
	}{
		{name: "seed", content: strings.Replace(testProfile, `  "scenarios": [`, `  "seed": 1,
  "scenarios": [`, 1), field: `unknown field "seed"`},
		{name: "first-pair", content: strings.Replace(testProfile, `        "hotPairCount": 7,`, `        "firstPair": 1,
        "hotPairCount": 7,`, 1), field: `unknown field "firstPair"`},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			dir := t.TempDir()
			writeProfile(t, dir, "removed-field", test.content)
			_, err := loadProfileFromDir(dir, "removed-field")
			if err == nil || !strings.Contains(err.Error(), test.field) {
				t.Fatalf("error = %v, want %s rejection", err, test.field)
			}
		})
	}
}

func writeProfile(t *testing.T, dir string, name string, content string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(dir, name+".json"), []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}
