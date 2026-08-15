package config

import (
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"
)

const testProfile = `{
  "name": "PROFILE_NAME",
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
  "replay": {
    "pacs008": {
      "share": 0.25,
      "delay": "7s"
    },
    "pacs002": {
      "share": 0.20,
      "delay": "11s"
    }
  },
  "scenarios": [
    {
      "name": "happy-path",
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
      "funding": {
        "payer": {
          "mode": "cover-generated-debits"
        },
        "receiver": {
          "mode": "fixed",
          "balance": "0.00"
        },
        "resetIfExists": true
      },
      "expectations": {
        "httpStatus": "2xx",
        "payerNotification": {
          "deliverySemantics": "at-least-once",
          "status": "ACSC",
          "reasonCodes": []
        }
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
	if cfg.Name != "explicit-profile" {
		t.Fatalf("Name = %q, want explicit-profile", cfg.Name)
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
	if cfg.Replay.Pacs008 == nil || cfg.Replay.Pacs008.Share != 0.25 || cfg.Replay.Pacs008.Delay != 7*time.Second {
		t.Fatalf("Replay = %#v", cfg.Replay)
	}
	if cfg.Replay.Pacs002 == nil || cfg.Replay.Pacs002.Share != 0.20 || cfg.Replay.Pacs002.Delay != 11*time.Second {
		t.Fatalf("Pacs002 replay = %#v", cfg.Replay)
	}
	if len(cfg.Scenarios) != 1 {
		t.Fatalf("Scenarios = %#v", cfg.Scenarios)
	}
	scenario := cfg.Scenarios[0]
	if scenario.Name != "happy-path" || scenario.Share != 1 {
		t.Fatalf("Scenario = %#v", scenario)
	}
	distribution := scenario.Participants
	if distribution.PairNumberStart != 1 || distribution.HotPairCount != 7 || distribution.ColdPairCount != 13 || distribution.HotTrafficShare != 0.75 {
		t.Fatalf("Participants = %#v", distribution)
	}
	if scenario.Amount.Minimum != 100 || scenario.Amount.Maximum != 100098 {
		t.Fatalf("Amount = %#v", scenario.Amount)
	}
	if scenario.Funding.Payer.Mode != FundingCoverGeneratedDebits || scenario.Funding.Receiver.Mode != FundingFixed || scenario.Funding.Receiver.Balance != "0.00" || !scenario.Funding.ResetIfExists {
		t.Fatalf("Funding = %#v", scenario.Funding)
	}
	if scenario.Expectations.HTTPStatus != ExpectedHTTP2xx ||
		scenario.Expectations.PayerNotification.DeliverySemantics != DeliveryAtLeastOnce ||
		scenario.Expectations.PayerNotification.Status != "ACSC" ||
		scenario.Expectations.PayerNotification.ReasonCodes == nil ||
		len(scenario.Expectations.PayerNotification.ReasonCodes) != 0 {
		t.Fatalf("Expectations = %#v", scenario.Expectations)
	}
	if cfg.Reporting.SLAThresholdMs != 3200 {
		t.Fatalf("Reporting = %#v", cfg.Reporting)
	}
}

func TestLoadProfileReadsSiblingCatalogFromModuleRoot(t *testing.T) {
	root := t.TempDir()
	profiles := filepath.Join(root, "profiles")
	moduleRoot := filepath.Join(root, "go-loadtool")
	if err := os.Mkdir(profiles, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(moduleRoot, 0o755); err != nil {
		t.Fatal(err)
	}
	writeProfile(t, profiles, "explicit-profile", testProfile)
	t.Chdir(moduleRoot)

	cfg, err := LoadProfile("explicit-profile")
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Name != "explicit-profile" {
		t.Fatalf("Name = %q, want explicit-profile", cfg.Name)
	}
}

func TestUniformSmokePreservesBaselineWorkload(t *testing.T) {
	cfg, err := loadProfileFromDir(filepath.Join("..", "..", "..", "profiles"), DefaultProfile)
	if err != nil {
		t.Fatal(err)
	}

	if cfg.Load.TargetTxRate != 2000 || cfg.Load.Warmup != time.Minute || cfg.Load.Duration != time.Minute || cfg.Load.Drain != 30*time.Second {
		t.Fatalf("uniform-smoke Load = %#v", cfg.Load)
	}
	scenario := cfg.Scenarios[0]
	distribution := scenario.Participants
	if distribution.PairNumberStart != 1 || distribution.HotPairCount != 10 || distribution.ColdPairCount != 40 || distribution.HotTrafficShare != 0.8 {
		t.Fatalf("uniform-smoke Distribution = %#v", distribution)
	}
	if scenario.Name != "happy-path" || scenario.Share != 1 || scenario.Amount.Minimum != 100 || scenario.Amount.Maximum != 100098 {
		t.Fatalf("uniform-smoke Scenario = %#v", scenario)
	}
	if cfg.Reporting.SLAThresholdMs != 1000 {
		t.Fatalf("uniform-smoke Reporting = %#v", cfg.Reporting)
	}
	if cfg.Replay.Pacs008 != nil {
		t.Fatalf("uniform-smoke Replay = %#v, want disabled", cfg.Replay)
	}
}

func TestLoadProfileAcceptsOmittedReplay(t *testing.T) {
	dir := t.TempDir()
	content := strings.Replace(testProfile, `  "replay": {
    "pacs008": {
      "share": 0.25,
      "delay": "7s"
    },
    "pacs002": {
      "share": 0.20,
      "delay": "11s"
    }
  },
`, "", 1)
	writeProfile(t, dir, "no-replay", content)

	cfg, err := loadProfileFromDir(dir, "no-replay")
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Replay.Pacs008 != nil || cfg.Replay.Pacs002 != nil {
		t.Fatalf("Replay = %#v, want disabled", cfg.Replay)
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

func TestLoadProfileRejectsMissingEmbeddedName(t *testing.T) {
	dir := t.TempDir()
	content := strings.Replace(testProfile, `  "name": "PROFILE_NAME",
`, "", 1)
	writeRawProfile(t, filepath.Join(dir, "missing-name.json"), content)

	_, err := loadProfileFromDir(dir, "missing-name")
	if err == nil || !strings.Contains(err.Error(), "invalid name") {
		t.Fatalf("error = %v, want missing embedded name rejection", err)
	}
}

func TestLoadProfileRejectsInvalidEmbeddedName(t *testing.T) {
	dir := t.TempDir()
	content := profileContent("Uppercase", testProfile)
	writeRawProfile(t, filepath.Join(dir, "selected-profile.json"), content)

	_, err := loadProfileFromDir(dir, "selected-profile")
	if err == nil || !strings.Contains(err.Error(), "invalid name") {
		t.Fatalf("error = %v, want invalid embedded name rejection", err)
	}
}

func TestLoadProfileRejectsNameDifferentFromSelectedFile(t *testing.T) {
	dir := t.TempDir()
	content := profileContent("other-profile", testProfile)
	writeRawProfile(t, filepath.Join(dir, "selected-profile.json"), content)

	_, err := loadProfileFromDir(dir, "selected-profile")
	if err == nil || !strings.Contains(err.Error(), `name "other-profile" does not match selected profile "selected-profile"`) {
		t.Fatalf("error = %v, want embedded name mismatch rejection", err)
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

func TestLoadRunProfileReadsEmbeddedIdentityAndRuntimeSettings(t *testing.T) {
	path := filepath.Join(t.TempDir(), "profile.json")
	writeRawProfile(t, path, profileContent("run-profile", testProfile))

	cfg, err := LoadRunProfile(path)
	if err != nil {
		t.Fatalf("LoadRunProfile() error = %v", err)
	}
	if cfg.Name != "run-profile" || cfg.Load.TargetTxRate != 1234 || cfg.Reporting.SLAThresholdMs != 3200 {
		t.Fatalf("LoadRunProfile() = %#v", cfg)
	}
}

func TestLoadRunProfileRejectsMalformedContract(t *testing.T) {
	path := filepath.Join(t.TempDir(), "profile.json")
	writeRawProfile(t, path, `{"name":"run-profile","schemaVersion":1,"unexpected":true}`)

	_, err := LoadRunProfile(path)
	if err == nil || !strings.Contains(err.Error(), `run profile at`) || !strings.Contains(err.Error(), `unknown field "unexpected"`) {
		t.Fatalf("error = %v, want strict run profile rejection", err)
	}
}

func TestLoadRunProfileRejectsMissingFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "profile.json")

	_, err := LoadRunProfile(path)
	if err == nil || !strings.Contains(err.Error(), `run profile not found`) {
		t.Fatalf("error = %v, want missing run profile rejection", err)
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
		{name: "replay share zero", old: `"share": 0.25`, new: `"share": 0`, wantMessage: "replay.pacs008.share"},
		{name: "replay share greater than one", old: `"share": 0.25`, new: `"share": 1.01`, wantMessage: "replay.pacs008.share"},
		{name: "replay share not integral per block", old: `"share": 0.25`, new: `"share": 0.015`, wantMessage: "whole number of entries"},
		{name: "replay delay zero", old: `"delay": "7s"`, new: `"delay": "0s"`, wantMessage: "replay.pacs008.delay"},
		{name: "replay delay fractional", old: `"delay": "7s"`, new: `"delay": "1500ms"`, wantMessage: "whole number of seconds"},
		{name: "pacs002 replay share zero", old: `"share": 0.20`, new: `"share": 0`, wantMessage: "replay.pacs002.share"},
		{name: "pacs002 replay share not integral per block", old: `"share": 0.20`, new: `"share": 0.015`, wantMessage: "replay.pacs002.share"},
		{name: "pacs002 replay delay zero", old: `"delay": "11s"`, new: `"delay": "0s"`, wantMessage: "replay.pacs002.delay"},
		{name: "scenario name", old: `"name": "happy-path",`, new: `"name": "Not-Supported",`, wantMessage: "scenario name"},
		{name: "share", old: `"share": 1.0`, new: `"share": 0.5`, wantMessage: "shares must sum"},
		{name: "pair range overflow", old: `"hotPairCount": 7`, new: `"hotPairCount": 1000000`, wantMessage: "maximum pair number 999999"},
		{name: "amount range", old: `"maximum": 100098`, new: `"maximum": 99`, wantMessage: "amount.maximum"},
		{name: "HTTP expectation", old: `"httpStatus": "2xx"`, new: `"httpStatus": "4xx"`, wantMessage: "expectations.httpStatus"},
		{name: "delivery semantics", old: `"deliverySemantics": "at-least-once"`, new: `"deliverySemantics": "exactly-once"`, wantMessage: "expectations.payerNotification.deliverySemantics"},
		{name: "notification status", old: `"status": "ACSC"`, new: `"status": "accepted"`, wantMessage: "expectations.payerNotification.status"},
		{name: "notification reason", old: `"reasonCodes": []`, new: `"reasonCodes": ["am04"]`, wantMessage: "expectations.payerNotification.reasonCodes"},
		{name: "duplicate notification reason", old: `"reasonCodes": []`, new: `"reasonCodes": ["AM04", "AM04"]`, wantMessage: "duplicate reason code"},
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

func TestLoadProfileRejectsDuplicateScenarioNames(t *testing.T) {
	data, err := os.ReadFile(filepath.Join("..", "..", "..", "profiles", "mixed-outcomes-smoke.json"))
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	content := strings.Replace(string(data), `"name": "insufficient-funds"`, `"name": "happy-path"`, 1)
	writeProfile(t, dir, "duplicate-scenario", content)

	_, err = loadProfileFromDir(dir, "duplicate-scenario")
	if err == nil || !strings.Contains(err.Error(), "duplicate scenario name") {
		t.Fatalf("error = %v, want duplicate scenario rejection", err)
	}
}

func TestMixedOutcomesSmokeLoadsGenericScenarios(t *testing.T) {
	cfg, err := loadProfileFromDir(filepath.Join("..", "..", "..", "profiles"), "mixed-outcomes-smoke")
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Load.TargetTxRate != 100 || cfg.Load.Warmup != 5*time.Second || cfg.Load.Duration != 10*time.Second || cfg.Load.Drain != 10*time.Second {
		t.Fatalf("mixed load = %#v", cfg.Load)
	}
	if len(cfg.Scenarios) != 2 || cfg.Scenarios[0].Name != "happy-path" || cfg.Scenarios[1].Name != "insufficient-funds" {
		t.Fatalf("mixed scenarios = %#v", cfg.Scenarios)
	}
	if cfg.Scenarios[0].Share != 0.8 || cfg.Scenarios[1].Share != 0.2 {
		t.Fatalf("mixed shares = %#v", cfg.Scenarios)
	}
	happyNotification := cfg.Scenarios[0].Expectations.PayerNotification
	insufficientNotification := cfg.Scenarios[1].Expectations.PayerNotification
	if happyNotification.DeliverySemantics != DeliveryAtLeastOnce || happyNotification.Status != "ACSC" || len(happyNotification.ReasonCodes) != 0 ||
		insufficientNotification.DeliverySemantics != DeliveryAtLeastOnce || insufficientNotification.Status != "RJCT" || len(insufficientNotification.ReasonCodes) != 1 || insufficientNotification.ReasonCodes[0] != "AM04" {
		t.Fatalf("mixed expectations = %#v", cfg.Scenarios)
	}
	if cfg.Scenarios[0].Participants.PairNumberStart != 1 || cfg.Scenarios[1].Participants.PairNumberStart != 41 {
		t.Fatalf("allocated ranges = %#v", cfg.Scenarios)
	}
	if cfg.Scenarios[0].Funding.Payer.Mode != FundingCoverGeneratedDebits || cfg.Scenarios[1].Funding.Payer.Mode != FundingFixed || cfg.Scenarios[1].Funding.Payer.Balance != "0.00" {
		t.Fatalf("mixed funding = %#v", cfg.Scenarios)
	}
	if cfg.Replay.Pacs008 == nil || cfg.Replay.Pacs008.Share != 0.05 || cfg.Replay.Pacs008.Delay != 10*time.Second {
		t.Fatalf("mixed replay = %#v", cfg.Replay)
	}
	if cfg.Replay.Pacs002 == nil || cfg.Replay.Pacs002.Share != 0.05 || cfg.Replay.Pacs002.Delay != 10*time.Second {
		t.Fatalf("mixed PACS.002 replay = %#v", cfg.Replay)
	}
}

func TestMixedOutcomesLongProfileDefinesStabilizationWorkload(t *testing.T) {
	profilesDir := filepath.Join("..", "..", "..", "profiles")
	smoke, err := loadProfileFromDir(profilesDir, "mixed-outcomes-smoke")
	if err != nil {
		t.Fatal(err)
	}
	long, err := loadProfileFromDir(profilesDir, "mixed-outcomes-2k-15m")
	if err != nil {
		t.Fatal(err)
	}

	if long.Load.TargetTxRate != 2000 || long.Load.Warmup != time.Minute || long.Load.Duration != 15*time.Minute || long.Load.Drain != 30*time.Second {
		t.Fatalf("mixed-outcomes-2k-15m Load = %#v", long.Load)
	}
	if !reflect.DeepEqual(long.Replay, smoke.Replay) {
		t.Fatalf("long replay = %#v, want smoke replay %#v", long.Replay, smoke.Replay)
	}
	if !reflect.DeepEqual(long.Scenarios, smoke.Scenarios) {
		t.Fatalf("long scenarios differ from functionally validated smoke")
	}
	if !reflect.DeepEqual(long.Connections, smoke.Connections) || !reflect.DeepEqual(long.Reporting, smoke.Reporting) {
		t.Fatalf("long runtime configuration differs from functionally validated smoke")
	}
}

func TestMixedOutcomesDiagnosticProfileDefinesShortInvestigationWorkload(t *testing.T) {
	profilesDir := filepath.Join("..", "..", "..", "profiles")
	diagnostic, err := loadProfileFromDir(profilesDir, "mixed-outcomes-2k-diagnostic")
	if err != nil {
		t.Fatal(err)
	}
	long, err := loadProfileFromDir(profilesDir, "mixed-outcomes-2k-15m")
	if err != nil {
		t.Fatal(err)
	}

	if diagnostic.Load.TargetTxRate != 2000 ||
		diagnostic.Load.Warmup != 15*time.Second ||
		diagnostic.Load.Duration != time.Minute ||
		diagnostic.Load.Drain != 30*time.Second {
		t.Fatalf("mixed-outcomes-2k-diagnostic Load = %#v", diagnostic.Load)
	}
	if !reflect.DeepEqual(diagnostic.Replay, long.Replay) ||
		!reflect.DeepEqual(diagnostic.Scenarios, long.Scenarios) ||
		!reflect.DeepEqual(diagnostic.Connections, long.Connections) ||
		!reflect.DeepEqual(diagnostic.Reporting, long.Reporting) {
		t.Fatal("diagnostic workload differs from mixed-outcomes-2k-15m outside the execution window")
	}
}

func TestLoadProfileAllocatesConsecutiveScenarioRanges(t *testing.T) {
	data, err := os.ReadFile(filepath.Join("..", "..", "..", "profiles", "mixed-outcomes-smoke.json"))
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	writeProfile(t, dir, "allocated", string(data))
	cfg, err := loadProfileFromDir(dir, "allocated")
	if err != nil {
		t.Fatal(err)
	}
	first := cfg.Scenarios[0].Participants
	second := cfg.Scenarios[1].Participants
	if first.PairNumberStart != 1 || second.PairNumberStart != 41 {
		t.Fatalf("allocated starts = %d/%d, want 1/41", first.PairNumberStart, second.PairNumberStart)
	}
}

func TestLoadProfileRequiresObservablePayerNotificationFields(t *testing.T) {
	tests := []struct {
		name        string
		old         string
		wantMessage string
	}{
		{name: "delivery semantics", old: `          "deliverySemantics": "at-least-once",
`, wantMessage: "expectations.payerNotification.deliverySemantics"},
		{name: "status", old: `          "status": "ACSC",
`, wantMessage: "expectations.payerNotification.status"},
		{name: "reason codes", old: `,
          "reasonCodes": []`, wantMessage: "expectations.payerNotification.reasonCodes"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			dir := t.TempDir()
			content := strings.Replace(testProfile, test.old, "", 1)
			if content == testProfile {
				t.Fatalf("test replacement %q was not applied", test.old)
			}
			writeProfile(t, dir, "missing-notification-field", content)
			_, err := loadProfileFromDir(dir, "missing-notification-field")
			if err == nil || !strings.Contains(err.Error(), test.wantMessage) {
				t.Fatalf("error = %v, want message containing %q", err, test.wantMessage)
			}
		})
	}
}

func TestLoadProfileValidatesExplicitFunding(t *testing.T) {
	tests := []struct {
		name        string
		old         string
		new         string
		wantMessage string
	}{
		{name: "missing fixed balance", old: `"mode": "fixed",
          "balance": "0.00"`, new: `"mode": "fixed"`, wantMessage: "funding.receiver.balance"},
		{name: "cover with balance", old: `"mode": "cover-generated-debits"`, new: `"mode": "cover-generated-debits", "balance": "1.00"`, wantMessage: "must be omitted"},
		{name: "receiver cover", old: `"mode": "fixed",
          "balance": "0.00"`, new: `"mode": "cover-generated-debits"`, wantMessage: "funding.receiver.mode"},
		{name: "unknown mode", old: `"mode": "cover-generated-debits"`, new: `"mode": "mystery"`, wantMessage: "funding.payer.mode"},
		{name: "negative", old: `"balance": "0.00"`, new: `"balance": "-1.00"`, wantMessage: "non-negative decimal"},
		{name: "exponent", old: `"balance": "0.00"`, new: `"balance": "1e3"`, wantMessage: "non-negative decimal"},
		{name: "precision", old: `"balance": "0.00"`, new: `"balance": "0.001"`, wantMessage: "at most two fractional digits"},
		{name: "overflow", old: `"balance": "0.00"`, new: `"balance": "92233720368547758.08"`, wantMessage: "overflows"},
		{name: "missing reset", old: `,
        "resetIfExists": true`, new: ``, wantMessage: "funding.resetIfExists"},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			dir := t.TempDir()
			content := strings.Replace(testProfile, test.old, test.new, 1)
			if content == testProfile {
				t.Fatalf("test replacement %q was not applied", test.old)
			}
			writeProfile(t, dir, "invalid-funding", content)
			_, err := loadProfileFromDir(dir, "invalid-funding")
			if err == nil || !strings.Contains(err.Error(), test.wantMessage) {
				t.Fatalf("error = %v, want message containing %q", err, test.wantMessage)
			}
		})
	}
}

func TestLoadProfileNormalizesFixedFundingBalance(t *testing.T) {
	dir := t.TempDir()
	content := strings.Replace(testProfile, `"balance": "0.00"`, `"balance": "12.3"`, 1)
	writeProfile(t, dir, "normalized-balance", content)
	cfg, err := loadProfileFromDir(dir, "normalized-balance")
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Scenarios[0].Funding.Receiver.Balance != "12.30" || cfg.Scenarios[0].Funding.Receiver.BalanceCents != 1230 {
		t.Fatalf("receiver funding = %#v", cfg.Scenarios[0].Funding.Receiver)
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

func writeProfile(t *testing.T, dir string, name string, content string) {
	t.Helper()
	writeRawProfile(t, filepath.Join(dir, name+".json"), profileContent(name, content))
}

func profileContent(name string, content string) string {
	const prefix = `"name": "`
	start := strings.Index(content, prefix)
	if start < 0 {
		return content
	}
	valueStart := start + len(prefix)
	valueEnd := strings.Index(content[valueStart:], `"`)
	if valueEnd < 0 {
		return content
	}
	return content[:valueStart] + name + content[valueStart+valueEnd:]
}

func writeRawProfile(t *testing.T, path string, content string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}
